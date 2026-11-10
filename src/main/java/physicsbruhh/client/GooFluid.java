package physicsbruhh.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import physicsbruhh.PhysicsBruhh;

/**
 * The liquid look: with the game rule {@code clear_goo} on, the goo is not drawn as a surface mesh
 * but as a liquid, the way water is drawn in film: every particle of the simulation becomes a small
 * disc, the discs are merged into one smooth surface on the screen, and through that surface the
 * world behind is seen bent and tinted by how much goo the light had to cross.
 *
 * <p>Passes, all after the world itself has been drawn ({@code shaders/core/goo_*}):
 * <ol>
 *   <li>the picture so far is copied aside (it is what will be seen through the goo);</li>
 *   <li>discs into a "nearest surface" texture, tested against the world's depth, keeping the nearest;</li>
 *   <li>discs into an "amount of goo" texture, adding up;</li>
 *   <li>three rounds of an edge-keeping blur over the surface, two smoothing passes over the amounts;</li>
 *   <li>one pass over the screen that puts the goo into the picture.</li>
 * </ol>
 *
 * <p>If anything here fails (a shader that does not compile on some driver, a texture format that is
 * not there) it is said once in the log, and from then on the goo is drawn with the surface shader
 * instead ({@link GooRenderer}). {@code /goolook shader} picks that on purpose.
 */
final class GooFluid {
    /** Distance between neighbouring particles at rest, in blocks (the simulation's own number). */
    private static final float SPACING = 0.97F / 12.0F;
    /** Radius a particle is drawn with, in spacings. */
    private static final float RADIUS = 1.5F;
    /** "No goo here" in the surface texture: red is minus the distance, green is whether there is goo. */
    private static final Vector4f NOTHING = new Vector4f(-1.0e5F, 0.0F, 0.0F, 0.0F), ZERO = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private static final int FRAME_BYTES = 144;

    private static final BindGroupLayout FRAME = BindGroupLayout.builder().withUniform("GooFrame", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout PARTICLES = BindGroupLayout.builder().withUniform("GooParticles", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();
    private static final BindGroupLayout AMOUNTS_IN = BindGroupLayout.builder()
            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FluidSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();
    private static final BindGroupLayout PICTURE_IN = BindGroupLayout.builder()
            .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SceneDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FluidSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("ThickSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, path);
    }

    private static RenderPipeline.Builder splat(String name) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/goo_splat"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(FRAME)
                .withBindGroupLayout(PARTICLES)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // the world's depth is only looked at: goo behind a wall is not drawn, and the goo leaves no trace in it
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withCull(false);
    }

    private static RenderPipeline.Builder screen(String name, String fragment) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id(fragment))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(FRAME)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
    }

    private static final RenderPipeline SURFACE = splat("goo_depth").withFragmentShader(id("core/goo_depth"))
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.MAX), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL)).build();
    private static final RenderPipeline AMOUNT = splat("goo_thick").withFragmentShader(id("core/goo_thick"))
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL)).build();
    /** Rounds of the surface blur: reach in particle spacings, from wide to narrow; the last one also rounds the outline. */
    private static final float[] REACH = { 3.0F, 2.0F, 1.2F };
    private static final RenderPipeline[] BLUR = new RenderPipeline[REACH.length * 2];
    private static final RenderPipeline[] SMOOTH = new RenderPipeline[2];
    private static final RenderPipeline PICTURE = screen("goo_fluid", "core/goo_fluid")
            .withBindGroupLayout(PICTURE_IN)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();

    static {
        for (int i = 0; i < BLUR.length; i++) {
            RenderPipeline.Builder builder = screen("goo_blur_" + i, "core/goo_blur")
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL))
                    .withShaderDefine("RADIUS", REACH[i / 2]);
            if (i % 2 == 0) builder.withShaderDefine("HORIZONTAL");
            if (i / 2 == REACH.length - 1) builder.withShaderDefine("OUTLINE");
            BLUR[i] = builder.build();
        }
        for (int i = 0; i < 2; i++) {
            RenderPipeline.Builder builder = screen("goo_smooth_" + i, "core/goo_smooth")
                    .withBindGroupLayout(AMOUNTS_IN)
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
            if (i == 0) builder.withShaderDefine("HORIZONTAL");
            SMOOTH[i] = builder.build();
        }
    }

    /** The player's choice ({@code /goolook liquid} or not). */
    static volatile boolean chosen = !"false".equalsIgnoreCase(System.getProperty("physicsbruhh.liquid"));
    /** Set when the liquid look failed on this machine; it then stays off until the game is restarted. */
    static volatile boolean broken;
    /** Frames drawn and particles in the last of them (smoke test and {@code /goolook info}). */
    static volatile int frames, drawnParticles;

    private GooFluid() {}

    /** Whether clear goo is drawn as liquid (rather than with the surface shader). */
    static boolean inUse() {
        return chosen && !broken && GooRenderer.shader;
    }

    static void register(RegisterRenderPipelinesEvent event) {
        // optional: if one of them does not compile on some driver the game still starts, and the surface look is used
        event.registerOptionalPipeline(SURFACE);
        event.registerOptionalPipeline(AMOUNT);
        for (RenderPipeline pipeline : BLUR) event.registerOptionalPipeline(pipeline);
        for (RenderPipeline pipeline : SMOOTH) event.registerOptionalPipeline(pipeline);
        event.registerOptionalPipeline(PICTURE);
    }

    // ------------------------------------------------------------------ per frame

    private static TextureTarget scene, surfaceA, surfaceB, amountA, amountB;
    private static MappableRingBuffer frameBuffer, particleBuffer;
    private static int particleCapacity;
    private static float[] staged = new float[0];
    private static int stagedCount, seenVersion = -1;
    private static BlockPos stagedOrigin = BlockPos.ZERO;
    private static final Long2IntOpenHashMap LIGHT = new Long2IntOpenHashMap();

    static void render(RenderLevelStageEvent.AfterLevel event) {
        if (!GooClient.clearLook || !inUse()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        try {
            draw(mc, level, event);
        } catch (Throwable t) {
            broken = true;
            PhysicsBruhh.LOGGER.error("The liquid look of the goo failed on this machine and is switched off; the goo is drawn with the surface shader instead", t);
        }
    }

    private static void draw(Minecraft mc, ClientLevel level, RenderLevelStageEvent.AfterLevel event) {
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        GpuTextureView mainColor = main.getColorTextureView(), mainDepth = main.getDepthTextureView();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        if (mainColor == null || mainDepth == null || projection == null || main.width <= 0 || main.height <= 0) return;

        // the particles: a private copy of the latest set, with the light where each one is
        boolean fresh;
        synchronized (GooClient.PARTICLE_LOCK) {
            fresh = GooClient.particleVersion != seenVersion;
            if (fresh) {
                seenVersion = GooClient.particleVersion;
                stagedCount = GooClient.particleCount;
                if (staged.length < stagedCount * 4) staged = new float[stagedCount * 4 + stagedCount];
                System.arraycopy(GooClient.particleData, 0, staged, 0, stagedCount * 4);
                stagedOrigin = GooClient.origin;
            }
        }
        final int count = stagedCount;
        if (count == 0) return;

        CompiledRenderPipeline surface = compiled(SURFACE), amount = compiled(AMOUNT), picture = compiled(PICTURE);
        CompiledRenderPipeline[] blur = new CompiledRenderPipeline[BLUR.length], smooth = new CompiledRenderPipeline[SMOOTH.length];
        for (int i = 0; i < BLUR.length; i++) blur[i] = compiled(BLUR[i]);
        for (int i = 0; i < SMOOTH.length; i++) smooth[i] = compiled(SMOOTH[i]);

        if (scene == null || scene.width != main.width || scene.height != main.height) targets(main.width, main.height);
        if (fresh || particleBuffer == null) upload(level, count);
        writeFrame(event);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(count * 6);
        GpuBuffer frame = frameBuffer.currentBuffer(), particles = particleBuffer.currentBuffer();

        scene.copyColorFrom(main);
        try (RenderPass pass = encoder.createRenderPass(() -> "Goo surface", surfaceA.getColorTextureView(), Optional.of(NOTHING), mainDepth, OptionalDouble.empty())) {
            pass.setPipeline(surface);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("GooParticles", particles);
            pass.setVertexBuffer(0, null);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(count * 6, 1, 0, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Goo amount", amountA.getColorTextureView(), Optional.of(ZERO), mainDepth, OptionalDouble.empty())) {
            pass.setPipeline(amount);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("GooParticles", particles);
            pass.setVertexBuffer(0, null);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(count * 6, 1, 0, 0, 0);
        }
        for (int i = 0; i < blur.length; i++) {
            TextureTarget from = i % 2 == 0 ? surfaceA : surfaceB, to = i % 2 == 0 ? surfaceB : surfaceA;
            try (RenderPass pass = encoder.createRenderPass(() -> "Goo surface blur", to.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(blur[i]);
                pass.setUniform("Projection", projection);
                pass.setUniform("GooFrame", frame);
                pass.setUniform("InSampler", from.getColorTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
        }
        for (int i = 0; i < smooth.length; i++) {
            TextureTarget from = i == 0 ? amountA : amountB, to = i == 0 ? amountB : amountA;
            try (RenderPass pass = encoder.createRenderPass(() -> "Goo amount blur", to.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(smooth[i]);
                pass.setUniform("Projection", projection);
                pass.setUniform("GooFrame", frame);
                pass.setUniform("InSampler", from.getColorTextureView(), linear);
                pass.setUniform("FluidSampler", surfaceA.getColorTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Goo", mainColor, Optional.empty())) {
            pass.setPipeline(picture);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("SceneSampler", scene.getColorTextureView(), linear);
            pass.setUniform("SceneDepthSampler", mainDepth, nearest);
            pass.setUniform("FluidSampler", surfaceA.getColorTextureView(), nearest);
            pass.setUniform("ThickSampler", amountA.getColorTextureView(), linear);
            pass.draw(3, 1, 0, 0);
        }
        drawnParticles = count;
        frames++;
    }

    private static CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiled == null || compiled.isClosed()) throw new IllegalStateException("shader pipeline " + pipeline.getLocation() + " is not available");
        return compiled;
    }

    private static void targets(int width, int height) {
        closeTargets();
        scene = new TextureTarget("Goo: scene behind", width, height, GpuFormat.RGBA8_UNORM, null);
        surfaceA = new TextureTarget("Goo: surface", width, height, GpuFormat.RG32_FLOAT, null);
        surfaceB = new TextureTarget("Goo: surface, half blurred", width, height, GpuFormat.RG32_FLOAT, null);
        amountA = new TextureTarget("Goo: amount", width, height, GpuFormat.RGBA16_FLOAT, null);
        amountB = new TextureTarget("Goo: amount, half blurred", width, height, GpuFormat.RGBA16_FLOAT, null);
    }

    private static void closeTargets() {
        for (TextureTarget target : new TextureTarget[] { scene, surfaceA, surfaceB, amountA, amountB }) {
            if (target != null) target.destroyBuffers();
        }
        scene = null; surfaceA = null; surfaceB = null; amountA = null; amountB = null;
    }

    /**
     * Particles into the buffer the vertex shader reads them from. The fourth number of each carries,
     * besides the size the simulation gives it, the block light and sky light at its place.
     */
    private static void upload(ClientLevel level, int count) {
        if (particleBuffer == null || particleCapacity < count) {
            if (particleBuffer != null) particleBuffer.close();
            particleCapacity = Math.max(16384, Integer.highestOneBit(count) * 2);
            final int bytes = particleCapacity * 16;
            particleBuffer = new MappableRingBuffer(() -> "Goo particles", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, bytes);
        }
        BlockPos o = stagedOrigin;
        LIGHT.clear();
        LIGHT.defaultReturnValue(-1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        particleBuffer.rotate();
        try (GpuBufferSlice.MappedView view = particleBuffer.currentBuffer().map(0L, (long) count * 16L, false, true)) {
            FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] p = staged;
            for (int i = 0; i < count; i++) {
                int k = i * 4;
                float w = p[k + 3];
                // what the simulation says: size 0..1, +2 for slime, +4 for a filler between particles pulled far apart
                int kind = (int) w;
                float size = Math.max(0.0F, Math.min(1.0F, w - kind));
                if (size == 0.0F && kind % 2 == 1) { size = 1.0F; kind--; } // exactly 1.0 arrives as the next whole number
                pos.set(o.getX() + (int) Math.floor(p[k]), o.getY() + (int) Math.floor(p[k + 1] + 0.05F), o.getZ() + (int) Math.floor(p[k + 2]));
                long key = pos.asLong();
                int light = LIGHT.get(key);
                if (light < 0) {
                    light = level.getBrightness(LightLayer.BLOCK, pos) | level.getBrightness(LightLayer.SKY, pos) << 4;
                    LIGHT.put(key, light);
                }
                int bits = Math.round(size * 255.0F) | ((kind >> 1) & 1) << 8 | (light & 15) << 9 | (light >> 4 & 15) << 13 | ((kind >> 2) & 1) << 17;
                out.put(k, p[k]).put(k + 1, p[k + 1]).put(k + 2, p[k + 2]).put(k + 3, (float) bits);
            }
        }
    }

    /** What the shaders are told about this frame (the {@code GooFrame} block, see {@code include/goo_frame.glsl}). */
    private static void writeFrame(RenderLevelStageEvent.AfterLevel event) {
        if (frameBuffer == null) frameBuffer = new MappableRingBuffer(() -> "Goo frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_BYTES);
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        BlockPos o = stagedOrigin;
        SkyRenderState sky = event.getLevelRenderState().skyRenderState;
        String skybox = String.valueOf(sky.skybox);
        boolean open = !"NONE".equals(skybox) && !"END".equals(skybox);

        // where the light comes from: the sun while it is up, the moon after that
        float sunX = (float) -Math.sin(sky.sunAngle), sunY = (float) Math.cos(sky.sunAngle);
        float day = open ? smooth(-0.12F, 0.25F, sunY) : 0.0F;
        float lightX = sunX, lightY = sunY, red, green, blue;
        float clearSky = open ? 0.35F + 0.65F * Math.max(0.0F, Math.min(1.0F, sky.rainBrightness)) : 0.0F;
        if (!open) {
            lightX = 0.35F; lightY = 0.94F; red = 0.0F; green = 0.0F; blue = 0.0F;
        } else if (sunY > -0.04F) {
            float high = smooth(0.0F, 0.35F, sunY), up = smooth(-0.04F, 0.10F, sunY) * 1.5F * clearSky;
            red = up; green = (0.55F + 0.37F * high) * up; blue = (0.30F + 0.48F * high) * up;
        } else {
            lightX = (float) -Math.sin(sky.moonAngle); lightY = (float) Math.cos(sky.moonAngle);
            float up = smooth(0.0F, 0.2F, lightY) * 0.22F * clearSky;
            red = 0.55F * up; green = 0.70F * up; blue = 1.0F * up;
        }
        if (lightY < 0.05F) lightY = 0.05F; // never from below the ground
        float length = (float) Math.sqrt(lightX * lightX + lightY * lightY);
        lightX /= length; lightY /= length;

        Vector3fc colour = open ? sky.skyColor : null;
        float skyR = colour == null ? 0.0F : colour.x(), skyG = colour == null ? 0.0F : colour.y(), skyB = colour == null ? 0.0F : colour.z();
        float haze = open ? 0.45F * (0.04F + 0.96F * day) * clearSky : 0.0F;

        frameBuffer.rotate();
        try (GpuBufferSlice.MappedView view = frameBuffer.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
            new Matrix4f(event.getModelViewMatrix()).get(0, data);
            FloatBuffer out = data.asFloatBuffer();
            out.put(16, (float) (o.getX() - camera.x)).put(17, (float) (o.getY() - camera.y)).put(18, (float) (o.getZ() - camera.z)).put(19, SPACING * RADIUS);
            out.put(20, lightX).put(21, lightY).put(22, 0.0F).put(23, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0F : 0.0F);
            out.put(24, skyR).put(25, skyG).put(26, skyB).put(27, (float) (System.nanoTime() / 1.0e9 % 3600.0));
            out.put(28, skyR * 0.55F + haze).put(29, skyG * 0.55F + haze).put(30, skyB * 0.55F + haze).put(31, day);
            out.put(32, red).put(33, green).put(34, blue).put(35, SPACING);
        }
    }

    private static float smooth(float from, float to, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - from) / (to - from)));
        return t * t * (3.0F - 2.0F * t);
    }
}
