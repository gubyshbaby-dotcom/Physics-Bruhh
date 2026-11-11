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
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GasParams;

/**
 * The gas, drawn, whichever solver computed it ({@code shaders/core/gas_where.fsh},
 * {@code gas_march.fsh}, {@code gas_show.fsh}), and what both solvers' passes are told about the
 * grid.
 *
 * <ol>
 *   <li>after each step: what every block of the box holds, into a small texture (the drawing
 *       steps over empty blocks; the game reads the sums back now and then);</li>
 *   <li>every frame, at half the screen's size: along the line of sight of each pixel, the light
 *       the gas adds and how much of what is behind it still shows;</li>
 *   <li>at a quarter of the screen's size: how much hot gas lies along each line of sight;</li>
 *   <li>all that put into the picture: what is behind, shifted a little where it is seen through
 *       hot gas, dimmed by the smoke, plus the gas's own light.</li>
 * </ol>
 */
final class GasDraw {
    /** Blobs the solvers take at once ({@code GAS_BLOBS} in gas.glsl). */
    static final int BLOBS = GasBox.BLOBS;
    private static final int GRID_BYTES = GasBox.GRID_FLOATS * 4, VIEW_BYTES = 64 + 4 * 16;

    static final BindGroupLayout GRID = BindGroupLayout.builder().withUniform("GasGrid", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout VIEW = BindGroupLayout.builder().withUniform("GasView", UniformType.UNIFORM_BUFFER).build();

    private static RenderPipeline where(String name, boolean coded) {
        RenderPipeline.Builder builder = GasGpu.pass(name, "gas_where", "GasSampler")
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
        if (coded) builder.withShaderDefine("CODED");
        return builder.build();
    }

    /** A pass over the screen that looks through the box along each pixel's line of sight. */
    private static RenderPipeline along(String name, String fragment, boolean coded, GpuFormat format, String... textures) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/" + fragment))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(GRID)
                .withBindGroupLayout(VIEW)
                .withBindGroupLayout(GasGpu.samplers(textures))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL));
        if (coded) builder.withShaderDefine("CODED");
        return builder.build();
    }

    private static RenderPipeline march(String name, boolean coded) {
        return along(name, "gas_march", coded, GpuFormat.RGBA16_FLOAT, "SceneDepthSampler", "GasSampler", "InfoSampler", "LightSampler");
    }

    private static RenderPipeline haze(String name, boolean coded) {
        return along(name, "gas_haze", coded, GpuFormat.R16_FLOAT, "SceneDepthSampler", "GasSampler", "InfoSampler");
    }

    /** The grid as the graphics card's solver leaves it, and as the processor's hands it over (bytes: CODED). */
    private static final RenderPipeline WHERE = where("gas_where", false), WHERE_CODED = where("gas_where_coded", true);
    private static final RenderPipeline MARCH = march("gas_march", false), MARCH_CODED = march("gas_march_coded", true);
    private static final RenderPipeline HAZE = haze("gas_haze", false), HAZE_CODED = haze("gas_haze_coded", true);
    /** What is behind (a copy of the picture so far), dimmed by how much the gas lets through, plus the gas's own light. */
    private static final RenderPipeline SHOW = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/gas_show"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/gas_show"))
            .withBindGroupLayout(GasGpu.samplers("InSampler", "HazeSampler", "SceneSampler"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();
    private static final RenderPipeline[] ALL = { WHERE, WHERE_CODED, MARCH, MARCH_CODED, HAZE, HAZE_CODED, SHOW };

    static void register(RegisterRenderPipelinesEvent event) {
        for (RenderPipeline pipeline : ALL) event.registerOptionalPipeline(pipeline);
    }

    static boolean available(boolean coded) {
        for (RenderPipeline pipeline : new RenderPipeline[] { coded ? WHERE_CODED : WHERE, coded ? MARCH_CODED : MARCH, coded ? HAZE_CODED : HAZE, SHOW }) {
            var compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
            if (compiled == null || compiled.isClosed()) return false;
        }
        return true;
    }

    private GasDraw() {}

    private static MappableRingBuffer gridBuffer, viewBuffer;
    private static TextureTarget info, light, shimmer, scene;
    private static GasBox infoBox;
    private static GpuTexture worldTexture;
    private static GpuTextureView worldView;
    private static GasBox worldBox;
    /** Read back from the info texture: smoke in all, heat in all, the hottest, then the smoke in each layer of blocks. */
    private static volatile float[] sums = new float[0];
    /** Read back with them: where in the world there is gas to be seen (blocks: the least corner, then the corner just past the greatest; all zero if there is none). */
    private static volatile int[] bounds;
    /** Steps since that was last read. */
    private static volatile int boundsAge;
    private static GpuBuffer readBuffer;
    private static boolean reading;
    private static int frame;
    static volatile int drawn, readBacks;

    /**
     * What the passes are told about the grid and this step ({@code GasGrid} in gas.glsl). {@code blobs}: twelve numbers
     * each, places in blocks from the corner of the box. {@code part}: the part of the box that is computed this step,
     * in blocks from its corner (the least corner, then the corner just past the greatest).
     */
    static GpuBuffer writeGrid(GasBox box, float dt, float time, int movedX, int movedY, int movedZ, float[] blobs, int blobCount, int[] part) {
        if (gridBuffer == null) gridBuffer = new MappableRingBuffer(() -> "Gas grid", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, GRID_BYTES);
        gridBuffer.rotate();
        try (GpuBufferSlice.MappedView view = gridBuffer.currentBuffer().map(false, true)) {
            box.grid(view.data().order(ByteOrder.nativeOrder()).asFloatBuffer(), dt, time, movedX, movedY, movedZ, blobs, blobCount, part);
        }
        return gridBuffer.currentBuffer();
    }

    /** The map of the world in the box, as a texture ({@code bytes}: four a block, laid out as {@link GasBox#blockPixel} says). */
    static GpuTextureView world(CommandEncoder encoder, GasBox box, ByteBuffer bytes, boolean changed) {
        if (worldTexture == null || !box.same(worldBox)) {
            closeWorld();
            worldTexture = RenderSystem.getDevice().createTexture(() -> "Gas: map of the world", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, box.blockW, box.blockH, 1, 1);
            worldView = RenderSystem.getDevice().createTextureView(worldTexture);
            worldBox = box;
            changed = true;
        }
        if (changed) {
            bytes.position(0).limit(box.blockW * box.blockH * 4);
            encoder.writeToTexture(worldTexture, bytes, 0, 0, 0, 0, box.blockW, box.blockH);
        }
        return worldView;
    }

    private static void closeWorld() {
        if (worldView != null) worldView.close();
        if (worldTexture != null) worldTexture.close();
        worldView = null; worldTexture = null; worldBox = null;
    }

    /**
     * After a step: what each block of the box holds. Every so often the result is read back ({@link #sums},
     * {@link #bounds}). {@code ox, oy, oz}: the corner of the box in the world.
     */
    static void where(CommandEncoder encoder, GasBox box, GpuBuffer grid, GpuTextureView gas, boolean coded, int ox, int oy, int oz) {
        info(box);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Gas: what each block holds", info.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(GasGpu.compiled(coded ? WHERE_CODED : WHERE));
            pass.setUniform("GasGrid", grid);
            pass.setUniform("GasSampler", gas, nearest);
            pass.draw(3, 1, 0, 0);
        }
        boundsAge++;
        if (!reading && ++frame % 6 == 0) readBack(encoder, box, ox, oy, oz);
    }

    /** What each block of the box held after the last step (nothing, before the first): the solver on the graphics card leaves alone the blocks nothing can happen in. */
    static GpuTextureView info(GasBox box) {
        if (info == null || !box.same(infoBox)) {
            if (info != null) info.destroyBuffers();
            info = new TextureTarget("Gas: what each block holds", box.blockW, box.blockH, GpuFormat.RGBA16_FLOAT, null);
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(info.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
            infoBox = box;
        }
        return info.getColorTextureView();
    }

    private static void readBack(CommandEncoder encoder, GasBox box, int ox, int oy, int oz) {
        final long bytes = (long) box.blockW * box.blockH * 8L;
        try {
            if (readBuffer == null || readBuffer.size() != bytes) {
                if (readBuffer != null) readBuffer.close();
                readBuffer = RenderSystem.getDevice().createBuffer(() -> "Gas: sums read back", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            }
            final GpuBuffer buffer = readBuffer;
            reading = true;
            encoder.copyTextureToBuffer(info.getColorTexture(), buffer, 0L, () -> {
                try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                    ShortBuffer in = view.data().order(ByteOrder.nativeOrder()).asShortBuffer();
                    float[] out = new float[3 + box.by];
                    int x0 = box.bx, y0 = box.by, z0 = box.bz, x1 = -1, y1 = -1, z1 = -1;
                    for (int y = 0; y < box.by; y++) {
                        for (int z = 0; z < box.bz; z++) {
                            for (int x = 0; x < box.bx; x++) {
                                int at = box.blockPixel(x, y, z);
                                // (the first two are zero where there is too little to be seen: gas_where.fsh)
                                short thickest = in.get(at), hot = in.get(at + 1);
                                float hottest = Float.float16ToFloat(hot), smoke = Float.float16ToFloat(in.get(at + 2)), heat = Float.float16ToFloat(in.get(at + 3));
                                out[0] += smoke; out[1] += heat; out[3 + y] += smoke;
                                if (hottest > out[2]) out[2] = hottest;
                                if ((thickest & 0x7FFF) != 0 || (hot & 0x7FFF) != 0) {
                                    if (x < x0) x0 = x;
                                    if (y < y0) y0 = y;
                                    if (z < z0) z0 = z;
                                    if (x > x1) x1 = x;
                                    if (y > y1) y1 = y;
                                    if (z > z1) z1 = z;
                                }
                            }
                        }
                    }
                    sums = out;
                    bounds = x1 < 0 ? new int[6] : new int[] { ox + x0, oy + y0, oz + z0, ox + x1 + 1, oy + y1 + 1, oz + z1 + 1 };
                    boundsAge = 0;
                    readBacks++;
                } catch (Throwable t) {
                    PhysicsBruhh.LOGGER.warn("Gas: the sums could not be read back: {}", t.toString());
                } finally {
                    reading = false;
                }
            }, 0);
        } catch (Throwable t) {
            reading = false;
            PhysicsBruhh.LOGGER.warn("Gas: the sums could not be read back: {}", t.toString());
        }
    }

    /** Smoke in all, heat in all, the hottest block, then the smoke in each layer of blocks from the bottom up; empty until first read. */
    static float[] sums() {
        return sums;
    }

    /**
     * Where in the world there is gas to be seen: blocks, the least corner, then the corner just past the greatest
     * (all zero if there is none). Null while that is not known: nothing read back yet, or not for a long while.
     */
    static int[] bounds() {
        return boundsAge > 40 ? null : bounds;
    }

    static void forget() {
        sums = new float[0];
        bounds = null;
    }

    /** What each block held is nothing again (the gas has been emptied). */
    static void forgetInfo() {
        if (info != null) RenderSystem.getDevice().createCommandEncoder().clearColorTexture(info.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
    }

    /** The gas into the picture. {@code ox, oy, oz}: the corner of the box in the world. */
    static void draw(CommandEncoder encoder, RenderLevelStageEvent.AfterLevel event, GasBox box, GpuBuffer grid, GpuTextureView gas, boolean coded, GpuTextureView world, int ox, int oy, int oz) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        GpuTextureView mainColor = main.getColorTextureView(), mainDepth = main.getDepthTextureView();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        if (mainColor == null || mainDepth == null || projection == null || main.width <= 0 || main.height <= 0 || info == null) return;
        int width = Math.max(1, main.width / GasClient.drawScale), height = Math.max(1, main.height / GasClient.drawScale);
        if (light == null || light.width != width || light.height != height) {
            if (light != null) light.destroyBuffers();
            light = new TextureTarget("Gas: light along each line of sight", width, height, GpuFormat.RGBA16_FLOAT, null);
        }
        int quarterWidth = Math.max(1, main.width / 4), quarterHeight = Math.max(1, main.height / 4);
        if (shimmer == null || shimmer.width != quarterWidth || shimmer.height != quarterHeight) {
            if (shimmer != null) shimmer.destroyBuffers();
            shimmer = new TextureTarget("Gas: hot gas along each line of sight", quarterWidth, quarterHeight, GpuFormat.R16_FLOAT, null);
        }
        if (scene == null || scene.width != main.width || scene.height != main.height) {
            if (scene != null) scene.destroyBuffers();
            scene = new TextureTarget("Gas: the picture behind", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
        }
        GpuBuffer view = writeView(event, ox, oy, oz);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "Gas: light along each line of sight", light.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(GasGpu.compiled(coded ? MARCH_CODED : MARCH));
            pass.setUniform("Projection", projection);
            pass.setUniform("GasGrid", grid);
            pass.setUniform("GasView", view);
            pass.setUniform("SceneDepthSampler", mainDepth, nearest);
            pass.setUniform("GasSampler", gas, linear);
            pass.setUniform("InfoSampler", info.getColorTextureView(), nearest);
            pass.setUniform("LightSampler", world, linear);
            pass.draw(3, 1, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Gas: hot gas along each line of sight", shimmer.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(GasGpu.compiled(coded ? HAZE_CODED : HAZE));
            pass.setUniform("Projection", projection);
            pass.setUniform("GasGrid", grid);
            pass.setUniform("GasView", view);
            pass.setUniform("SceneDepthSampler", mainDepth, nearest);
            pass.setUniform("GasSampler", gas, linear);
            pass.setUniform("InfoSampler", info.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        scene.copyColorFrom(main);
        try (RenderPass pass = encoder.createRenderPass(() -> "Gas", mainColor, Optional.empty())) {
            pass.setPipeline(GasGpu.compiled(SHOW));
            pass.setUniform("InSampler", light.getColorTextureView(), linear);
            pass.setUniform("HazeSampler", shimmer.getColorTextureView(), linear);
            pass.setUniform("SceneSampler", scene.getColorTextureView(), linear);
            pass.draw(3, 1, 0, 0);
        }
        drawn++;
    }

    /** Where the gas is looked at from and the light of the day ({@code GasView} in gas_view.glsl). */
    private static GpuBuffer writeView(RenderLevelStageEvent.AfterLevel event, int ox, int oy, int oz) {
        if (viewBuffer == null) viewBuffer = new MappableRingBuffer(() -> "Gas view", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, VIEW_BYTES);
        var camera = event.getLevelRenderState().cameraRenderState.pos;
        SkyRenderState sky = event.getLevelRenderState().skyRenderState;
        String skybox = String.valueOf(sky.skybox);
        boolean open = !"NONE".equals(skybox) && !"END".equals(skybox);

        // where the light comes from: the sun while it is up, the moon after that (as for the goo, GooFluid)
        float sunX = (float) -Math.sin(sky.sunAngle), sunY = (float) Math.cos(sky.sunAngle);
        float clearSky = open ? 0.35F + 0.65F * Math.max(0.0F, Math.min(1.0F, sky.rainBrightness)) : 0.0F;
        float day = open ? smooth(-0.12F, 0.25F, sunY) : 0.0F;
        float lightX = sunX, lightY = sunY, red = 0.0F, green = 0.0F, blue = 0.0F;
        if (!open) {
            lightX = 0.35F; lightY = 0.94F;
        } else if (sunY > -0.04F) {
            float high = smooth(0.0F, 0.35F, sunY), up = smooth(-0.04F, 0.10F, sunY) * 0.62F * clearSky;
            red = up; green = (0.55F + 0.39F * high) * up; blue = (0.30F + 0.50F * high) * up;
        } else {
            lightX = (float) -Math.sin(sky.moonAngle); lightY = (float) Math.cos(sky.moonAngle);
            float up = smooth(0.0F, 0.2F, lightY) * 0.07F * clearSky;
            red = 0.55F * up; green = 0.70F * up; blue = 1.0F * up;
        }
        if (lightY < 0.08F) lightY = 0.08F;
        float length = (float) Math.sqrt(lightX * lightX + lightY * lightY + 0.09F);
        Vector3fc colour = open ? sky.skyColor : null;
        float skyR = colour == null ? 0.02F : 0.012F + colour.x() * 0.42F + 0.10F * day * clearSky;
        float skyG = colour == null ? 0.02F : 0.014F + colour.y() * 0.42F + 0.10F * day * clearSky;
        float skyB = colour == null ? 0.02F : 0.020F + colour.z() * 0.42F + 0.08F * day * clearSky;

        viewBuffer.rotate();
        try (GpuBufferSlice.MappedView view = viewBuffer.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
            new Matrix4f(event.getModelViewMatrix()).get(0, data);
            FloatBuffer out = data.asFloatBuffer();
            out.put(16, (float) (camera.x - ox)).put(17, (float) (camera.y - oy)).put(18, (float) (camera.z - oz)).put(19, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0F : 0.0F);
            out.put(20, lightX / length).put(21, lightY / length).put(22, 0.3F / length).put(23, (float) (drawn % 1024));
            out.put(24, red).put(25, green).put(26, blue).put(27, (float) (System.nanoTime() / 1.0e9 % 3600.0));
            out.put(28, skyR).put(29, skyG).put(30, skyB).put(31, 0.0F);
        }
        return viewBuffer.currentBuffer();
    }

    private static float smooth(float from, float to, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - from) / (to - from)));
        return t * t * (3.0F - 2.0F * t);
    }

    static void close() {
        if (info != null) info.destroyBuffers();
        if (light != null) light.destroyBuffers();
        if (scene != null) scene.destroyBuffers();
        if (shimmer != null) shimmer.destroyBuffers();
        info = null; light = null; scene = null; shimmer = null; infoBox = null;
        closeWorld();
        if (readBuffer != null && !reading) { readBuffer.close(); readBuffer = null; }
        sums = new float[0];
        bounds = null;
    }
}
