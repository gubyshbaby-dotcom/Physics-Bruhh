package physicsbruhh.client;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import physicsbruhh.GooEntity;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GooSim;
import physicsbruhh.sim.JavaSim;
import physicsbruhh.sim.NativeSim;

/**
 * Owns the client-side goo world.
 *
 * <p>Threads: the simulation runs on its own thread at 60 steps a second and is the only thread
 * that touches the {@link GooSim}. The client thread feeds it (new bodies, collider snapshots)
 * through queues and reads back body bounds and the latest mesh.
 */
public final class GooClient {
    private static final int PARTICLES_PER_EDGE = 12;
    /**
     * The most pieces of goo simulated at once ({@code -Dphysicsbruhh.goo.most=<n>}). It is there so that a
     * world full of honey cannot eat all the memory (a piece takes about half a megabyte), not because
     * the simulation needs a number: room is made as pieces arrive. Past it, the oldest piece that lies
     * still makes room for a new one.
     */
    private static final int MAX_BODIES = Math.max(24, Math.min(8192, Integer.getInteger("physicsbruhh.goo.most", 1024)));
    /** The Java simulation keeps room for all its pieces from the start, and is far too slow for many anyway. */
    private static final int MAX_BODIES_JAVA = Math.min(MAX_BODIES, 64);
    /** Block boxes and moving boxes (piston parts, creatures) around the goo that is awake, at most. */
    private static final int MAX_STATIC = 1 << 16, MAX_KINEMATIC = 512;
    /**
     * How strongly goo clings to a sticky piston's head (1 = an ordinary block). High enough that slime
     * stretched to three blocks between two of them never peels off; at 3 it did so about every other time.
     */
    private static final float STICKY_HEAD = 8.0F;

    /** What the client thread hands to the simulation each tick. */
    private record Colliders(float[] statics, int staticCount, float[] kinematics, int kinematicCount) {}

    /** Latest surface, shared with the renderer under {@link #MESH_LOCK}. */
    static final Object MESH_LOCK = new Object();
    static float[][] meshData = { new float[0], new float[0], new float[0] };
    static int[] meshQuads = { 0, 0, 0 };
    /** Where the puddles of that surface are ({@link GooSim#PART_FLOATS} floats each), and how many there are. */
    static float[][] meshParts = { new float[0], new float[0], new float[0] };
    static int[] meshPartCount = { 0, 0, 0 };
    static int meshVersion;
    static volatile BlockPos origin = BlockPos.ZERO;
    /**
     * Latest particles (x, y, z, kind and size: see {@link GooSim#particles}), shared with the liquid
     * look and with the lava under {@link #PARTICLE_LOCK}. Only kept up to date while
     * {@link #wantParticles} is set. Which surface meshes are built meanwhile is in {@link #wantMeshes}.
     */
    static final Object PARTICLE_LOCK = new Object();
    static float[] particleData = new float[0];
    static int particleCount, particleVersion;
    static volatile boolean wantParticles;
    /** The materials a surface mesh is wanted for, a bit each: honey and slime unless they are drawn as liquid, lava only where it cannot be drawn from its particles. */
    static volatile int wantMeshes = 3;
    /** Whether there is lava that flows as a body among the goo (it is drawn and looked after by {@link LavaGoo}). */
    static volatile boolean lavaPresent;
    private static int lavaLinger;

    private static volatile GooSim sim;
    private static Thread thread;
    private static volatile boolean running, paused;
    private static final ConcurrentLinkedQueue<Runnable> COMMANDS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Colliders> COLLIDERS = new AtomicReference<>();
    private static volatile float[] bodyInfo = new float[0];
    private static final ConcurrentHashMap<Integer, Integer> SLOT_OF_ENTITY = new ConcurrentHashMap<>();
    private static final List<GooEntity> PENDING = new ArrayList<>();
    private static final IntOpenHashSet KNOWN = new IntOpenHashSet();
    private static final Int2ObjectOpenHashMap<GooEntity> LIVE = new Int2ObjectOpenHashMap<>();
    /** How much goo each piece holds as far as the simulation has been told (particles); the markers say how much it should be. */
    private static final Int2IntOpenHashMap TOLD = new Int2IntOpenHashMap();
    /** Goo is drawn as clear liquid (the server's {@code clear_goo} game rule, read off the goo markers). */
    static volatile boolean clearLook;
    /** Cells of bodies added this tick: their surroundings are scanned at once, so they never spend a tick without a floor. */
    private static final List<BlockPos> NEW_CELLS = new ArrayList<>();
    /**
     * Cells where a block has just turned into goo, and what goo. The block itself may still be there
     * in this game's own picture of the world: a block placed by hand is put there by the client at
     * once, on its own guess, the goo arrives as soon as the server has made it, and the word that the
     * block is gone only comes with the server's next round of block changes, a tick later. Scanned as
     * a block, it would be a solid cube exactly where the new goo is, and the goo would be thrown out
     * of it through all six faces. So until it has been seen gone it is not a collider.
     */
    private record Born(int material, int until) {}
    private static final Long2ObjectOpenHashMap<Born> BORN = new Long2ObjectOpenHashMap<>();
    /** Client ticks a block goo came from may linger before it counts as a real block after all (ten seconds: lag). */
    private static final int BORN_GRACE = 200;
    /** How many pieces of goo have arrived while the block they came from was still standing in this client's world (smoke test). */
    static volatile int bornInsideBlock;
    /** The local player's box, widened a little (relative to {@link #origin}), and how much goo is in it: honey in the low 16 bits, slime above. */
    private static volatile float[] probeBox;
    private static volatile int probeResult;
    /** How far around the player's box goo counts as touching them. */
    static final double PROBE_REACH = 0.22;

    /**
     * Test mode ({@code -Dphysicsbruhh.lockstep=true}): exactly three simulation steps per client
     * tick instead of wall-clock time, so a machine that is too slow shows the same motion, only slower.
     */
    private static final boolean LOCKSTEP = Boolean.getBoolean("physicsbruhh.lockstep");
    private static final Semaphore STEP_REQUEST = new Semaphore(0), STEP_DONE = new Semaphore(0);

    private GooClient() {}

    // ------------------------------------------------------------------ client thread

    static void join(GooEntity goo) {
        PENDING.add(goo);
        LIVE.put(goo.getId(), goo);
    }

    static void leave(GooEntity goo) {
        PENDING.remove(goo);
        final int id = goo.getId();
        LIVE.remove(id);
        TOLD.remove(id);
        if (KNOWN.remove(id)) {
            COMMANDS.add(() -> { Integer slot = SLOT_OF_ENTITY.remove(id); GooSim s = sim; if (slot != null && s != null) s.removeBody(slot, true); });
        }
    }

    static void reset() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) { try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
        GooSim s = sim;
        sim = null;
        if (s != null) s.close();
        COMMANDS.clear(); COLLIDERS.set(null); SLOT_OF_ENTITY.clear(); PENDING.clear();
        bodyInfo = new float[0]; KNOWN.clear(); LIVE.clear(); TOLD.clear(); clearLook = false; NEW_CELLS.clear(); BORN.clear(); probeBox = null; probeResult = 0; traceIds = new int[0]; traceCounts = new int[0];
        java.util.Arrays.fill(SLEEP_HASH_VALID, false); java.util.Arrays.fill(WAKING, 0);
        PART_IDS.clear(); HEADS.clear();
        synchronized (MESH_LOCK) { java.util.Arrays.fill(meshQuads, 0); java.util.Arrays.fill(meshPartCount, 0); meshVersion++; }
        synchronized (PARTICLE_LOCK) { particleCount = 0; particleVersion++; }
        lavaPresent = false;
    }

    private static void start() {
        GooSim s = null;
        if (NativeSim.available()) {
            try { s = new NativeSim(PARTICLES_PER_EDGE, MAX_BODIES); } catch (RuntimeException e) { PhysicsBruhh.LOGGER.warn("Rust core failed to start: {}", e.toString()); }
        }
        if (s == null) {
            PhysicsBruhh.LOGGER.info("Using the Java simulation ({})", NativeSim.loadMessage());
            s = new JavaSim(PARTICLES_PER_EDGE, MAX_BODIES_JAVA);
        } else {
            PhysicsBruhh.LOGGER.info("Using the Rust simulation ({})", NativeSim.loadMessage());
        }
        sim = s;
        running = true;
        thread = new Thread(GooClient::simLoop, "PhysicsBruhh-sim");
        thread.setDaemon(true);
        thread.start();
    }

    /** Errors in {@link #tick} so far in this world; after three the goo stays switched off until the world is left. */
    static volatile int failures;

    /** One client tick. Whatever goes wrong in here must not take the game down: the goo is dropped and the reason logged. */
    static void tick() {
        if (failures >= 3) return;
        try {
            tickUnguarded();
        } catch (Throwable t) {
            failures++;
            PhysicsBruhh.LOGGER.error("Goo simulation hit an error and was reset ({} of 3 before it stays off in this world)", failures, t);
            try { reset(); } catch (Throwable again) { PhysicsBruhh.LOGGER.error("... and could not be reset", again); }
        }
    }

    private static void tickUnguarded() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) { if (sim != null) reset(); return; }
        paused = mc.isPaused();
        boolean clear = false, lava = false;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() == GooSim.LAVA) lava = true;
            else if (goo.clear()) clear = true;
        }
        clearLook = clear;
        lavaPresent = lava;
        // (a piece that has just gone shrinks away for a moment yet: its particles are wanted that long)
        if (lava) lavaLinger = 20; else if (lavaLinger > 0) lavaLinger--;
        boolean liquid = clear && GooFluid.inUse();
        wantParticles = liquid || lava || lavaLinger > 0;
        wantMeshes = (liquid ? 0 : 3) | (lava && !LavaGoo.inUse() ? 4 : 0);
        // What this tick has to tell the simulation is held back until the colliders it scanned are out: goo must not be
        // simulated for even one step against colliders from before it existed, which may hold the block it came from.
        List<Runnable> orders = new ArrayList<>();
        if (!PENDING.isEmpty() && !paused) {
            if (sim == null) start();
            for (GooEntity goo : PENDING) {
                if (goo.isRemoved()) continue;
                BlockPos cell = BlockPos.containing(goo.getX(), goo.getY() + 0.01, goo.getZ());
                if (KNOWN.isEmpty()) origin = cell; // rebase so local coordinates stay small
                KNOWN.add(goo.getId());
                NEW_CELLS.add(cell);
                if (!goo.poured()) {
                    BORN.put(cell.asLong(), new Born(goo.material(), scanTick + BORN_GRACE));
                    if (level.getBlockState(cell).is(sourceBlock(goo.material()))) bornInsideBlock++;
                }
                BlockPos o = origin;
                final int id = goo.getId(), mat = goo.material();
                final float x = cell.getX() - o.getX(), y = cell.getY() - o.getY(), z = cell.getZ() - o.getZ();
                final float age = goo.spawnAgeTicks() / 20.0F, lx = goo.leanX(), lz = goo.leanZ();
                final float killY = level.getMinY() - 8 - o.getY();
                // a block that turned into goo; goo being poured right now (it starts empty, the stream follows below); or poured goo met later, lying there
                final int amount = goo.amount();
                final float thick = goo.thick();
                final int how = !goo.poured() ? 0 : goo.spawnAgeTicks() <= 2 ? 1 : 2;
                TOLD.put(id, how == 0 ? GooSim.BLOCK : how == 1 ? 0 : amount);
                orders.add(() -> {
                    GooSim s = sim;
                    if (s == null) return;
                    s.setKillY(killY);
                    int slot = how == 0 ? s.addBody(mat, x, y, z, lx, lz, age, thick) : how == 1 ? s.addEmpty(mat, x, y, z, thick) : s.addResting(mat, x, y, z, amount, thick);
                    if (slot >= 0) { SLOT_OF_ENTITY.values().removeIf(v -> v == slot); SLOT_OF_ENTITY.put(id, slot); }
                });
            }
            PENDING.clear();
        }
        // goo poured into a piece or taken out of it: the marker's amount has changed, the simulation follows
        for (GooEntity goo : LIVE.values()) {
            final int id = goo.getId();
            if (!KNOWN.contains(id) || goo.isRemoved()) continue;
            final int told = TOLD.get(id), now = goo.amount();
            if (now == told) continue;
            TOLD.put(id, now);
            BlockPos o = origin;
            Vec3 spot = goo.spot();
            final float px = (float) (spot.x - o.getX()), py = (float) (spot.y - o.getY()), pz = (float) (spot.z - o.getZ());
            final float vx = goo.push().x(), vy = goo.push().y(), vz = goo.push().z();
            final boolean lump = goo.lump();
            NEW_CELLS.add(BlockPos.containing(spot));
            orders.add(() -> {
                GooSim s = sim;
                Integer slot = SLOT_OF_ENTITY.get(id);
                if (s == null || slot == null) return;
                if (now > told) s.emit(slot, px, py, pz, vx, vy, vz, now - told, lump);
                else s.take(slot, px, py, pz, told - now);
            });
        }
        if (sim != null && !paused) {
            Colliders c = scan(level);
            if (c != null) COLLIDERS.set(c);
            NEW_CELLS.clear();
            BlockPos o = origin;
            if (traceRow != null) noteTrace(c, o);
            if (mc.player != null && !mc.player.isSpectator()) {
                // the player's box shoves goo out of itself, so what tells how deep they stand in it is the goo pressed against the box
                AABB box = mc.player.getBoundingBox().inflate(PROBE_REACH, 0.0, PROBE_REACH).expandTowards(0.0, -0.12, 0.0);
                probeBox = new float[] { (float) (box.minX - o.getX()), (float) (box.minY - o.getY()), (float) (box.minZ - o.getZ()), (float) (box.maxX - o.getX()), (float) (box.maxY - o.getY()), (float) (box.maxZ - o.getZ()) };
            } else {
                probeBox = null; probeResult = 0;
            }
        }
        COMMANDS.addAll(orders);
        if (LOCKSTEP && sim != null && running && !paused) {
            STEP_DONE.drainPermits();
            STEP_REQUEST.release();
            try { STEP_DONE.tryAcquire(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static final long[] SLEEP_HASH = new long[MAX_BODIES];
    private static final boolean[] SLEEP_HASH_VALID = new boolean[MAX_BODIES];
    private static final int[] WAKING = new int[MAX_BODIES];

    /**
     * Collects block collision boxes and piston parts around every awake body. Sleeping bodies are
     * only watched: if any block around one changes it is woken, and piston heads next to it stay
     * known so goo stuck to them is still stuck when it wakes.
     */
    private static Colliders scan(ClientLevel level) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        float[] st = new float[512 * GooSim.STATIC_FLOATS];
        float[] kin = new float[MAX_KINEMATIC * GooSim.KINEMATIC_FLOATS];
        int ns = 0, nk = 0;
        boolean any = false;
        LongOpenHashSet seen = new LongOpenHashSet(), seenParts = new LongOpenHashSet();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<int[]> regions = new ArrayList<>();
        scanTick++;
        for (int b = 0; b + 8 <= info.length && b / 8 < MAX_BODIES; b += 8) {
            final int slot = b / 8;
            // free slots, bodies that are shrinking away (they collide with nothing), and anything whose bounds are not sane numbers
            if (info[b] != 1.0F || !saneBounds(info, b)) { SLEEP_HASH_VALID[slot] = false; WAKING[slot] = 0; continue; }
            int x0 = (int) Math.floor(info[b + 2] - 1.0F) + o.getX(), y0 = (int) Math.floor(info[b + 3] - 1.0F) + o.getY(), z0 = (int) Math.floor(info[b + 4] - 1.0F) + o.getZ();
            int x1 = (int) Math.floor(info[b + 5] + 1.0F) + o.getX(), y1 = (int) Math.floor(info[b + 6] + 1.0F) + o.getY(), z1 = (int) Math.floor(info[b + 7] + 1.0F) + o.getZ();
            if (info[b + 1] != 0.0F) {
                SLEEP_HASH_VALID[slot] = false; WAKING[slot] = 0;
            } else {
                long hash = 1L;
                for (int y = y0 - 1; y <= y1 + 1; y++) for (int z = z0 - 1; z <= z1 + 1; z++) for (int x = x0 - 1; x <= x1 + 1; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    hash = hash * 31L + System.identityHashCode(state);
                    if (isPistonPart(state) && seenParts.add(pos.asLong())) nk = notePistonPart(level, state, pos, o, kin, nk);
                }
                // a creature moving about inside it counts as a change too
                AABB resting = new AABB(info[b + 2] + o.getX() - 0.1, info[b + 3] + o.getY() - 0.1, info[b + 4] + o.getZ() - 0.1, info[b + 5] + o.getX() + 0.1, info[b + 6] + o.getY() + 0.1, info[b + 7] + o.getZ() + 0.1);
                for (Entity e : level.getEntities((Entity) null, resting, GooClient::pushesGoo)) {
                    hash = hash * 31L + e.getId() * 7919L + (long) Math.floor(e.getX() * 16.0) * 31L + (long) Math.floor(e.getY() * 16.0) * 17L + (long) Math.floor(e.getZ() * 16.0);
                }
                boolean changed = SLEEP_HASH_VALID[slot] && hash != SLEEP_HASH[slot];
                SLEEP_HASH[slot] = hash; SLEEP_HASH_VALID[slot] = true;
                if (changed) {
                    WAKING[slot] = 10; // scanned as awake until the simulation thread has caught up
                    COMMANDS.add(() -> { GooSim g = sim; if (g != null) g.wake(slot); });
                }
                if (WAKING[slot] > 0) WAKING[slot]--; else continue;
            }
            any = true;
            regions.add(new int[] { x0, y0, z0, x1, y1, z1 });
        }
        // bodies added this tick are not in the simulation's report yet: their cell and its neighbours
        for (BlockPos cell : NEW_CELLS) {
            any = true;
            regions.add(new int[] { cell.getX() - 1, cell.getY() - 1, cell.getZ() - 1, cell.getX() + 1, cell.getY() + 2, cell.getZ() + 1 });
        }
        IntOpenHashSet seenEntities = new IntOpenHashSet();
        for (int[] region : regions) {
            final int x0 = region[0], y0 = region[1], z0 = region[2], x1 = region[3], y1 = region[4], z1 = region[5];
            // creatures walking through: boxes that move, where they were at the start of the tick
            for (Entity e : level.getEntities((Entity) null, new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1), GooClient::pushesGoo)) {
                if (nk < MAX_KINEMATIC - 8 && seenEntities.add(e.getId())) nk = addCreature(e, o, kin, nk);
            }
            // piston parts are looked for one block further out than ordinary blocks: a retracting head
            // is drawn (and collides) in front of its base, but the moving block itself sits on the base
            for (int y = y0 - 1; y <= y1 + 1; y++) for (int z = z0 - 1; z <= z1 + 1; z++) for (int x = x0 - 1; x <= x1 + 1; x++) {
                pos.set(x, y, z);
                boolean inner = x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
                if (inner && !seen.add(pos.asLong())) continue;
                BlockState state = level.getBlockState(pos);
                if (!BORN.isEmpty()) {
                    Born born = BORN.get(pos.asLong());
                    if (born != null) {
                        if (scanTick < born.until() && state.is(sourceBlock(born.material()))) continue; // still standing where its goo already is
                        BORN.remove(pos.asLong()); // seen gone (or it has stood there too long to be that block): from now on a block here is a block
                    }
                }
                if (state.isAir()) continue;
                VoxelShape shape = null;
                if (isPistonPart(state)) {
                    if (seenParts.add(pos.asLong())) nk = notePistonPart(level, state, pos, o, kin, nk);
                    // heads and blocks in transit only collide as moving parts; a piston's base is also an ordinary block,
                    // and stays one (without its head) while the head slides back into it
                    if (state.is(Blocks.PISTON_HEAD)) continue;
                    if (state.is(Blocks.MOVING_PISTON)) { shape = retractingBase(level, pos); if (shape == null) continue; }
                }
                if (!inner) continue;
                if (shape == null) shape = state.getCollisionShape(level, pos);
                // a block of lava is a wall to lava that flows as a body (and to goo): what has come out of a lake lies on it and is pushed on by what follows
                if (shape.isEmpty() && lavaBlock(state)) shape = net.minecraft.world.phys.shapes.Shapes.block();
                if (shape.isEmpty()) continue;
                float stick = stickiness(state);
                for (AABB box : shape.toAabbs()) {
                    if (ns >= MAX_STATIC) break;
                    if ((ns + 1) * GooSim.STATIC_FLOATS > st.length) st = java.util.Arrays.copyOf(st, st.length * 2);
                    int i = ns * GooSim.STATIC_FLOATS;
                    st[i] = (float) (box.minX + x - o.getX()); st[i + 1] = (float) (box.minY + y - o.getY()); st[i + 2] = (float) (box.minZ + z - o.getZ());
                    st[i + 3] = (float) (box.maxX + x - o.getX()); st[i + 4] = (float) (box.maxY + y - o.getY()); st[i + 5] = (float) (box.maxZ + z - o.getZ());
                    st[i + 6] = stick;
                    ns++;
                }
            }
        }
        nk = moveHeads(level, o, kin, nk);
        if (scanTick % 200 == 0 && !BORN.isEmpty()) BORN.values().removeIf(born -> scanTick >= born.until()); // cells no scan came by again
        return any ? new Colliders(st, ns, kin, nk) : null; // nothing awake: leave the simulation's colliders alone
    }

    /** The block a piece of goo of this material comes from. */
    private static net.minecraft.world.level.block.Block sourceBlock(int material) {
        return material == GooSim.HONEY ? Blocks.HONEY_BLOCK : material == GooSim.LAVA ? Blocks.LAVA : Blocks.SLIME_BLOCK;
    }

    /** A whole block of lava (not lava that is running through a block). */
    private static boolean lavaBlock(BlockState state) {
        net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
        return fluid.isSource() && fluid.is(net.minecraft.tags.FluidTags.LAVA) && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    /** A body's bounds as reported by the simulation: real numbers, the right way round, not absurdly large or far away. */
    private static boolean saneBounds(float[] info, int b) {
        for (int a = 0; a < 3; a++) {
            float lo = info[b + 2 + a], hi = info[b + 5 + a];
            if (!(lo <= hi) || !(hi - lo <= 24.0F) || !(Math.abs(lo) < 1.0e6F) || !(Math.abs(hi) < 1.0e6F)) return false; // also false for NaN
        }
        return true;
    }

    /** How strongly goo clings to a surface (1 = ordinary block). */
    private static float stickiness(BlockState state) {
        if (state.is(Blocks.STICKY_PISTON) || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SLIME_BLOCK)) return 2.0F;
        return 1.0F;
    }

    private static boolean pushesGoo(Entity e) {
        return !(e instanceof GooEntity) && e.isAlive() && !e.isSpectator() && !e.noPhysics;
    }

    /** A creature (or item, or the player): its box where it was at the start of this tick, moving to where it is now. */
    /**
     * Blocks a tick (2.5 a second) up to which goo holds on to a creature. Someone wading through honey or sneaking is
     * slower than that and draws strings; someone who walks, runs, jumps or falls is not held: the goo stays where it
     * is instead of riding along on them (and, for slime, bouncing them in mid-air off what they carry).
     */
    private static final double HOLD_SPEED = 0.125;
    /** Scan tick until which the player's own body holds no goo: they have shaken themselves clean. */
    private static volatile int shakenUntil;

    /** The player shakes off whatever goo sticks to them; for two seconds none sticks again. */
    static void shakeOff() {
        shakenUntil = scanTick + 40;
    }

    private static int addCreature(Entity e, BlockPos o, float[] kin, int nk) {
        AABB box = e.getBoundingBox();
        double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
        // goo sticks to a creature that is slow and dry; water and rain wash it off
        boolean holds = dx * dx + dy * dy + dz * dz <= HOLD_SPEED * HOLD_SPEED && !e.isInWaterOrRain()
                && !(scanTick < shakenUntil && e == Minecraft.getInstance().player);
        if (dx * dx + dy * dy + dz * dz > 4.0) { dx = 0.0; dy = 0.0; dz = 0.0; } // teleported: not a shove
        int i = nk * GooSim.KINEMATIC_FLOATS;
        kin[i] = -(e.getId() % 8_000_000 + 1); // creatures have negative ids: they push goo aside but do not make it stiffen like a piston does
        kin[i + 1] = (float) (box.minX - dx - o.getX()); kin[i + 2] = (float) (box.minY - dy - o.getY()); kin[i + 3] = (float) (box.minZ - dz - o.getZ());
        kin[i + 4] = (float) (box.maxX - dx - o.getX()); kin[i + 5] = (float) (box.maxY - dy - o.getY()); kin[i + 6] = (float) (box.maxZ - dz - o.getZ());
        kin[i + 7] = (float) (dx * 20.0); kin[i + 8] = (float) (dy * 20.0); kin[i + 9] = (float) (dz * 20.0);
        kin[i + 10] = holds ? 1.0F : 0.0F;
        kin[i + 11] = 0.0F;
        return nk + 1;
    }

    /** The base of a piston whose head is sliding back in (the block there is "in transit" meanwhile), or null. */
    private static VoxelShape retractingBase(ClientLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston) || !piston.isSourcePiston() || piston.isExtending()) return null;
        BlockState moved = piston.getMovedState();
        boolean sticky = moved.is(Blocks.STICKY_PISTON) || (moved.is(Blocks.PISTON_HEAD) && moved.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY);
        return (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
                .setValue(PistonBaseBlock.FACING, piston.getDirection()).setValue(PistonBaseBlock.EXTENDED, true).getCollisionShape(level, pos);
    }

    private static final Long2IntOpenHashMap PART_IDS = new Long2IntOpenHashMap();
    private static int nextPartId = 1;

    /** One number per moving part (block position + box index): never shared, never reused, small enough to be exact in a float. */
    private static int partId(BlockPos idPos, int index) {
        long key = idPos.asLong() * 8L + Math.min(index, 7);
        int id = PART_IDS.get(key);
        if (id == 0) {
            if (PART_IDS.size() > 8192) PART_IDS.clear();
            if (nextPartId > 16_000_000) nextPartId = 1;
            id = nextPartId++;
            PART_IDS.put(key, id);
        }
        return id;
    }

    private static BlockState headState(Direction facing, boolean sticky) {
        return Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, facing).setValue(PistonHeadBlock.TYPE, sticky ? PistonType.STICKY : PistonType.DEFAULT);
    }

    private static boolean isPistonPart(BlockState state) {
        return state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON) || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    /**
     * What the goo is told about one piston's head. The game only says where a head is now, and a
     * head that slides for two ticks can be missed entirely when the client stutters; goo stuck to
     * it would then be left hanging in the air. So each head near goo is followed here: {@code shown}
     * is how far it is pulled in (0 = out, 1 = in) as far as the goo knows, and it chases the real
     * state at piston speed, half a block per tick, whether or not the slide itself was seen.
     */
    private static final class Head {
        final BlockPos base;
        Direction facing;
        boolean sticky, exact, fresh = true;
        float shown, target, start;
        int seenTick;

        Head(BlockPos base) { this.base = base; }
    }

    private static final Long2ObjectOpenHashMap<Head> HEADS = new Long2ObjectOpenHashMap<>();
    private static int scanTick;
    /** Heads that started to move this tick (for the sound they make when there is goo in front of them). */
    static final List<BlockPos> strokes = new ArrayList<>();

    private static void noteHead(BlockPos base, Direction facing, boolean sticky, float start, float target, boolean exact) {
        long key = base.asLong();
        Head head = HEADS.get(key);
        if (head == null) {
            if (HEADS.size() > 512) HEADS.clear();
            head = new Head(base.immutable());
            HEADS.put(key, head);
        }
        if (head.seenTick != scanTick) { head.seenTick = scanTick; head.exact = false; }
        else if (head.exact && !exact) return; // the moving block knows better than the base it belongs to
        head.facing = facing; head.sticky = sticky; head.start = start; head.target = target; head.exact = exact;
    }

    /** A piston base, a head, or a block in transit. Heads are only noted here; {@link #moveHeads} reports them. */
    private static int notePistonPart(ClientLevel level, BlockState state, BlockPos pos, BlockPos o, float[] kin, int nk) {
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
            float in = state.getValue(PistonBaseBlock.EXTENDED) ? 0.0F : 1.0F;
            noteHead(pos, state.getValue(PistonBaseBlock.FACING), state.is(Blocks.STICKY_PISTON), in, in, false);
            return nk;
        }
        if (state.is(Blocks.PISTON_HEAD)) {
            Direction facing = state.getValue(PistonHeadBlock.FACING);
            noteHead(pos.relative(facing.getOpposite()), facing, state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY, 0.0F, 0.0F, false);
            return nk;
        }
        if (!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston)) return nk;
        BlockState moved = piston.getMovedState();
        if (piston.isSourcePiston()) {
            boolean sticky = moved.is(Blocks.STICKY_PISTON) || (moved.is(Blocks.PISTON_HEAD) && moved.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY);
            float before = piston.getProgress(0.0F), after = piston.getProgress(1.0F);
            // while extending the moving block sits where the head will end up; while retracting, on the base
            if (piston.isExtending()) noteHead(pos.relative(piston.getDirection().getOpposite()), piston.getDirection(), sticky, 1.0F - before, 1.0F - after, true);
            else noteHead(pos, piston.getDirection(), sticky, before, after, true);
            return nk;
        }
        // a block being pushed or pulled: where it was at the start of this tick, and the velocity that carries it through the tick
        VoxelShape shape = moved.getCollisionShape(level, pos);
        if (shape.isEmpty()) return nk;
        float ox = piston.getXOff(0.0F), oy = piston.getYOff(0.0F), oz = piston.getZOff(0.0F);
        float vx = (piston.getXOff(1.0F) - ox) * 20.0F, vy = (piston.getYOff(1.0F) - oy) * 20.0F, vz = (piston.getZOff(1.0F) - oz) * 20.0F;
        return addBoxes(shape, pos, pos, ox, oy, oz, vx, vy, vz, stickiness(moved), null, o, kin, nk);
    }

    /** Advances every followed head by one tick and reports the ones that are not fully pulled in. */
    private static int moveHeads(ClientLevel level, BlockPos o, float[] kin, int nk) {
        var it = HEADS.values().iterator();
        while (it.hasNext()) {
            Head head = it.next();
            if (head.seenTick != scanTick) {
                // not near any goo right now: keep it honest by looking at its base
                BlockState base = level.getBlockState(head.base);
                if (base.is(Blocks.PISTON) || base.is(Blocks.STICKY_PISTON)) head.target = base.getValue(PistonBaseBlock.EXTENDED) ? 0.0F : 1.0F;
                else if (!base.is(Blocks.MOVING_PISTON)) { it.remove(); continue; }
            }
            if (head.fresh) { head.fresh = false; head.shown = head.start; } // first sighting: no catching up to do
            float next = head.shown + Math.max(-0.5F, Math.min(0.5F, head.target - head.shown));
            // a pulled-in head stays known until its base is an ordinary block again, so goo stuck to it has something to hold on to
            if (head.shown < 1.0F || next < 1.0F || level.getBlockState(head.base).is(Blocks.MOVING_PISTON)) {
                Direction f = head.facing;
                BlockPos headPos = head.base.relative(f);
                VoxelShape shape = headState(f, head.sticky).getCollisionShape(level, headPos);
                float v = -(next - head.shown) * 20.0F;
                // only a sticky head holds on to goo; a plain one just pushes
                nk = addBoxes(shape, headPos, head.base, -f.getStepX() * head.shown, -f.getStepY() * head.shown, -f.getStepZ() * head.shown,
                        f.getStepX() * v, f.getStepY() * v, f.getStepZ() * v, head.sticky ? STICKY_HEAD : 0.0F, f, o, kin, nk);
                if (next != head.shown && (head.shown == 0.0F || head.shown == 1.0F)) strokes.add(headPos);
            }
            head.shown = next;
        }
        return nk;
    }

    /**
     * {@code front}: the way a piston head faces, or null for anything else. The head's plate (the box
     * that fills the whole cross-section) is marked with it, and pushes whatever gets caught inside
     * it out of its front, never out of its back towards the piston.
     */
    private static int addBoxes(VoxelShape shape, BlockPos at, BlockPos idPos, float ox, float oy, float oz, float vx, float vy, float vz, float stick, Direction front, BlockPos o, float[] kin, int nk) {
        int index = 0;
        for (AABB box : shape.toAabbs()) {
            if (nk >= MAX_KINEMATIC) break;
            int i = nk * GooSim.KINEMATIC_FLOATS;
            kin[i] = partId(idPos, index);
            kin[i + 1] = (float) (box.minX + ox + at.getX() - o.getX()); kin[i + 2] = (float) (box.minY + oy + at.getY() - o.getY()); kin[i + 3] = (float) (box.minZ + oz + at.getZ() - o.getZ());
            kin[i + 4] = (float) (box.maxX + ox + at.getX() - o.getX()); kin[i + 5] = (float) (box.maxY + oy + at.getY() - o.getY()); kin[i + 6] = (float) (box.maxZ + oz + at.getZ() - o.getZ());
            kin[i + 7] = vx; kin[i + 8] = vy; kin[i + 9] = vz;
            kin[i + 10] = stick;
            float code = 0.0F;
            if (front != null) {
                boolean plate = switch (front.getAxis()) {
                    case X -> box.getYsize() > 0.9 && box.getZsize() > 0.9;
                    case Y -> box.getXsize() > 0.9 && box.getZsize() > 0.9;
                    case Z -> box.getXsize() > 0.9 && box.getYsize() > 0.9;
                };
                if (plate) code = front.getAxis().ordinal() * 2 + (front.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 2);
            }
            kin[i + 11] = code;
            nk++; index++;
        }
        return nk;
    }

    /** One line about what the simulation is doing right now (for logs and the CI smoke test). */
    static String describe() {
        GooSim s = sim;
        float[] info = bodyInfo;
        int bodies = 0, awake = 0;
        for (int b = 0; b + 8 <= info.length; b += 8) { if (info[b] != 0.0F) { bodies++; if (info[b + 1] != 0.0F) awake++; } }
        int honey, slime;
        synchronized (MESH_LOCK) { honey = meshQuads[0]; slime = meshQuads[1]; }
        return "backend=" + (s == null ? "none" : s.backend()) + " running=" + running + " bodies=" + bodies + " awake=" + awake
                + " honeyQuads=" + honey + " slimeQuads=" + slime + " heads=" + HEADS.size() + " stepMs=" + String.format(java.util.Locale.ROOT, "%.2f", stepMillis);
    }

    static boolean healthy() { return sim != null && running; }

    /**
     * The pieces of lava this client simulates, for {@link LavaGoo}: for each, the marker's id and where
     * the simulation has it (least and greatest corner, in the world). Returns how many were written.
     */
    static int lavaPieces(int[] ids, double[] bounds) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        int n = 0;
        for (GooEntity goo : LIVE.values()) {
            if (n >= ids.length) break;
            if (goo.material() != GooSim.LAVA || goo.isRemoved()) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            ids[n] = goo.getId();
            bounds[n * 6] = info[b + 2] + o.getX(); bounds[n * 6 + 1] = info[b + 3] + o.getY(); bounds[n * 6 + 2] = info[b + 4] + o.getZ();
            bounds[n * 6 + 3] = info[b + 5] + o.getX(); bounds[n * 6 + 4] = info[b + 6] + o.getY(); bounds[n * 6 + 5] = info[b + 7] + o.getZ();
            n++;
        }
        return n;
    }

    /** How much goo the local player is standing in: honey particles in the low 16 bits, slime in the next 16. */
    static int contact() { return probeBox == null ? 0 : probeResult; }

    /**
     * How thick the goo of one kind is that a box stands in: that of the piece whose simulated bounds
     * reach into the box (the thickest, if several do); 1 if none does.
     */
    static float thickAt(AABB box, int material) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        float thick = 0.0F;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material || goo.isRemoved()) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            if (box.intersects(info[b + 2] + o.getX(), info[b + 3] + o.getY(), info[b + 4] + o.getZ(), info[b + 5] + o.getX(), info[b + 6] + o.getY(), info[b + 7] + o.getZ())) thick = Math.max(thick, goo.thick());
        }
        return thick > 0.0F ? thick : 1.0F;
    }

    /** The goo whose bounds the segment {@code from}-{@code to} enters first, within {@code limit} blocks; null if none. */
    static GooEntity pick(Vec3 from, Vec3 to, double limit) {
        Picked picked = pickPoint(from, to, limit);
        return picked == null ? null : picked.goo();
    }

    /** A piece of goo and the place a line of sight met it. */
    record Picked(GooEntity goo, Vec3 point) {}

    /** Like {@link #pick}, with the point where the segment enters the goo's bounds. */
    static Picked pickPoint(Vec3 from, Vec3 to, double limit) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        GooEntity best = null;
        Vec3 bestPoint = null;
        double bestDistance = limit;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() == GooSim.LAVA) continue; // not something to punch or scoop
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            AABB box = new AABB(info[b + 2] + o.getX(), info[b + 3] + o.getY(), info[b + 4] + o.getZ(), info[b + 5] + o.getX(), info[b + 6] + o.getY(), info[b + 7] + o.getZ()).inflate(0.05);
            Vec3 point = box.contains(from) ? from : box.clip(from, to).orElse(null);
            if (point == null) continue;
            double distance = point.distanceTo(from);
            if (distance < bestDistance) {
                bestDistance = distance; best = goo;
                // a little way in: the bounds are a box around the goo, the goo itself starts further on
                bestPoint = point.add(to.subtract(from).normalize().scale(Math.min(0.25, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.5)));
            }
        }
        return best == null ? null : new Picked(best, bestPoint);
    }

    /** Material of the goo within a block of the given cell, or -1 if there is none. */
    static int materialNear(BlockPos cell) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() == GooSim.LAVA) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F) continue;
            int b = slot * 8;
            if (info[b + 2] + o.getX() < cell.getX() + 2 && info[b + 5] + o.getX() > cell.getX() - 1 && info[b + 3] + o.getY() < cell.getY() + 2 && info[b + 6] + o.getY() > cell.getY() - 1
                    && info[b + 4] + o.getZ() < cell.getZ() + 2 && info[b + 7] + o.getZ() > cell.getZ() - 1) return goo.material();
        }
        return -1;
    }

    /** World x range of the widest slime body lying across the line {@code z} (smoke test: is the band between two pistons where it should be?). */
    static float[] slimeSpan(double z) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        float[] span = { 0.0F, 0.0F };
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != GooSim.SLIME) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            if (info[b + 4] + o.getZ() > z || info[b + 7] + o.getZ() < z) continue;
            float lo = info[b + 2] + o.getX(), hi = info[b + 5] + o.getX();
            if (hi - lo > span[1] - span[0]) { span[0] = lo; span[1] = hi; }
        }
        return span;
    }

    /** Middle of the bounds (x, bottom y, z) of the goo of this material nearest to (x, z), or null (smoke test: where did that puddle end up?). */
    static double[] centreNear(int material, double x, double z) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            double cx = (info[b + 2] + info[b + 5]) * 0.5 + o.getX(), cz = (info[b + 4] + info[b + 7]) * 0.5 + o.getZ();
            double d = (cx - x) * (cx - x) + (cz - z) * (cz - z);
            if (d < bestDistance) { bestDistance = d; best = new double[] { cx, info[b + 3] + o.getY(), cz }; }
        }
        return best;
    }

    // ---- tracing one row of blocks (smoke test only): what the simulation was told and what it holds on to

    /** Block row (y, z) to trace, or null. Set by the smoke test. */
    static volatile int[] traceRow;
    private static volatile int[] traceIds = new int[0], traceCounts = new int[0], traceStuck = new int[0];
    private static volatile String traceColliders = "";

    private static void noteTrace(Colliders c, BlockPos o) {
        int[] row = traceRow;
        if (row == null) return;
        StringBuilder sb = new StringBuilder();
        List<Integer> ids = new ArrayList<>();
        for (Head head : HEADS.values()) {
            if (head.base.getY() != row[0] || head.base.getZ() != row[1]) continue;
            sb.append(" head@").append(head.base.getX()).append(head.sticky ? "s" : "p").append(" shown=").append(head.shown).append(" target=").append(head.target).append(head.exact ? " exact" : "").append(" base=").append(Minecraft.getInstance().level.getBlockState(head.base).getBlock().getName().getString());
            ids.add(-head.base.getX() - 1000); // marks the start of a head's ids
            for (int i = 0; i < 3; i++) ids.add(partId(head.base, i));
        }
        if (c != null) {
            float y = row[0] - o.getY(), z = row[1] - o.getZ();
            sb.append(" | static:");
            for (int k = 0; k < c.staticCount(); k++) {
                int i = k * GooSim.STATIC_FLOATS;
                if (c.statics()[i + 1] >= y - 0.01F && c.statics()[i + 1] < y + 1.0F && c.statics()[i + 2] < z + 1.0F && c.statics()[i + 5] > z) sb.append(String.format(java.util.Locale.ROOT, " %.2f..%.2f", c.statics()[i] + o.getX(), c.statics()[i + 3] + o.getX()));
            }
            sb.append(" | moving:");
            for (int k = 0; k < c.kinematicCount(); k++) {
                int i = k * GooSim.KINEMATIC_FLOATS;
                if (c.kinematics()[i + 2] < y + 1.0F && c.kinematics()[i + 5] > y && c.kinematics()[i + 3] < z + 1.0F && c.kinematics()[i + 6] > z) sb.append(String.format(java.util.Locale.ROOT, " #%d %.2f..%.2f v=%.1f", (int) c.kinematics()[i], c.kinematics()[i + 1] + o.getX(), c.kinematics()[i + 4] + o.getX(), c.kinematics()[i + 7]));
            }
        } else {
            sb.append(" | nothing sent");
        }
        traceColliders = sb.toString();
        traceIds = ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One line about the traced row: bodies lying in it, piston heads, what was sent to the simulation and how many particles hold on to what. */
    static String trace() {
        int[] row = traceRow;
        if (row == null) return "";
        float[] info = bodyInfo;
        BlockPos o = origin;
        StringBuilder sb = new StringBuilder();
        for (GooEntity goo : LIVE.values()) {
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] == 0.0F) continue;
            int b = slot * 8;
            if (info[b + 4] + o.getZ() > row[1] + 0.5 || info[b + 7] + o.getZ() < row[1] + 0.5) continue;
            int[] stuck = traceStuck;
            sb.append(String.format(java.util.Locale.ROOT, " body%d(%s%s) x=%.2f..%.2f on blocks=%d", slot, goo.material() == GooSim.SLIME ? "slime" : goo.material() == GooSim.LAVA ? "lava" : "honey", info[b + 1] != 0.0F ? "" : ",asleep", info[b + 2] + o.getX(), info[b + 5] + o.getX(), slot < stuck.length ? stuck[slot] : -1));
        }
        int[] ids = traceIds, counts = traceCounts;
        sb.append(" | held:");
        for (int i = 0; i < ids.length && i < counts.length; i++) {
            if (ids[i] < 0) sb.append(" @").append(-ids[i] - 1000).append(':');
            else sb.append(counts[i]).append(' ');
        }
        return sb + " |" + traceColliders;
    }

    static int quadCount(int material) { synchronized (MESH_LOCK) { return meshQuads[material]; } }

    /** Particles last handed to the liquid look. */
    static int particleCount() { synchronized (PARTICLE_LOCK) { return particleCount; } }

    /** Smoke test: keep {@link #amountNear} up to date. */
    static volatile boolean watchAmounts;
    private static volatile int[] bodyAmounts = new int[0];

    /** How many particles the simulated goo of one kind holds whose marker is within two blocks of (x, z); -1 if there is none (smoke test). */
    static int amountNear(int material, double x, double z) {
        int[] amounts = bodyAmounts;
        int total = -1;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material || goo.isRemoved()) continue;
            if ((goo.getX() - x) * (goo.getX() - x) + (goo.getZ() - z) * (goo.getZ() - z) > 4.0) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot >= amounts.length) continue;
            total = Math.max(total, 0) + amounts[slot];
        }
        return total;
    }

    /** Width (the longer way across) and height of the simulated goo of one kind whose marker is nearest to (x, z); null if none (smoke test). */
    static double[] sizeNear(int material, double x, double z) {
        GooEntity goo = gooNear(material, x, z);
        float[] info = bodyInfo;
        if (goo == null) return null;
        Integer slot = SLOT_OF_ENTITY.get(goo.getId());
        if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) return null;
        int b = slot * 8;
        return new double[] { Math.max(info[b + 5] - info[b + 2], info[b + 7] - info[b + 4]), info[b + 6] - info[b + 3] };
    }

    /** The simulated bounds, in the world, of the goo of one kind whose marker sits in this cell (min x, y, z, max x, y, z); null if there is none (smoke test). */
    static double[] boundsAt(int material, BlockPos cell) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material || goo.isRemoved() || !BlockPos.containing(goo.getX(), goo.getY() + 0.01, goo.getZ()).equals(cell)) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) return null;
            int b = slot * 8;
            return new double[] { info[b + 2] + o.getX(), info[b + 3] + o.getY(), info[b + 4] + o.getZ(), info[b + 5] + o.getX(), info[b + 6] + o.getY(), info[b + 7] + o.getZ() };
        }
        return null;
    }

    /** The simulated bounds, in the world, of every piece of one kind whose marker stands in this column of blocks, lowest first (smoke test). */
    static List<double[]> boundsInColumn(int material, int x, int z) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        List<double[]> found = new ArrayList<>();
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material || goo.isRemoved() || (int) Math.floor(goo.getX()) != x || (int) Math.floor(goo.getZ()) != z) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F || !saneBounds(info, slot * 8)) continue;
            int b = slot * 8;
            found.add(new double[] { info[b + 2] + o.getX(), info[b + 3] + o.getY(), info[b + 4] + o.getZ(), info[b + 5] + o.getX(), info[b + 6] + o.getY(), info[b + 7] + o.getZ() });
        }
        found.sort((a, b) -> Double.compare(a[1], b[1]));
        return found;
    }

    /** The piece of one kind whose marker is nearest to (x, z), within two blocks; null if none (smoke test). */
    static GooEntity gooNear(int material, double x, double z) {
        GooEntity best = null;
        double bestDistance = 4.0;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != material || goo.isRemoved()) continue;
            double d = (goo.getX() - x) * (goo.getX() - x) + (goo.getZ() - z) * (goo.getZ() - z);
            if (d < bestDistance) { bestDistance = d; best = goo; }
        }
        return best;
    }

    private static volatile float stepMillis;
    /** Simulated time per real time over the last second or so (1 = keeping up), and the threads the solver uses. */
    private static volatile float simSpeed = 1.0F;
    private static volatile int simThreads = 1;
    /** Asked for with {@code /goolook threads}: how many threads the core is to use from now on (0: its own choice), or -1 if nothing is asked. */
    static volatile int wantThreads = -1;
    /**
     * Where a step of the Rust core goes, in milliseconds a step over the last second or so: predict and collide,
     * neighbours, new springs, the rounds of constraints, anchors and springs afterwards, velocities and viscosity,
     * sleeping; then of the rounds: density, correction, springs, anchors and colliders. Null: not known.
     */
    private static volatile float[] phases;
    private static final int PHASES = 11;

    /** What the simulation is doing, for {@code /goolook info}: backend (null if it is not running), threads, pieces, pieces moving, ms per update, speed. */
    static Object[] info() {
        GooSim s = sim;
        float[] info = bodyInfo;
        int bodies = 0, awake = 0;
        for (int b = 0; b + 8 <= info.length; b += 8) { if (info[b] != 0.0F) { bodies++; if (info[b + 1] != 0.0F) awake++; } }
        return new Object[] { s == null || !running ? null : s.backend(), simThreads, bodies, awake, stepMillis, simSpeed, MAX_BODIES, phases };
    }

    // ------------------------------------------------------------------ simulation thread

    private static void simLoop() {
        ByteBuffer staticBuf = direct(4096 * GooSim.STATIC_FLOATS), kinBuf = direct(MAX_KINEMATIC * GooSim.KINEMATIC_FLOATS);
        ByteBuffer infoBuf = direct(MAX_BODIES * GooSim.BODY_FLOATS);
        ByteBuffer meshBuf = direct(1 << 18), particleBuf = direct(1 << 16), partBuf = direct(1 << 10);
        int[] phaseAt = new int[PHASES];
        long last = System.nanoTime(), speedFrom = 0L;
        int speedSteps = 0;
        boolean dirty = true, hadAwake = false, asParticles = false, slow = false;
        int asMeshes = 3;
        try {
            while (running) {
                GooSim s = sim;
                if (s == null) break;
                if (LOCKSTEP && !STEP_REQUEST.tryAcquire(20, TimeUnit.MILLISECONDS)) continue;
                Runnable cmd;
                while ((cmd = COMMANDS.poll()) != null) { cmd.run(); dirty = true; }
                Colliders c = COLLIDERS.getAndSet(null);
                if (c != null) {
                    int boxes = Math.min(c.staticCount(), MAX_STATIC);
                    if (staticBuf.capacity() < boxes * GooSim.STATIC_FLOATS * 4) staticBuf = direct(boxes * 2 * GooSim.STATIC_FLOATS);
                    staticBuf.asFloatBuffer().put(0, c.statics(), 0, boxes * GooSim.STATIC_FLOATS);
                    s.setStatic(staticBuf, boxes);
                    kinBuf.asFloatBuffer().put(0, c.kinematics(), 0, c.kinematicCount() * GooSim.KINEMATIC_FLOATS);
                    s.setKinematic(kinBuf, c.kinematicCount());
                }
                long now = System.nanoTime();
                float dt = (now - last) / 1.0e9F;
                last = now;
                // The core makes up for lost time with up to three steps at once. That is right after a hiccup, and wrong
                // when a step itself takes longer than a sixtieth of a second: the time can never be made up then, and
                // three steps between two looks at the goo only make its slow motion jerky. Then: one step, and show it.
                if (slow) dt = Math.min(dt, 1.0F / 60.0F + 1.0e-6F);
                int awake = 0;
                if (LOCKSTEP) { for (int k = 0; k < 3; k++) awake = s.step(1.0F / 60.0F + 1.0e-6F); }
                else if (!paused) awake = s.step(dt);
                if (s instanceof NativeSim n && n.broken()) throw new IllegalStateException("the Rust core reported an internal error");
                int slots = Math.min(s.bodies(infoBuf), MAX_BODIES);
                float[] info = new float[Math.max(0, slots) * GooSim.BODY_FLOATS];
                infoBuf.asFloatBuffer().get(0, info, 0, info.length);
                bodyInfo = info;
                float[] probe = probeBox;
                if (probe != null) probeResult = Math.max(0, s.probe(probe[0], probe[1], probe[2], probe[3], probe[4], probe[5]));
                if (traceRow != null) {
                    int[] ids = traceIds, counts = new int[ids.length];
                    for (int i = 0; i < ids.length; i++) counts[i] = ids[i] > 0 ? s.count(0, ids[i]) : 0;
                    traceCounts = counts;
                    int[] stuck = new int[Math.max(0, slots)];
                    for (int b = 0; b < stuck.length; b++) stuck[b] = s.count(1, b);
                    traceStuck = stuck;
                }
                // once more after the last moving body has stopped or gone, or its final state would never be shown
                boolean particles = wantParticles;
                int meshes = wantMeshes;
                boolean changed = awake > 0 || dirty || hadAwake || particles != asParticles || meshes != asMeshes;
                // what is not kept up from here on must not be left to draw
                if (asParticles && !particles) synchronized (PARTICLE_LOCK) { particleCount = 0; particleVersion++; }
                if (meshes != asMeshes) synchronized (MESH_LOCK) {
                    for (int mat = 0; mat < GooSim.MATERIALS; mat++) if ((meshes >> mat & 1) == 0) { meshQuads[mat] = 0; meshPartCount[mat] = 0; }
                    meshVersion++;
                }
                asParticles = particles;
                asMeshes = meshes;
                if (watchAmounts) {
                    int[] amounts = new int[Math.max(0, slots)];
                    for (int b = 0; b < amounts.length; b++) amounts[b] = info.length >= (b + 1) * GooSim.BODY_FLOATS && info[b * GooSim.BODY_FLOATS] != 0.0F ? s.count(4, b) : 0;
                    bodyAmounts = amounts;
                }
                hadAwake = awake > 0;
                if (changed && particles) {
                    int count = s.particles(particleBuf);
                    if (count < 0 && count > -(1 << 26)) { particleBuf = direct(-count + (1 << 14)); count = s.particles(particleBuf); }
                    if (count < 0) count = 0;
                    synchronized (PARTICLE_LOCK) {
                        if (particleData.length < count * 4) particleData = new float[count * 4 + count * 2];
                        particleBuf.asFloatBuffer().get(0, particleData, 0, count * 4);
                        particleCount = count;
                        particleVersion++;
                    }
                }
                if (changed) {
                    for (int mat = 0; mat < GooSim.MATERIALS; mat++) {
                        if ((meshes >> mat & 1) == 0) continue;
                        int quads = s.mesh(mat, meshBuf);
                        if (quads < 0 && quads > -(1 << 26)) { meshBuf = direct(-quads + (1 << 16)); quads = s.mesh(mat, meshBuf); }
                        if (quads < 0) quads = 0;
                        int floats = quads * GooSim.FLOATS_PER_QUAD;
                        int parts = s.meshParts(mat, partBuf);
                        if (parts < 0 && parts > -(1 << 26)) { partBuf = direct(-parts * 2); parts = s.meshParts(mat, partBuf); }
                        if (parts < 0) parts = 0;
                        synchronized (MESH_LOCK) {
                            if (meshData[mat].length < floats) meshData[mat] = new float[floats + floats / 2];
                            meshBuf.asFloatBuffer().get(0, meshData[mat], 0, floats);
                            meshQuads[mat] = quads;
                            if (meshParts[mat].length < parts * GooSim.PART_FLOATS) meshParts[mat] = new float[parts * GooSim.PART_FLOATS * 2];
                            partBuf.asFloatBuffer().get(0, meshParts[mat], 0, parts * GooSim.PART_FLOATS);
                            meshPartCount[mat] = parts;
                            meshVersion++;
                        }
                    }
                    dirty = false;
                }
                long spent = System.nanoTime() - now;
                stepMillis += (spent / 1.0e6F - stepMillis) * 0.05F;
                slow = spent > 16_666_666L;
                // how much simulated time went by per real time, about once a second
                int ask = wantThreads;
                if (ask >= 0) { wantThreads = -1; simThreads = Math.max(1, s.count(5, ask)); }
                if (speedFrom == 0L) {
                    speedFrom = now; speedSteps = s.count(2, 0); simThreads = Math.max(1, s.count(3, 0));
                    for (int k = 0; k < PHASES; k++) phaseAt[k] = s.count(10 + k, 0);
                } else if (now - speedFrom > 1_000_000_000L) {
                    int steps = s.count(2, 0), taken = (steps - speedSteps) & 0x3fffffff;
                    simSpeed = Math.min(1.0F, taken / 60.0F / ((now - speedFrom) / 1.0e9F));
                    speedFrom = now; speedSteps = steps;
                    // where the steps went (the counters are microseconds and wrap, as the difference of two does)
                    float[] went = new float[PHASES];
                    boolean any = false;
                    for (int k = 0; k < PHASES; k++) {
                        int micros = s.count(10 + k, 0);
                        went[k] = taken > 0 ? (micros - phaseAt[k]) / 1000.0F / taken : 0.0F;
                        any |= micros != 0;
                        phaseAt[k] = micros;
                    }
                    phases = any && taken > 0 ? went : null;
                }
                if (LOCKSTEP) { STEP_DONE.release(); continue; }
                long budget = 16_666_666L - spent;
                if (budget > 200_000L) LockSupport.parkNanos(budget); else Thread.onSpinWait();
            }
        } catch (Throwable t) {
            // never take the game down: stop simulating and say why
            PhysicsBruhh.LOGGER.error("Goo simulation stopped", t);
            running = false;
        }
    }

    private static ByteBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
    }
}
