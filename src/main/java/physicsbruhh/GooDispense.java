package physicsbruhh;

import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import physicsbruhh.sim.GooSim;

/**
 * What dispensers do with honey and slime while {@code physical_goo} is on:
 * <ul>
 * <li>a honey bottle is poured out as a stream of honey, the empty bottle stays in the dispenser;</li>
 * <li>a slime ball is shot out as a lump of slime;</li>
 * <li>a honey or slime block comes out as a whole block's worth of goo in front of the dispenser;</li>
 * <li>an empty bottle is filled from honey lying in front of the dispenser.</li>
 * </ul>
 * Whenever none of that applies (the rule is off, there is no room, no honey to bottle) the item is
 * dispensed the way it was before.
 */
public final class GooDispense {
    private GooDispense() {}

    /** One of ours, tried first; the behaviour that was registered before stays as the fallback. */
    private record Wrapped(DispenseItemBehavior before, Own own) implements DispenseItemBehavior {
        @Override
        public ItemStack dispense(BlockSource source, ItemStack stack) {
            if (GooEvents.enabled(source.level()) && own.applies.test(source, stack)) return own.dispense(source, stack);
            return before.dispense(source, stack);
        }
    }

    private abstract static class Own extends DefaultDispenseItemBehavior {
        final BiPredicate<BlockSource, ItemStack> applies;

        Own(BiPredicate<BlockSource, ItemStack> applies) { this.applies = applies; }

        /** Hands {@link DefaultDispenseItemBehavior}'s way of leaving something behind in the dispenser to the subclasses. */
        ItemStack leave(BlockSource source, ItemStack dispensed, ItemStack remainder) {
            return this.consumeWithRemainder(source, dispensed, remainder);
        }
    }

    private static Vec3 step(Direction direction, double length) {
        return new Vec3(direction.getStepX() * length, direction.getStepY() * length, direction.getStepZ() * length);
    }

    private static void wrap(Item item, Own own) {
        DispenseItemBehavior before = DispenserBlock.DISPENSER_REGISTRY.get(item);
        DispenserBlock.registerBehavior(item, new Wrapped(before != null ? before : new DefaultDispenseItemBehavior(), own));
    }

    /** The honey in front of a dispenser that an empty bottle could be filled from, or null. */
    private static GooEntity honeyInFront(BlockSource source) {
        BlockPos front = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
        AABB cell = new AABB(front).inflate(0.2);
        for (GooEntity goo : GooEvents.gooIn(source.level(), cell.inflate(2.0))) {
            if (goo.material() == GooSim.HONEY && !goo.isRemoved() && goo.amount() >= GooSim.BOTTLE / 2 && goo.reach().intersects(cell)) return goo;
        }
        return null;
    }

    private static boolean frontIsFree(BlockSource source) {
        BlockPos front = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
        return source.level().getBlockState(front).canBeReplaced();
    }

    public static void register() {
        wrap(Items.HONEY_BOTTLE, new Own((source, stack) -> true) {
            @Override
            protected ItemStack execute(BlockSource source, ItemStack stack) {
                ServerLevel level = source.level();
                Direction facing = source.state().getValue(DispenserBlock.FACING);
                Vec3 from = source.center().add(step(facing, 0.66));
                // honey does not shoot out, it pours: a slow push out of the front, a little upwards unless it is poured straight down
                Vec3 velocity = step(facing, 2.3).add(0.0, facing.getAxis() == Direction.Axis.Y ? 0.0 : 0.5, 0.0);
                Vec3 target = source.center().add(step(facing, 1.6));
                if (GooEvents.pour(level, GooSim.HONEY, from, velocity, target, GooSim.BOTTLE, false) == 0) return stack;
                level.playSound(null, source.pos(), SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.BLOCKS, 0.8F, 0.9F);
                return leave(source, stack, new ItemStack(Items.GLASS_BOTTLE));
            }
        });
        wrap(Items.SLIME_BALL, new Own((source, stack) -> true) {
            @Override
            protected ItemStack execute(BlockSource source, ItemStack stack) {
                ServerLevel level = source.level();
                Direction facing = source.state().getValue(DispenserBlock.FACING);
                Vec3 from = source.center().add(step(facing, 0.92));
                Vec3 velocity = step(facing, 5.5).add(0.0, facing.getAxis() == Direction.Axis.Y ? 0.0 : 1.6, 0.0);
                if (GooEvents.pour(level, GooSim.SLIME, from, velocity, GooEvents.landing(level, null, from, velocity), GooSim.BALL, true) == 0) return stack;
                level.playSound(null, source.pos(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
                stack.shrink(1);
                return stack;
            }
        });
        for (Item block : new Item[] { Items.HONEY_BLOCK, Items.SLIME_BLOCK }) {
            final int material = block == Items.SLIME_BLOCK ? GooSim.SLIME : GooSim.HONEY;
            wrap(block, new Own((source, stack) -> frontIsFree(source)) {
                @Override
                protected ItemStack execute(BlockSource source, ItemStack stack) {
                    Direction facing = source.state().getValue(DispenserBlock.FACING);
                    BlockPos front = source.pos().relative(facing);
                    GooEvents.spawnBlock(source.level(), front, material, facing.getStepX(), facing.getStepZ());
                    source.level().playSound(null, front, material == GooSim.SLIME ? SoundEvents.SLIME_BLOCK_PLACE : SoundEvents.HONEY_BLOCK_PLACE, SoundSource.BLOCKS, 0.9F, 0.9F);
                    stack.shrink(1);
                    return stack;
                }
            });
        }
        wrap(Items.GLASS_BOTTLE, new Own((source, stack) -> honeyInFront(source) != null) {
            @Override
            protected ItemStack execute(BlockSource source, ItemStack stack) {
                GooEntity goo = honeyInFront(source);
                if (goo == null) return stack;
                Direction facing = source.state().getValue(DispenserBlock.FACING);
                Vec3 at = source.center().add(step(facing, 0.9)).add(0.0, -0.3, 0.0);
                goo.scoop(at, GooSim.BOTTLE);
                source.level().playSound(null, source.pos(), SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.9F, 1.0F);
                return leave(source, stack, new ItemStack(Items.HONEY_BOTTLE));
            }
        });
    }
}
