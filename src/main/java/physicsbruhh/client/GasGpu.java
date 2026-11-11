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
 *   pressure    pressure                         -> pressure, one round of many      (coarse)
 *   project     velocity, pressure               -> velocity that piles nothing up   (coarse)
 *   carry       gas, velocity, world             -> gas carried along                (fine)
 *   burn        carried, gas, velocity, world    -> gas burnt, cooled, fed           (fine)
 * </pre>
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
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/gas_region"))
                .withFragmentShader(id("core/" + name))
                .withBindGroupLayout(GasDraw.GRID)
                .withBindGroupLayout(samplers(textures))
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL));
        if (fine) builder.withShaderDefine("FINE");
        return builder.build();
    }

    private static final RenderPipeline SPIN = step("gas_spin", GpuFormat.RGBA16_FLOAT, false, "VelSampler");
    private static final RenderPipeline VELOCITY = step("gas_velocity", GpuFormat.RGBA16_FLOAT, false, "VelSampler", "SpinSampler", "GasSampler", "WorldSampler");
    private static final RenderPipeline DIVERGENCE = step("gas_divergence", GpuFormat.RG16_FLOAT, false, "VelSampler", "GasSampler", "PressureSampler", "WorldSampler");
    private static final RenderPipeline PRESSURE = step("gas_pressure", GpuFormat.RG16_FLOAT, false, "PressureSampler");
    private static final RenderPipeline PROJECT = step("gas_project", GpuFormat.RGBA16_FLOAT, false, "VelSampler", "PressureSampler");
    private static final RenderPipeline CARRY = step("gas_carry", GpuFormat.RGBA16_FLOAT, true, "GasSampler", "VelSampler", "WorldSampler");
    private static final RenderPipeline BURN = step("gas_burn", GpuFormat.RGBA16_FLOAT, true, "CarriedSampler", "GasSampler", "VelSampler", "WorldSampler");
    private static final RenderPipeline[] ALL = { SPIN, VELOCITY, DIVERGENCE, PRESSURE, PROJECT, CARRY, BURN };

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
    private final TextureTarget[] velocity = new TextureTarget[2], pressure = new TextureTarget[2], gas = new TextureTarget[3];
    private final TextureTarget spin;
    /** Which of each holds what the last step left. */
    private int v, p, g;
    int steps;

    GasGpu(GasBox box) {
        this.box = box;
        for (int i = 0; i < 2; i++) {
            velocity[i] = new TextureTarget("Gas: velocity " + i, box.coarseW, box.coarseH, GpuFormat.RGBA16_FLOAT, null);
            pressure[i] = new TextureTarget("Gas: pressure " + i, box.coarseW, box.coarseH, GpuFormat.RG16_FLOAT, null);
        }
        spin = new TextureTarget("Gas: spin", box.coarseW, box.coarseH, GpuFormat.RGBA16_FLOAT, null);
        for (int i = 0; i < 3; i++) gas[i] = new TextureTarget("Gas: smoke, heat, fuel " + i, box.fineW, box.fineH, GpuFormat.RGBA16_FLOAT, null);
        clear();
    }

    void clear() {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (TextureTarget target : velocity) encoder.clearColorTexture(target.getColorTexture(), NOTHING);
        for (TextureTarget target : pressure) encoder.clearColorTexture(target.getColorTexture(), NOTHING);
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
        final GpuTextureView lastPressure = pressure[p].getColorTextureView();
        run(encoder, "Gas: divergence", DIVERGENCE, pressure[1 - p], grid, coarse, pass -> {
            pass.setUniform("VelSampler", pushed, nearest);
            pass.setUniform("GasSampler", before, linear);
            pass.setUniform("PressureSampler", lastPressure, nearest);
            pass.setUniform("WorldSampler", world, nearest);
        });
        // an even number of rounds: the pressure ends up in the texture the divergence was written to
        int at = 1 - p;
        for (int round = 0; round < box.rounds; round++) {
            final GpuTextureView from = pressure[at].getColorTextureView();
            run(encoder, "Gas: pressure", PRESSURE, pressure[1 - at], grid, coarse, pass -> pass.setUniform("PressureSampler", from, nearest));
            at = 1 - at;
        }
        p = at;
        final GpuTextureView solved = pressure[p].getColorTextureView();
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

    void close() {
        for (TextureTarget target : velocity) target.destroyBuffers();
        for (TextureTarget target : pressure) target.destroyBuffers();
        for (TextureTarget target : gas) target.destroyBuffers();
        spin.destroyBuffers();
    }
}
