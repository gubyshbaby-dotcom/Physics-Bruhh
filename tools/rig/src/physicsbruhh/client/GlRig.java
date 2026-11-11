package physicsbruhh.client;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/** The gas solver as compute shaders on OpenGL, without the game: the same scene and sums as ComputeRig. */
public class GlRig {
    public static void main(String[] args) throws Exception {
        String scene = args.length > 0 ? args[0] : "fire";
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int cpb = args.length > 2 ? Integer.parseInt(args[2]) : 2, ratio = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        boolean wide = scene.equals("fires") || scene.equals("far");
        GasBox box = wide ? new GasBox(24, 16, 16, cpb, ratio, 4) : new GasBox(10, 14, 10, cpb, ratio, 4);
        Function<String, String> read = name -> { try { return Files.readString(Path.of(ComputeRig.ROOT + name)); } catch (Exception e) { throw new RuntimeException(e); } };

        if (!glfwInit()) throw new IllegalStateException("no GLFW");
        // as the game: a 3.3 core context is asked for; what comes back is whatever the driver has
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE); glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "rig", 0L, 0L);
        if (window == 0L) throw new IllegalStateException("no window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("OpenGL: " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER) + "; compute: " + GasComputeOpenGl.supported() + "; box " + box);

        ByteBuffer map = MemoryUtil.memCalloc(box.blockW * box.blockH * 4);
        for (int y = 0; y < box.by; y++) for (int z = 0; z < box.bz; z++) for (int x = 0; x < box.bx; x++) ComputeRig.set(box, map, x, y, z, y < 2, 0);
        for (int z = 0; z < box.bz; z++) for (int x = 0; x < box.bx; x++) map.put(box.blockPixel(x, 1, z), (byte) 255);
        switch (scene) {
            case "camp" -> ComputeRig.set(box, map, 5, 2, 5, false, 2);
            case "far" -> ComputeRig.set(box, map, 4, 2, 8, false, 1);
            default -> ComputeRig.set(box, map, 5, 2, 5, false, 1);
        }
        int[] part = { 0, 0, 0, box.bx, box.by, box.bz };
        if (System.getenv("REGION") != null) { String[] v = System.getenv("REGION").split(","); for (int i = 0; i < 6; i++) part[i] = Integer.parseInt(v[i]); }

        // something bound everywhere the solver changes bindings, to see that it puts them back
        int someTexture = glCreateTextures(GL_TEXTURE_2D), someBuffer = glCreateBuffers(), someSampler = glCreateSamplers();
        glTextureStorage2D(someTexture, 1, GL_RGBA8, 4, 4);
        glNamedBufferData(someBuffer, 256L, GL_STATIC_DRAW);
        for (int unit = 0; unit < 6; unit++) { glBindTextureUnit(unit, someTexture); glBindSampler(unit, someSampler); }
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, someBuffer, 64L, 128L);

        long built = System.nanoTime();
        GasComputeOpenGl gas = new GasComputeOpenGl(box, read);
        System.out.println("programs built in " + (System.nanoTime() - built) / 1000000 + " ms");
        ByteBuffer grid = MemoryUtil.memCalloc(GasBox.GRID_FLOATS * 4).order(ByteOrder.nativeOrder());
        FloatBuffer out = MemoryUtil.memAllocFloat(box.fineW * box.fineH * 4);
        float dt = 1.0F / 30.0F, time = 0.0F, fpb = cpb * ratio, cell = 1.0F / (fpb * fpb * fpb);
        double took = 0.0;
        for (int step = 0; step < steps; step++) {
            box.grid(grid.asFloatBuffer(), dt, time, 0, 0, 0, new float[0], 0, part);
            long start = System.nanoTime();
            gas.begin();
            gas.step(grid, step == 0 ? map : null, part);
            gas.end();
            glFinish();
            took += (System.nanoTime() - start) / 1.0e9;
            time += dt;
            if (step % 5 == 4 || System.getenv("EVERY") != null) {
                glGetTextureImage(GasComputeOpenGl.name(gas.gas()), 0, GL_RGBA, GL_FLOAT, out);
                double smoke = 0.0, heat = 0.0, fuel = 0.0;
                for (int i = 0; i < box.fineW * box.fineH; i++) { smoke += out.get(i * 4); heat += out.get(i * 4 + 1); fuel += out.get(i * 4 + 2); }
                System.out.println(String.format("step %d: fuel %.7f heat %.7f smoke %.7f", step + 1, fuel * cell, heat * cell, smoke * cell));
            }
        }
        System.out.println(String.format("a step takes %.1f ms here", took / steps * 1000.0));
        boolean back = glGetInteger(GL_CURRENT_PROGRAM) == 0 && glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0) == someBuffer && glGetInteger64i(GL_UNIFORM_BUFFER_START, 0) == 64L && glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 0) == 128L;
        for (int unit = 0; unit < 6; unit++) back &= glGetIntegeri(GL_TEXTURE_BINDING_2D, unit) == someTexture && glGetIntegeri(GL_SAMPLER_BINDING, unit) == someSampler;
        System.out.println("what was bound before is bound again: " + back + "; OpenGL errors: " + glGetError());
        gas.close();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
