package physicsbruhh.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import physicsbruhh.GasRules;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GasParams;
import physicsbruhh.sim.NativeSim;

/**
 * Fire and smoke as gas: with the game rule {@code physicsbruhh:physical_gas} on, the air in a box
 * around the player is simulated (velocity, pressure, smoke, heat, fuel), and what burns in it
 * feeds it: blocks of fire and campfires, torches, lava, lit furnaces, burning creatures and the
 * player, explosions. Blocks are walls to it.
 *
 * <p>The solver runs on the graphics card ({@link GasGpu}); where its passes do not work, the Rust
 * core computes a coarser box on a thread of its own ({@link GasCpu}). Either way the result is
 * drawn by {@link GasDraw}. {@code /gaslook} sets how fine the grid is on this client and says how
 * long a step takes.
 *
 * <p>This class keeps the box where the player is, keeps the map of the world in it up to date
 * (a few columns each tick, all of it when the box moves), collects the burning creatures and the
 * explosions, and runs a step when a frame is drawn.
 */
final class GasClient {
    /** How fine: blocks in the box, coarse cells to a block, fine cells to a coarse one, rounds of the pressure solve on the coarse grid ({@link GasBox#rounds}). */
    enum Quality {
        OFF(0, 0, 0, 0, 0, 0), LOW(24, 16, 24, 2, 2, 2), MEDIUM(24, 20, 24, 3, 2, 4), HIGH(32, 24, 32, 4, 2, 4), ULTRA(40, 28, 40, 4, 2, 8);

        final int bx, by, bz, cpb, ratio, rounds;

        Quality(int bx, int by, int bz, int cpb, int ratio, int rounds) {
            this.bx = bx; this.by = by; this.bz = bz; this.cpb = cpb; this.ratio = ratio; this.rounds = rounds;
        }

        GasBox box() { return new GasBox(bx, by, bz, cpb, ratio, rounds); }
    }

    /** What the processor's solver computes, whatever the quality asked for: the same reach as "low", half as fine. */
    private static final GasBox COARSE = new GasBox(24, 16, 24, 2, 1, 4);

    /** This client's choices ({@code /gaslook}; the command line wins: {@code -Dphysicsbruhh.gas=off|low|medium|high|ultra}, {@code -Dphysicsbruhh.gas.solver=auto|gpu|cpu}). */
    static volatile Quality quality = Quality.MEDIUM;
    static volatile String solver = "auto";
    /** The picture of the gas is computed at the screen's size divided by this. */
    static volatile int drawScale = 2;
    /** What the server says the game rule is where the player is. */
    static volatile boolean ruleOn;
    /** Set when neither solver works on this machine; the gas then stays off until the game is restarted. */
    static volatile boolean broken;
    private static boolean gpuFailed;

    private GasClient() {}

    // ------------------------------------------------------------------ the box and the world in it

    private static GasBox box;
    private static GasGpu gpu;
    private static GasCpu cpu;
    private static int ox, oy, oz;
    private static boolean placed;
    /** The corner of the box the grids on the graphics card were last stepped for: the box may have moved since. */
    private static int gridX, gridY, gridZ;
    /** The map for the processor's solver: four bytes a block (solid, kind, strength, light), x fastest, then z, then y. */
    private static byte[] plain = new byte[0];
    /** The map as the picture the passes read: red the sky's light, green the kind (plus 128 if solid), blue the strength, alpha the light of lamps. */
    private static ByteBuffer picture;
    private static boolean pictureChanged;
    private static int[] sourcesInColumn = new int[0];
    /** For each column along x: between which heights and how far along z the blocks that feed the gas are (if there are any). */
    private static int[] sourcesFromY = new int[0], sourcesToY = new int[0], sourcesFromZ = new int[0], sourcesToZ = new int[0];
    private static int scanAt;
    /** Blocks in the box that feed the gas. */
    static volatile int sources;

    private record Blast(double x, double y, double z, float power, long born) {}
    private static final List<Blast> BLASTS = new ArrayList<>();
    /** The blobs of this tick: where (in the world), then the other nine numbers of each. */
    private static final double[] blobAt = new double[GasDraw.BLOBS * 3];
    private static final float[] blobRest = new float[GasDraw.BLOBS * 9];
    private static volatile int blobCount;
    private static final float[] blobsInBox = new float[GasDraw.BLOBS * GasParams.BLOB_FLOATS];
    static volatile int blastsSeen, burning;

    private static long lastFrame, lastAlive;
    private static float pending, time;
    static volatile int stepsDone;

    static void onState(GasRules.State state) {
        ruleOn = state.on();
    }

    private static volatile double lastBlastX, lastBlastY, lastBlastZ;
    private static volatile float lastBlastPower;
    private static volatile long lastBlast;

    /** Whether an explosion has just gone off about here, and the gas is drawing it: the game's own white puffs of it are then left out. */
    static boolean blastAt(double x, double y, double z) {
        if (!draws(x, y, z) || System.nanoTime() - lastBlast > 1_500_000_000L) return false;
        double dx = x - lastBlastX, dy = y - lastBlastY, dz = z - lastBlastZ, reach = 3.0 + 2.0 * lastBlastPower;
        return dx * dx + dy * dy + dz * dz < reach * reach;
    }

    static void onBlast(GasRules.Blast blast) {
        lastBlastX = blast.x(); lastBlastY = blast.y(); lastBlastZ = blast.z(); lastBlastPower = blast.power(); lastBlast = System.nanoTime();
        synchronized (BLASTS) {
            if (BLASTS.size() < 32) BLASTS.add(new Blast(blast.x(), blast.y(), blast.z(), blast.power(), System.nanoTime()));
        }
        blastsSeen++;
    }

    /** Whether gas is wanted at all right now. */
    static boolean wanted() {
        return ruleOn && quality != Quality.OFF && !broken;
    }

    private static boolean useGpu() {
        if (gpuFailed || "cpu".equals(solver)) return false;
        return GasGpu.available() && GasDraw.available(false);
    }

    /** Whether gas is being computed and drawn right now. */
    static boolean showing() {
        return box != null && placed && !broken && (gpu != null && gpu.steps > 0 || cpu != null && cpu.gas() != null);
    }

    /** Whether the gas is drawn at this place in the world, so that the game's own smoke and flames there are left out: inside the box, away from its edge, where the gas fades. */
    static boolean draws(double x, double y, double z) {
        return showing() && x >= ox + 2 && y >= oy + 2 && z >= oz + 2 && x < ox + box.bx - 2 && y < oy + box.by - 2 && z < oz + box.bz - 2;
    }

    /** The blocks of fire and lit campfires the gas draws the flames of. */
    private static void hideFlames(ClientLevel level) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (showing()) {
            final int bx = box.bx, by = box.by, bz = box.bz;
            for (int y = 2; y < by - 2; y++) {
                for (int z = 2; z < bz - 2; z++) {
                    for (int x = 2; x < bx - 2; x++) {
                        int kind = plain[(x + bx * (z + bz * y)) * 4 + 1] & 15;
                        if (kind == GasParams.FIRE || kind == GasParams.CAMPFIRE) set.add(BlockPos.asLong(ox + x, oy + y, oz + z));
                    }
                }
            }
        }
        GasHide.update(level, set);
    }

    /** "graphics card", "processor" or "none". */
    static String solverInUse() {
        return gpu != null ? "graphics card" : cpu != null ? "processor" : "none";
    }

    static void reset() {
        ruleOn = false;
        release();
        synchronized (BLASTS) { BLASTS.clear(); }
    }

    private static void release() {
        // called from the client thread and the render thread, which are the same thread
        if (gpu != null) { gpu.close(); gpu = null; }
        if (cpu != null) { cpu.close(); cpu = null; }
        GasDraw.close();
        box = null; placed = false; sources = 0; blobCount = 0; pending = 0.0F;
        GasHide.update(Minecraft.getInstance().level, new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
    }

    /** Every tick of the client: the box follows the player, the map of the world in it is brought up to date, burning creatures and explosions are collected. */
    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !wanted()) {
            if (box != null) release();
            return;
        }
        try {
            GasBox want = useGpu() ? quality.box() : COARSE;
            if (!want.same(box)) {
                release();
                box = want;
                plain = new byte[box.bx * box.by * box.bz * 4];
                picture = ByteBuffer.allocateDirect(box.blockW * box.blockH * 4).order(ByteOrder.nativeOrder());
                sourcesInColumn = new int[box.bx];
                sourcesFromY = new int[box.bx]; sourcesToY = new int[box.bx]; sourcesFromZ = new int[box.bx]; sourcesToZ = new int[box.bx];
            }
            Vec3 eye = mc.player.getEyePosition();
            int wantX = (int) Math.floor(eye.x) - box.bx / 2, wantY = (int) Math.floor(eye.y) - Math.round(box.by * 0.35F), wantZ = (int) Math.floor(eye.z) - box.bz / 2;
            boolean all = false;
            if (!placed) {
                ox = wantX; oy = wantY; oz = wantZ; gridX = ox; gridY = oy; gridZ = oz; placed = true; all = true;
            } else if (Math.abs(wantX - ox) >= 2 || Math.abs(wantY - oy) >= 2 || Math.abs(wantZ - oz) >= 2) {
                ox = wantX; oy = wantY; oz = wantZ; all = true;
            }
            if (all) {
                scan(level, 0, box.bx);
            } else {
                int columns = Math.max(1, (box.bx + 3) / 4);
                scan(level, scanAt, Math.min(box.bx, scanAt + columns));
                scanAt = scanAt + columns >= box.bx ? 0 : scanAt + columns;
            }
            int total = 0;
            for (int count : sourcesInColumn) total += count;
            sources = total;
            hideFlames(level);
            collect(level);
            if (cpu != null) {
                int n = blobsInBox(ox, oy, oz);
                cpu.feed(plain, ox, oy, oz, blobsInBox, n, alive());
            }
        } catch (Throwable t) {
            fail("keeping track of what burns", t);
        }
    }

    /** Columns {@code from..to} (along x) of the map, from the world, each from the top down. */
    private static void scan(ClientLevel level, int from, int to) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int bx = box.bx, by = box.by, bz = box.bz;
        for (int x = from; x < to; x++) {
            int found = 0, fromY = by, toY = -1, fromZ = bz, toZ = -1;
            for (int z = 0; z < bz; z++) {
                BlockState state = level.getBlockState(pos.set(ox + x, oy + by - 1, oz + z));
                int skyAbove = 0, lampsAbove = 0;
                boolean openAbove = false;
                for (int y = by - 1; y >= 0; y--) {
                    BlockState below = level.getBlockState(pos.set(ox + x, oy + y - 1, oz + z));
                    pos.set(ox + x, oy + y, oz + z);
                    int kind = 0, strength = 127;
                    boolean solid = false;
                    if (!state.isAir()) {
                        if (state.getBlock() instanceof BaseFireBlock) {
                            kind = GasParams.FIRE | (state.is(Blocks.SOUL_FIRE) ? GasParams.SOUL : 0);
                        } else if (state.getBlock() instanceof CampfireBlock) {
                            if (state.getValue(CampfireBlock.LIT)) {
                                kind = GasParams.CAMPFIRE | (state.is(Blocks.SOUL_CAMPFIRE) ? GasParams.SOUL : 0);
                                if (state.getValue(CampfireBlock.SIGNAL_FIRE)) strength = 230;
                            }
                        } else if (state.getBlock() instanceof TorchBlock) {
                            kind = GasParams.TORCH | (state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH) ? GasParams.SOUL : 0);
                        } else if (!state.getFluidState().isEmpty()) {
                            solid = true; // gas does not go into water or lava
                        } else {
                            solid = state.isCollisionShapeFullBlock(level, pos);
                        }
                    }
                    if (kind == 0 && !solid) {
                        // open space: lava or a lit furnace under it warms and smokes it
                        if (below.is(Blocks.LAVA)) kind = GasParams.OVER_LAVA;
                        else if (below.getBlock() instanceof AbstractFurnaceBlock && below.getValue(AbstractFurnaceBlock.LIT)) kind = GasParams.CHIMNEY;
                    }
                    if (kind != 0) {
                        found++;
                        fromY = Math.min(fromY, y); toY = Math.max(toY, y); fromZ = Math.min(fromZ, z); toZ = Math.max(toZ, z);
                    }
                    // a block is as light as the open space over it: smoke lying on the ground is not darkened by the ground
                    int sky = solid ? (openAbove ? skyAbove : 0) : level.getBrightness(LightLayer.SKY, pos);
                    int lamps = solid ? (openAbove ? lampsAbove : 0) : level.getBrightness(LightLayer.BLOCK, pos);
                    int at = (x + bx * (z + bz * y)) * 4;
                    plain[at] = (byte) (solid ? 255 : 0); plain[at + 1] = (byte) kind; plain[at + 2] = (byte) strength; plain[at + 3] = (byte) (sky * 16 + lamps);
                    put(box.blockPixel(x, y, z), sky * 17, kind | (solid ? 128 : 0), strength, lamps * 17);
                    openAbove = !solid; skyAbove = sky; lampsAbove = lamps;
                    state = below;
                }
            }
            sourcesInColumn[x] = found;
            sourcesFromY[x] = fromY; sourcesToY[x] = toY; sourcesFromZ[x] = fromZ; sourcesToZ[x] = toZ;
        }
    }

    // ------------------------------------------------------------------ the part of the box that is computed

    /** In blocks from the corner of the box: the least corner of the part that is computed this step, then the corner just past its greatest. */
    private static final int[] part = new int[6];
    /** How much of the box that is, 0 to 1. */
    static volatile float partOfBox = 1.0F;

    private static void include(double x0, double y0, double z0, double x1, double y1, double z1) {
        part[0] = Math.min(part[0], (int) Math.floor(x0)); part[1] = Math.min(part[1], (int) Math.floor(y0)); part[2] = Math.min(part[2], (int) Math.floor(z0));
        part[3] = Math.max(part[3], (int) Math.ceil(x1)); part[4] = Math.max(part[4], (int) Math.ceil(y1)); part[5] = Math.max(part[5], (int) Math.ceil(z1));
    }

    /**
     * Gas is seldom everywhere in the box: a campfire and its smoke fill a few dozen blocks of thousands. The solver on
     * the graphics card computes only a smaller box round what there is: the gas that was last seen (read back now and
     * then, so a little old: three blocks more on every side), the blocks that feed it and the room over them, the
     * burning creatures, and for a few seconds the place of an explosion. Whatever gets to the edge of that is gone, as
     * at the edge of the whole box; while it is not known where the gas is, the whole box is computed.
     */
    private static void findPart(int blobs) {
        final int bx = box.bx, by = box.by, bz = box.bz;
        int[] seen = GasDraw.bounds();
        if (seen == null || WHOLE) {
            part[0] = 0; part[1] = 0; part[2] = 0; part[3] = bx; part[4] = by; part[5] = bz;
            partOfBox = 1.0F;
            return;
        }
        part[0] = bx; part[1] = by; part[2] = bz; part[3] = 0; part[4] = 0; part[5] = 0;
        if (seen[3] > seen[0]) include(seen[0] - ox - 3, seen[1] - oy - 3, seen[2] - oz - 3, seen[3] - ox + 3, seen[4] - oy + 3, seen[5] - oz + 3);
        for (int x = 0; x < bx; x++) {
            if (sourcesInColumn[x] > 0) include(x - 2, sourcesFromY[x] - 1, sourcesFromZ[x] - 2, x + 3, sourcesToY[x] + 6, sourcesToZ[x] + 3);
        }
        for (int i = 0; i < blobs; i++) {
            int at = i * GasParams.BLOB_FLOATS;
            float reach = blobsInBox[at + 3] + 3.0F;
            include(blobsInBox[at] - reach, blobsInBox[at + 1] - reach, blobsInBox[at + 2] - reach, blobsInBox[at] + reach, blobsInBox[at + 1] + reach, blobsInBox[at + 2] + reach);
        }
        synchronized (BLASTS) {
            for (Blast blast : BLASTS) {
                double reach = 3.0 + 1.5 * blast.power;
                include(blast.x - ox - reach, blast.y - oy - reach, blast.z - oz - reach, blast.x - ox + reach, blast.y - oy + reach, blast.z - oz + reach);
            }
        }
        part[0] = Math.max(0, part[0]); part[1] = Math.max(0, part[1]); part[2] = Math.max(0, part[2]);
        part[3] = Math.min(bx, part[3]); part[4] = Math.min(by, part[4]); part[5] = Math.min(bz, part[5]);
        if (part[3] <= part[0] || part[4] <= part[1] || part[5] <= part[2]) {
            // nothing anywhere: one block, so that there is something to draw the passes over
            part[0] = 0; part[1] = 0; part[2] = 0; part[3] = 1; part[4] = 1; part[5] = 1;
        }
        partOfBox = (float) (part[3] - part[0]) * (part[4] - part[1]) * (part[5] - part[2]) / ((float) bx * by * bz);
    }

    /** {@code -Dphysicsbruhh.gas.whole=true}: the whole box is computed every step, as if gas were everywhere (to compare). */
    private static final boolean WHOLE = Boolean.getBoolean("physicsbruhh.gas.whole");

    private static void put(int at, int red, int green, int blue, int alpha) {
        int now = (red & 255) | (green & 255) << 8 | (blue & 255) << 16 | (alpha & 255) << 24;
        int was = (picture.get(at) & 255) | (picture.get(at + 1) & 255) << 8 | (picture.get(at + 2) & 255) << 16 | (picture.get(at + 3) & 255) << 24;
        if (now == was) return;
        picture.put(at, (byte) red).put(at + 1, (byte) green).put(at + 2, (byte) blue).put(at + 3, (byte) alpha);
        pictureChanged = true;
    }

    /** Burning creatures and explosions near the box, as blobs. */
    private static void collect(ClientLevel level) {
        int n = 0, onFire = 0;
        long now = System.nanoTime();
        synchronized (BLASTS) {
            for (int i = BLASTS.size() - 1; i >= 0; i--) {
                Blast blast = BLASTS.get(i);
                float age = (now - blast.born) / 1.0e9F;
                // (kept a while after it has stopped feeding the gas: the solver computes the room round it meanwhile, findPart)
                if (age > 3.0F) { BLASTS.remove(i); continue; }
                if (age > 0.7F || n >= GasDraw.BLOBS) continue;
                float reach = Math.max(1.0F, 0.6F * blast.power);
                blobAt[n * 3] = blast.x; blobAt[n * 3 + 1] = blast.y; blobAt[n * 3 + 2] = blast.z;
                // a fireball at first: fuel, heat and a great deal of new gas; then the smoke it leaves
                if (age < 0.15F) rest(n, reach, 14.0F, 6.0F, 1.4F, 0.0F, 0.0F, 0.0F, 0.0F, 15.0F * blast.power);
                else rest(n, 0.85F * reach, 0.0F, 3.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
                n++;
            }
        }
        double reach = 2.0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isOnFire() || entity.isSpectator()) continue;
            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            if (x < ox - reach || y < oy - reach || z < oz - reach || x > ox + box.bx + reach || y > oy + box.by + reach || z > oz + box.bz + reach) continue;
            onFire++;
            float width = entity.getBbWidth(), height = entity.getBbHeight();
            float radius = Math.max(0.4F, 0.75F * width);
            int parts = Math.max(1, Math.min(3, Math.round(height / (1.4F * radius))));
            Vec3 speed = entity.getDeltaMovement();
            for (int part = 0; part < parts && n < GasDraw.BLOBS; part++) {
                blobAt[n * 3] = x; blobAt[n * 3 + 1] = y + height * (part + 0.5) / parts; blobAt[n * 3 + 2] = z;
                rest(n, radius, 5.0F, 0.4F, 0.6F, 0.0F, (float) speed.x * 20.0F, (float) speed.y * 20.0F, (float) speed.z * 20.0F, 0.0F);
                n++;
            }
        }
        burning = onFire;
        blobCount = n;
    }

    private static void rest(int n, float radius, float fuel, float smoke, float heat, float soul, float vx, float vy, float vz, float expand) {
        int k = n * 9;
        blobRest[k] = radius; blobRest[k + 1] = fuel; blobRest[k + 2] = smoke; blobRest[k + 3] = heat; blobRest[k + 4] = soul;
        blobRest[k + 5] = vx; blobRest[k + 6] = vy; blobRest[k + 7] = vz; blobRest[k + 8] = expand;
    }

    /** The blobs with their places counted from a corner of the box, as the solvers take them. Returns how many. */
    private static int blobsInBox(int cornerX, int cornerY, int cornerZ) {
        int n = blobCount;
        for (int i = 0; i < n; i++) {
            int to = i * GasParams.BLOB_FLOATS, from = i * 9;
            blobsInBox[to] = (float) (blobAt[i * 3] - cornerX); blobsInBox[to + 1] = (float) (blobAt[i * 3 + 1] - cornerY); blobsInBox[to + 2] = (float) (blobAt[i * 3 + 2] - cornerZ);
            blobsInBox[to + 3] = blobRest[from];
            for (int k = 0; k < 4; k++) blobsInBox[to + 4 + k] = blobRest[from + 1 + k];
            for (int k = 0; k < 4; k++) blobsInBox[to + 8 + k] = blobRest[from + 5 + k];
        }
        return n;
    }

    /** Whether there is anything to compute: something feeds the gas, or some of it is still in the air. */
    private static boolean alive() {
        long now = System.nanoTime();
        float[] sums = cpu != null ? cpu.totals() : GasDraw.sums();
        boolean gasLeft = sums.length >= 2 && (sums[0] > 0.02F || sums[1] > 0.02F);
        if (sources > 0 || blobCount > 0 || gasLeft) lastAlive = now;
        // what the sums say is a few frames old (and for a while there may be none): keep going a little longer than they say
        return now - lastAlive < (sums.length == 0 ? 60_000_000_000L : 3_000_000_000L);
    }

    // ------------------------------------------------------------------ every frame

    static void render(RenderLevelStageEvent.AfterLevel event) {
        if (box == null || !placed || !wanted()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            frame(mc, event);
        } catch (Throwable t) {
            fail(gpu != null ? "the solver on the graphics card" : "drawing", t);
        }
    }

    private static void frame(Minecraft mc, RenderLevelStageEvent.AfterLevel event) {
        boolean onCard = useGpu();
        if (gpu == null && cpu == null && !alive()) return; // nothing burns here: nothing to set up yet
        if (onCard && gpu == null && box.same(quality.box())) {
            if (cpu != null) { cpu.close(); cpu = null; }
            gpu = new GasGpu(box);
            PhysicsBruhh.LOGGER.info("Gas: computed on the graphics card, {}", box);
        } else if (!onCard && cpu == null && box.same(COARSE)) {
            if (gpu != null) { gpu.close(); gpu = null; }
            if (!NativeSim.available()) throw new IllegalStateException("the graphics card cannot run the gas solver and the Rust core is not there (" + NativeSim.loadMessage() + ")");
            if (!GasDraw.available(true)) throw new IllegalStateException("the passes that draw the gas are not available on this machine");
            cpu = new GasCpu(box);
            PhysicsBruhh.LOGGER.info("Gas: computed by the Rust core on the processor, {}", box);
        }
        if (gpu == null && cpu == null) return; // the box is about to be remade for the other solver (next tick)
        if (cpu != null && cpu.failed) throw new IllegalStateException("the gas solver of the Rust core failed");

        long now = System.nanoTime();
        float passed = lastFrame == 0L ? 0.0F : Math.min(0.25F, (now - lastFrame) / 1.0e9F);
        lastFrame = now;
        boolean awake = alive() && !mc.isPaused();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuTextureView world = GasDraw.world(encoder, box, picture, pictureChanged);
        pictureChanged = false;

        boolean stepped = false;
        float dt = 0.0F;
        if (gpu != null && awake) {
            pending += passed;
            if (pending >= 1.0F / 61.0F) {
                dt = Math.min(pending, 1.0F / 20.0F);
                pending = 0.0F;
                stepped = true;
            }
        }
        time = (time + dt) % 3600.0F;
        int n = gpu != null ? blobsInBox(ox, oy, oz) : 0;
        int movedX = stepped ? ox - gridX : 0, movedY = stepped ? oy - gridY : 0, movedZ = stepped ? oz - gridZ : 0;
        if (Math.abs(movedX) >= box.bx / 2 || Math.abs(movedY) >= box.by / 2 || Math.abs(movedZ) >= box.bz / 2) {
            // the player is somewhere else altogether: nothing of the old gas is in the box any more
            gpu.clear();
            movedX = 0; movedY = 0; movedZ = 0;
        }
        if (gpu != null) {
            if (stepped) findPart(n);
        } else {
            // the processor's solver computes the whole box
            part[0] = 0; part[1] = 0; part[2] = 0; part[3] = box.bx; part[4] = box.by; part[5] = box.bz;
            partOfBox = 1.0F;
        }
        GpuBuffer grid = GasDraw.writeGrid(box, dt, time, movedX, movedY, movedZ, blobsInBox, n, part);
        Timer.begin(encoder);
        if (gpu != null) {
            if (stepped) {
                gpu.step(encoder, grid, world, part[4] - part[1]);
                gridX = ox; gridY = oy; gridZ = oz;
                stepsDone++;
                GasDraw.where(encoder, box, grid, gpu.gas(), false, gridX, gridY, gridZ);
            }
        } else if (cpu.take(encoder)) {
            stepsDone = cpu.steps;
            GasDraw.where(encoder, box, grid, cpu.gas(), true, cpu.shownX(), cpu.shownY(), cpu.shownZ());
        }
        Timer.middle(encoder, stepped);
        boolean something = gpu != null ? gpu.steps > 0 : cpu.gas() != null;
        float[] sums = cpu != null ? cpu.totals() : GasDraw.sums();
        boolean empty = sums.length >= 2 && sums[0] <= 0.0F && sums[1] <= 0.0F && sources == 0 && blobCount == 0;
        if (something && !empty) {
            if (gpu != null) GasDraw.draw(encoder, event, box, grid, gpu.gas(), false, world, gridX, gridY, gridZ);
            else GasDraw.draw(encoder, event, box, grid, cpu.gas(), true, world, cpu.shownX(), cpu.shownY(), cpu.shownZ());
        }
        Timer.end(encoder);
    }

    private static void fail(String what, Throwable t) {
        if (gpu != null && !gpuFailed) {
            gpuFailed = true;
            PhysicsBruhh.LOGGER.error("Gas: {} failed on this machine; the Rust core computes the gas on the processor from now on", what, t);
            release();
        } else {
            broken = true;
            PhysicsBruhh.LOGGER.error("Gas: {} failed on this machine; fire and smoke are left as the game draws them", what, t);
            try { release(); } catch (Throwable ignored) { }
        }
    }

    /** For the smoke test and {@code /gaslook info}: smoke in all, heat in all, the hottest, then the smoke in each layer of blocks from the bottom of the box up. */
    static float[] sums() {
        if (cpu != null) {
            float[] t = cpu.totals();
            if (t.length < 4) return new float[0];
            float[] out = new float[t.length - 1];
            out[0] = t[0]; out[1] = t[1]; out[2] = t[2];
            System.arraycopy(t, 4, out, 3, t.length - 4);
            return out;
        }
        return GasDraw.sums();
    }

    static int boxY() { return oy; }

    /** Milliseconds a step takes: on the graphics card as its own clock says (less than zero if it has none), on the processor as measured. */
    static float stepMs() {
        return cpu != null ? cpu.stepMs : Timer.stepMs;
    }

    static float drawMs() {
        return Timer.drawMs;
    }

    /** How long the graphics card takes over a step and over the drawing, by its own clock. */
    private static final class Timer {
        private static GpuQueryPool pool;
        private static boolean off;
        private static int turn;
        private static final boolean[] written = new boolean[3], stepped = new boolean[3];
        static volatile float stepMs = -1.0F, drawMs = -1.0F;

        static void begin(CommandEncoder encoder) {
            if (off) return;
            try {
                if (pool == null) pool = RenderSystem.getDevice().createTimestampQueryPool(9);
                turn = (turn + 1) % 3;
                if (written[turn]) {
                    OptionalLong[] v = pool.getValues(turn * 3, 3);
                    if (v[0].isPresent() && v[1].isPresent() && v[2].isPresent()) {
                        float period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
                        float step = (v[1].getAsLong() - v[0].getAsLong()) * period / 1.0e6F, draw = (v[2].getAsLong() - v[1].getAsLong()) * period / 1.0e6F;
                        if (stepped[turn] && step >= 0.0F) stepMs = stepMs < 0.0F ? step : stepMs * 0.9F + step * 0.1F;
                        if (draw >= 0.0F) drawMs = drawMs < 0.0F ? draw : drawMs * 0.9F + draw * 0.1F;
                    }
                }
                encoder.writeTimestamp(pool, turn * 3);
                written[turn] = true;
            } catch (Throwable t) {
                off = true;
                PhysicsBruhh.LOGGER.info("Gas: this graphics card has no clock to time the passes with ({})", t.toString());
            }
        }

        static void middle(CommandEncoder encoder, boolean didStep) {
            if (off || pool == null) return;
            try { encoder.writeTimestamp(pool, turn * 3 + 1); stepped[turn] = didStep; } catch (Throwable t) { off = true; }
        }

        static void end(CommandEncoder encoder) {
            if (off || pool == null) return;
            try { encoder.writeTimestamp(pool, turn * 3 + 2); } catch (Throwable t) { off = true; }
        }
    }

    // ------------------------------------------------------------------ this client's choices

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("physicsbruhh-gas.properties");
    }

    static void load() {
        String fine = System.getenv("PHYSICSBRUHH_GAS") != null ? System.getenv("PHYSICSBRUHH_GAS") : System.getProperty("physicsbruhh.gas");
        String by = System.getProperty("physicsbruhh.gas.solver", System.getenv("PHYSICSBRUHH_GAS_SOLVER"));
        try {
            Path file = file();
            if (Files.exists(file)) {
                Properties properties = new Properties();
                try (var in = Files.newInputStream(file)) { properties.load(in); }
                if (fine == null) fine = properties.getProperty("gas");
                if (by == null) by = properties.getProperty("solver");
            }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not read the gas setting: {}", e.toString());
        }
        if (fine != null) {
            try { quality = Quality.valueOf(fine.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { PhysicsBruhh.LOGGER.warn("Gas: unknown setting '{}'", fine); }
        }
        if (by != null && (by.equals("auto") || by.equals("gpu") || by.equals("cpu"))) solver = by;
        drawScale = Integer.getInteger("physicsbruhh.gas.scale", 2) <= 1 ? 1 : 2;
    }

    private static void save() {
        try {
            Properties properties = new Properties();
            properties.setProperty("gas", quality.name().toLowerCase(Locale.ROOT));
            properties.setProperty("solver", solver);
            try (var out = Files.newOutputStream(file())) { properties.store(out, "Physics Bruhh, smoke and fire, this client only. gas = off | low | medium | high | ultra; solver = auto | gpu | cpu"); }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not save the gas setting: {}", e.toString());
        }
    }

    static void registerCommand(RegisterClientCommandsEvent event) {
        var command = Commands.literal("gaslook");
        for (Quality q : Quality.values()) {
            command.then(Commands.literal(q.name().toLowerCase(Locale.ROOT)).executes(context -> {
                quality = q;
                save();
                context.getSource().sendSuccess(() -> q == Quality.OFF
                        ? Component.translatableWithFallback("commands.physicsbruhh.gas.off", "Fire and smoke are left as the game draws them on this client")
                        : Component.translatableWithFallback("commands.physicsbruhh.gas.set", "Fire and smoke: a box of %s x %s x %s blocks around you, %s cells to the block", q.bx, q.by, q.bz, q.cpb * q.ratio), false);
                return 1;
            }));
        }
        for (String by : new String[] { "auto", "gpu", "cpu" }) {
            command.then(Commands.literal("solver").then(Commands.literal(by).executes(context -> {
                solver = by;
                gpuFailed = false;
                save();
                context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.gas.solver." + by,
                        by.equals("cpu") ? "Fire and smoke are computed by the Rust core on the processor (coarser)" : "Fire and smoke are computed on the graphics card where it can do it"), false);
                return 1;
            })));
        }
        command.then(Commands.literal("info").executes(context -> {
            if (!ruleOn) {
                context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.gas.info.rule", "Fire and smoke are not gas here: the game rule physicsbruhh:physical_gas is off"), false);
            } else if (broken) {
                context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.gas.info.broken", "Fire and smoke as gas did not work on this machine (see the log)"), false);
            } else if (box == null || gpu == null && cpu == null) {
                context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.gas.info.idle", "Fire and smoke: nothing is being computed right now (%s)", quality.name().toLowerCase(Locale.ROOT)), false);
            } else {
                final GasBox b = box;
                float ms = stepMs(), draw = drawMs();
                String step = ms < 0.0F ? "?" : String.format(Locale.ROOT, "%.2f", ms), picture = draw < 0.0F ? "?" : String.format(Locale.ROOT, "%.2f", draw);
                String million = String.format(Locale.ROOT, "%.2f", b.fineCells() / 1.0e6), share = String.valueOf(Math.round(partOfBox * 100.0F));
                context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.gas.info",
                        "Fire and smoke: computed on the %s, %s x %s x %s cells (%s million), a step takes %s ms and the drawing %s ms; %s blocks and %s creatures feed it; %s%% of the box is computed right now",
                        Component.translatableWithFallback("commands.physicsbruhh.gas.on." + (gpu != null ? "gpu" : "cpu"), solverInUse()), b.fx, b.fy, b.fz, million, step, picture, sources, burning, share), false);
            }
            return 1;
        }));
        event.getDispatcher().register(command);
    }
}
