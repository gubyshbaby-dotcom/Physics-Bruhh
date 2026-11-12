package physicsbruhh.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector4f;
import physicsbruhh.PhysicsBruhh;

/**
 * The surface of the lava as a heavy liquid. In a square of the world around the player (64 by 64
 * blocks, four cells to the block) the graphics card keeps, cell by cell, how far the lava's
 * surface stands above or below where it rests and how fast it is rising, and steps that on every
 * frame ({@code shaders/core/lava_waves.fsh}): a hump or a hollow spreads as a wave, the wave is
 * thrown back by the banks, and all of it dies away quickly, as it does in something as thick as
 * lava. What starts it is whatever pushes the surface: a bubble swelling and bursting
 * ({@link LavaBubbles}), a creature or a thing falling in or wading through. The lava's surface is
 * drawn lifted and tilted by it ({@code include/lava_waves.glsl}), and where the lava was stirred
 * its crust is torn for a while.
 *
 * <p>The solver is told where the lava is by a small map, a pixel a block: the height the top of
 * the lava rests at in that column (of the lava nearest the player's own height, where there are
 * several storeys of it). The map is put together from what the sections' surfaces say.
 */
final class LavaWaves {
    private LavaWaves() {}

    static final int BLOCKS = 64, CELLS = 4, ACROSS = BLOCKS * CELLS;
    private static final int STEP_BYTES = 16 + 16 * 16, MOST_PUSHES = 16;

    private static final BindGroupLayout STEP_SET = BindGroupLayout.builder().withUniform("LavaWavesStep", UniformType.UNIFORM_BUFFER).build();
    /** The two textures the lava's surface is drawn with: the waves as they are now, and the map. */
    static final BindGroupLayout SEEN = BindGroupLayout.builder()
            .withUniform("LavaWavesNow", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("LavaWavesMap", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();
    private static final RenderPipeline STEP = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/lava_waves"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/lava_waves"))
            .withBindGroupLayout(LavaClient.FRAME)
            .withBindGroupLayout(STEP_SET)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LavaWavesMap", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    static void register(RegisterRenderPipelinesEvent event) {
        event.registerOptionalPipeline(STEP);
    }

    private static final Vector4f NOTHING = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private static TextureTarget one, other;
    private static boolean flipped;
    private static GpuTexture mapTexture;
    private static GpuTextureView mapView;
    private static final ByteBuffer MAP = ByteBuffer.allocateDirect(BLOCKS * BLOCKS * 4).order(ByteOrder.nativeOrder());
    /** While the map is put together: how far from the player's height the lava in each column is that has been taken so far. */
    private static final float[] NEAREST = new float[BLOCKS * BLOCKS];
    private static MappableRingBuffer stepBuffer;

    /** The square: its least corner (blocks), the height its heights are counted from, and whether it has been placed at all. */
    private static int gridX, gridZ, baseY, mapEyeY;
    private static boolean placed, mapStale = true;
    /** Cells the square has moved since the last step. */
    private static int movedX, movedZ;
    private static long lastStep;
    /** The waves cannot be worked out on this machine (the lava is then drawn at rest). */
    static volatile boolean off;

    /** Pushes waiting for the next step: x, z (blocks), how wide (blocks), how hard (blocks a second, up or down). */
    private static final double[] PUSH_AT = new double[MOST_PUSHES * 2];
    private static final float[] PUSH = new float[MOST_PUSHES * 2];
    private static int pushes;
    private static final Int2IntOpenHashMap SPLASHED = new Int2IntOpenHashMap();
    private static int ticks;

    /** For {@code /lavalook info} and the smoke test. */
    static volatile int pushed, steps, splashes, lavaColumns;

    /** Something pushes the surface at a place: up (a bubble swelling) or down (a bubble gone, something falling in). */
    static void push(double x, double z, float wide, float hard) {
        pushed++;
        if (pushes >= MOST_PUSHES) return;
        PUSH_AT[pushes * 2] = x; PUSH_AT[pushes * 2 + 1] = z;
        PUSH[pushes * 2] = wide; PUSH[pushes * 2 + 1] = hard;
        pushes++;
    }

    static void clear() {
        pushes = 0;
        placed = false;
        mapStale = true;
        SPLASHED.clear();
        if (one != null) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.clearColorTexture(one.getColorTexture(), NOTHING);
            encoder.clearColorTexture(other.getColorTexture(), NOTHING);
        }
    }

    /** Whatever is in the lava and moving pushes it; what falls in hard throws drops up as well. */
    static void tick(Minecraft mc, ClientLevel level) {
        ticks++;
        if (!placed || off) return;
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isInLava()) continue;
            double x = entity.getX(), z = entity.getZ();
            if (x < gridX || z < gridZ || x >= gridX + BLOCKS || z >= gridZ + BLOCKS) continue;
            Vec3 speed = entity.getDeltaMovement();
            float along = (float) Math.sqrt(speed.x * speed.x + speed.z * speed.z), down = (float) Math.abs(speed.y);
            float size = Math.min(1.5F, 0.4F + entity.getBbWidth());
            float hard = (2.2F * down + 1.2F * along) * size;
            if (hard < 0.03F) continue;
            push(x, z, 0.25F + 0.6F * entity.getBbWidth(), -Math.min(3.0F, hard));
            if (down > 0.3F && ticks - SPLASHED.getOrDefault(entity.getId(), -100) > 15) {
                SPLASHED.put(entity.getId(), ticks);
                splashes++;
                LavaBubbles.splash(level, x, entity.getY(), z, Math.min(0.8F, 0.25F + 0.5F * size * down));
            }
        }
        if (ticks % 200 == 0) SPLASHED.clear();
    }

    private static boolean ready() {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(STEP);
        return compiled != null && !compiled.isClosed();
    }

    /** The textures the surface is drawn with are there (empty, if the waves cannot be worked out). */
    private static void make() {
        if (one != null) return;
        one = new TextureTarget("Lava: waves", ACROSS, ACROSS, GpuFormat.RGBA16_FLOAT, null);
        other = new TextureTarget("Lava: waves, the step before", ACROSS, ACROSS, GpuFormat.RGBA16_FLOAT, null);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(one.getColorTexture(), NOTHING);
        encoder.clearColorTexture(other.getColorTexture(), NOTHING);
        mapTexture = RenderSystem.getDevice().createTexture(() -> "Lava: where it rests", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, BLOCKS, BLOCKS, 1, 1);
        mapView = RenderSystem.getDevice().createTextureView(mapTexture);
        for (int i = 0; i < BLOCKS * BLOCKS * 4; i++) MAP.put(i, (byte) 0);
        MAP.position(0).limit(BLOCKS * BLOCKS * 4);
        encoder.writeToTexture(mapTexture, MAP, 0, 0, 0, 0, BLOCKS, BLOCKS);
    }

    /** The square follows the player (in strides of eight blocks, so that it does not move at every step). */
    static void place(BlockPos eye) {
        make();
        int wantX = (eye.getX() - BLOCKS / 2 + 4) & ~7, wantZ = (eye.getZ() - BLOCKS / 2 + 4) & ~7;
        if (!placed) {
            gridX = wantX; gridZ = wantZ;
            baseY = eye.getY() - 32;
            placed = true;
            mapStale = true;
            movedX = movedZ = 0;
            lastStep = 0L;
        } else if (Math.abs(eye.getX() - (gridX + BLOCKS / 2)) > 12 || Math.abs(eye.getZ() - (gridZ + BLOCKS / 2)) > 12) {
            movedX += (wantX - gridX) * CELLS; movedZ += (wantZ - gridZ) * CELLS;
            gridX = wantX; gridZ = wantZ;
            mapStale = true;
        }
        if (Math.abs(eye.getY() - 32 - baseY) > 12) { baseY = eye.getY() - 32; mapStale = true; }
        if (Math.abs(eye.getY() - mapEyeY) > 2) mapStale = true;
    }

    /** What the lava's surface has changed somewhere: the map is put together afresh. */
    static void changed() {
        mapStale = true;
    }

    /** The map is to be put together now; {@link #rest} is called for the top of the lava in every column, then {@link #endMap}. */
    static boolean beginMap(BlockPos eye) {
        if (!mapStale || !placed) return false;
        mapStale = false;
        mapEyeY = eye.getY();
        Arrays.fill(NEAREST, Float.MAX_VALUE);
        for (int i = 0; i < BLOCKS * BLOCKS * 4; i++) MAP.put(i, (byte) 0);
        lavaColumns = 0;
        return true;
    }

    static boolean covers(int sectionX, int sectionY, int sectionZ) {
        int x = sectionX << 4, y = sectionY << 4, z = sectionZ << 4;
        return x + 16 > gridX && x < gridX + BLOCKS && z + 16 > gridZ && z < gridZ + BLOCKS && y + 16 > baseY && y < baseY + 63;
    }

    static void rest(int x, float y, int z) {
        int gx = x - gridX, gz = z - gridZ;
        float up = y - baseY;
        if (gx < 0 || gz < 0 || gx >= BLOCKS || gz >= BLOCKS || up < 0.0F || up > 62.0F) return;
        float away = Math.abs(y - mapEyeY);
        int at = gz * BLOCKS + gx;
        if (away >= NEAREST[at]) return;
        if (NEAREST[at] == Float.MAX_VALUE) lavaColumns++;
        NEAREST[at] = away;
        int code = Math.round((up + 1.0F) * 512.0F);
        MAP.put(at * 4, (byte) (code >> 8)).put(at * 4 + 1, (byte) code).put(at * 4 + 3, (byte) 255);
    }

    static void endMap() {
        MAP.position(0).limit(BLOCKS * BLOCKS * 4);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(mapTexture, MAP, 0, 0, 0, 0, BLOCKS, BLOCKS);
    }

    /** What the lava's frame is told about the square ({@code LavaGrid} and {@code LavaGridSet} in lava_frame.glsl). */
    static void describe(FloatBuffer out, int at, BlockPos eye) {
        boolean on = placed && !off && lavaColumns > 0;
        out.put(at, gridX - eye.getX()).put(at + 1, gridZ - eye.getZ()).put(at + 2, on ? 1.0F / BLOCKS : 0.0F).put(at + 3, baseY - eye.getY());
        out.put(at + 4, 1.0F / ACROSS).put(at + 5, CELLS).put(at + 6, 1.0F / BLOCKS).put(at + 7, 0.0F);
    }

    /** A step of the waves, by however long it is since the last one (at most a thirtieth of a second: slower frames, slower waves). */
    static void step(GpuBuffer frame) {
        if (off || !placed) return;
        if (lavaColumns == 0 && pushes == 0) { lastStep = 0L; return; }
        try {
            if (!ready()) throw new IllegalStateException("shader pipeline " + STEP.getLocation() + " is not available");
            long now = System.nanoTime();
            float dt = lastStep == 0L ? 1.0F / 60.0F : Math.max(1.0F / 240.0F, Math.min(1.0F / 30.0F, (now - lastStep) / 1.0e9F));
            lastStep = now;
            if (stepBuffer == null) stepBuffer = new MappableRingBuffer(() -> "Lava waves step", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, STEP_BYTES);
            stepBuffer.rotate();
            try (GpuBufferSlice.MappedView view = stepBuffer.currentBuffer().map(false, true)) {
                FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
                out.put(0, dt).put(1, movedX).put(2, movedZ).put(3, pushes);
                for (int i = 0; i < pushes; i++) {
                    out.put(4 + i * 4, (float) ((PUSH_AT[i * 2] - gridX) * CELLS)).put(5 + i * 4, (float) ((PUSH_AT[i * 2 + 1] - gridZ) * CELLS))
                            .put(6 + i * 4, PUSH[i * 2] * CELLS).put(7 + i * 4, PUSH[i * 2 + 1]);
                }
            }
            pushes = 0;
            movedX = movedZ = 0;
            TextureTarget from = flipped ? other : one, to = flipped ? one : other;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "Lava: a step of its waves", to.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipelineNullable(STEP));
                pass.setUniform("LavaFrame", frame);
                pass.setUniform("LavaWavesStep", stepBuffer.currentBuffer());
                pass.setUniform("InSampler", from.getColorTextureView(), nearest);
                pass.setUniform("LavaWavesMap", mapView, nearest);
                pass.draw(3, 1, 0, 0);
            }
            flipped = !flipped;
            steps++;
        } catch (Throwable t) {
            off = true;
            PhysicsBruhh.LOGGER.error("Lava: the waves of its surface could not be worked out on this machine; the lava is drawn at rest", t);
        }
    }

    /** The waves as they are now, and the map, for drawing the surface. */
    static GpuTextureView now() {
        return (flipped ? other : one).getColorTextureView();
    }

    static GpuTextureView map() {
        return mapView;
    }

    /** Whether a section's lava lies in the square (its calm blocks are then cut finer as they are drawn, for the waves to move). */
    static boolean moves(int sectionX, int sectionY, int sectionZ) {
        return placed && !off && lavaColumns > 0 && covers(sectionX, sectionY, sectionZ);
    }
}
