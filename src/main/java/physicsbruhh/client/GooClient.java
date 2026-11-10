package physicsbruhh.client;

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
    /** Cells of bodies added this tick: their surroundings are scanned at once, so they never spend a tick without a floor. */
    private static final List<BlockPos> NEW_CELLS = new ArrayList<>();
    /** The local player's box (relative to {@link #origin}) and how much goo is inside it: honey in the low 16 bits, slime above. */
    private static volatile float[] probeBox;
    private static volatile int probeResult;

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
        bodyInfo = new float[0]; KNOWN.clear(); LIVE.clear(); clearLook = false; NEW_CELLS.clear(); probeBox = null; probeResult = 0;
        java.util.Arrays.fill(SLEEP_HASH_VALID, false); java.util.Arrays.fill(WAKING, 0);
        PART_IDS.clear(); HEADS.clear();
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
                NEW_CELLS.add(cell);
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
        if (sim != null && !paused) {
            Colliders c = scan(level);
            if (c != null) COLLIDERS.set(c);
            NEW_CELLS.clear();
            BlockPos o = origin;
            if (mc.player != null && !mc.player.isSpectator()) {
                AABB box = mc.player.getBoundingBox();
                probeBox = new float[] { (float) (box.minX - o.getX()), (float) (box.minY - o.getY()), (float) (box.minZ - o.getZ()), (float) (box.maxX - o.getX()), (float) (box.maxY - o.getY()), (float) (box.maxZ - o.getZ()) };
            } else {
                probeBox = null; probeResult = 0;
            }
        }
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
            if (info[b] == 0.0F) { SLEEP_HASH_VALID[slot] = false; WAKING[slot] = 0; continue; }
            int x0 = (int) Math.floor(info[b + 2] - 1.0F) + o.getX(), y0 = (int) Math.floor(info[b + 3] - 1.0F) + o.getY(), z0 = (int) Math.floor(info[b + 4] - 1.0F) + o.getZ();
            int x1 = (int) Math.floor(info[b + 5] + 1.0F) + o.getX(), y1 = (int) Math.floor(info[b + 6] + 1.0F) + o.getY(), z1 = (int) Math.floor(info[b + 7] + 1.0F) + o.getZ();
            if (x1 - x0 > 24 || y1 - y0 > 24 || z1 - z0 > 24) continue; // something flew off; ignore it
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
        return any ? new Colliders(st, ns, kin, nk) : null; // nothing awake: leave the simulation's colliders alone
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
    private static int addCreature(Entity e, BlockPos o, float[] kin, int nk) {
        AABB box = e.getBoundingBox();
        double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
        if (dx * dx + dy * dy + dz * dz > 4.0) { dx = 0.0; dy = 0.0; dz = 0.0; } // teleported: not a shove
        int i = nk * GooSim.KINEMATIC_FLOATS;
        kin[i] = -(e.getId() % 8_000_000 + 1); // creatures have negative ids: they push goo aside but do not make it stiffen like a piston does
        kin[i + 1] = (float) (box.minX - dx - o.getX()); kin[i + 2] = (float) (box.minY - dy - o.getY()); kin[i + 3] = (float) (box.minZ - dz - o.getZ());
        kin[i + 4] = (float) (box.maxX - dx - o.getX()); kin[i + 5] = (float) (box.maxY - dy - o.getY()); kin[i + 6] = (float) (box.maxZ - dz - o.getZ());
        kin[i + 7] = (float) (dx * 20.0); kin[i + 8] = (float) (dy * 20.0); kin[i + 9] = (float) (dz * 20.0);
        kin[i + 10] = 1.0F;
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
                        f.getStepX() * v, f.getStepY() * v, f.getStepZ() * v, head.sticky ? 3.0F : 0.0F, f, o, kin, nk);
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

    /** How much goo the local player is standing in: honey particles in the low 16 bits, slime in the next 16. */
    static int contact() { return probeBox == null ? 0 : probeResult; }

    /** The goo whose bounds the segment {@code from}-{@code to} enters first, within {@code limit} blocks; null if none. */
    static GooEntity pick(Vec3 from, Vec3 to, double limit) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        GooEntity best = null;
        double bestDistance = limit;
        for (GooEntity goo : LIVE.values()) {
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F) continue;
            int b = slot * 8;
            AABB box = new AABB(info[b + 2] + o.getX(), info[b + 3] + o.getY(), info[b + 4] + o.getZ(), info[b + 5] + o.getX(), info[b + 6] + o.getY(), info[b + 7] + o.getZ()).inflate(0.05);
            double distance = box.contains(from) ? 0.0 : box.clip(from, to).map(hit -> hit.distanceTo(from)).orElse(Double.MAX_VALUE);
            if (distance < bestDistance) { bestDistance = distance; best = goo; }
        }
        return best;
    }

    /** Material of the goo within a block of the given cell, or -1 if there is none. */
    static int materialNear(BlockPos cell) {
        float[] info = bodyInfo;
        BlockPos o = origin;
        for (GooEntity goo : LIVE.values()) {
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] != 1.0F) continue;
            int b = slot * 8;
            if (info[b + 2] + o.getX() < cell.getX() + 2 && info[b + 5] + o.getX() > cell.getX() - 1 && info[b + 3] + o.getY() < cell.getY() + 2 && info[b + 6] + o.getY() > cell.getY() - 1
                    && info[b + 4] + o.getZ() < cell.getZ() + 2 && info[b + 7] + o.getZ() > cell.getZ() - 1) return goo.material();
        }
        return -1;
    }

    /** World x range of the slime body that is widest along x (smoke test: is the band between two pistons where it should be?). */
    static float[] widestSlime() {
        float[] info = bodyInfo;
        float[] span = { 0.0F, 0.0F };
        for (GooEntity goo : LIVE.values()) {
            if (goo.material() != GooSim.SLIME) continue;
            Integer slot = SLOT_OF_ENTITY.get(goo.getId());
            if (slot == null || slot * 8 + 8 > info.length || info[slot * 8] == 0.0F) continue;
            float lo = info[slot * 8 + 2] + origin.getX(), hi = info[slot * 8 + 5] + origin.getX();
            if (hi - lo > span[1] - span[0]) { span[0] = lo; span[1] = hi; }
        }
        return span;
    }

    static int quadCount(int material) { synchronized (MESH_LOCK) { return meshQuads[material]; } }

    private static volatile float stepMillis;

    // ------------------------------------------------------------------ simulation thread

    private static void simLoop() {
        ByteBuffer staticBuf = direct(MAX_STATIC * GooSim.STATIC_FLOATS), kinBuf = direct(MAX_KINEMATIC * GooSim.KINEMATIC_FLOATS);
        ByteBuffer infoBuf = direct(MAX_BODIES * GooSim.BODY_FLOATS);
        ByteBuffer meshBuf = direct(1 << 18);
        long last = System.nanoTime();
        boolean dirty = true, hadAwake = false;
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
                float[] probe = probeBox;
                if (probe != null) probeResult = Math.max(0, s.probe(probe[0], probe[1], probe[2], probe[3], probe[4], probe[5]));
                // once more after the last moving body has stopped or gone, or its final state would never be shown
                boolean changed = awake > 0 || dirty || hadAwake;
                hadAwake = awake > 0;
                if (changed) {
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
