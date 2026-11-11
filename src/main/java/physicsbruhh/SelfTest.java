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
import physicsbruhh.sim.GooSim;

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

    private static int blastsBefore;

    private static void run(MinecraftServer server, String... commands) {
        for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** The goo of one kind nearest to a column, within two blocks; null if there is none. */
    private static GooEntity nearest(ServerLevel level, EntityType<GooEntity> type, double x, double z) {
        GooEntity best = null; double bestDistance = 4.0;
        for (GooEntity goo : level.getEntities(type, new AABB(x - 2, Y - 2, z - 2, x + 2, Y + 3, z + 2), e -> !e.isRemoved())) {
            double d = (goo.getX() - x) * (goo.getX() - x) + (goo.getZ() - z) * (goo.getZ() - z);
            if (d < bestDistance) { bestDistance = d; best = goo; }
        }
        return best;
    }

    /** How many of an item the dispenser at a place holds. */
    private static int held(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container)) return -1;
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) count += container.getItem(i).getCount();
        return count;
    }

    private static boolean honeyUse(GooEntity goo, net.minecraft.world.entity.player.Player player, ItemStack with) {
        player.setItemInHand(InteractionHand.MAIN_HAND, with);
        return goo.interact(player, InteractionHand.MAIN_HAND, new Vec3(0.0, 0.4, 0.0)).consumesAction();
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
                // I: dispensers. A honey bottle is poured out, a slime ball is shot out, a slime block comes out as goo.
                run(server, "kill @e[type=minecraft:pig]", "goo clear",
                        "setblock 1 " + Y + " 0 minecraft:dispenser[facing=east]{Items:[{Slot:0b,id:\"minecraft:honey_bottle\",count:1}]}",
                        "setblock 0 " + Y + " 0 minecraft:redstone_block",
                        "setblock 1 " + Y + " 12 minecraft:dispenser[facing=east]{Items:[{Slot:0b,id:\"minecraft:slime_ball\",count:2}]}",
                        "setblock 0 " + Y + " 12 minecraft:redstone_block",
                        "setblock -5 " + Y + " 0 minecraft:dispenser[facing=east]{Items:[{Slot:0b,id:\"minecraft:slime_block\",count:1}]}",
                        "setblock -6 " + Y + " 0 minecraft:redstone_block");
            } else if (tick == 215) {
                GooEntity honey = nearest(level, PhysicsBruhh.HONEY_GOO.get(), 3.5, 0.5);
                expect("I: a dispensed honey bottle became a stream of honey (" + (honey == null ? "nothing" : honey.amount() + " particles") + ")",
                        honey != null && honey.poured() && honey.amount() == GooSim.BOTTLE && honey.push().x() > 1.0F);
                expect("I: ... and left its bottle in the dispenser", held(level, new BlockPos(1, Y, 0), Items.GLASS_BOTTLE) == 1 && held(level, new BlockPos(1, Y, 0), Items.HONEY_BOTTLE) == 0);
                GooEntity lump = nearest(level, PhysicsBruhh.SLIME_GOO.get(), 4.5, 12.5);
                expect("I: a dispensed slime ball became a lump of slime (" + (lump == null ? "nothing" : lump.amount() + " particles at x " + lump.getX()) + ")",
                        lump != null && lump.poured() && lump.lump() && lump.amount() == GooSim.BALL && lump.getX() > 2.5);
                expect("I: ... one ball at a time", held(level, new BlockPos(1, Y, 12), Items.SLIME_BALL) == 1);
                GooEntity block = nearest(level, PhysicsBruhh.SLIME_GOO.get(), -3.5, 0.5);
                expect("I: a dispensed slime block became a block of goo", block != null && !block.poured() && block.amount() == GooSim.BLOCK && held(level, new BlockPos(-5, Y, 0), Items.SLIME_BLOCK) == 0);
                // an empty bottle in a dispenser facing that honey takes it up again
                run(server, "setblock 5 " + Y + " 0 minecraft:dispenser[facing=west]{Items:[{Slot:0b,id:\"minecraft:glass_bottle\",count:1}]}",
                        "setblock 6 " + Y + " 0 minecraft:redstone_block",
                        // a second ball out of the other one: it joins the lump that is already lying there
                        "setblock 0 " + Y + " 12 minecraft:air");
            } else if (tick == 218) {
                run(server, "setblock 0 " + Y + " 12 minecraft:redstone_block");
            } else if (tick == 230) {
                expect("I: a dispensed empty bottle was filled from the honey in front of it", held(level, new BlockPos(5, Y, 0), Items.HONEY_BOTTLE) == 1);
                expect("I: ... and the honey is gone", nearest(level, PhysicsBruhh.HONEY_GOO.get(), 3.5, 0.5) == null);
                GooEntity lump = nearest(level, PhysicsBruhh.SLIME_GOO.get(), 4.5, 12.5);
                expect("I: a second slime ball joined the first lump (" + (lump == null ? "nothing" : lump.amount() + " particles") + ")", lump != null && lump.amount() == 2 * GooSim.BALL);
                // J: a player's hands. Bottle on honey, bare hand on slime, slime ball on slime, honey bottle poured while sneaking, slime ball thrown.
                BlockPos h = new BlockPos(4, Y, 6), sl = new BlockPos(-4, Y, 6);
                level.setBlock(h, Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                level.setBlock(sl, Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                GooEntity honey = GooEvents.convertToGoo(level, h, 1, 0), slime = GooEvents.convertToGoo(level, sl, 1, 0);
                var player = FakePlayerFactory.getMinecraft(level);
                player.setPos(4.5, Y, 4.5);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                boolean used = honey.interact(player, InteractionHand.MAIN_HAND, new Vec3(0.0, 0.4, 0.0)).consumesAction();
                expect("J: an empty bottle is filled from honey goo", used && player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.HONEY_BOTTLE) && honey.amount() == GooSim.BLOCK - GooSim.BOTTLE);
                used = honey.interact(player, InteractionHand.MAIN_HAND, new Vec3(0.0, 0.4, 0.0)).consumesAction();
                expect("J: a honey bottle is poured back into it", used && player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.GLASS_BOTTLE) && honey.amount() == GooSim.BLOCK);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.getInventory().clearContent();
                used = slime.interact(player, InteractionHand.MAIN_HAND, new Vec3(0.0, 0.5, 0.0)).consumesAction();
                expect("J: a bare hand tears a slime ball off slime goo", used && slime.amount() == GooSim.BLOCK - GooSim.BALL && player.getInventory().countItem(Items.SLIME_BALL) == 1);
                player.getInventory().clearContent();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SLIME_BALL));
                used = slime.interact(player, InteractionHand.MAIN_HAND, new Vec3(0.0, 0.5, 0.0)).consumesAction();
                expect("J: a slime ball is pressed back on", used && slime.amount() == GooSim.BLOCK && player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty());
                expect("J: a stick does nothing to goo", !honeyUse(honey, player, new ItemStack(Items.STICK)));
                // pouring and throwing, at the floor two blocks ahead
                player.setPos(0.5, Y, 8.5);
                player.setYRot(-90.0F);
                player.setXRot(35.0F);
                player.setShiftKeyDown(true);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HONEY_BOTTLE));
                var pour = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
                NeoForge.EVENT_BUS.post(pour);
                GooEntity poured = nearest(level, PhysicsBruhh.HONEY_GOO.get(), 2.5, 8.5);
                expect("J: a honey bottle used while sneaking is poured out (" + (poured == null ? "nothing" : poured.amount() + " particles at x " + poured.getX()) + ")",
                        pour.isCanceled() && poured != null && poured.poured() && poured.amount() == GooSim.BOTTLE && player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.GLASS_BOTTLE));
                player.setShiftKeyDown(false);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HONEY_BOTTLE));
                var drink = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
                NeoForge.EVENT_BUS.post(drink);
                expect("J: without sneaking the honey bottle is left alone (to be drunk)", !drink.isCanceled() && player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.HONEY_BOTTLE));
                player.setPos(0.5, Y, 10.5);
                player.setXRot(10.0F);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SLIME_BALL, 3));
                var toss = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
                NeoForge.EVENT_BUS.post(toss);
                GooEntity thrown = nearest(level, PhysicsBruhh.SLIME_GOO.get(), 4.5, 10.5);
                expect("J: a slime ball is thrown as a lump (" + (thrown == null ? "nothing" : thrown.amount() + " particles at x " + thrown.getX()) + ")",
                        toss.isCanceled() && thrown != null && thrown.lump() && thrown.amount() == GooSim.BALL && thrown.getX() > 2.0 && player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2);
                // K: punching less than a block of slime gives slime balls
                thrown.hurtServer(level, level.damageSources().generic(), 1.0F);
            } else if (tick == 234) {
                int balls = 0;
                for (Entity e : level.getEntities((Entity) null, new AABB(-6, Y - 1, -1, 7, Y + 3, 14), e -> e instanceof net.minecraft.world.entity.item.ItemEntity)) {
                    ItemStack stack = ((net.minecraft.world.entity.item.ItemEntity) e).getItem();
                    if (stack.is(Items.SLIME_BALL)) balls += stack.getCount();
                }
                expect("K: a punched lump of slime came back as a slime ball (" + balls + ")", balls == 1);
                // L: how thick the goo will be stands on the block's item, is turned with the wheel, and goes with the block
                run(server, "goo clear", "kill @e[type=minecraft:item]");
                var player = FakePlayerFactory.getMinecraft(level);
                player.setShiftKeyDown(false);
                player.setPos(2.5, Y, 4.5);
                ItemStack honeyBlock = new ItemStack(Items.HONEY_BLOCK, 3);
                player.setItemInHand(InteractionHand.MAIN_HAND, honeyBlock);
                expect("L: a block of honey is of the usual thickness to begin with", GooThickness.of(honeyBlock) == 1.0F && honeyBlock.get(PhysicsBruhh.THICKNESS.get()) == null);
                float turned = GooThickness.turn(player, 4);
                expect("L: four notches of the wheel make it four times as thick (" + turned + ")", turned == 4.0F && GooThickness.of(player.getItemInHand(InteractionHand.MAIN_HAND)) == 4.0F);
                expect("L: ... and no further than that", GooThickness.turn(player, 3) == 4.0F);
                GooThickness.turn(player, -4);
                expect("L: turned back, the stack is an ordinary one again", player.getItemInHand(InteractionHand.MAIN_HAND).get(PhysicsBruhh.THICKNESS.get()) == null);
                GooThickness.turn(player, -8);
                expect("L: the other way it stops at a quarter", GooThickness.of(player.getItemInHand(InteractionHand.MAIN_HAND)) == 0.25F);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                expect("L: a stick has no thickness to turn", GooThickness.turn(player, 1) == 0.0F);
                // placed by hand: the goo is as thick as the item said
                ItemStack thickHoney = new ItemStack(Items.HONEY_BLOCK, 2);
                GooThickness.set(thickHoney, 4.0F);
                player.setItemInHand(InteractionHand.MAIN_HAND, thickHoney);
                BlockPos floorUnder = new BlockPos(2, Y - 1, 6);
                var placed = thickHoney.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(new Vec3(2.5, Y, 6.5), Direction.UP, floorUnder, false)));
                expect("L: the block was placed (" + placed + ", " + thickHoney.getCount() + " left)", placed.consumesAction() && thickHoney.getCount() == 1);
                // out of a dispenser: the same
                run(server, "setblock -6 " + Y + " 0 minecraft:air", "setblock -5 " + Y + " 0 minecraft:air",
                        "setblock -5 " + Y + " 0 minecraft:dispenser[facing=east]{Items:[{Slot:0b,id:\"minecraft:slime_block\",count:1,components:{\"physicsbruhh:thickness\":0.5f}}]}",
                        "setblock -6 " + Y + " 0 minecraft:redstone_block");
            } else if (tick == 244) {
                GooEntity honey = nearest(level, PhysicsBruhh.HONEY_GOO.get(), 2.5, 6.5);
                expect("L: honey placed from a stack turned thick is thick goo (" + (honey == null ? "nothing" : "thickness " + honey.thick()) + ")", honey != null && honey.thick() == 4.0F && !honey.poured());
                GooEntity slime = nearest(level, PhysicsBruhh.SLIME_GOO.get(), -3.5, 0.5);
                expect("L: a thin slime block out of a dispenser is thin goo (" + (slime == null ? "nothing" : "thickness " + slime.thick()) + ")", slime != null && slime.thick() == 0.5F);
                if (honey != null) honey.hurtServer(level, level.damageSources().generic(), 1.0F);
            } else if (tick == 248) {
                float back = -1.0F;
                for (Entity e : level.getEntities((Entity) null, new AABB(-6, Y - 1, -1, 7, Y + 3, 14), e -> e instanceof net.minecraft.world.entity.item.ItemEntity)) {
                    ItemStack stack = ((net.minecraft.world.entity.item.ItemEntity) e).getItem();
                    if (stack.is(Items.HONEY_BLOCK)) back = GooThickness.of(stack);
                }
                expect("L: punched, thick goo comes back as a block that is still thick (" + back + ")", back == 4.0F);
                // tidy up, and leave something for the client test that opens this world next
                run(server, "goo clear", "kill @e[type=minecraft:item]",
                        "setblock 1 " + Y + " 0 minecraft:air", "setblock 0 " + Y + " 0 minecraft:air", "setblock 1 " + Y + " 12 minecraft:air", "setblock 0 " + Y + " 12 minecraft:air",
                        "setblock -5 " + Y + " 0 minecraft:air", "setblock -6 " + Y + " 0 minecraft:air", "setblock 5 " + Y + " 0 minecraft:air", "setblock 6 " + Y + " 0 minecraft:air");
                level.setBlock(new BlockPos(-1, Y, 3), Blocks.SLIME_BLOCK.defaultBlockState(), 3);
                GooEvents.convert(level, new BlockPos(-1, Y, 3), 1, 0);
                level.setBlock(new BlockPos(4, Y, 10), Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                GooEvents.convert(level, new BlockPos(4, Y, 10), 1, 0);
                // M: smoke and fire. The server only passes on the game rule and where things explode.
                expect("M: fire and smoke are gas unless the game rule says otherwise", level.getGameRules().get(PhysicsBruhh.PHYSICAL_GAS.get()));
                blastsBefore = GasRules.blastsTold;
                run(server, "summon minecraft:tnt 0.5 " + (Y + 14) + " 6.5 {fuse:0}");
            } else if (tick == 252) {
                expect("M: an explosion is told to the clients near it (" + (GasRules.blastsTold - blastsBefore) + ")", GasRules.blastsTold == blastsBefore + 1);
                run(server, "gamerule physicsbruhh:physical_gas false", "summon minecraft:tnt 0.5 " + (Y + 14) + " 6.5 {fuse:0}");
            } else if (tick == 256) {
                expect("M: ... and not with the game rule off (" + (GasRules.blastsTold - blastsBefore) + ")", GasRules.blastsTold == blastsBefore + 1);
                run(server, "gamerule physicsbruhh:physical_gas true");
            } else if (tick == 258) {
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
