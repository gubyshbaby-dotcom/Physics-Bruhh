package physicsbruhh.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import physicsbruhh.client.compute.VkCompute;

/**
 * {@link GasCompute} on Vulkan. Knows nothing of the game either: a device, its allocator, and for
 * every step a command buffer to record into ({@link #begin}, {@link #end}).
 */
final class GasComputeVulkan extends GasCompute {
    private final VkCompute compute;
    private final VkCompute.Uniforms uniforms;
    private final List<VkCompute.Image> fresh = new ArrayList<>();
    /** A program with the images it reads and writes, made the first time that combination comes up (there are few). */
    private final Map<List<Object>, VkCompute.Bound> bound = new HashMap<>();
    private VkCompute.Staging mapBytes;
    private VkCommandBuffer commands;
    private int numbersAt;

    GasComputeVulkan(GasBox box, VkDevice device, long vma, Function<String, String> read) {
        super(box);
        compute = new VkCompute(device, vma);
        try {
            // (a few slots: the numbers of a step the graphics card has not got to yet are not overwritten by the next)
            uniforms = compute.uniforms(GasBox.GRID_FLOATS * 4, 8);
            build(read, "#version 450");
        } catch (Throwable t) {
            compute.close();
            throw t;
        }
    }

    VkCompute compute() {
        return compute;
    }

    /** Before a step: the commands of the step go into {@code commands}. */
    void begin(VkCommandBuffer commands) {
        this.commands = commands;
        for (VkCompute.Image image : fresh) image.clear(commands);
        fresh.clear();
        compute.all(commands);
    }

    /** After a step (and whatever else was recorded with it): what was computed can be read by what follows. */
    void end() {
        compute.all(commands);
        commands = null;
    }

    private VkCommandBuffer commands() {
        if (commands == null) throw new IllegalStateException("no command buffer to record the gas solver's programs into");
        return commands;
    }

    @Override
    protected Object image(int kind, int width, int height, String name) {
        VkCompute.Image image = compute.image(kind == HALF ? VkCompute.RGBA16F : kind == WHOLE ? VkCompute.RGBA32F : VkCompute.RGBA8, width, height, 1);
        fresh.add(image);
        return image;
    }

    @Override
    protected Object program(String name, String source, int samplers, int writes) {
        ByteBuffer code = VkCompute.compile(name, source);
        try {
            VkCompute.Kind[] kinds = new VkCompute.Kind[samplers + 2];
            Arrays.fill(kinds, VkCompute.Kind.SAMPLED);
            kinds[0] = VkCompute.Kind.UNIFORMS;
            kinds[samplers + 1] = VkCompute.Kind.STORED;
            return compute.program(code, kinds);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    @Override
    protected void numbers(ByteBuffer grid) {
        numbersAt = uniforms.write(grid);
    }

    @Override
    protected void run(Object program, Object[] reads, boolean[] linear, Object writes, int x, int y, int z) {
        List<Object> key = new ArrayList<>(reads.length * 2 + 2);
        key.add(program);
        for (int i = 0; i < reads.length; i++) { key.add(reads[i]); key.add(linear[i]); }
        key.add(writes);
        VkCompute.Bound with = bound.get(key);
        if (with == null) {
            Object[] things = new Object[reads.length + 2];
            things[0] = uniforms;
            for (int i = 0; i < reads.length; i++) things[i + 1] = compute.sampled((VkCompute.Image) reads[i], linear[i]);
            things[reads.length + 1] = writes;
            with = ((VkCompute.Program) program).bind(things);
            bound.put(key, with);
        }
        compute.dispatch(commands(), with, numbersAt, x, y, z);
        // what this wrote is read by the next
        compute.between(commands());
    }

    @Override
    protected void clear(Object image) {
        VkCompute.Image target = (VkCompute.Image) image;
        if (fresh.contains(target)) return; // (emptied before its first use anyway)
        if (commands == null) { fresh.add(target); return; }
        compute.all(commands);
        target.clear(commands);
        compute.all(commands);
    }

    @Override
    protected void upload(Object world, ByteBuffer bytes) {
        VkCompute.Image image = (VkCompute.Image) world;
        int size = image.width * image.height * 4;
        if (mapBytes == null) mapBytes = compute.staging(size);
        MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), MemoryUtil.memAddress(mapBytes.bytes), size);
        mapBytes.flush();
        compute.all(commands());
        compute.copyIn(commands(), mapBytes.buffer, image);
        compute.all(commands());
    }

    /** Frees everything. The graphics card must be done with it (the caller waits for that, or puts this off until it is). */
    @Override
    void close() {
        compute.close();
    }
}
