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
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import physicsbruhh.LavaFlow;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GooSim;

/**
 * Lava that has left its block and flows as a body: a tongue out of a hole in a wall, a rope down
 * the wall, a lobe creeping over the floor. The server only keeps a marker for each piece
 * ({@link LavaFlow}); the lava itself is simulated here with the goo ({@link GooClient}, material
 * {@link GooSim#LAVA}), and this class does the two things that make it lava.
 *
 * <p><b>It draws it</b>, from the particles of the simulation, after the world: every particle a
 * disc, the discs merged into one surface on the screen (the passes of the liquid look do that),
 * then what the surface carries (a pattern and a heat that belong to the lava and travel with it),
 * and one pass that puts the lava into the picture and lights the world around it, which the
 * game's own light cannot do for something that is not a block ({@code shaders/core/lava_*}).
 *
 * <p><b>It tells the server what only a client knows</b>: which creatures stand in the lava, where
 * each piece has got to, and where the lava has filled a hollow to the brim.
 *
 * <p>If the drawing fails on a machine it is said once in the log and the lava is drawn as a plain
 * glowing surface instead ({@link GooRenderer}).
 */
final class LavaGoo {
    private LavaGoo() {}

    private static final Vector4f NOTHING = new Vector4f(-1.0e5F, 0.0F, 0.0F, 0.0F), ZERO = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private static final int FRAME_BYTES = 160;
    /** As {@code include/lava_lights.glsl} has it. */
    private static final int LIGHTS = 24, LIGHT_BYTES = (LIGHTS + 1) * 16;
    /** Lava is gathered into lights by cells this wide (blocks); a light reaches this far; a full cell of lava this many particles. */
    private static final float LIGHT_CELL = 0.75F, LIGHT_REACH = 3.5F, LIGHT_FULL = 300.0F;

    private static final BindGroupLayout PARTICLES = BindGroupLayout.builder().withUniform("LavaParticles", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();
    private static final BindGroupLayout LIGHTS_IN = BindGroupLayout.builder().withUniform("LavaLights", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout SURFACE_IN = BindGroupLayout.builder().withUniform("FluidSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout PICTURE_IN = BindGroupLayout.builder()
            .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SceneDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FluidSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SkinSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, path);
    }

    private static RenderPipeline.Builder splat(String name) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/lava_splat"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(GooFluid.FRAME)
                .withBindGroupLayout(PARTICLES)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
    }

    /** The nearest surface of the lava per pixel (the discs as halves of spheres, the nearest kept). */
    private static final RenderPipeline SURFACE = splat("lava_goo_surface").withFragmentShader(id("core/goo_depth"))
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.MAX), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL)).build();
    /** What the surface carries: pattern, heat and their weight add up, the grain keeps its greatest value. */
    private static final RenderPipeline SKIN = splat("lava_goo_skin").withFragmentShader(id("core/lava_skin")).withBindGroupLayout(SURFACE_IN)
            .withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(
                    new BlendEquation(BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD), new BlendEquation(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX))),
                    GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();
    private static final RenderPipeline PICTURE = GooFluid.screen("lava_goo", "core/lava_goo")
            .withBindGroupLayout(LIGHTS_IN)
            .withBindGroupLayout(PICTURE_IN)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    /** Set when drawing the lava this way failed on this machine; it then stays off until the game is restarted. */
    static volatile boolean broken;
    /** For {@code /lavalook info} and the smoke test: frames drawn, particles and lights in the last of them; what has been told to the server. */
    static volatile int frames, drawnParticles, drawnLights, pieces, reports, toldBurning, toldBrimful;

    /** Whether lava that flows as a body is drawn from its particles (rather than as a plain surface). */
    static boolean inUse() {
        return !broken && GooRenderer.shader && (!Packs.active || Packs.LIQUID_ON_TOP);
    }

    static void register(RegisterRenderPipelinesEvent event) {
        // optional: if one of them does not compile on some driver the game still starts, and the lava is drawn as a plain surface
        event.registerOptionalPipeline(SURFACE);
        event.registerOptionalPipeline(SKIN);
        event.registerOptionalPipeline(PICTURE);
    }

    static void reset() {
        stagedCount = 0; seenVersion = -1; drawnParticles = 0; drawnLights = 0; pieces = 0;
        FULL_BEFORE.clear(); TOLD_FULL.clear(); ticks = 0;
    }

    // ------------------------------------------------------------------ per frame

    private static TextureTarget scene, surfaceA, surfaceB, skin;
    private static MappableRingBuffer frameBuffer, particleBuffer, lightBuffer;
    private static int particleCapacity;
    /** The lava among the latest particles, as the shader reads it (x, y, z, bits), and the lights made of it. */
    private static float[] staged = new float[0];
    private static final float[] lights = new float[LIGHTS * 4];
    private static int stagedCount, lightCount, seenVersion = -1;
    private static BlockPos stagedOrigin = BlockPos.ZERO;
    private static final Long2IntOpenHashMap LIGHT_SLOT = new Long2IntOpenHashMap();
    private static float[] gathered = new float[0];

    static void render(RenderLevelStageEvent.AfterLevel event) {
        if (!inUse()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            draw(mc, event);
        } catch (Throwable t) {
            broken = true;
            PhysicsBruhh.LOGGER.error("Lava that flows as a body could not be drawn from its particles on this machine; it is drawn as a plain surface instead", t);
        }
    }

    /** The lava among the simulation's latest particles, packed for the shader; and where it is, as lights. */
    private static boolean stage() {
        synchronized (GooClient.PARTICLE_LOCK) {
            if (GooClient.particleVersion == seenVersion) return false;
            seenVersion = GooClient.particleVersion;
            int all = GooClient.particleCount;
            float[] p = GooClient.particleData;
            if (staged.length < all * 4) staged = new float[all * 4 + all];
            int n = 0;
            for (int i = 0; i < all; i++) {
                int k = i * 4;
                float w = p[k + 3];
                // what the simulation says: size 0..1, +4 for a filler, +8 for lava, +16 * pattern, +1024 * heat
                int kind = (int) w;
                if ((kind & 8) == 0) continue;
                float size = Math.max(0.0F, Math.min(1.0F, w - kind));
                if (size == 0.0F && (kind & 1) == 1) { size = 1.0F; kind--; } // exactly 1.0 arrives as the next whole number
                int o = n++ * 4;
                staged[o] = p[k]; staged[o + 1] = p[k + 1]; staged[o + 2] = p[k + 2];
                staged[o + 3] = Math.round(size * 255.0F) | (kind >> 4 & 63) << 8 | (kind >> 10 & 63) << 14 | (kind >> 2 & 1) << 20;
            }
            stagedCount = n;
            stagedOrigin = GooClient.origin;
        }
        // the lights: lava gathered by cells; the cells with most lava in them light the world
        LIGHT_SLOT.clear();
        LIGHT_SLOT.defaultReturnValue(-1);
        int cells = 0;
        for (int i = 0; i < stagedCount; i++) {
            int o = i * 4, bits = (int) staged[o + 3];
            if ((bits >> 20 & 1) != 0) continue;
            long key = BlockPos.asLong((int) Math.floor(staged[o] / LIGHT_CELL), (int) Math.floor(staged[o + 1] / LIGHT_CELL), (int) Math.floor(staged[o + 2] / LIGHT_CELL));
            int slot = LIGHT_SLOT.get(key);
            if (slot < 0) {
                slot = cells++;
                LIGHT_SLOT.put(key, slot);
                if (gathered.length < cells * 5) gathered = java.util.Arrays.copyOf(gathered, Math.max(320, cells * 10));
                java.util.Arrays.fill(gathered, slot * 5, slot * 5 + 5, 0.0F);
            }
            float size = (bits & 255) / 255.0F;
            int g = slot * 5;
            gathered[g] += staged[o] * size; gathered[g + 1] += staged[o + 1] * size; gathered[g + 2] += staged[o + 2] * size;
            gathered[g + 3] += size; gathered[g + 4] += (bits >> 14 & 63) / 63.0F * size;
        }
        int count = 0;
        for (int pick = 0; pick < LIGHTS; pick++) {
            int best = -1;
            for (int c = 0; c < cells; c++) if (gathered[c * 5 + 3] > 0.5F && (best < 0 || gathered[c * 5 + 3] > gathered[best * 5 + 3])) best = c;
            if (best < 0) break;
            int g = best * 5;
            float weight = gathered[g + 3];
            lights[count * 4] = gathered[g] / weight; lights[count * 4 + 1] = gathered[g + 1] / weight + 0.1F; lights[count * 4 + 2] = gathered[g + 2] / weight;
            lights[count * 4 + 3] = Math.min(1.5F, weight / LIGHT_FULL) * (0.35F + 0.65F * gathered[g + 4] / weight);
            gathered[g + 3] = 0.0F;
            count++;
        }
        lightCount = count;
        return true;
    }

    private static void draw(Minecraft mc, RenderLevelStageEvent.AfterLevel event) {
        boolean fresh = stage();
        final int count = stagedCount;
        drawnParticles = count;
        drawnLights = lightCount;
        if (count == 0) return;
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        GpuTextureView mainColor = main.getColorTextureView(), mainDepth = main.getDepthTextureView();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        if (mainColor == null || mainDepth == null || projection == null || main.width <= 0 || main.height <= 0) return;

        CompiledRenderPipeline surface = GooFluid.compiled(SURFACE), carried = GooFluid.compiled(SKIN), picture = GooFluid.compiled(PICTURE);
        CompiledRenderPipeline[] blur = new CompiledRenderPipeline[GooFluid.BLUR.length];
        for (int i = 0; i < blur.length; i++) blur[i] = GooFluid.compiled(GooFluid.BLUR[i]);

        if (scene == null || scene.width != main.width || scene.height != main.height) targets(main.width, main.height);
        if (fresh || particleBuffer == null) upload(count);
        writeFrame(event);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(count * 6);
        GpuBuffer frame = frameBuffer.currentBuffer(), particles = particleBuffer.currentBuffer(), lit = lightBuffer.currentBuffer();

        scene.copyColorFrom(main);
        try (RenderPass pass = encoder.createRenderPass(() -> "Lava surface", surfaceA.getColorTextureView(), Optional.of(NOTHING))) {
            pass.setPipeline(surface);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("LavaParticles", particles);
            pass.setVertexBuffer(0, null);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(count * 6, 1, 0, 0, 0);
        }
        for (int i = 0; i < blur.length; i++) {
            TextureTarget from = i % 2 == 0 ? surfaceA : surfaceB, to = i % 2 == 0 ? surfaceB : surfaceA;
            try (RenderPass pass = encoder.createRenderPass(() -> "Lava surface blur", to.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(blur[i]);
                pass.setUniform("Projection", projection);
                pass.setUniform("GooFrame", frame);
                pass.setUniform("InSampler", from.getColorTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Lava skin", skin.getColorTextureView(), Optional.of(ZERO))) {
            pass.setPipeline(carried);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("LavaParticles", particles);
            pass.setUniform("FluidSampler", surfaceA.getColorTextureView(), nearest);
            pass.setVertexBuffer(0, null);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(count * 6, 1, 0, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Lava", mainColor, Optional.empty())) {
            pass.setPipeline(picture);
            pass.setUniform("Projection", projection);
            pass.setUniform("GooFrame", frame);
            pass.setUniform("LavaLights", lit);
            pass.setUniform("SceneSampler", scene.getColorTextureView(), linear);
            pass.setUniform("SceneDepthSampler", mainDepth, nearest);
            pass.setUniform("FluidSampler", surfaceA.getColorTextureView(), nearest);
            pass.setUniform("SkinSampler", skin.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        frames++;
    }

    private static void targets(int width, int height) {
        for (TextureTarget target : new TextureTarget[] { scene, surfaceA, surfaceB, skin }) if (target != null) target.destroyBuffers();
        scene = new TextureTarget("Lava: scene behind", width, height, GpuFormat.RGBA8_UNORM, null);
        surfaceA = new TextureTarget("Lava: surface", width, height, GpuFormat.RG32_FLOAT, null);
        surfaceB = new TextureTarget("Lava: surface, half blurred", width, height, GpuFormat.RG32_FLOAT, null);
        skin = new TextureTarget("Lava: what the surface carries", width, height, GpuFormat.RGBA16_FLOAT, null);
    }

    private static void upload(int count) {
        if (particleBuffer == null || particleCapacity < count) {
            if (particleBuffer != null) particleBuffer.close();
            particleCapacity = Math.max(8192, Integer.highestOneBit(count) * 2);
            final int bytes = particleCapacity * 16;
            particleBuffer = new MappableRingBuffer(() -> "Lava particles", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, bytes);
        }
        particleBuffer.rotate();
        try (GpuBufferSlice.MappedView view = particleBuffer.currentBuffer().map(0L, (long) count * 16L, false, true)) {
            view.data().order(ByteOrder.nativeOrder()).asFloatBuffer().put(0, staged, 0, count * 4);
        }
    }

    /** What the shaders are told about this frame: the liquid look's block (only what lava uses of it), and the lights. */
    private static void writeFrame(RenderLevelStageEvent.AfterLevel event) {
        if (frameBuffer == null) frameBuffer = new MappableRingBuffer(() -> "Lava body frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_BYTES);
        if (lightBuffer == null) lightBuffer = new MappableRingBuffer(() -> "Lava lights", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, LIGHT_BYTES);
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        BlockPos o = stagedOrigin;
        frameBuffer.rotate();
        try (GpuBufferSlice.MappedView view = frameBuffer.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
            new Matrix4f(event.getModelViewMatrix()).get(0, data);
            FloatBuffer out = data.asFloatBuffer();
            out.put(16, (float) (o.getX() - camera.x)).put(17, (float) (o.getY() - camera.y)).put(18, (float) (o.getZ() - camera.z)).put(19, GooFluid.SPACING * GooFluid.RADIUS);
            out.put(20, 0.0F).put(21, 1.0F).put(22, 0.0F).put(23, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0F : 0.0F);
            out.put(24, 0.0F).put(25, 0.0F).put(26, 0.0F).put(27, (float) (System.nanoTime() / 1.0e9 % 3600.0));
            out.put(28, 0.0F).put(29, 0.0F).put(30, 0.0F).put(31, 0.0F);
            out.put(32, 0.0F).put(33, 0.0F).put(34, 0.0F).put(35, GooFluid.SPACING);
            out.put(36, 0.0F).put(37, 0.0F).put(38, 1.0F).put(39, 1.0F);
        }
        lightBuffer.rotate();
        try (GpuBufferSlice.MappedView view = lightBuffer.currentBuffer().map(false, true)) {
            FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
            out.put(0, lights, 0, LIGHTS * 4);
            out.put(LIGHTS * 4, lightCount).put(LIGHTS * 4 + 1, LIGHT_REACH).put(LIGHTS * 4 + 2, 1.0F).put(LIGHTS * 4 + 3, 0.0F);
        }
    }

    // ------------------------------------------------------------------ what the server is told

    /** Lava touches a creature when this many of its particles are pressed against the creature's box. */
    private static final int TOUCH = 10;
    /** A block is full of lava from this many particles on. */
    private static final int BRIM = GooSim.BLOCK * 4 / 5;
    private static int ticks;
    private static float[] mine = new float[0];
    private static final LongOpenHashSet FULL_BEFORE = new LongOpenHashSet(), TOLD_FULL = new LongOpenHashSet();
    private static final Long2IntOpenHashMap IN_CELL = new Long2IntOpenHashMap();
    private static final int[] PIECE_IDS = new int[LavaFlow.Report.MOST_PIECES];
    private static final double[] PIECE_BOUNDS = new double[LavaFlow.Report.MOST_PIECES * 6];
    /** Whether the local player stands in lava that flows as a body (smoke test). */
    static volatile boolean playerInLava;

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused() || !GooClient.lavaPresent) { playerInLava = false; pieces = 0; return; }
        if (++ticks % 5 != 0) return;
        // the lava as it is now, in the world's own coordinates
        int n = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        synchronized (GooClient.PARTICLE_LOCK) {
            int all = GooClient.particleCount;
            float[] p = GooClient.particleData;
            BlockPos o = GooClient.origin;
            if (mine.length < all * 3) mine = new float[all * 3 + all];
            for (int i = 0; i < all; i++) {
                int k = i * 4, kind = (int) p[k + 3];
                // lava, not a filler, not fading away (a whole particle's size, 1.0, arrives as the next whole number)
                if ((kind & 12) != 8 || (kind & 1) == 0 && p[k + 3] - kind < 0.5F) continue;
                float x = p[k] + o.getX(), y = p[k + 1] + o.getY(), z = p[k + 2] + o.getZ();
                mine[n * 3] = x; mine[n * 3 + 1] = y; mine[n * 3 + 2] = z;
                n++;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            }
        }
        IntArrayList burning = new IntArrayList();
        boolean me = false;
        if (n > 0) {
            // creatures push the lava out of themselves as they push goo, so what touches them lies against their box
            for (Entity e : level.getEntities((Entity) null, new AABB(minX - 0.5, minY - 0.5, minZ - 0.5, maxX + 0.5, maxY + 0.5, maxZ + 0.5), other -> other.isAlive() && !other.isSpectator() && !(other instanceof physicsbruhh.GooEntity))) {
                if (burning.size() >= LavaFlow.Report.MOST_BURNING) break;
                AABB box = e.getBoundingBox().inflate(GooClient.PROBE_REACH, 0.0, GooClient.PROBE_REACH).expandTowards(0.0, -0.12, 0.0);
                int touching = 0;
                for (int i = 0; i < n && touching < TOUCH; i++) {
                    float x = mine[i * 3], y = mine[i * 3 + 1], z = mine[i * 3 + 2];
                    if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ) touching++;
                }
                if (touching >= TOUCH) {
                    burning.add(e.getId());
                    if (e == mc.player) me = true;
                }
            }
        }
        playerInLava = me;
        boolean second = ticks % 10 == 0;
        LongArrayList brimful = new LongArrayList();
        int count = 0;
        float[] places = new float[0];
        if (second) {
            // where the lava stands a block deep: said to the server once it has stood so for two looks in a row
            IN_CELL.clear();
            for (int i = 0; i < n; i++) IN_CELL.addTo(BlockPos.asLong((int) Math.floor(mine[i * 3]), (int) Math.floor(mine[i * 3 + 1]), (int) Math.floor(mine[i * 3 + 2])), 1);
            LongOpenHashSet full = new LongOpenHashSet();
            for (Long2IntMap.Entry entry : IN_CELL.long2IntEntrySet()) if (entry.getIntValue() >= BRIM) full.add(entry.getLongKey());
            for (long cell : full) {
                if (FULL_BEFORE.contains(cell) && !TOLD_FULL.contains(cell) && brimful.size() < LavaFlow.Report.MOST_BRIMFUL) brimful.add(cell);
            }
            TOLD_FULL.retainAll(full);
            TOLD_FULL.addAll(brimful);
            FULL_BEFORE.clear();
            FULL_BEFORE.addAll(full);
            // where the pieces have got to
            count = GooClient.lavaPieces(PIECE_IDS, PIECE_BOUNDS);
            places = new float[count * 3];
            for (int i = 0; i < count; i++) {
                places[i * 3] = (float) ((PIECE_BOUNDS[i * 6] + PIECE_BOUNDS[i * 6 + 3]) * 0.5);
                places[i * 3 + 1] = (float) PIECE_BOUNDS[i * 6 + 1];
                places[i * 3 + 2] = (float) ((PIECE_BOUNDS[i * 6 + 2] + PIECE_BOUNDS[i * 6 + 5]) * 0.5);
            }
            pieces = count;
        }
        if (burning.isEmpty() && brimful.isEmpty() && count == 0) return;
        if (mc.getConnection() == null) return;
        ClientPacketDistributor.sendToServer(new LavaFlow.Report(burning.toIntArray(), java.util.Arrays.copyOf(PIECE_IDS, count), places, brimful.toLongArray()));
        reports++;
        toldBurning += burning.size();
        toldBrimful += brimful.size();
    }

    /** What {@code /lavalook info} says about lava that flows as a body. */
    static String describe() {
        return pieces + " pieces, " + drawnParticles + " particles, " + drawnLights + " lights" + (inUse() ? "" : broken ? " (plain surface: drawing from particles failed)" : " (plain surface)");
    }
}
