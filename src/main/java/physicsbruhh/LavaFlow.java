package physicsbruhh;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import physicsbruhh.sim.GooSim;

/**
 * Lava that flows as a body. In the game a block of lava that finds room next to it stays where it
 * is and puts thinner and thinner copies of itself into the blocks around, for ever. Here, near
 * players, it does what a block of something thick and liquid would do: the block itself lets go,
 * and what was in it oozes out through the opening as one piece, pours down, spreads and cools. It
 * is as much lava as the block held, and no more.
 *
 * <p>How: the one place the game asks "may this lava spread into that block" is hooked
 * ({@code mixin/LavaFluidMixin}); if the lava asking is a whole block (a source) it is taken out of
 * the world and a marker entity is left in its place, as for honey and slime. The clients turn the
 * marker into simulated lava. Everything else about lava is left to the game: lava that is already
 * flowing goes on flowing as it always did, a block of lava next to water makes stone as ever, and
 * far from any player nothing changes at all, so the world generates the way it always has.
 *
 * <p>What the server cannot know is where the lava has gone to: its shape only exists on the
 * clients. So they report ({@link Report}): which creatures stand in it, where each piece has got
 * to, and where it has filled a hollow to the brim and may be a block again.
 */
public final class LavaFlow {
    private LavaFlow() {}

    /** Lava is physical this close to a player (blocks); further away it is the game's own. */
    private static final double NEAR = 48.0;
    /** Pieces that have left their block within this many ticks count as still on the move. */
    private static final int YOUNG = 400;
    /** How many pieces may be on the move around one place at a time, and how many there may be there at all: more lava waits in its blocks. */
    private static final int MOST_YOUNG = 6, MOST = 16;
    private static final double AROUND = 40.0;
    /** A piece has cooled to a crust after this many ticks, and is gone after this many (give or take). */
    static final int COOLED = 1600, LIFE = 2400;
    /** A hollow counts as filled to the brim by this much lava (particles of the simulation). */
    private static final int BRIM = GooSim.BLOCK * 4 / 5;
    /** For the self tests: lava is physical with no player near. */
    public static volatile boolean anywhere = Boolean.getBoolean("physicsbruhh.lava.anywhere");
    /** Whether the game has ever asked (so the hook is in place), and how many blocks have let go since the server started (self tests). */
    public static volatile boolean asked;
    public static volatile int released, pooled, burnt, waiting, quenched;

    public static boolean enabled(ServerLevel level) {
        return level.getGameRules().get(PhysicsBruhh.PHYSICAL_LAVA.get());
    }

    /** The block that has just let go: the rest of what the game was doing with it this tick comes to nothing. */
    private static long lastFrom;
    private static long lastTime = -1L;
    private static ServerLevel lastLevel;

    /**
     * The game is about to let lava spread into the block {@code to}, coming from the direction
     * {@code direction} points away from. Returns true if that is not to happen: because the lava it
     * comes from has become a body instead (or will, as soon as there is room for one more).
     */
    public static boolean ooze(LevelAccessor accessor, BlockPos to, Direction direction) {
        if (!(accessor instanceof ServerLevel level)) return false;
        asked = true;
        if (!enabled(level)) return false;
        BlockPos from = to.relative(direction.getOpposite());
        if (level == lastLevel && level.getGameTime() == lastTime && from.asLong() == lastFrom) return true;
        FluidState fluid = level.getFluidState(from);
        if (!fluid.isSource() || !fluid.is(FluidTags.LAVA)) return false;
        BlockState state = level.getBlockState(from);
        if (!(state.getBlock() instanceof LiquidBlock)) return false;
        // lava meeting water is the game's business: stone, cobblestone, obsidian
        if (!level.getFluidState(to).isEmpty()) return false;
        if (!anywhere && !level.hasNearbyAlivePlayer(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5, NEAR)) return false;
        if (!room(level, from)) {
            // too much lava on the move already: this block holds until some of that has come to rest
            waiting++;
            level.scheduleTick(from, fluid.getType(), 20 + level.getRandom().nextInt(20));
            return true;
        }
        release(level, from, direction);
        return true;
    }

    private static boolean room(ServerLevel level, BlockPos at) {
        List<? extends GooEntity> near = level.getEntities(PhysicsBruhh.LAVA_GOO.get(), new AABB(at).inflate(AROUND), e -> !e.isRemoved());
        if (near.size() >= MOST) return false;
        int young = 0;
        for (GooEntity goo : near) if (goo.age() < YOUNG) young++;
        return young < MOST_YOUNG;
    }

    /** The block of lava at {@code from} becomes a body of lava that leans the way it found room. */
    public static GooEntity release(ServerLevel level, BlockPos from, Direction direction) {
        GooEntity goo = new GooEntity(PhysicsBruhh.LAVA_GOO.get(), level);
        goo.setPos(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
        goo.setLean(direction.getStepX(), direction.getStepZ());
        lastLevel = level; lastTime = level.getGameTime(); lastFrom = from.asLong();
        level.setBlock(from, Blocks.AIR.defaultBlockState(), 3);
        level.addFreshEntity(goo);
        level.playSound(null, from, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.7F, 0.6F + 0.2F * level.getRandom().nextFloat());
        released++;
        return goo;
    }

    /** What a piece of lava does as time goes by, as far as the server is concerned: it sets fire to what burns, hisses, cools, and in the end is gone. */
    static void tick(GooEntity goo, ServerLevel level, int age) {
        if (age > LIFE + goo.getId() % 200) { goo.discard(); return; }
        // lava that has got into water is put out: a hiss, and if it was most of a block, a block of cobblestone where the water was
        if (age % 10 == 5) {
            BlockPos at = goo.blockPosition();
            FluidState fluid = level.getFluidState(at);
            if (fluid.is(FluidTags.WATER)) {
                level.levelEvent(1501, at, 0);
                if (goo.amount() >= GooSim.BLOCK / 2 && level.getBlockState(at).getBlock() instanceof LiquidBlock) level.setBlockAndUpdate(at, Blocks.COBBLESTONE.defaultBlockState());
                quenched++;
                goo.discard();
                return;
            }
        }
        if (age >= COOLED) return;
        if (age % 40 == 20) ignite(level, goo.blockPosition(), level.getRandom());
        if (age % 120 == 60 && level.getRandom().nextInt(3) == 0) {
            level.playSound(null, goo.getX(), goo.getY(), goo.getZ(), SoundEvents.LAVA_AMBIENT, SoundSource.BLOCKS, 0.35F, 0.9F + 0.3F * level.getRandom().nextFloat());
        }
    }

    /** What a block of lava does to things that burn near it now and then (the game's own rule, from where the piece lies). */
    private static void ignite(ServerLevel level, BlockPos at, RandomSource random) {
        if (!level.canSpreadFireAround(at)) return;
        int passes = random.nextInt(3);
        if (passes > 0) {
            BlockPos test = at;
            for (int pass = 0; pass < passes; pass++) {
                test = test.offset(random.nextInt(3) - 1, pass == 0 ? 0 : 1, random.nextInt(3) - 1);
                if (!level.isLoaded(test)) return;
                BlockState state = level.getBlockState(test);
                if (state.isAir()) {
                    if (burnsAround(level, test)) {
                        level.setBlockAndUpdate(test, BaseFireBlock.getState(level, test));
                        return;
                    }
                } else if (state.is(BlockTags.BLOCKS_LAVA_FIRE_SPREAD)) {
                    return;
                }
            }
        } else {
            // what it lies on, and what stands next to it
            BlockPos base = random.nextBoolean() ? at : at.below();
            for (int i = 0; i < 3; i++) {
                BlockPos test = base.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
                if (!level.isLoaded(test)) return;
                if (level.isEmptyBlock(test.above()) && level.getBlockState(test).ignitedByLava(level, test, Direction.UP)) {
                    level.setBlockAndUpdate(test.above(), BaseFireBlock.getState(level, test));
                }
            }
        }
    }

    private static boolean burnsAround(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (level.isLoaded(next) && level.getBlockState(next).ignitedByLava(level, next, direction.getOpposite())) return true;
        }
        return false;
    }

    /** Whether lava standing in this block would stay there: something under it and on all four sides (a wall, or more lava). */
    private static boolean hollow(ServerLevel level, BlockPos cell) {
        for (Direction direction : new Direction[] { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            BlockPos next = cell.relative(direction);
            BlockState state = level.getBlockState(next);
            FluidState fluid = state.getFluidState();
            boolean lava = fluid.is(FluidTags.LAVA) && fluid.isSource();
            if (!lava && !state.isFaceSturdy(level, next, direction.getOpposite())) return false;
        }
        return true;
    }

    /**
     * Lava has filled the block {@code cell} to the brim, a client says. If it would stay there as
     * a block, it becomes one again: a block's worth is taken out of the pieces lying there.
     */
    private static void pool(ServerLevel level, BlockPos cell) {
        if (!level.isLoaded(cell) || !level.getBlockState(cell).isAir() || !hollow(level, cell)) return;
        Vec3 middle = Vec3.atCenterOf(cell);
        List<? extends GooEntity> near = level.getEntities(PhysicsBruhh.LAVA_GOO.get(), new AABB(cell).inflate(3.0), e -> !e.isRemoved());
        int there = 0;
        for (GooEntity goo : near) there += goo.amount();
        if (there < BRIM) return;
        List<GooEntity> byDistance = new java.util.ArrayList<>(near);
        byDistance.sort(java.util.Comparator.comparingDouble(g -> g.distanceToSqr(middle.x, middle.y - 0.5, middle.z)));
        int wanted = GooSim.BLOCK;
        for (GooEntity goo : byDistance) {
            if (wanted <= 0) break;
            wanted -= goo.scoop(middle, wanted);
        }
        level.setBlock(cell, Blocks.LAVA.defaultBlockState(), 3);
        pooled++;
    }

    /**
     * From a client, a few times a second while it has lava: the creatures that stand in it (their
     * ids), where the pieces it simulates have got to (their ids, and for each the middle of where it
     * lies and its underside), and the blocks the lava has filled to the brim.
     */
    public record Report(int[] burning, int[] pieces, float[] places, long[] brimful) implements CustomPacketPayload {
        public static final Type<Report> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "lava_report"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Report> CODEC = StreamCodec.ofMember(Report::write, Report::read);
        public static final int MOST_BURNING = 64, MOST_PIECES = 64, MOST_BRIMFUL = 16;

        private void write(RegistryFriendlyByteBuf buffer) {
            int burn = Math.min(burning.length, MOST_BURNING), count = Math.min(pieces.length, MOST_PIECES), full = Math.min(brimful.length, MOST_BRIMFUL);
            buffer.writeVarInt(burn);
            for (int i = 0; i < burn; i++) buffer.writeVarInt(burning[i]);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buffer.writeVarInt(pieces[i]);
                buffer.writeFloat(places[i * 3]); buffer.writeFloat(places[i * 3 + 1]); buffer.writeFloat(places[i * 3 + 2]);
            }
            buffer.writeVarInt(full);
            for (int i = 0; i < full; i++) buffer.writeLong(brimful[i]);
        }

        private static Report read(RegistryFriendlyByteBuf buffer) {
            int burn = Math.max(0, Math.min(MOST_BURNING, buffer.readVarInt()));
            int[] burning = new int[burn];
            for (int i = 0; i < burn; i++) burning[i] = buffer.readVarInt();
            int count = Math.max(0, Math.min(MOST_PIECES, buffer.readVarInt()));
            int[] pieces = new int[count];
            float[] places = new float[count * 3];
            for (int i = 0; i < count; i++) {
                pieces[i] = buffer.readVarInt();
                places[i * 3] = buffer.readFloat(); places[i * 3 + 1] = buffer.readFloat(); places[i * 3 + 2] = buffer.readFloat();
            }
            int full = Math.max(0, Math.min(MOST_BRIMFUL, buffer.readVarInt()));
            long[] brimful = new long[full];
            for (int i = 0; i < full; i++) brimful[i] = buffer.readLong();
            return new Report(burning, pieces, places, brimful);
        }

        @Override
        public Type<Report> type() { return TYPE; }
    }

    static void handle(Report report, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) report(player, level, report);
    }

    /** What is done with what a client says about the lava it simulates. */
    static void report(ServerPlayer player, ServerLevel level, Report report) {
        if (!enabled(level)) return;
        // where the pieces have got to: the marker follows, a few blocks at a time
        for (int i = 0; i < report.pieces().length; i++) {
            if (!(level.getEntity(report.pieces()[i]) instanceof GooEntity goo) || goo.material() != GooSim.LAVA || goo.isRemoved()) continue;
            double x = report.places()[i * 3], y = report.places()[i * 3 + 1], z = report.places()[i * 3 + 2];
            if (!Double.isFinite(x + y + z) || goo.distanceToSqr(player) > 96.0 * 96.0) continue;
            double moved = goo.distanceToSqr(x, y, z);
            if (moved < 0.25 * 0.25 || moved > 12.0 * 12.0) continue;
            goo.setPos(x, y, z);
        }
        // creatures standing in it burn as in a block of lava; a piece of lava has to be near, and the creature near the player who says so
        for (int id : report.burning()) {
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity instanceof GooEntity || entity.isSpectator() || entity.distanceToSqr(player) > 64.0 * 64.0) continue;
            if (level.getEntities(PhysicsBruhh.LAVA_GOO.get(), entity.getBoundingBox().inflate(6.0), e -> !e.isRemoved() && e.age() < LIFE).isEmpty()) continue;
            entity.lavaIgnite();
            entity.lavaHurt();
            burnt++;
        }
        for (long cell : report.brimful()) {
            BlockPos pos = BlockPos.of(cell);
            if (pos.distSqr(player.blockPosition()) < 64.0 * 64.0) pool(level, pos);
        }
    }

    /** Lava's own fluid, for those who only have the block. */
    static boolean isLavaSource(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.isSource() && fluid.getType().isSame(Fluids.LAVA);
    }
}
