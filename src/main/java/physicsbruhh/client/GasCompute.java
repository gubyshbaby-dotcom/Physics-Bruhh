package physicsbruhh.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gas solver as compute shaders: the same steps as {@link GasGpu}, each run by the graphics
 * card as a program over the cells of a grid instead of drawn as a picture of it.
 *
 * <p>The game's graphics layer has no such programs, so they are run by the calls of what is under
 * it: Vulkan ({@link GasComputeVulkan}) or OpenGL 4.3 and later ({@link GasComputeOpenGl}). Both
 * do what this class asks of them, and this class knows nothing of the game: it also runs in a
 * test rig without it.
 *
 * <p>The programs are the fragment shaders of the passes themselves ({@code shaders/core/gas_*.fsh}),
 * wrapped ({@link #wrap}): where a pass draws a rectangle for every layer of the part of the box
 * that is computed and the shader is run for every pixel of it, here the shader's body is called
 * for every cell of that part and what it would have drawn is stored. So both ways of computing
 * give the same numbers, and a change to a pass is a change to both.
 *
 * <p>What this way saves is what drawing costs beside the arithmetic: a render target set up and
 * cleared for every pass. A texture is cleared here only when cells of it that held something fall
 * out of the part that is computed.
 */
abstract class GasCompute {
    /** Kinds of image: four half numbers a cell (velocity, gas), four whole ones (pressure), four bytes (the map of the world). */
    static final int HALF = 0, WHOLE = 1, BYTES = 2;

    final GasBox box;
    int steps;

    // ------------------------------------------------------------------ what the graphics card's side does

    /** An image of the given kind: the grid of a field, laid out as {@link GasBox} says. All zero. */
    protected abstract Object image(int kind, int width, int height, String name);

    /** A compute program from GLSL that reads {@code samplers} images and writes one of the given kind. */
    protected abstract Object program(String name, String source, int samplers, int writes);

    /** This step's numbers ({@code GasGrid} in gas.glsl), for the programs run until the next call. */
    protected abstract void numbers(ByteBuffer grid);

    /** Runs a program over {@code x * y * z} groups of 8 by 8 cells. {@code linear}: whether each image is read smoothly between cells. */
    protected abstract void run(Object program, Object[] reads, boolean[] linear, Object writes, int x, int y, int z);

    /** An image all zero again. */
    protected abstract void clear(Object image);

    /** The map of the world into its image (four bytes a block, {@link GasBox#blockPixel}). */
    protected abstract void upload(Object world, ByteBuffer bytes);

    /** Frees everything. */
    abstract void close();

    // ------------------------------------------------------------------ the programs

    private static final Pattern INCLUDE = Pattern.compile("(?m)^#include <physicsbruhh:([a-z_.]+)>\\s*$");
    private static final Pattern SAMPLER = Pattern.compile("(?m)^uniform sampler2D (\\w+);");

    /** A pass as a compute program: its source, and the names of the images it reads in the order of their bindings (1, 2, ...; the numbers are at 0, the image written after the last read). */
    record Wrapped(String source, String[] samplers) {}

    /**
     * The fragment shader of a pass made into a compute shader.
     *
     * @param read     gives the text of a file under {@code shaders/} ("core/gas_spin.fsh", "include/gas.glsl")
     * @param fragment the pass ("gas_spin")
     * @param define   what the pass's pipeline tells its shaders ("LEVEL2"), or null
     * @param over     the cells the pass is run for: those of the "fine" grid, of a "level" of the pressure solve (the
     *                 coarse grid or one under it, as the define says), or the "blocks" of the box
     * @param version  the first line ("#version 450")
     * @param format   how the image written is declared ("rgba16f")
     */
    static Wrapped wrap(Function<String, String> read, String fragment, String define, String over, String version, String format) {
        String text = read.apply("core/" + fragment + ".fsh");
        for (int depth = 0; depth < 8; depth++) {
            Matcher include = INCLUDE.matcher(text);
            if (!include.find()) break;
            StringBuilder out = new StringBuilder();
            do { include.appendReplacement(out, Matcher.quoteReplacement(read.apply("include/" + include.group(1)))); } while (include.find());
            include.appendTail(out);
            text = out.toString();
        }
        text = text.replaceAll("(?m)^#(version|extension).*$", "");
        text = text.replace("layout(std140) uniform GasGrid", "layout(std140, binding = 0) uniform GasGrid");
        List<String> samplers = new ArrayList<>();
        Matcher sampler = SAMPLER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (sampler.find()) {
            samplers.add(sampler.group(1));
            sampler.appendReplacement(out, "layout(binding = " + samplers.size() + ") uniform sampler2D " + sampler.group(1) + ";");
        }
        sampler.appendTail(out);
        text = out.toString();
        if (!text.contains("layout(location = 0) out vec4 fragColor;")) throw new IllegalStateException(fragment + " does not write fragColor the way the passes do");
        text = text.replace("layout(location = 0) out vec4 fragColor;", "");
        text = text.replace("void main()", "void gasMain()").replace("gl_FragCoord", "gasFragCoord");
        String head = version + "\n" + (define == null ? "" : "#define " + define + "\n")
                + "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n"
                + "layout(binding = " + (samplers.size() + 1) + ", " + format + ") uniform writeonly image2D GasOut;\n"
                + "vec4 gasFragCoord;\nvec4 fragColor;\n";
        String tail = "\n// For every cell of the part of the grid that is computed (as gas_region.vsh draws it): what the pass would draw there.\n"
                + "void main() {\n"
                + (over.equals("fine") ? "    vec4 grid = GasFine;\n    ivec3 lo = gasLow(grid), hi = gasHigh(grid);\n"
                        : over.equals("blocks") ? "    vec4 grid = GasBlocks;\n    ivec3 lo = ivec3(GasFrom.xyz), hi = ivec3(GasTo.xyz) - 1;\n"
                        : "    vec4 grid = gasLevelGrid(GAS_LEVEL);\n    ivec3 lo = gasLevelFrom(GAS_LEVEL), hi = lo + gasLevelCells(GAS_LEVEL) - 1;\n")
                + "    ivec3 cell = lo + ivec3(gl_GlobalInvocationID.xzy);\n"
                + "    if (any(greaterThan(cell, hi))) return;\n"
                + "    ivec2 pixel = gasPixel(grid, cell);\n"
                + "    gasFragCoord = vec4(vec2(pixel) + 0.5, 0.0, 1.0);\n"
                + "    fragColor = vec4(0.0);\n"
                + "    gasMain();\n"
                + "    imageStore(GasOut, pixel, fragColor);\n"
                + "}\n";
        return new Wrapped(head + text + tail, samplers.toArray(new String[0]));
    }

    /** A pass ready to run: its program and where each image it reads goes. */
    private final class Pass {
        final String name;
        final Object program;
        final String[] samplers;
        /** Which level of the pressure solve it works on (0 the coarse grid), or FINE for the fine grid, or BLOCKS for the blocks of the box. */
        final int level;

        Pass(Function<String, String> read, String version, String fragment, int level, int writes) {
            String define = level <= 0 ? null : "LEVEL" + level;
            Wrapped wrapped = wrap(read, fragment, define, level == FINE ? "fine" : level == BLOCKS ? "blocks" : "level", version, writes == WHOLE ? "rgba32f" : "rgba16f");
            this.name = fragment + (level > 0 ? " " + level : "");
            this.samplers = wrapped.samplers;
            this.level = level;
            this.program = program(name, wrapped.source, samplers.length, writes);
        }
    }

    private static final int FINE = -1, BLOCKS = -2;
    private Pass spin, velocityPass, divergence, project, carry, burn, where;
    private final Pass[] round = new Pass[GasBox.UNDER + 1], down = new Pass[GasBox.UNDER], up = new Pass[GasBox.UNDER];

    // ------------------------------------------------------------------ the grids

    /** An image and the part of it (in blocks of the box, as when it was written) that may hold something. */
    private static final class Field {
        final Object image;
        final int[] used = { 0, 0, 0, 0, 0, 0 };

        Field(Object image) {
            this.image = image;
        }
    }

    private final Field[] velocity = new Field[2], gas = new Field[3];
    private final Field[][] pressure = new Field[GasBox.UNDER + 1][2];
    /** What each block held after the last step: the steps over the fine grid leave alone the blocks nothing can happen in (gasAwake in gas.glsl). */
    private Field spinField, world, info;
    /** Which of each holds what the last step left. */
    private int v, p, g;
    private final int[] part = new int[6];

    protected GasCompute(GasBox box) {
        this.box = box;
    }

    /**
     * Sets everything up; called by the constructors of the two kinds once they can make programs and images.
     *
     * @param read    gives the text of a file under {@code shaders/}
     * @param version the first line of the programs ("#version 450")
     */
    protected final void build(Function<String, String> read, String version) {
        spin = new Pass(read, version, "gas_spin", 0, HALF);
        velocityPass = new Pass(read, version, "gas_velocity", 0, HALF);
        divergence = new Pass(read, version, "gas_divergence", 0, WHOLE);
        project = new Pass(read, version, "gas_project", 0, HALF);
        carry = new Pass(read, version, "gas_carry", FINE, HALF);
        burn = new Pass(read, version, "gas_burn", FINE, HALF);
        where = new Pass(read, version, "gas_where", BLOCKS, HALF);
        for (int level = 0; level <= GasBox.UNDER; level++) {
            round[level] = new Pass(read, version, "gas_pressure", level, WHOLE);
            if (level > 0) down[level - 1] = new Pass(read, version, "gas_down", level, WHOLE);
            if (level < GasBox.UNDER) up[level] = new Pass(read, version, "gas_up", level, WHOLE);
        }
        for (int i = 0; i < 2; i++) {
            velocity[i] = new Field(image(HALF, box.coarseW, box.coarseH, "velocity " + i));
            pressure[0][i] = new Field(image(WHOLE, box.coarseW, box.coarseH, "pressure " + i));
            for (int level = 1; level <= GasBox.UNDER; level++) {
                pressure[level][i] = new Field(image(WHOLE, box.underW[level - 1], box.underH[level - 1], "pressure " + i + ", level " + level));
            }
        }
        spinField = new Field(image(HALF, box.coarseW, box.coarseH, "spin"));
        for (int i = 0; i < 3; i++) gas[i] = new Field(image(HALF, box.fineW, box.fineH, "smoke, heat, fuel " + i));
        world = new Field(image(BYTES, box.blockW, box.blockH, "map of the world"));
        info = new Field(image(HALF, box.blockW, box.blockH, "what each block holds"));
    }

    /** The image that holds smoke, heat and fuel as the last step left them (four half numbers a cell, laid out as {@link GasBox} says). */
    Object gas() {
        return gas[g].image;
    }

    /** The part of the box the last step computed: blocks, the least corner, then the corner just past the greatest. */
    int[] part() {
        return part;
    }

    /** Everything empty again. */
    void forget() {
        for (Field field : velocity) empty(field);
        for (Field[] level : pressure) for (Field field : level) empty(field);
        for (Field field : gas) empty(field);
        empty(spinField);
        empty(info);
    }

    private void empty(Field field) {
        clear(field.image);
        java.util.Arrays.fill(field.used, 0);
    }

    /**
     * Before a field is written for the part of the box computed now: if it may hold something outside that part,
     * it is emptied first (what is read of it later must be nothing there); then that part is counted as used.
     */
    private Object writing(Field field) {
        int[] u = field.used;
        boolean holds = u[3] > u[0] && u[4] > u[1] && u[5] > u[2];
        if (holds && (u[0] < part[0] || u[1] < part[1] || u[2] < part[2] || u[3] > part[3] || u[4] > part[4] || u[5] > part[5])) {
            clear(field.image);
            holds = false;
        }
        if (!holds) {
            System.arraycopy(part, 0, u, 0, 6);
        } else {
            for (int i = 0; i < 3; i++) { u[i] = Math.min(u[i], part[i]); u[i + 3] = Math.max(u[i + 3], part[i + 3]); }
        }
        return field.image;
    }

    private void run(Pass pass, Field writes, Object... named) {
        Object[] reads = new Object[pass.samplers.length];
        boolean[] linear = new boolean[pass.samplers.length];
        for (int i = 0; i < pass.samplers.length; i++) {
            boolean found = false;
            for (int k = 0; k + 2 < named.length; k += 3) {
                if (pass.samplers[i].equals(named[k])) { reads[i] = ((Field) named[k + 1]).image; linear[i] = (Boolean) named[k + 2]; found = true; break; }
            }
            if (!found) throw new IllegalStateException("the pass " + pass.name + " reads " + pass.samplers[i] + " and is not given it");
        }
        // groups of 8 by 8 cells along x and z, one layer each, over the part of the pass's grid that is computed
        int cells = pass.level == FINE ? box.cpb * box.ratio : pass.level == BLOCKS ? 1 : box.cpb;
        int shift = Math.max(0, pass.level);
        int x = ((part[3] - part[0]) * cells + (1 << shift) - 1) >> shift, y = ((part[4] - part[1]) * cells + (1 << shift) - 1) >> shift, z = ((part[5] - part[2]) * cells + (1 << shift) - 1) >> shift;
        run(pass.program, reads, linear, writing(writes), (x + 7) / 8, (z + 7) / 8, y);
    }

    /**
     * One step.
     *
     * @param grid  the grid's numbers for this step ({@link GasBox#grid}), {@link GasBox#GRID_FLOATS} of them
     * @param map   the map of the world if it has changed since the last step (four bytes a block), or null
     * @param part  the part of the box to compute: blocks, the least corner, then the corner just past the greatest
     */
    void step(ByteBuffer grid, ByteBuffer map, int[] part) {
        System.arraycopy(part, 0, this.part, 0, 6);
        if (map != null || steps == 0) {
            if (map == null) throw new IllegalStateException("the first step needs the map of the world");
            upload(world.image, map);
        }
        numbers(grid);
        final Field was = velocity[v], pushed = velocity[1 - v], before = gas[g], carried = gas[(g + 1) % 3], result = gas[(g + 2) % 3];
        run(spin, spinField, "VelSampler", was, false);
        run(velocityPass, pushed, "VelSampler", was, true, "SpinSampler", spinField, false, "GasSampler", before, true, "WorldSampler", world, false);
        run(divergence, pressure[0][1 - p], "VelSampler", pushed, false, "GasSampler", before, true, "PressureSampler", pressure[0][p], false, "WorldSampler", world, false);
        // the pressure, as Gas::pressure in gas.rs: down the levels and back up, once or more
        final int[] at = new int[GasBox.UNDER + 1];
        at[0] = 1 - p;
        final int cycles = Math.max(1, box.rounds / 4), each = Math.max(1, box.rounds / (2 * cycles));
        for (int cycle = 0; cycle < cycles; cycle++) downAndUp(0, at, each);
        p = at[0];
        run(project, velocity[v], "VelSampler", pushed, false, "PressureSampler", pressure[0][p], false);
        run(carry, carried, "GasSampler", before, true, "VelSampler", velocity[v], true, "WorldSampler", world, false, "InfoSampler", info, false);
        run(burn, result, "CarriedSampler", carried, true, "GasSampler", before, false, "VelSampler", velocity[v], true, "WorldSampler", world, false, "InfoSampler", info, false);
        run(where, info, "GasSampler", result, false);
        g = (g + 2) % 3;
        steps++;
    }

    private void even(int level, int[] at, int rounds) {
        for (int i = 0; i < rounds; i++) {
            run(round[level], pressure[level][1 - at[level]], "PressureSampler", pressure[level][at[level]], false);
            at[level] = 1 - at[level];
        }
    }

    private void downAndUp(int level, int[] at, int each) {
        if (level == GasBox.UNDER) {
            even(level, at, 2 * each + 4);
            return;
        }
        even(level, at, each);
        run(down[level], pressure[level + 1][0], "PressureSampler", pressure[level][at[level]], false);
        at[level + 1] = 0;
        downAndUp(level + 1, at, each);
        run(up[level], pressure[level][1 - at[level]], "PressureSampler", pressure[level][at[level]], false, "UnderSampler", pressure[level + 1][at[level + 1]], false);
        at[level] = 1 - at[level];
        even(level, at, each);
    }
}
