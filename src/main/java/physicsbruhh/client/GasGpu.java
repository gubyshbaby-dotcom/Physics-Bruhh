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
 *   carry       gas, velocity, world, info       -> gas carried along                (fine)
 *   burn        carried, gas, velocity, world, info -> gas burnt, cooled, fed        (fine)
 * </pre>
 *
 * <p>The passes over the fine grid are drawn only over the blocks anything can happen in
 * ({@code gasAwake} in gas.glsl, {@code shaders/core/gas_blocks.vsh}): "info" is what each block
 * held after the last step ({@link GasDraw#where}).
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
     * part of the box that is computed ({@code shaders/core/gas_region.vsh}). The rest of the texture holds nothing:
     * it is cleared before the pass if it held anything outside that part ({@code wipe}), and otherwise left alone.
     */
    private static RenderPipeline step(String name, GpuFormat format, boolean fine, String... textures) {
        return pipeline(name, name, format, fine ? "core/gas_blocks" : "core/gas_region", null, textures);
    }

    /** The same with the shaders told one more thing ({@code define}): which level of the pressure solve the pass works on. */
    private static RenderPipeline step(String name, String fragment, GpuFormat format, String define, String... textures) {
        return pipeline(name, fragment, format, "core/gas_region", define, textures);
    }

    /**
     * {@code vertex}: what the pass is drawn over. "core/gas_region": a rectangle for each layer of the part of the box
     * that is computed; "core/gas_blocks" (the fine grid): a rectangle for each layer of each block of it that is awake.
     */
    private static RenderPipeline pipeline(String name, String fragment, GpuFormat format, String vertex, String define, String... textures) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id(vertex))
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
    private static final RenderPipeline CARRY = step("gas_carry", GpuFormat.RGBA16_FLOAT, true, "GasSampler", "VelSampler", "WorldSampler", "InfoSampler");
    private static final RenderPipeline BURN = step("gas_burn", GpuFormat.RGBA16_FLOAT, true, "CarriedSampler", "GasSampler", "VelSampler", "WorldSampler", "InfoSampler");
    /** The same two for a texture that was not cleared first: the blocks that are not awake are drawn too, as nothing ({@code gas_blocks.vsh}). */
    private static final RenderPipeline CARRY_KEPT = pipeline("gas_carry_kept", "gas_carry", GpuFormat.RGBA16_FLOAT, "core/gas_blocks", "GAS_KEPT", "GasSampler", "VelSampler", "WorldSampler", "InfoSampler");
    private static final RenderPipeline BURN_KEPT = pipeline("gas_burn_kept", "gas_burn", GpuFormat.RGBA16_FLOAT, "core/gas_blocks", "GAS_KEPT", "CarriedSampler", "GasSampler", "VelSampler", "WorldSampler", "InfoSampler");
    private static final RenderPipeline[] ALL;
    static {
        java.util.List<RenderPipeline> all = new java.util.ArrayList<>(java.util.List.of(SPIN, VELOCITY, DIVERGENCE, PROJECT, CARRY, BURN, CARRY_KEPT, BURN_KEPT));
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
    /**
     * The textures of the fine grid are left uncleared while the part that is computed is less than this much of the
     * box ({@code PHYSICSBRUHH_GAS_KEEP} in the environment sets it; 1 = always, 0 = never, as it was before).
     */
    private static final float KEEP_UNDER = keepUnder();

    private static float keepUnder() {
        try {
            String set = System.getenv("PHYSICSBRUHH_GAS_KEEP");
            return set == null ? 0.25F : Float.parseFloat(set) >= 1.0F ? 2.0F : Float.parseFloat(set);
        } catch (RuntimeException e) {
            return 0.25F;
        }
    }

    final GasBox box;
    private final TextureTarget[] velocity = new TextureTarget[2], gas = new TextureTarget[3];
    /** The pressure on the coarse grid (the first) and on each grid under it: two textures each, written in turn. */
    private final TextureTarget[][] pressure = new TextureTarget[GasBox.UNDER + 1][2];
    private final TextureTarget spin;
    /** Which of each holds what the last step left. */
    private int v, p, g;
    /**
     * For each texture of the coarse and the fine grid: the part of the box it was last written over (blocks: the
     * least corner, the corner just past the greatest). Outside that part it holds nothing. Not in here: empty.
     */
    private final java.util.IdentityHashMap<TextureTarget, int[]> holds = new java.util.IdentityHashMap<>();
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
        holds.clear();
    }

    /**
     * Whether a texture has to be cleared before it is written over {@code part}: only if it holds something outside
     * that part, which then would be left lying there. As long as the part that is computed stays as it is or grows,
     * nothing is cleared at all, and a pass costs what the part costs and not what the whole box would. (Clearing
     * every texture before every pass was most of the cost of a large box with a small fire in it.)
     */
    private boolean wipe(TextureTarget to, int[] part) {
        int[] had = holds.put(to, part.clone());
        return had != null && (had[0] < part[0] || had[1] < part[1] || had[2] < part[2] || had[3] > part[3] || had[4] > part[4] || had[5] > part[5]);
    }

    /** Smoke, heat and fuel as the last step left them. */
    GpuTextureView gas() {
        return gas[g].getColorTextureView();
    }

    /**
     * One pass: {@code layers} rectangles (one for each layer of the part that is computed; for the fine grid one for
     * each layer of each block of it), over a texture that is cleared first if {@code wipe} says so.
     */
    private static void run(CommandEncoder encoder, String name, RenderPipeline pipeline, TextureTarget to, GpuBuffer grid, int layers, boolean wipe, Consumer<RenderPass> textures) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(layers * 6);
        try (RenderPass pass = encoder.createRenderPass(() -> name, to.getColorTextureView(), wipe ? Optional.of(NOTHING) : Optional.empty())) {
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
     * that is computed) are in {@code grid}; {@code part} is that part once more (blocks: the least corner, then the
     * corner just past the greatest); {@code info}: what each block held after the last step.
     */
    void step(CommandEncoder encoder, GpuBuffer grid, GpuTextureView world, GpuTextureView info, int[] part, float partOfBox) {
        final int coarse = (part[4] - part[1]) * box.cpb;
        // the fine grid: a rectangle for each of its layers through each block of the part
        final int fine = (part[3] - part[0]) * (part[4] - part[1]) * (part[5] - part[2]) * box.cpb * box.ratio;
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final GpuTextureView was = velocity[v].getColorTextureView(), pushed = velocity[1 - v].getColorTextureView();
        final GpuTextureView before = gas[g].getColorTextureView(), carried = gas[(g + 1) % 3].getColorTextureView();
        run(encoder, "Gas: spin", SPIN, spin, grid, coarse, wipe(spin, part), pass -> pass.setUniform("VelSampler", was, nearest));
        run(encoder, "Gas: velocity", VELOCITY, velocity[1 - v], grid, coarse, wipe(velocity[1 - v], part), pass -> {
            pass.setUniform("VelSampler", was, linear);
            pass.setUniform("SpinSampler", spin.getColorTextureView(), nearest);
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("WorldSampler", world, nearest);
        });
        final GpuTextureView lastPressure = pressure[0][p].getColorTextureView();
        run(encoder, "Gas: divergence", DIVERGENCE, pressure[0][1 - p], grid, coarse, wipe(pressure[0][1 - p], part), pass -> {
            pass.setUniform("VelSampler", pushed, nearest);
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("PressureSampler", lastPressure, nearest);
            pass.setUniform("WorldSampler", world, nearest);
        });
        // the pressure, as Gas::pressure in gas.rs: down the levels and back up, once or more
        final int[] at = new int[GasBox.UNDER + 1];
        at[0] = 1 - p;
        final int cycles = Math.max(1, box.rounds / 4), each = Math.max(1, box.rounds / (2 * cycles));
        for (int cycle = 0; cycle < cycles; cycle++) downAndUp(encoder, grid, nearest, 0, at, each, coarse, part);
        p = at[0];
        final GpuTextureView solved = pressure[0][p].getColorTextureView();
        run(encoder, "Gas: project", PROJECT, velocity[v], grid, coarse, wipe(velocity[v], part), pass -> {
            pass.setUniform("VelSampler", pushed, nearest);
            pass.setUniform("PressureSampler", solved, nearest);
        });
        // velocity[v] holds the new velocity again
        final GpuTextureView now = velocity[v].getColorTextureView();
        // A texture of the fine grid that is not cleared gets its blocks that are not awake written over with nothing.
        // That is drawing over the part, where clearing is wiping the whole box: worth it only while the part is small.
        final boolean small = partOfBox < KEEP_UNDER;
        final boolean wipeCarried = wipe(gas[(g + 1) % 3], part) | !small, wipeBurnt = wipe(gas[(g + 2) % 3], part) | !small;
        run(encoder, "Gas: carry", wipeCarried ? CARRY : CARRY_KEPT, gas[(g + 1) % 3], grid, fine, wipeCarried, pass -> {
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("VelSampler", now, linear);
            pass.setUniform("WorldSampler", world, nearest);
            pass.setUniform("InfoSampler", info, nearest);
        });
        run(encoder, "Gas: burn", wipeBurnt ? BURN : BURN_KEPT, gas[(g + 2) % 3], grid, fine, wipeBurnt, pass -> {
            pass.setUniform("CarriedSampler", carried, linear);
            pass.setUniform("GasSampler", before, nearest);
            pass.setUniform("VelSampler", now, linear);
            pass.setUniform("WorldSampler", world, nearest);
            pass.setUniform("InfoSampler", info, nearest);
        });
        g = (g + 2) % 3;
        steps++;
    }

    /** Rounds of the pressure solve on a level; {@code at} says which of the level's two textures holds the pressure. */
    private void even(CommandEncoder encoder, GpuBuffer grid, GpuSampler nearest, int level, int[] at, int rounds, int layers, int[] part) {
        for (int round = 0; round < rounds; round++) {
            final GpuTextureView from = pressure[level][at[level]].getColorTextureView();
            // (the grids under the coarse one are small and lie where the part begins: they are cleared every time)
            final boolean wipe = level > 0 || wipe(pressure[level][1 - at[level]], part);
            run(encoder, "Gas: pressure", PRESSURE[level], pressure[level][1 - at[level]], grid, layers, wipe, pass -> pass.setUniform("PressureSampler", from, nearest));
            at[level] = 1 - at[level];
        }
    }

    /** A level of the pressure solve and those under it: rounds, what is left handed down, the answer taken up, rounds. {@code layers}: layers of the coarse grid that are computed. */
    private void downAndUp(CommandEncoder encoder, GpuBuffer grid, GpuSampler nearest, int level, int[] at, int each, int layers, int[] part) {
        final int here = layers + (1 << level) - 1 >> level;
        if (level == GasBox.UNDER) {
            // nothing under the last: more rounds instead
            even(encoder, grid, nearest, level, at, 2 * each + 4, here, part);
            return;
        }
        final int under = layers + (2 << level) - 1 >> level + 1;
        even(encoder, grid, nearest, level, at, each, here, part);
        final GpuTextureView left = pressure[level][at[level]].getColorTextureView();
        run(encoder, "Gas: pressure handed down", DOWN[level], pressure[level + 1][0], grid, under, true, pass -> pass.setUniform("PressureSampler", left, nearest));
        at[level + 1] = 0;
        downAndUp(encoder, grid, nearest, level + 1, at, each, layers, part);
        final GpuTextureView own = pressure[level][at[level]].getColorTextureView(), answer = pressure[level + 1][at[level + 1]].getColorTextureView();
        run(encoder, "Gas: pressure taken up", UP[level], pressure[level][1 - at[level]], grid, here, level > 0 || wipe(pressure[level][1 - at[level]], part), pass -> {
            pass.setUniform("PressureSampler", own, nearest);
            pass.setUniform("UnderSampler", answer, nearest);
        });
        at[level] = 1 - at[level];
        even(encoder, grid, nearest, level, at, each, here, part);
    }

    void close() {
        for (TextureTarget target : velocity) target.destroyBuffers();
        for (TextureTarget[] level : pressure) for (TextureTarget target : level) target.destroyBuffers();
        for (TextureTarget target : gas) target.destroyBuffers();
        spin.destroyBuffers();
    }
}
