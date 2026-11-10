package physicsbruhh;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import physicsbruhh.sim.GooSim;

/** Decides when a vanilla honey or slime block becomes goo. Runs on the logical server only. */
public final class GooEvents {
    private GooEvents() {}

    public static boolean enabled(ServerLevel level) {
        return level.getGameRules().get(PhysicsBruhh.PHYSICAL_GOO.get());
    }

    public static boolean isGooBlock(BlockState state) {
        return state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SLIME_BLOCK);
    }

    /** Replaces the block at {@code pos} with a goo marker. Returns false if it was not a goo block. */
    public static boolean convert(ServerLevel level, BlockPos pos, float leanX, float leanZ) {
        return convertToGoo(level, pos, leanX, leanZ) != null;
    }

    /** Replaces the block at {@code pos} with a goo marker and returns it, or null if it was not a goo block. */
    public static GooEntity convertToGoo(ServerLevel level, BlockPos pos, float leanX, float leanZ) {
        BlockState state = level.getBlockState(pos);
        if (!isGooBlock(state)) return null;
        GooEntity goo = new GooEntity(state.is(Blocks.SLIME_BLOCK) ? PhysicsBruhh.SLIME_GOO.get() : PhysicsBruhh.HONEY_GOO.get(), level);
        goo.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        goo.setLean(leanX, leanZ);
        goo.applyLook(level);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.addFreshEntity(goo);
        return goo;
    }

    static List<GooEntity> gooIn(ServerLevel level, AABB box) {
        List<GooEntity> found = new ArrayList<>(level.getEntities(PhysicsBruhh.HONEY_GOO.get(), box, e -> true));
        found.addAll(level.getEntities(PhysicsBruhh.SLIME_GOO.get(), box, e -> true));
        return found;
    }

    /** A whole block's worth of goo in the cell {@code pos}, as if a block had just turned into goo there. */
    public static GooEntity spawnBlock(ServerLevel level, BlockPos pos, int material, float leanX, float leanZ) {
        GooEntity goo = new GooEntity(material == GooSim.SLIME ? PhysicsBruhh.SLIME_GOO.get() : PhysicsBruhh.HONEY_GOO.get(), level);
        goo.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        goo.setLean(leanX, leanZ);
        goo.applyLook(level);
        level.addFreshEntity(goo);
        return goo;
    }

    /**
     * Goo poured or thrown: {@code count} particles of it leave {@code from} with a velocity (blocks
     * per second) and are expected to come down around {@code target}. They join a piece of the same
     * material lying there if it has room, otherwise they become a new piece. Returns how many went in.
     */
    public static int pour(ServerLevel level, int material, Vec3 from, Vec3 velocity, Vec3 target, int count, boolean lump) {
        GooEntity best = null;
        double bestDistance = 1.6 * 1.6;
        for (GooEntity goo : gooIn(level, AABB.ofSize(target, 4.0, 4.0, 4.0))) {
            if (goo.material() != material || goo.isRemoved() || goo.amount() + count > GooSim.BLOCK) continue;
            double d = goo.distanceToSqr(target.x, target.y, target.z);
            if (d < bestDistance) { bestDistance = d; best = goo; }
        }
        if (best == null) {
            best = new GooEntity(material == GooSim.SLIME ? PhysicsBruhh.SLIME_GOO.get() : PhysicsBruhh.HONEY_GOO.get(), level);
            BlockPos cell = BlockPos.containing(target);
            best.setPos(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            best.startEmpty();
            best.applyLook(level);
            int poured = best.pour(from, velocity, count, lump);
            level.addFreshEntity(best);
            return poured;
        }
        return best.pour(from, velocity, count, lump);
    }

    /**
     * Where something thrown from {@code from} with a velocity (blocks per second) comes down: followed
     * for three seconds or until it meets a block. {@code thrower} may be null (a dispenser).
     */
    public static Vec3 landing(ServerLevel level, Entity thrower, Vec3 from, Vec3 velocity) {
        Vec3 p = from, v = velocity.scale(0.05);
        for (int i = 0; i < 60; i++) {
            Vec3 next = p.add(v);
            BlockHitResult hit = level.clip(thrower != null
                    ? new ClipContext(p, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower)
                    : new ClipContext(p, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                Direction face = hit.getDirection();
                return hit.getLocation().add(face.getStepX() * 0.1, face.getStepY() * 0.1, face.getStepZ() * 0.1);
            }
            p = next;
            v = v.add(0.0, -0.025, 0.0);
        }
        return p;
    }

    /**
     * A honey bottle used while sneaking is poured out (the bottle stays); a slime ball is thrown as
     * a lump of slime. On goo itself the same items go straight into it, see {@link GooEntity#interact}.
     */
    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        boolean honey = stack.is(Items.HONEY_BOTTLE) && player.isSecondaryUseActive(), slime = stack.is(Items.SLIME_BALL);
        if (!honey && !slime) return;
        // the client cannot know whether the rule is on, so it leaves this to the server
        if (!(player.level() instanceof ServerLevel level) || !enabled(level) || player.isSpectator()) return;
        Vec3 look = player.getLookAngle();
        if (honey) {
            Vec3 from = player.getEyePosition().add(look.scale(0.5)).add(0.0, -0.2, 0.0);
            Vec3 velocity = look.scale(2.2);
            if (pour(level, GooSim.HONEY, from, velocity, landing(level, player, from, velocity), GooSim.BOTTLE, false) == 0) return;
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.8F, 1.0F);
            player.setItemInHand(event.getHand(), ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
        } else {
            Vec3 from = player.getEyePosition().add(look.scale(0.75)).add(0.0, -0.1, 0.0);
            Vec3 velocity = look.scale(7.0).add(0.0, 1.2, 0.0);
            if (pour(level, GooSim.SLIME, from, velocity, landing(level, player, from, velocity), GooSim.BALL, true) == 0) return;
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6F, 0.6F);
            stack.consume(1, player);
        }
        event.setCancellationResult(InteractionResult.SUCCESS_SERVER);
        event.setCanceled(true);
    }

    /**
     * Landing on or next to goo counts as landing on the block it was made of: no damage on slime
     * (unless sneaking), a fifth of it on honey.
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        AABB body = entity.getBoundingBox().inflate(0.5, 0.3, 0.5);
        boolean slime = false, honey = false;
        for (GooEntity goo : gooIn(level, body.inflate(2.0))) {
            if (!goo.reach().intersects(body)) continue;
            if (goo.material() == GooSim.SLIME) slime = true; else honey = true;
        }
        if (slime && !entity.isSuppressingBounce()) event.setDamageMultiplier(0.0F);
        else if (honey) event.setDamageMultiplier(event.getDamageMultiplier() * 0.2F);
    }

    private record Placed(ServerLevel level, BlockPos pos, float leanX, float leanZ) {}

    private static final List<Placed> PLACED = new ArrayList<>();

    /** A placed honey or slime block melts / comes alive right away. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        Entity placer = event.getEntity();
        // for players the event carries the new block; for anything else only what was there before,
        // so those positions are simply looked at again once the placement is over
        if (placer instanceof Player && !isGooBlock(event.getPlacedBlock())) return;
        BlockPos pos = event.getPos().immutable();
        // lean away from whoever placed it, with some variety
        double ang = level.getRandom().nextDouble() * Math.PI * 2.0;
        if (placer != null) {
            ang = Math.atan2(pos.getZ() + 0.5 - placer.getZ(), pos.getX() + 0.5 - placer.getX()) + (level.getRandom().nextDouble() - 0.5) * 1.9;
        }
        // swapped at the end of the tick, when the placement has fully gone through (or was cancelled)
        PLACED.add(new Placed(level, pos, (float) Math.cos(ang), (float) Math.sin(ang)));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PLACED.isEmpty()) return;
        List<Placed> batch = List.copyOf(PLACED);
        PLACED.clear();
        for (Placed p : batch) {
            if (p.level().getServer() == event.getServer() && enabled(p.level())) convert(p.level(), p.pos(), p.leanX(), p.leanZ());
        }
    }

    /**
     * Before a piston moves, every honey or slime block it would push or pull becomes goo instead.
     * The piston then simply extends or retracts through the freed space and, on the clients, its
     * head shoves and drags the goo.
     */
    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        Direction dir = event.getDirection();
        float lx = dir.getStepX(), lz = dir.getStepZ();
        if (!event.getPistonMoveType().isExtend) {
            // Only sticky pistons drag anything back, and only the block right in front of the head.
            // (The structure resolver is useless here: the head is still in the way at this point.)
            if (!event.getState().is(Blocks.STICKY_PISTON)) return;
            BlockPos front = event.getPos().relative(dir, 2);
            for (GooEntity goo : gooIn(level, new AABB(front))) goo.shift(dir.getOpposite(), false);
            squish(level, front, convertToGoo(level, front, -lx, -lz), dir.getOpposite());
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        // goo already lying where the head is about to be goes with it
        for (GooEntity goo : gooIn(level, new AABB(event.getPos().relative(dir)))) goo.shift(dir, false);
        for (BlockPos pos : List.copyOf(resolver.getToPush())) squish(level, pos, convertToGoo(level, pos, lx, lz), dir);
    }

    /** A block a piston has just turned into goo: it ends up one block along, and it is heard. */
    private static void squish(ServerLevel level, BlockPos pos, GooEntity goo, Direction dir) {
        if (goo == null) return;
        goo.shift(dir, true);
        level.playSound(null, pos, goo.material() == GooSim.SLIME ? SoundEvents.SLIME_BLOCK_HIT : SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.BLOCKS, 0.9F, 0.9F);
    }
}
