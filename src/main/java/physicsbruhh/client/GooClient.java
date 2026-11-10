package physicsbruhh.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;
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
    private static final int MAX_BODIES = 24;
    private static final int MAX_STATIC = 4096, MAX_KINEMATIC = 64;

    /** What the client thread hands to the simulation each tick. */
    private record Colliders(float[] statics, int staticCount, float[] kinematics, int kinematicCount) {}

    /** Latest surface, shared with the renderer under {@link #MESH_LOCK}. */
    static final Object MESH_LOCK = new Object();
    static float[][] meshData = { new float[0], new float[0] };
    static int[] meshQuads = { 0, 0 };
    static int meshVersion;
    static volatile BlockPos origin = BlockPos.ZERO;

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
    /** Goo is drawn as clear liquid (the server's {@code clear_goo} game rule, read off the goo markers). */
    static volatile boolean clearLook;

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
        bodyInfo = new float[0]; KNOWN.clear(); LIVE.clear(); clearLook = false;
        java.util.Arrays.fill(SLEEP_HASH_VALID, false); java.util.Arrays.fill(WAKING, 0);
        synchronized (MESH_LOCK) { meshQuads[0] = 0; meshQuads[1] = 0; meshVersion++; }
    }

    private static void start() {
        GooSim s = null;
        if (NativeSim.available()) {
            try { s = new NativeSim(PARTICLES_PER_EDGE, MAX_BODIES); } catch (RuntimeException e) { PhysicsBruhh.LOGGER.warn("Rust core failed to start: {}", e.toString()); }
        }
        if (s == null) {
            PhysicsBruhh.LOGGER.info("Using the Java simulation ({})", NativeSim.loadMessage());
            s = new JavaSim(PARTICLES_PER_EDGE, MAX_BODIES);
        } else {
            PhysicsBruhh.LOGGER.info("Using the Rust simulation ({})", NativeSim.loadMessage());
        }
        sim = s;
        running = true;
        thread = new Thread(GooClient::simLoop, "PhysicsBruhh-sim");
        thread.setDaemon(true);
        thread.start();
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) { if (sim != null) reset(); return; }
        paused = mc.isPaused();
        boolean clear = false;
        for (GooEntity goo : LIVE.values()) { if (goo.clear()) { clear = true; break; } }
        clearLook = clear;
        if (!PENDING.isEmpty()) {
            if (sim == null) start();
            for (GooEntity goo : PENDING) {
                if (goo.isRemoved()) continue;
                BlockPos cell = BlockPos.containing(goo.getX(), goo.getY() + 0.01, goo.getZ());
                if (KNOWN.isEmpty()) origin = cell; // rebase so local coordinates stay small
                KNOWN.add(goo.getId());
                BlockPos o = origin;
                final int id = goo.getId(), mat = goo.material();
                final float x = cell.getX() - o.getX(), y = cell.getY() - o.getY(), z = cell.getZ() - o.getZ();
                final float age = goo.spawnAgeTicks() / 20.0F, lx = goo.leanX(), lz = goo.leanZ();
                final float killY = level.getMinY() - 8 - o.getY();
                COMMANDS.add(() -> {
                    GooSim s = sim;
                    if (s == null) return;
                    s.setKillY(killY);
                    int slot = s.addBody(mat, x, y, z, lx, lz, age);
                    if (slot >= 0) { SLOT_OF_ENTITY.values().removeIf(v -> v == slot); SLOT_OF_ENTITY.put(id, slot); }
                });
            }
            PENDING.clear();
        }
        if (sim != null && !paused) { Colliders c = scan(level); if (c != null) COLLIDERS.set(c); }
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
        for (int b = 0; b + 8 <= info.length && b / 8 < MAX_BODIES; b += 8) {
            final int slot = b / 8;
            if (info[b] == 0.0F) { SLEEP_HASH_VALID[slot] = false; WAKING[slot] = 0; continue; }
            int x0 = (int) Math.floor(info[b + 2] - 1.0F) + o.getX(), y0 = (int) Math.floor(info[b + 3] - 1.0F) + o.getY(), z0 = (int) Math.floor(info[b + 4] - 1.0F) + o.getZ();
            int x1 = (int) Math.floor(info[b + 5] + 1.0F) + o.getX(), y1 = (int) Math.floor(info[b + 6] + 1.0F) + o.getY(), z1 = (int) Math.floor(info[b + 7] + 1.0F) + o.getZ();
            if (x1 - x0 > 24 || y1 - y0 > 24 || z1 - z0 > 24) continue; // something flew off; ignore it
            if (info[b + 1] != 0.0F) {
                SLEEP_HASH_VALID[slot] = false; WAKING[slot] = 0;
            } else {
                long hash = 1L;
                for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    hash = hash * 31L + System.identityHashCode(state);
                    if ((state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) && seenParts.add(pos.asLong())) nk = addPistonPart(level, state, pos, o, kin, nk);
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
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                pos.set(x, y, z);
                if (!seen.add(pos.asLong())) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) {
                    if (seenParts.add(pos.asLong())) nk = addPistonPart(level, state, pos, o, kin, nk);
                    continue;
                }
                VoxelShape shape = state.getCollisionShape(level, pos);
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
        return any ? new Colliders(st, ns, kin, nk) : null; // nothing awake: leave the simulation's colliders alone
    }

    /** How strongly goo clings to a surface (1 = ordinary block). */
    private static float stickiness(BlockState state) {
        if (state.is(Blocks.STICKY_PISTON) || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SLIME_BLOCK)) return 2.0F;
        return 1.0F;
    }

    private static BlockState headState(Direction facing, boolean sticky) {
        return Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, facing).setValue(PistonHeadBlock.TYPE, sticky ? PistonType.STICKY : PistonType.DEFAULT);
    }

    /**
     * A piston head (at rest or sliding) or a block in transit. These are reported as moving boxes
     * with stable ids: a head keeps the same id while it extends, rests and retracts, so goo that
     * stuck to it is carried along instead of being left behind when the head starts to move.
     * The position is where the part was at the start of this tick, the velocity carries it to where
     * it is at the end, which is what the game draws between the two.
     */
    private static int addPistonPart(ClientLevel level, BlockState state, BlockPos pos, BlockPos o, float[] kin, int nk) {
        BlockState shapeState;
        BlockPos idPos = pos;
        float stick;
        float ox = 0.0F, oy = 0.0F, oz = 0.0F, vx = 0.0F, vy = 0.0F, vz = 0.0F;
        if (state.is(Blocks.PISTON_HEAD)) {
            Direction facing = state.getValue(PistonHeadBlock.FACING);
            boolean sticky = state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY;
            shapeState = headState(facing, sticky);
            idPos = pos.relative(facing.getOpposite()); // heads are named after their base
            stick = sticky ? 3.0F : 0.6F;
        } else {
            if (!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston)) return nk;
            BlockState moved = piston.getMovedState();
            shapeState = moved;
            stick = stickiness(moved);
            if (piston.isSourcePiston()) {
                boolean sticky = moved.is(Blocks.STICKY_PISTON) || (moved.is(Blocks.PISTON_HEAD) && moved.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY);
                shapeState = headState(piston.getDirection(), sticky);
                stick = sticky ? 3.0F : 0.6F;
                // while extending the moving block sits where the head will end up; while retracting, on the base
                if (piston.isExtending()) idPos = pos.relative(piston.getDirection().getOpposite());
            }
            ox = piston.getXOff(0.0F); oy = piston.getYOff(0.0F); oz = piston.getZOff(0.0F);
            vx = (piston.getXOff(1.0F) - ox) * 20.0F; vy = (piston.getYOff(1.0F) - oy) * 20.0F; vz = (piston.getZOff(1.0F) - oz) * 20.0F;
        }
        VoxelShape shape = shapeState.getCollisionShape(level, pos);
        if (shape.isEmpty()) return nk;
        int index = 0;
        for (AABB box : shape.toAabbs()) {
            if (nk >= MAX_KINEMATIC) break;
            int i = nk * GooSim.KINEMATIC_FLOATS;
            kin[i] = (float) (((idPos.asLong() * 31L + index) & 0xFFFFFFL) + 1); // stable id, exact in a float
            kin[i + 1] = (float) (box.minX + ox + pos.getX() - o.getX()); kin[i + 2] = (float) (box.minY + oy + pos.getY() - o.getY()); kin[i + 3] = (float) (box.minZ + oz + pos.getZ() - o.getZ());
            kin[i + 4] = (float) (box.maxX + ox + pos.getX() - o.getX()); kin[i + 5] = (float) (box.maxY + oy + pos.getY() - o.getY()); kin[i + 6] = (float) (box.maxZ + oz + pos.getZ() - o.getZ());
            kin[i + 7] = vx; kin[i + 8] = vy; kin[i + 9] = vz;
            kin[i + 10] = stick;
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
                + " honeyQuads=" + honey + " slimeQuads=" + slime + " stepMs=" + String.format(java.util.Locale.ROOT, "%.2f", stepMillis);
    }

    static boolean healthy() { return sim != null && running; }

    /** Widest extent along x of any slime body, in blocks (smoke test: has the band between two pistons held?). */
    static float widestSlime() {
        float[] info = bodyInfo;
        float widest = 0.0F;
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != GooSim.SLIME) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] == 0.0F) continue;
            widest = Math.max(widest, info[slot * 8 + 5] - info[slot * 8 + 2]);
        }
        return widest;
    }

    static int quadCount(int material) { synchronized (MESH_LOCK) { return meshQuads[material]; } }

    private static volatile float stepMillis;

    // ------------------------------------------------------------------ simulation thread

    private static void simLoop() {
        ByteBuffer staticBuf = direct(MAX_STATIC * GooSim.STATIC_FLOATS), kinBuf = direct(MAX_KINEMATIC * GooSim.KINEMATIC_FLOATS);
        ByteBuffer infoBuf = direct(MAX_BODIES * GooSim.BODY_FLOATS);
        ByteBuffer meshBuf = direct(1 << 18);
        long last = System.nanoTime();
        boolean dirty = true;
        try {
            while (running) {
                GooSim s = sim;
                if (s == null) break;
                if (LOCKSTEP && !STEP_REQUEST.tryAcquire(20, TimeUnit.MILLISECONDS)) continue;
                Runnable cmd;
                while ((cmd = COMMANDS.poll()) != null) { cmd.run(); dirty = true; }
                Colliders c = COLLIDERS.getAndSet(null);
                if (c != null) {
                    staticBuf.asFloatBuffer().put(0, c.statics(), 0, Math.min(c.staticCount(), MAX_STATIC) * GooSim.STATIC_FLOATS);
                    s.setStatic(staticBuf, Math.min(c.staticCount(), MAX_STATIC));
                    kinBuf.asFloatBuffer().put(0, c.kinematics(), 0, c.kinematicCount() * GooSim.KINEMATIC_FLOATS);
                    s.setKinematic(kinBuf, c.kinematicCount());
                }
                long now = System.nanoTime();
                float dt = (now - last) / 1.0e9F;
                last = now;
                int awake = 0;
                if (LOCKSTEP) { for (int k = 0; k < 3; k++) awake = s.step(1.0F / 60.0F + 1.0e-6F); }
                else if (!paused) awake = s.step(dt);
                if (s instanceof NativeSim n && n.broken()) throw new IllegalStateException("the Rust core reported an internal error");
                int slots = s.bodies(infoBuf);
                float[] info = new float[Math.max(0, slots) * GooSim.BODY_FLOATS];
                infoBuf.asFloatBuffer().get(0, info, 0, info.length);
                bodyInfo = info;
                if (awake > 0 || dirty) {
                    for (int mat = 0; mat < 2; mat++) {
                        int quads = s.mesh(mat, meshBuf);
                        if (quads < 0 && quads > -(1 << 26)) { meshBuf = direct(-quads + (1 << 16)); quads = s.mesh(mat, meshBuf); }
                        if (quads < 0) quads = 0;
                        int floats = quads * GooSim.FLOATS_PER_QUAD;
                        synchronized (MESH_LOCK) {
                            if (meshData[mat].length < floats) meshData[mat] = new float[floats + floats / 2];
                            meshBuf.asFloatBuffer().get(0, meshData[mat], 0, floats);
                            meshQuads[mat] = quads;
                            meshVersion++;
                        }
                    }
                    dirty = false;
                }
                long spent = System.nanoTime() - now;
                stepMillis += (spent / 1.0e6F - stepMillis) * 0.05F;
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
