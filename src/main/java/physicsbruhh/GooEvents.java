package physicsbruhh;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        BlockState state = level.getBlockState(pos);
        if (!isGooBlock(state)) return false;
        GooEntity goo = new GooEntity(state.is(Blocks.SLIME_BLOCK) ? PhysicsBruhh.SLIME_GOO.get() : PhysicsBruhh.HONEY_GOO.get(), level);
        goo.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        goo.setLean(leanX, leanZ);
        goo.applyLook(level);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.addFreshEntity(goo);
        return true;
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
        float lx = event.getDirection().getStepX(), lz = event.getDirection().getStepZ();
        if (!event.getPistonMoveType().isExtend) {
            // Only sticky pistons drag anything back, and only the block right in front of the head.
            // (The structure resolver is useless here: the head is still in the way at this point.)
            if (event.getState().is(Blocks.STICKY_PISTON)) convert(level, event.getPos().relative(event.getDirection(), 2), -lx, -lz);
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (BlockPos pos : List.copyOf(resolver.getToPush())) convert(level, pos, lx, lz);
    }
}
