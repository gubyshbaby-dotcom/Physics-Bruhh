package physicsbruhh;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * In-game check for CI: with {@code -Dphysicsbruhh.selftest=true} a dedicated server builds a few
 * piston contraptions next to real honey and slime blocks, checks that the mod turned them into
 * goo (or left them alone when the game rule is off), prints {@code SELFTEST PASS/FAIL} and stops.
 */
public final class SelfTest {
    private static final int Y = 200;
    private static int tick;
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    private SelfTest() {}

    public static void register() {
        if (!Boolean.getBoolean("physicsbruhh.selftest")) return;
        PhysicsBruhh.LOGGER.info("SELFTEST enabled");
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> run(e.getServer()));
    }

    private static void expect(String what, boolean ok) {
        checks++;
        PhysicsBruhh.LOGGER.info("SELFTEST {} {}", ok ? "ok  " : "BAD ", what);
        if (!ok) failures.add(what);
    }

    private static int goo(ServerLevel level, EntityType<GooEntity> type, BlockPos around) {
        return level.getEntities(type, new AABB(around).inflate(1.6), e -> true).size();
    }

    /** Health of the pig nearest to the given column, or -1 if there is none within three blocks. */
    private static float pigHealth(ServerLevel level, int x, int z) {
        float health = -1.0F; double best = 9.0;
        for (Entity e : level.getEntities((Entity) null, new AABB(x - 3, Y - 2, z - 3, x + 4, Y + 12, z + 4), e -> e instanceof LivingEntity && EntityType.getKey(e.getType()).getPath().equals("pig"))) {
            double d = (e.getX() - x - 0.5) * (e.getX() - x - 0.5) + (e.getZ() - z - 0.5) * (e.getZ() - z - 0.5);
            if (d < best) { best = d; health = ((LivingEntity) e).getHealth(); }
        }
        return health;
    }

    private static BlockState piston(boolean sticky) {
        return (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST);
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        tick++;
        try {
            if (tick == 1) {
                level.setChunkForced(0, 0, true);
                level.setChunkForced(-1, 0, true);
            } else if (tick == 60) {
                for (int x = -6; x <= 6; x++) for (int z = -1; z <= 13; z++) level.setBlock(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState(), 3);
                // A: direct conversion
                BlockPos a = new BlockPos(4, Y, 0);
                level.setBlock(a, Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                expect("honey block converts", GooEvents.convert(level, a, 1, 0));
                expect("converted block is air", level.getBlockState(a).isAir());
                expect("stone does not convert", !GooEvents.convert(level, a.below(), 1, 0));
                // B: piston pushes a slime block (z = 3)
                level.setBlock(new BlockPos(-1, Y, 3), Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                level.setBlock(new BlockPos(-2, Y, 3), piston(false), 3);
                level.setBlock(new BlockPos(-3, Y, 3), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                // C: sticky piston, extended first with nothing in front (z = 6)
                level.setBlock(new BlockPos(-2, Y, 6), piston(true), 3);
                level.setBlock(new BlockPos(-3, Y, 6), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            } else if (tick == 80) {
                expect("A: honey goo marker exists", goo(level, PhysicsBruhh.HONEY_GOO.get(), new BlockPos(4, Y, 0)) == 1);
                expect("B: piston extended", level.getBlockState(new BlockPos(-2, Y, 3)).getValue(PistonBaseBlock.EXTENDED));
                expect("B: slime block was not pushed as a block", !level.getBlockState(new BlockPos(0, Y, 3)).is(Blocks.SLIME_BLOCK));
                expect("B: slime became goo", goo(level, PhysicsBruhh.SLIME_GOO.get(), new BlockPos(-1, Y, 3)) == 1);
                expect("C: sticky piston extended", level.getBlockState(new BlockPos(-2, Y, 6)).getValue(PistonBaseBlock.EXTENDED));
                // C: honey in front of the extended sticky head, then retract
                level.setBlock(new BlockPos(0, Y, 6), Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                level.setBlock(new BlockPos(-3, Y, 6), Blocks.AIR.defaultBlockState(), 3);
                // F: a honey block placed the ordinary way (a player using the item on the floor)
                BlockPos floor = new BlockPos(4, Y - 1, 10);
                ItemStack stack = new ItemStack(Items.HONEY_BLOCK);
                stack.useOn(new UseOnContext(level, FakePlayerFactory.getMinecraft(level), InteractionHand.MAIN_HAND, stack,
                        new BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false)));
            } else if (tick == 100) {
                expect("C: sticky piston retracted", !level.getBlockState(new BlockPos(-2, Y, 6)).getValue(PistonBaseBlock.EXTENDED));
                expect("C: honey was not pulled as a block", !level.getBlockState(new BlockPos(-1, Y, 6)).is(Blocks.HONEY_BLOCK));
                expect("C: honey became goo", goo(level, PhysicsBruhh.HONEY_GOO.get(), new BlockPos(0, Y, 6)) == 1);
                expect("F: placed honey is not a block any more", level.getBlockState(new BlockPos(4, Y, 10)).isAir());
                expect("F: placed honey became goo", goo(level, PhysicsBruhh.HONEY_GOO.get(), new BlockPos(4, Y, 10)) == 1);
                // D: with the rule off, vanilla behaviour (z = 9)
                level.getGameRules().set(PhysicsBruhh.PHYSICAL_GOO.get(), false, server);
                level.setBlock(new BlockPos(-1, Y, 9), Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                level.setBlock(new BlockPos(-2, Y, 9), piston(false), 3);
                level.setBlock(new BlockPos(-3, Y, 9), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            } else if (tick == 120) {
                expect("D: rule off, slime block pushed as a block", level.getBlockState(new BlockPos(0, Y, 9)).is(Blocks.SLIME_BLOCK));
                expect("D: rule off, no goo", goo(level, PhysicsBruhh.SLIME_GOO.get(), new BlockPos(-1, Y, 9)) == 0);
                level.getGameRules().set(PhysicsBruhh.PHYSICAL_GOO.get(), true, server);
                // E: hitting goo gives the block back
                for (GooEntity g : level.getEntities(PhysicsBruhh.HONEY_GOO.get(), new AABB(new BlockPos(4, Y, 0)).inflate(1.6), e -> true)) {
                    g.hurtServer(level, level.damageSources().generic(), 1.0F);
                }
            } else if (tick == 125) {
                expect("E: punched goo is gone", goo(level, PhysicsBruhh.HONEY_GOO.get(), new BlockPos(4, Y, 0)) == 0);
                // G: the clear command, first with a radius that only reaches the slime of test B, then everywhere
                int before = level.getEntities(PhysicsBruhh.HONEY_GOO.get(), e -> true).size() + level.getEntities(PhysicsBruhh.SLIME_GOO.get(), e -> true).size();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPosition(new Vec3(-0.5, Y, 3.5)).withSuppressedOutput(), "goo clear 2");
                expect("G: /goo clear 2 removed the goo next to it", goo(level, PhysicsBruhh.SLIME_GOO.get(), new BlockPos(-1, Y, 3)) == 0);
                int after = level.getEntities(PhysicsBruhh.HONEY_GOO.get(), e -> true).size() + level.getEntities(PhysicsBruhh.SLIME_GOO.get(), e -> true).size();
                expect("G: ... and left the rest (" + before + " -> " + after + ")", after > 0 && after < before);
            } else if (tick == 127) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "goo clear");
            } else if (tick == 130) {
                int left = level.getEntities(PhysicsBruhh.HONEY_GOO.get(), e -> true).size() + level.getEntities(PhysicsBruhh.SLIME_GOO.get(), e -> true).size();
                expect("G: /goo clear removed everything", left == 0);
                // leave something for the client test that opens this world next
                level.setBlock(new BlockPos(-1, Y, 3), Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                GooEvents.convert(level, new BlockPos(-1, Y, 3), 1, 0);
                level.setBlock(new BlockPos(4, Y, 10), Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                GooEvents.convert(level, new BlockPos(4, Y, 10), 1, 0);
                // H: what the original blocks do to things that land on them. Three pigs fall ten blocks:
                // onto slime goo, onto honey goo and onto bare stone.
                for (String at : new String[] { "-0.5 " + (Y + 10) + " 3.5", "4.5 " + (Y + 10) + " 10.5", "-4.5 " + (Y + 10) + " 11.5" }) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "summon minecraft:pig " + at);
                }
            } else if (tick == 200) {
                float onSlime = pigHealth(level, -1, 3), onHoney = pigHealth(level, 4, 10), onStone = pigHealth(level, -5, 11);
                expect("H: a pig that fell on stone got hurt (health " + onStone + ")", onStone > 0.0F && onStone <= 4.5F);
                expect("H: a pig that fell on slime goo did not (health " + onSlime + ")", onSlime >= 9.5F);
                expect("H: a pig that fell on honey goo was cushioned (health " + onHoney + ")", onHoney >= 7.5F && onHoney < 10.0F);
                finish(server);
            }
        } catch (Throwable t) {
            PhysicsBruhh.LOGGER.error("SELFTEST crashed", t);
            failures.add("exception: " + t);
            finish(server);
        }
    }

    private static void finish(MinecraftServer server) {
        if (failures.isEmpty()) PhysicsBruhh.LOGGER.info("SELFTEST PASS ({} checks)", checks);
        else PhysicsBruhh.LOGGER.error("SELFTEST FAIL ({} of {}): {}", failures.size(), checks, failures);
        tick = Integer.MIN_VALUE / 2;
        server.halt(false);
    }
}
