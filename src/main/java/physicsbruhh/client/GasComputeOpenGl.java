package physicsbruhh.client;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.util.function.Function;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link GasCompute} on OpenGL, by OpenGL's own calls on the thread that draws. The game asks for
 * OpenGL 3.3 and makes do with it; compute shaders came with 4.3, and this uses the calls of 4.5
 * that leave the bindings the game keeps track of alone. Graphics cards of the last ten years have
 * it, whatever version the game asked for; where it is not there, {@link #supported} says so.
 *
 * <p>The few bindings that have to be changed while the programs run (the program in use, the
 * first texture units and their samplers, the first uniform buffer) are put back as they were
 * ({@link #begin}, {@link #end}): the game does not ask OpenGL what is bound, it remembers.
 */
final class GasComputeOpenGl extends GasCompute {
    /** Texture units the programs read through: a pass reads four images at most. */
    private static final int UNITS = 5;

    /** Whether the OpenGL of this thread can run the gas solver's programs. */
    static boolean supported() {
        try {
            GLCapabilities capabilities = GL.getCapabilities();
            return capabilities != null && capabilities.OpenGL45;
        } catch (Throwable t) {
            return false;
        }
    }

    private record Image(int id, int format, int width, int height) {}

    private final java.util.List<Integer> textures = new java.util.ArrayList<>(), programs = new java.util.ArrayList<>();
    private final int numbers, linearSampler, nearestSampler;
    private boolean begun;
    // what was bound before a step
    private int oldProgram, oldUniformBuffer, oldUniformBuffer0, oldUnpackBuffer;
    private long oldUniformStart, oldUniformSize;
    private final int[] oldTexture = new int[UNITS], oldSampler = new int[UNITS];

    GasComputeOpenGl(GasBox box, Function<String, String> read) {
        super(box);
        if (!supported()) throw new IllegalStateException("this OpenGL has no compute shaders (4.5 is needed)");
        numbers = glCreateBuffers();
        glNamedBufferData(numbers, GasBox.GRID_FLOATS * 4L, GL_DYNAMIC_DRAW);
        linearSampler = sampler(GL_LINEAR);
        nearestSampler = sampler(GL_NEAREST);
        try {
            build(read, "#version 430 core");
        } catch (Throwable t) {
            close();
            throw t;
        }
    }

    private static int sampler(int filter) {
        int sampler = glCreateSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, filter);
        glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, filter);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return sampler;
    }

    /** The OpenGL name of an image (a texture of one level; {@link #gas} gives the image of the result). */
    static int name(Object image) {
        return ((Image) image).id;
    }

    /** Before a step: remembers what is bound where the programs need their own things. */
    void begin() {
        oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        oldUniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
        oldUniformBuffer0 = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0);
        oldUniformStart = glGetInteger64i(GL_UNIFORM_BUFFER_START, 0);
        oldUniformSize = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 0);
        oldUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        for (int unit = 0; unit < UNITS; unit++) {
            oldTexture[unit] = glGetIntegeri(GL_TEXTURE_BINDING_2D, unit);
            oldSampler[unit] = glGetIntegeri(GL_SAMPLER_BINDING, unit);
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, numbers);
        begun = true;
    }

    /** After a step: what was computed can be read by anything, and what was bound before is bound again. */
    void end() {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        for (int unit = 0; unit < UNITS; unit++) {
            glBindTextureUnit(unit, oldTexture[unit]);
            glBindSampler(unit, oldSampler[unit]);
        }
        for (int unit = 1; unit <= UNITS; unit++) glBindImageTexture(unit, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
        if (oldUniformBuffer0 != 0 && oldUniformSize > 0L) glBindBufferRange(GL_UNIFORM_BUFFER, 0, oldUniformBuffer0, oldUniformStart, oldUniformSize);
        else glBindBufferBase(GL_UNIFORM_BUFFER, 0, oldUniformBuffer0);
        glBindBuffer(GL_UNIFORM_BUFFER, oldUniformBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, oldUnpackBuffer);
        glUseProgram(oldProgram);
        begun = false;
    }

    @Override
    protected Object image(int kind, int width, int height, String name) {
        int format = kind == HALF ? GL_RGBA16F : kind == WHOLE ? GL_RGBA32F : GL_RGBA8;
        int id = glCreateTextures(GL_TEXTURE_2D);
        textures.add(id);
        glTextureStorage2D(id, 1, format, width, height);
        glTextureParameteri(id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        Image image = new Image(id, format, width, height);
        clear(image);
        return image;
    }

    @Override
    protected Object program(String name, String source, int samplers, int writes) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("compute shader " + name + ": " + log);
        }
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("compute shader " + name + ": " + log);
        }
        programs.add(program);
        return new int[] { program, samplers };
    }

    @Override
    protected void numbers(ByteBuffer grid) {
        glNamedBufferSubData(numbers, 0L, grid);
    }

    @Override
    protected void run(Object program, Object[] reads, boolean[] linear, Object writes, int x, int y, int z) {
        if (!begun) throw new IllegalStateException("the gas solver's programs are run between begin and end");
        int[] p = (int[]) program;
        glUseProgram(p[0]);
        // (the images read are at bindings 1, 2, ... and the one written after them: GasCompute.wrap)
        for (int i = 0; i < reads.length; i++) {
            glBindTextureUnit(i + 1, ((Image) reads[i]).id);
            glBindSampler(i + 1, linear[i] ? linearSampler : nearestSampler);
        }
        Image out = (Image) writes;
        glBindImageTexture(p[1] + 1, out.id, 0, false, 0, GL_WRITE_ONLY, out.format);
        glDispatchCompute(x, y, z);
        // what this wrote is read by the next
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    @Override
    protected void clear(Object image) {
        Image target = (Image) image;
        glClearTexImage(target.id, 0, GL_RGBA, target.format == GL_RGBA8 ? GL_UNSIGNED_BYTE : GL_FLOAT, (ByteBuffer) null);
    }

    @Override
    protected void upload(Object world, ByteBuffer bytes) {
        Image image = (Image) world;
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT), rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH), skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS), skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1); glPixelStorei(GL_UNPACK_ROW_LENGTH, 0); glPixelStorei(GL_UNPACK_SKIP_ROWS, 0); glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glTextureSubImage2D(image.id, 0, 0, 0, image.width, image.height, GL_RGBA, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(bytes));
        glPixelStorei(GL_UNPACK_ALIGNMENT, alignment); glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength); glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows); glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
    }

    /**
     * Rectangles of an image copied into the same places of another texture of the same size and kind (the game's
     * own, by its OpenGL name): {@code rectangles} holds x, y, width, height of each.
     */
    static void copy(Object image, int to, int[] rectangles, int count) {
        int from = ((Image) image).id;
        for (int i = 0; i < count; i++) {
            int x = rectangles[i * 4], y = rectangles[i * 4 + 1];
            glCopyImageSubData(from, GL_TEXTURE_2D, 0, x, y, 0, to, GL_TEXTURE_2D, 0, x, y, 0, rectangles[i * 4 + 2], rectangles[i * 4 + 3], 1);
        }
    }

    @Override
    void close() {
        for (int texture : textures) glDeleteTextures(texture);
        for (int program : programs) glDeleteProgram(program);
        textures.clear(); programs.clear();
        glDeleteBuffers(numbers);
        glDeleteSamplers(linearSampler);
        glDeleteSamplers(nearestSampler);
    }
}
