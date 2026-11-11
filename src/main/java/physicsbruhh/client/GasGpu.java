package physicsbruhh.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector4f;
import physicsbruhh.PhysicsBruhh;

/**
 * The gas solver on the graphics card: the steps of {@code native/src/gas.rs}, each one pass over
 * a texture ({@code shaders/core/gas_*.fsh}).
 *
 * <pre>
 *   spin        velocity                         -> how the air spins                (coarse)
 *   velocity    velocity, spin, gas, world       -> velocity carried and pushed      (coarse)
 *   divergence  velocity, gas, pressure, world   -> what the pressure has to undo    (coarse)
 *   pressure    pressure                         -> pressure, a round                (coarse, and the grids under it)
 *   down        pressure                         -> what is left, for the grid under (the grids under the coarse one)
 *   up          pressure, the grid under's       -> pressure with its answer added   (coarse, and the grids under it but the last)
 *   project     velocity, pressure               -> velocity that piles nothing up   (coarse)
 *   carry       gas, velocity, world             -> gas carried along                (fine)
 *   burn        carried, gas, velocity, world    -> gas burnt, cooled, fed           (fine)
 * </pre>
 *
 * <p>The pressure is solved in steps down and up a few grids, each twice as coarse as the one above
 * it ({@code Gas::pressure} in gas.rs says why): a few rounds, what they leave undone handed down,
 * the answer taken back up, a few rounds more. Its textures hold whole 32-bit numbers: what is
 * handed down is the small difference of large ones.
 */
final class GasGpu {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, path);
    }

    static BindGroupLayout samplers(String... names) {
        BindGroupLayout.Builder builder = BindGroupLayout.builder();
        for (String name : names) builder = builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        return builder.build();
    }

    /** A pass over a whole texture that reads the grid's numbers and the named textures. */
    static RenderPipeline.Builder pass(String name, String fragment, String... textures) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/" + fragment))
                .withBindGroupLayout(GasDraw.GRID)
                .withBindGroupLayout(samplers(textures))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
    }

    /**
     * A step of the solver: drawn not over the whole texture of its grid but over one rectangle for each layer of the
     * part of the box that is computed ({@code shaders/core/gas_region.vsh}); the rest of the texture is cleared.
     */
    private static RenderPipeline step(String name, GpuFormat format, boolean fine, String... textures) {
        return step(name, name, format, fine ? "FINE" : null, textures);
    }

    /** The same with the shaders told one more thing ({@code define}): that the grid is the fine one, or which level of the pressure solve the pass works on. */
    private static RenderPipeline step(String name, String fragment, GpuFormat format, String define, String... textures) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/gas_region"))
                .withFragmentShader(id("core/" + fragment))
                .withBindGroupLayout(GasDraw.GRID)
                .withBindGroupLayout(samplers(textures))
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL));
        if (define != null) builder.withShaderDefine(define);
        return builder.build();
    }

    /** What the shaders of a pass are told when it works on a level of the pressure solve other than the coarse grid itself. */
    private static String level(int level) {
        return level == 0 ? null : "LEVEL" + level;
    }

    /** The pressure and what it has to undo, on every level: two whole numbers a cell. */
    private static final GpuFormat PRESSURE_FORMAT = GpuFormat.RG32_FLOAT;

    private static final RenderPipeline SPIN = step("gas_spin", GpuFormat.RGBA16_FLOAT, false, "VelSampler");
    private static final RenderPipeline VELOCITY = step("gas_velocity", GpuFormat.RGBA16_FLOAT, false, "VelSampler", "SpinSampler", "GasSampler", "WorldSampler");
    private static final RenderPipeline DIVERGENCE = step("gas_divergence", PRESSURE_FORMAT, false, "VelSampler", "GasSampler", "PressureSampler", "WorldSampler");
    /** A round on the coarse grid and on each grid under it; what a level leaves undone handed to the one under it (by the level written); a level's answer taken up (by the level written). */
    private static final RenderPipeline[] PRESSURE = new RenderPipeline[GasBox.UNDER + 1], DOWN = new RenderPipeline[GasBox.UNDER], UP = new RenderPipeline[GasBox.UNDER];
    static {
        for (int level = 0; level <= GasBox.UNDER; level++) {
            PRESSURE[level] = step(level == 0 ? "gas_pressure" : "gas_pressure_" + level, "gas_pressure", PRESSURE_FORMAT, level(level), "PressureSampler");
            if (level > 0) DOWN[level - 1] = step("gas_down_" + level, "gas_down", PRESSURE_FORMAT, level(level), "PressureSampler");
            if (level < GasBox.UNDER) UP[level] = step("gas_up_" + level, "gas_up", PRESSURE_FORMAT, level(level), "PressureSampler", "UnderSampler");
        }
    }
    private static final RenderPipeline PROJECT = step("gas_project", GpuFormat.RGBA16_FLOAT, false, "VelSampler", "PressureSampler");
    private static final RenderPipeline CARRY = step("gas_carry", GpuFormat.RGBA16_FLOAT, true, "GasSampler", "VelSampler", "WorldSampler");
    private static final RenderPipeline BURN = step("gas_burn", GpuFormat.RGBA16_FLOAT, true, "CarriedSampler", "GasSampler", "VelSampler", "WorldSampler");
    private static final RenderPipeline[] ALL;
    static {
        java.util.List<RenderPipeline> all = new java.util.ArrayList<>(java.util.List.of(SPIN, VELOCITY, DIVERGENCE, PROJECT, CARRY, BURN));
        all.addAll(java.util.List.of(PRESSURE)); all.addAll(java.util.List.of(DOWN)); all.addAll(java.util.List.of(UP));
        ALL = all.toArray(new RenderPipeline[0]);
    }

    static void register(RegisterRenderPipelinesEvent event) {
        // optional: where one of them does not compile the game still starts, and the processor computes the gas
        for (RenderPipeline pipeline : ALL) event.registerOptionalPipeline(pipeline);
    }

    /** Whether all the passes are there on this machine. */
    static boolean available() {
        for (RenderPipeline pipeline : ALL) {
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
            if (compiled == null || compiled.isClosed()) return false;
        }
        return true;
    }

    static CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiled == null || compiled.isClosed()) throw new IllegalStateException("shader pipeline " + pipeline.getLocation() + " is not available");
        return compiled;
    }

    private static final Vector4f NOTHING = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

    final GasBox box;
    private final TextureTarget[] velocity = new TextureTarget[2], gas = new TextureTarget[3];
    /** The pressure on the coarse grid (the first) and on each grid under it: two textures each, written in turn. */
    private final TextureTarget[][] pressure = new TextureTarget[GasBox.UNDER + 1][2];
    private final TextureTarget spin;
    /** Which of each holds what the last step left. */
    private int v, p, g;
    int steps;

    GasGpu(GasBox box) {
        this.box = box;
        for (int i = 0; i < 2; i++) {
            velocity[i] = new TextureTarget("Gas: velocity " + i, box.coarseW, box.coarseH, GpuFormat.RGBA16_FLOAT, null);
            pressure[0][i] = new TextureTarget("Gas: pressure " + i, box.coarseW, box.coarseH, PRESSURE_FORMAT, null);
            for (int level = 1; level <= GasBox.UNDER; level++) {
                pressure[level][i] = new TextureTarget("Gas: pressure " + i + ", level " + level, box.underW[level - 1], box.underH[level - 1], PRESSURE_FORMAT, null);
            }
        }
        spin = new TextureTarget("Gas: spin", box.coarseW, box.coarseH, GpuFormat.RGBA16_FLOAT, null);
        for (int i = 0; i < 3; i++) gas[i] = new TextureTarget("Gas: smoke, heat, fuel " + i, box.fineW, box.fineH, GpuFormat.RGBA16_FLOAT, null);
        clear();
    }

    void clear() {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (TextureTarget target : velocity) encoder.clearColorTexture(target.getColorTexture(), NOTHING);
        for (TextureTarget[] level : pressure) for (TextureTarget target : level) encoder.clearColorTexture(target.getColorTexture(), NOTHING);
        for (TextureTarget target : gas) encoder.clearColorTexture(target.getColorTexture(), NOTHING);
        encoder.clearColorTexture(spin.getColorTexture(), NOTHING);
    }

    /** Smoke, heat and fuel as the last step left them. */
    GpuTextureView gas() {
        return gas[g].getColorTextureView();
    }

    /** One pass: the whole texture cleared, then a rectangle for each of {@code layers} layers of the part that is computed. */
    private static void run(CommandEncoder encoder, String name, RenderPipeline pipeline, TextureTarget to, GpuBuffer grid, int layers, Consumer<RenderPass> textures) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(layers * 6);
        try (RenderPass pass = encoder.createRenderPass(() -> name, to.getColorTextureView(), Optional.of(NOTHING))) {
            pass.setPipeline(compiled(pipeline));
            pass.setUniform("GasGrid", grid);
            textures.accept(pass);
            pass.setVertexBuffer(0, null);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(layers * 6, 1, 0, 0, 0);
        }
    }

    /**
     * One step. The grid's numbers (how long the step is, how far the box has moved, the blobs, the part of the box
     * that is computed) are in {@code grid}; {@code layers} is how many layers of blocks that part is high.
     */
    void step(CommandEncoder encoder, GpuBuffer grid, GpuTextureView world, int layers) {
        final int coarse = layers * box.cpb, fine = coarse * box.ratio;
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final GpuTextureView was = velocity[v].getColorTextureView(), pushed = velocity[1 - v].getColorTextureView();
        final GpuTextureView before = gas[g].getColorTextureView(), carried = gas[(g + 1) % 3].getColorTextureView();
        run(encoder, "Gas: spin", SPIN, spin, grid, coarse, pass -> pass.setUniform("VelSampler", was, nearest));
        run(encoder, "Gas: velocity", VELOCITY, velocity[1 - v], grid, coarse, pass -> {
            pass.setUniform("VelSampler", was, linear);
            pass.setUniform("SpinSampler", spin.getColorTextureView(), nearest);
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("WorldSampler", world, nearest);
        });
        final GpuTextureView lastPressure = pressure[0][p].getColorTextureView();
        run(encoder, "Gas: divergence", DIVERGENCE, pressure[0][1 - p], grid, coarse, pass -> {
            pass.setUniform("VelSampler", pushed, nearest);
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("PressureSampler", lastPressure, nearest);
            pass.setUniform("WorldSampler", world, nearest);
        });
        // the pressure, as Gas::pressure in gas.rs: down the levels and back up, once or more
        final int[] at = new int[GasBox.UNDER + 1];
        at[0] = 1 - p;
        final int cycles = Math.max(1, box.rounds / 4), each = Math.max(1, box.rounds / (2 * cycles));
        for (int cycle = 0; cycle < cycles; cycle++) downAndUp(encoder, grid, nearest, 0, at, each, coarse);
        p = at[0];
        final GpuTextureView solved = pressure[0][p].getColorTextureView();
        run(encoder, "Gas: project", PROJECT, velocity[v], grid, coarse, pass -> {
            pass.setUniform("VelSampler", pushed, nearest);
            pass.setUniform("PressureSampler", solved, nearest);
        });
        // velocity[v] holds the new velocity again
        final GpuTextureView now = velocity[v].getColorTextureView();
        run(encoder, "Gas: carry", CARRY, gas[(g + 1) % 3], grid, fine, pass -> {
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("VelSampler", now, linear);
            pass.setUniform("WorldSampler", world, nearest);
        });
        run(encoder, "Gas: burn", BURN, gas[(g + 2) % 3], grid, fine, pass -> {
            pass.setUniform("CarriedSampler", carried, linear);
            pass.setUniform("GasSampler", before, nearest);
            pass.setUniform("VelSampler", now, linear);
            pass.setUniform("WorldSampler", world, nearest);
        });
        g = (g + 2) % 3;
        steps++;
    }

    /** Rounds of the pressure solve on a level; {@code at} says which of the level's two textures holds the pressure. */
    private void even(CommandEncoder encoder, GpuBuffer grid, GpuSampler nearest, int level, int[] at, int rounds, int layers) {
        for (int round = 0; round < rounds; round++) {
            final GpuTextureView from = pressure[level][at[level]].getColorTextureView();
            run(encoder, "Gas: pressure", PRESSURE[level], pressure[level][1 - at[level]], grid, layers, pass -> pass.setUniform("PressureSampler", from, nearest));
            at[level] = 1 - at[level];
        }
    }

    /** A level of the pressure solve and those under it: rounds, what is left handed down, the answer taken up, rounds. {@code layers}: layers of the coarse grid that are computed. */
    private void downAndUp(CommandEncoder encoder, GpuBuffer grid, GpuSampler nearest, int level, int[] at, int each, int layers) {
        final int here = layers + (1 << level) - 1 >> level;
        if (level == GasBox.UNDER) {
            // nothing under the last: more rounds instead
            even(encoder, grid, nearest, level, at, 2 * each + 4, here);
            return;
        }
        final int under = layers + (2 << level) - 1 >> level + 1;
        even(encoder, grid, nearest, level, at, each, here);
        final GpuTextureView left = pressure[level][at[level]].getColorTextureView();
        run(encoder, "Gas: pressure handed down", DOWN[level], pressure[level + 1][0], grid, under, pass -> pass.setUniform("PressureSampler", left, nearest));
        at[level + 1] = 0;
        downAndUp(encoder, grid, nearest, level + 1, at, each, layers);
        final GpuTextureView own = pressure[level][at[level]].getColorTextureView(), answer = pressure[level + 1][at[level + 1]].getColorTextureView();
        run(encoder, "Gas: pressure taken up", UP[level], pressure[level][1 - at[level]], grid, here, pass -> {
            pass.setUniform("PressureSampler", own, nearest);
            pass.setUniform("UnderSampler", answer, nearest);
        });
        at[level] = 1 - at[level];
        even(encoder, grid, nearest, level, at, each, here);
    }

    void close() {
        for (TextureTarget target : velocity) target.destroyBuffers();
        for (TextureTarget[] level : pressure) for (TextureTarget target : level) target.destroyBuffers();
        for (TextureTarget target : gas) target.destroyBuffers();
        spin.destroyBuffers();
    }
}
