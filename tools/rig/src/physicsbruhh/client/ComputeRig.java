package physicsbruhh.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import physicsbruhh.client.compute.VkCompute;
import rig.Vk;

/**
 * The gas solver as compute shaders on Vulkan, without the game: a block of fire in a small box, and after every fifth
 * step the fuel, heat and smoke in it. `cargo run --release --example gas_preview -- close 2 out` with EARLY=1 in the
 * environment prints the same sums from the Rust solver; they agree to four digits for the first thirty steps.
 * REGION=x0,y0,z0,x1,y1,z1 computes only that part of the box; REGION2 switches to another part halfway.
 */
public class ComputeRig {
    /** Where the mod's shaders are (run.sh sets it). */
    static final String ROOT = System.getProperty("shaders", "src/main/resources/assets/physicsbruhh/shaders") + "/";

    public static void main(String[] args) throws Exception {
        String scene = args.length > 0 ? args[0] : "fire";
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int cpb = args.length > 2 ? Integer.parseInt(args[2]) : 2, ratio = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        boolean wide = scene.equals("fires") || scene.equals("far");
        GasBox box = wide ? new GasBox(24, 16, 16, cpb, ratio, 4) : new GasBox(10, 14, 10, cpb, ratio, 4);
        Function<String, String> read = name -> { try { return Files.readString(Path.of(ROOT + name)); } catch (Exception e) { throw new RuntimeException(e); } };
        if (System.getenv("DUMP") != null) { System.out.println(GasCompute.wrap(read, System.getenv("DUMP"), null, System.getenv("DUMP").equals("gas_where") ? "blocks" : System.getenv("DUMP").matches("gas_(carry|burn)") ? "fine" : "level", "#version 450", "rgba16f").source()); return; }

        ByteBuffer map = MemoryUtil.memCalloc(box.blockW * box.blockH * 4);
        for (int y = 0; y < box.by; y++) for (int z = 0; z < box.bz; z++) for (int x = 0; x < box.bx; x++) set(box, map, x, y, z, y < 2, 0);
        for (int z = 0; z < box.bz; z++) for (int x = 0; x < box.bx; x++) map.put(box.blockPixel(x, 1, z), (byte) 255);
        switch (scene) {
            case "camp" -> set(box, map, 5, 2, 5, false, 2);
            case "fires" -> { set(box, map, 4, 2, 8, false, 1); set(box, map, 10, 2, 8, false, 2); set(box, map, 15, 2, 8, false, 1 | 16); set(box, map, 20, 2, 8, false, 3); }
            case "far" -> set(box, map, 4, 2, 8, false, 1);
            default -> set(box, map, 5, 2, 5, false, 1);
        }
        int[] part = { 0, 0, 0, box.bx, box.by, box.bz };
        if (System.getenv("REGION") != null) { String[] v = System.getenv("REGION").split(","); for (int i = 0; i < 6; i++) part[i] = Integer.parseInt(v[i]); }
        // REGIONS=a;b: the first for the first half of the steps, then the second (the part that is computed changes)
        int[] second = null;
        if (System.getenv("REGION2") != null) { second = new int[6]; String[] v = System.getenv("REGION2").split(","); for (int i = 0; i < 6; i++) second[i] = Integer.parseInt(v[i]); }

        try (Vk vk = new Vk()) {
            System.out.println("device: " + vk.name + "; box " + box);
            long built = System.nanoTime();
            GasComputeVulkan gas = new GasComputeVulkan(box, vk.device, vk.vma, read);
            System.out.println("programs built in " + (System.nanoTime() - built) / 1000000 + " ms");
            VkCompute.Staging out = gas.compute().staging(box.fineW * box.fineH * 8);
            ByteBuffer grid = MemoryUtil.memCalloc(GasBox.GRID_FLOATS * 4).order(ByteOrder.nativeOrder());
            float dt = 1.0F / 30.0F, time = 0.0F;
            double took = 0.0;
            float fpb = cpb * ratio, cell = 1.0F / (fpb * fpb * fpb);
            for (int step = 0; step < steps; step++) {
                int[] now = second != null && step >= steps / 2 ? second : part;
                box.grid(grid.asFloatBuffer(), dt, time, 0, 0, 0, new float[0], 0, now, 0, 0, 0);
                VkCommandBuffer commands = vk.begin();
                gas.begin(commands);
                gas.step(grid, step == 0 ? map : null, now);
                gas.end();
                boolean look = step % 5 == 4 || System.getenv("EVERY") != null;
                if (look) gas.compute().copyOut(commands, (VkCompute.Image) gas.gas(), out.buffer);
                took += vk.run(commands);
                time += dt;
                if (look) {
                    out.fetch();
                    double smoke = 0.0, heat = 0.0, fuel = 0.0;
                    int bad = 0;
                    for (int i = 0; i < box.fineW * box.fineH; i++) {
                        float s = Float.float16ToFloat(out.bytes.getShort(i * 8)), h = Float.float16ToFloat(out.bytes.getShort(i * 8 + 2)), f = Float.float16ToFloat(out.bytes.getShort(i * 8 + 4));
                        if (Float.isNaN(s) || Float.isNaN(h) || Float.isNaN(f)) bad++;
                        smoke += s; heat += h; fuel += f;
                    }
                    System.out.println(String.format("step %d: fuel %.7f heat %.7f smoke %.7f%s", step + 1, fuel * cell, heat * cell, smoke * cell, bad > 0 ? " BAD " + bad : ""));
                }
            }
            System.out.println(String.format("a step takes %.1f ms here", took / steps * 1000.0));
            org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(vk.device);
            gas.close();
            MemoryUtil.memFree(grid); MemoryUtil.memFree(map);
        }
    }

    static void set(GasBox box, ByteBuffer map, int x, int y, int z, boolean solid, int kind) {
        int at = box.blockPixel(x, y, z);
        map.put(at, (byte) (solid ? 0 : 255)).put(at + 1, (byte) (kind | (solid ? 128 : 0))).put(at + 2, (byte) 128).put(at + 3, (byte) 0);
    }
}
