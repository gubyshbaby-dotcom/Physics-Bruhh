package physicsbruhh;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

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
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.addFreshEntity(goo);
        return true;
    }

    /** A placed honey or slime block melts / comes alive right away. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        if (!isGooBlock(event.getPlacedBlock())) return;
        BlockPos pos = event.getPos().immutable();
        Entity placer = event.getEntity();
        // lean away from whoever placed it, with some variety
        double ang = level.getRandom().nextDouble() * Math.PI * 2.0;
        if (placer != null) {
            ang = Math.atan2(pos.getZ() + 0.5 - placer.getZ(), pos.getX() + 0.5 - placer.getX()) + (level.getRandom().nextDouble() - 0.5) * 1.9;
        }
        float lx = (float) Math.cos(ang), lz = (float) Math.sin(ang);
        // swap it once the placement has fully gone through (the event can still be cancelled by others)
        level.getServer().execute(() -> convert(level, pos, lx, lz));
    }

    /**
     * Before a piston moves, every honey or slime block it would push or pull becomes goo instead.
     * The piston then simply extends or retracts through the freed space and, on the clients, its
     * head shoves and drags the goo.
     */
    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        // only sticky pistons drag blocks back; a plain piston retracting leaves its neighbours alone
        if (!event.getPistonMoveType().isExtend && !event.getState().is(Blocks.STICKY_PISTON)) return;
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        List<BlockPos> moved = List.copyOf(resolver.getToPush());
        float lx = event.getDirection().getStepX(), lz = event.getDirection().getStepZ();
        if (!event.getPistonMoveType().isExtend) { lx = -lx; lz = -lz; }
        for (BlockPos pos : moved) convert(level, pos, lx, lz);
    }
}
