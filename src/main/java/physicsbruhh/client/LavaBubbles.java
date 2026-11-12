package physicsbruhh.client;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Lava that bubbles. Where lava lies in a pool (a block of it with lava all around, the deeper and
 * wider the more so) gas comes up through it now and then: the surface swells into a dome, the
 * dome bursts with a pop, and its skin flies off as drops that fall back. Here the bubbles are
 * found a place and a size, kept while they live, and handed to the graphics card, which makes the
 * dome and the drops out of them ({@code shaders/core/lava_bubbles.vsh}); the surface around them
 * is pushed by them ({@link LavaWaves}).
 */
final class LavaBubbles {
    private LavaBubbles() {}

    static final int MOST = 48;
    /** Faces a bubble is drawn with: its dome and its drops (the same numbers as lava_bubbles.vsh). */
    static final int FACES = 5 * 12 + 8 * 3 * 6;
    /** How far from the player bubbles come up, and how long after it has burst a bubble's drops are still about. */
    private static final float REACH = 30.0F, AFTER = 2.2F;
    private static final int TRIES = 6;
    /** How often, as a multiple of the usual (the smoke test wants to see some within seconds). */
    static volatile float rate = 1.0F;

    private static final double[] x = new double[MOST], y = new double[MOST], z = new double[MOST];
    private static final float[] radius = new float[MOST], born = new float[MOST], swell = new float[MOST], seed = new float[MOST], sky = new float[MOST];
    private static final boolean[] burst = new boolean[MOST];
    private static int count;
    private static final Random RANDOM = new Random();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static MappableRingBuffer buffer;
    private static int written;

    /** For {@code /lavalook info} and the smoke test. */
    static volatile int risen, popped, alive;

    static void clear() {
        count = 0;
        written = 0;
        alive = 0;
    }

    private static boolean lava(ClientLevel level, int bx, int by, int bz) {
        return level.getFluidState(POS.set(bx, by, bz)).getType().isSame(Fluids.LAVA);
    }

    static void tick(Minecraft mc, ClientLevel level, float now) {
        if (mc.player == null) return;
        // those whose time has come burst, those that are over are dropped
        for (int i = 0; i < count; i++) {
            if (!burst[i] && now >= born[i] + swell[i]) {
                burst[i] = true;
                popped++;
                level.playLocalSound(x[i], y[i], z[i], SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.25F + 0.6F * radius[i], 1.2F - 0.7F * radius[i] + 0.1F * RANDOM.nextFloat(), false);
                for (int k = 0; k < 1 + (int) (radius[i] * 4.0F); k++) level.addParticle(ParticleTypes.LAVA, x[i], y[i] + 0.15, z[i], 0.0, 0.0, 0.0);
                LavaWaves.push(x[i], z[i], radius[i] * 1.2F, -0.9F * radius[i]);
            } else if (!burst[i]) {
                // while it swells it lifts the surface around it
                LavaWaves.push(x[i], z[i], radius[i] * 1.6F, 0.035F * radius[i]);
            }
            if (now > born[i] + swell[i] + AFTER) {
                int last = --count;
                x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
                radius[i] = radius[last]; born[i] = born[last]; swell[i] = swell[last]; seed[i] = seed[last]; sky[i] = sky[last]; burst[i] = burst[last];
                i--;
            }
        }
        alive = count;
        // new ones: places are tried around the player, the nearer the more often
        double px = mc.player.getX(), pz = mc.player.getZ();
        int py = mc.player.getBlockY();
        for (int t = 0; t < TRIES && count < MOST; t++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0, far = RANDOM.nextDouble() * REACH;
            int bx = (int) Math.floor(px + Math.cos(angle) * far), bz = (int) Math.floor(pz + Math.sin(angle) * far);
            // the top of the lava in that column, around the player's height
            int by = Integer.MIN_VALUE;
            boolean above = lava(level, bx, py + 13, bz);
            for (int h = py + 12; h >= py - 24; h--) {
                boolean here = lava(level, bx, h, bz);
                if (here && !above) { by = h; break; }
                above = here;
            }
            if (by == Integer.MIN_VALUE) continue;
            FluidState fluid = level.getFluidState(POS.set(bx, by, bz));
            if (!fluid.isSource() || !level.getBlockState(POS.set(bx, by + 1, bz)).isAir()) continue;
            // a pool: lava on every side; how deep it is, and how far it reaches at the least
            boolean pool = true;
            for (int dz = -1; dz <= 1 && pool; dz++) for (int dx = -1; dx <= 1; dx++) if (!lava(level, bx + dx, by, bz + dz)) { pool = false; break; }
            if (!pool) continue;
            int deep = 0;
            while (deep < 4 && lava(level, bx, by - 1 - deep, bz)) deep++;
            int wide = 3;
            for (int side = 0; side < 4; side++) {
                int sx = side == 0 ? 1 : side == 1 ? -1 : 0, sz = side == 2 ? 1 : side == 3 ? -1 : 0, run = 1;
                while (run < 3 && lava(level, bx + sx * (run + 1), by, bz + sz * (run + 1))) run++;
                wide = Math.min(wide, run);
            }
            if (RANDOM.nextFloat() > 0.012F * rate * (1.0F + 0.5F * deep)) continue;
            float r = RANDOM.nextFloat();
            float size = Math.max(0.16F, Math.min(0.85F, (0.2F + 0.5F * r * r) * (0.6F + 0.2F * deep) * (0.5F + 0.25F * wide)));
            double cx = bx + 0.2 + 0.6 * RANDOM.nextDouble(), cz = bz + 0.2 + 0.6 * RANDOM.nextDouble(), cy = by + fluid.getOwnHeight();
            boolean crowded = false;
            for (int i = 0; i < count; i++) {
                double dx = x[i] - cx, dz = z[i] - cz, apart = radius[i] + size + 0.3;
                if (Math.abs(y[i] - cy) < 1.0 && dx * dx + dz * dz < apart * apart) { crowded = true; break; }
            }
            if (crowded) continue;
            int i = count++;
            x[i] = cx; y[i] = cy; z[i] = cz;
            radius[i] = size;
            born[i] = now;
            swell[i] = 0.7F + 0.9F * RANDOM.nextFloat() + 0.5F * size;
            seed[i] = RANDOM.nextFloat();
            sky[i] = level.getBrightness(LightLayer.SKY, POS.set(bx, by + 1, bz)) / 15.0F;
            burst[i] = false;
            risen++;
        }
    }

    /** The bubbles as the graphics card is given them: places counted from the section the camera is in. */
    static void write(int sx, int sy, int sz) {
        written = 0;
        if (count == 0) return;
        if (buffer == null) buffer = new MappableRingBuffer(() -> "Lava bubbles", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, (1 + MOST * 2) * 16);
        buffer.rotate();
        try (GpuBufferSlice.MappedView view = buffer.currentBuffer().map(0L, (long) (1 + count * 2) * 16L, false, true)) {
            FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
            out.put(0, sx).put(1, sy).put(2, sz).put(3, 0.0F);
            for (int i = 0; i < count; i++) {
                int o = 4 + i * 8;
                out.put(o, (float) (x[i] - (sx << 4))).put(o + 1, (float) (y[i] - (sy << 4))).put(o + 2, (float) (z[i] - (sz << 4))).put(o + 3, radius[i]);
                out.put(o + 4, born[i]).put(o + 5, swell[i]).put(o + 6, seed[i]).put(o + 7, sky[i]);
            }
        }
        written = count;
    }

    /** How many are ready to be drawn this frame, and from what. */
    static int written() {
        return written;
    }

    static GpuBuffer buffer() {
        return buffer.currentBuffer();
    }
}
