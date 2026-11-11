package physicsbruhh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import physicsbruhh.GooEntity;
import physicsbruhh.GooThickness;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import physicsbruhh.GooEvents;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GooSim;

/**
 * Client smoke test for CI: with {@code -Dphysicsbruhh.clienttest=true} and a world opened through
 * quick play, it builds a small stage (goo between two pairs of sticky pistons, goo in front of
 * plain pistons, free blocks), runs the pistons twice, first with the blocky look and then with
 * the clear one, while an outside script takes screenshots. Along the way one piece is removed while
 * others move, a pig is stood in a slime block, and the player is walked into a honey puddle and
 * dropped onto slime. It checks that the simulation ran without errors of the mod's own, its surface
 * went through the renderer, the slime stayed stretched between the retracted pistons and the player
 * felt both kinds of goo. Then, in a corner of its own, a dispenser pours out a honey bottle and
 * another shoots slime balls, and the player fills a bottle from the puddle, pours it out again
 * and throws a slime ball. Prints {@code CLIENTTEST PASS/FAIL} and closes the game.
 */
final class ClientSelfTest {
    private static final int Y = 200;
    private static int tick = -1;
    private static long firstTick;
    private static boolean done;
    private static float restingWidth = -1.0F, bandLeft = 99.0F, bandRight = -99.0F, bandWidest;
    private static boolean sawHoney, sawSlime, sawClear, waded, bounced, removedOne;
    private static int bouncesBefore;
    private static boolean liquidExpected, bottleFilled, pouredBack, thrown, wheelTurned, placedThick, thicknessShows;
    private static int wheelPassed, menuTicks;
    private static boolean openedWorld;
    private static String thicknessSeen = "not looked at";
    private static int liquidFrames, honeyPoured = -1, slimeShot = -1, honeyAfterBottle = -1, honeyAfterPour = -1, honeySimulated = -1;
    private static volatile int emptyBottles = -1;
    /** The hands part runs by what has happened, not by the clock: how far it is, since when, where the player should stand, and what it got stuck on. */
    private static int step, stepTick;
    private static double[] spot = { 19.0, 11.0 };
    private static String stuck;

    private static void step(int next) {
        step = next;
        stepTick = tick;
    }

    /** How much honey the markers around the dispensers' puddle say there is. */
    private static int honeyMarkers() {
        GooEntity honey = GooClient.gooNear(GooSim.HONEY, 20.5, 11.0);
        return honey == null ? 0 : honey.amount();
    }

    private static boolean standsAt(Minecraft mc, double x, double z) {
        return Math.abs(mc.player.getX() - x) < 0.3 && Math.abs(mc.player.getZ() - z) < 0.3;
    }

    /**
     * The player's hands on goo: fill a bottle from the puddle, pour it out again while sneaking,
     * throw a slime ball. Every step waits for what it needs (the server's answer may take any
     * number of client ticks when frames are slow) and gives up after ten seconds' worth of ticks.
     */
    private static void hands(Minecraft mc, MinecraftServer server) {
        var held = mc.player.getMainHandItem();
        boolean late = tick > stepTick + 200;
        switch (step) {
            case 1 -> { // by the puddle with an empty bottle: use it on the honey
                if (tick > stepTick + 5 && held.is(Items.GLASS_BOTTLE) && standsAt(mc, spot[0], spot[1]) && useOnGoo(mc)) step(2);
                else if (late) { stuck = "the player never got to use an empty bottle on the honey puddle"; step(8); }
            }
            case 2 -> { // the bottle comes back full, and the puddle is a bottle short
                if (held.is(Items.HONEY_BOTTLE) && honeyMarkers() == GooSim.BOTTLE) {
                    bottleFilled = true;
                    honeyAfterBottle = honeyMarkers();
                    PhysicsBruhh.LOGGER.info("CLIENTTEST bottle filled from the puddle (the markers now say {} particles)", honeyAfterBottle);
                    stage("hands: the honey bottle poured out while sneaking");
                    mc.options.keyShift.setDown(true);
                    step(3);
                } else if (late) { honeyAfterBottle = honeyMarkers(); bottleFilled = held.is(Items.HONEY_BOTTLE); stuck = "an empty bottle used on the honey puddle was not filled, or took no honey (in hand: " + held + ", markers: " + honeyAfterBottle + ")"; step(8); }
            }
            case 3 -> { // sneaking by now: use the honey bottle
                if (tick >= stepTick + 8) { mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); step(4); }
            }
            case 4 -> { // the bottle is empty again and the honey is back
                if (held.is(Items.GLASS_BOTTLE) && honeyMarkers() == 2 * GooSim.BOTTLE) {
                    pouredBack = true;
                    honeyAfterPour = honeyMarkers();
                    mc.options.keyShift.setDown(false);
                    PhysicsBruhh.LOGGER.info("CLIENTTEST honey bottle poured out (the markers now say {} particles)", honeyAfterPour);
                    stage("hands: a slime ball thrown");
                    commands(server, "tp @a 18.0 200 12.5 -135 5", "item replace entity @a weapon.mainhand with minecraft:slime_ball 2");
                    step(5);
                } else if (late) { mc.options.keyShift.setDown(false); honeyAfterPour = honeyMarkers(); stuck = "a honey bottle used while sneaking was not poured out (in hand: " + held + ", markers: " + honeyAfterPour + ")"; step(8); }
            }
            case 5 -> { // back at the edge with two slime balls: throw one
                if (tick > stepTick + 5 && held.is(Items.SLIME_BALL) && held.getCount() == 2 && standsAt(mc, 18.0, 12.5)) { mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); step(6); }
                else if (late) { stuck = "the player never got to throw a slime ball"; step(8); }
            }
            case 6 -> {
                if (held.is(Items.SLIME_BALL) && held.getCount() == 1) { thrown = true; step(7); }
                else if (late) { stuck = "a slime ball was not thrown (in hand: " + held + ")"; step(8); }
            }
            case 7 -> { // let it fly and land, and the poured honey arrive in the simulation
                if (tick >= stepTick + 50) {
                    honeySimulated = GooClient.amountNear(GooSim.HONEY, 20.5, 11.0);
                    PhysicsBruhh.LOGGER.info("CLIENTTEST slime ball thrown; simulated honey by the dispensers: {} particles ({})", honeySimulated, GooClient.describe());
                    step(8);
                }
            }
            default -> {
                // ---- thickness: the wheel, a block placed by hand, and thin, usual and thick goo side by side
                stage("thickness: the wheel on a block of honey");
                commands(server, "goo clear", "gamemode creative @a", "clear @a", "item replace entity @a weapon.mainhand with minecraft:honey_block 2", "tp @a 12.5 200 9.0 0 60");
                step(20);
            }
        }
    }

    /** Sneak, turn the wheel four notches on a block of honey, place it, and watch thin, usual and thick goo next to each other. */
    private static void thickness(Minecraft mc, MinecraftServer server) {
        var held = mc.player.getMainHandItem();
        boolean late = tick > stepTick + 200;
        switch (step) {
            case 20 -> { // at the place, block in hand: sneak
                if (tick > stepTick + 5 && held.is(Items.HONEY_BLOCK) && standsAt(mc, 12.5, 9.0)) { mc.options.keyShift.setDown(true); step(21); }
                else if (late) { stuck = "the player never got a block of honey in hand"; step(27); }
            }
            case 21 -> { // sneaking: four notches up
                if (mc.player.isShiftKeyDown()) {
                    int passed = 0;
                    for (int k = 0; k < 4; k++) if (GooWheel.turn(mc, 1)) passed++;
                    wheelPassed = passed;
                    step(22);
                } else if (late) { stuck = "the player never sneaked"; step(27); }
            }
            case 22 -> { // the server's answer: the stack in hand is four times as thick
                if (GooThickness.of(held) == 4.0F) {
                    wheelTurned = true;
                    PhysicsBruhh.LOGGER.info("CLIENTTEST wheel: the block in hand is now of thickness {}", GooThickness.of(held));
                    stage("thickness: the thick block placed by hand");
                    mc.options.keyShift.setDown(false);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(new Vec3(12.5, 200.0, 11.5), net.minecraft.core.Direction.UP, new BlockPos(12, 199, 11), false));
                    step(23);
                } else if (late) { mc.options.keyShift.setDown(false); stuck = "the wheel did not make the block in hand thicker (" + wheelPassed + " turns passed on, thickness " + GooThickness.of(held) + ")"; step(27); }
            }
            case 23 -> { // the goo it turned into is as thick
                GooEntity goo = GooClient.gooNear(GooSim.HONEY, 12.5, 11.5);
                if (goo != null && goo.thick() == 4.0F) {
                    placedThick = true;
                    stage("thickness: thin, usual and thick honey; thin and thick slime");
                    server.execute(() -> {
                        var level = server.overworld();
                        physicsbruhh.GooEvents.spawnBlock(level, new BlockPos(8, 200, 11), GooSim.HONEY, -1.0F, 0.0F, 0.25F);
                        physicsbruhh.GooEvents.spawnBlock(level, new BlockPos(10, 200, 11), GooSim.HONEY, -1.0F, 0.0F, 1.0F);
                        physicsbruhh.GooEvents.spawnBlock(level, new BlockPos(8, 200, 8), GooSim.SLIME, 0.0F, 1.0F, 0.25F);
                        physicsbruhh.GooEvents.spawnBlock(level, new BlockPos(10, 200, 8), GooSim.SLIME, 0.0F, 1.0F, 4.0F);
                    });
                    commands(server, "tp @a 14.5 202 9.5 84 36");
                    step(24);
                } else if (late) { stuck = "a block of honey turned thick did not become thick goo (" + (goo == null ? "no goo there" : "thickness " + goo.thick()) + ")"; step(27); }
            }
            case 24 -> { // eight seconds: the thin one has run out, the thick one is still a heap
                if (tick >= stepTick + 170) {
                    double[] thin = GooClient.sizeNear(GooSim.HONEY, 8.5, 11.5), usual = GooClient.sizeNear(GooSim.HONEY, 10.5, 11.5), thick = GooClient.sizeNear(GooSim.HONEY, 12.5, 11.5);
                    double[] soft = GooClient.sizeNear(GooSim.SLIME, 8.5, 8.5), stiff = GooClient.sizeNear(GooSim.SLIME, 10.5, 8.5);
                    thicknessSeen = String.format(java.util.Locale.ROOT, "honey thin %s, usual %s, thick %s; slime thin %s, thick %s", size(thin), size(usual), size(thick), size(soft), size(stiff));
                    PhysicsBruhh.LOGGER.info("CLIENTTEST thickness: {}", thicknessSeen);
                    thicknessShows = thin != null && usual != null && thick != null && soft != null && stiff != null
                            && thin[0] > usual[0] + 0.2 && usual[0] > thick[0] + 0.05 && thin[1] < usual[1] - 0.05 && usual[1] < thick[1] - 0.1 && soft[1] < stiff[1] - 0.04;
                    // for the screenshots: the thick heap at the bottom right, behind the block in the hand
                    stage("goo behind the hand");
                    commands(server, "tp @a 14.5 202 9.5 22 34");
                    step(25);
                }
            }
            case 25 -> {
                if (tick >= stepTick + 30) step(27);
            }
            default -> step(9);
        }
    }

    private static String size(double[] s) {
        return s == null ? "(none)" : String.format(java.util.Locale.ROOT, "%.2f wide %.2f high", s[0], s[1]);
    }

    /** Right click with the main hand on whatever goo the player is looking at, the way the use key does it. */
    private static boolean useOnGoo(Minecraft mc) {
        Vec3 from = mc.player.getEyePosition(1.0F);
        Vec3 to = from.add(mc.player.getViewVector(1.0F).scale(mc.player.entityInteractionRange()));
        GooClient.Picked picked = GooClient.pickPoint(from, to, mc.player.entityInteractionRange());
        return picked != null && GooTouch.use(mc, mc.player, picked, InteractionHand.MAIN_HAND);
    }

    private ClientSelfTest() {}

    static void register() {
        if (!Boolean.getBoolean("physicsbruhh.clienttest")) return;
        PhysicsBruhh.LOGGER.info("CLIENTTEST enabled");
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> run());
    }

    private static void commands(MinecraftServer server, String... lines) {
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            for (String line : lines) server.getCommands().performPrefixedCommand(source, line);
        });
    }

    private static void goo(MinecraftServer server, int x, int z, String block, float leanX, float leanZ) {
        commands(server, "setblock " + x + " " + Y + " " + z + " minecraft:" + block);
        server.execute(() -> GooEvents.convert(server.overworld(), new BlockPos(x, Y, z), leanX, leanZ));
    }

    /** The row of the slime between the two sticky pistons. */
    private static final int BAND_Z = 6;

    private static String slime() {
        float[] span = GooClient.slimeSpan(BAND_Z + 0.5);
        return String.format(java.util.Locale.ROOT, "%.2f..%.2f", span[0], span[1]);
    }

    private static void stage(String what) {
        PhysicsBruhh.LOGGER.info("CLIENTTEST stage: {}; {} band={}", what, GooClient.describe(), slime());
    }

    private static String at(double[] p, double up) {
        return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", p[0], p[1] + up, p[2]);
    }

    private static void run() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (firstTick == 0L) firstTick = System.nanoTime();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            // NeoForge puts a screen of warnings about other mods' metadata (Iris has one) where --quickPlaySingleplayer
            // would have gone into the world: go there from that screen
            var screen = mc.gui.screen();
            if (++menuTicks > 60 && !openedWorld && screen != null && screen.getClass().getSimpleName().contains("LoadingError")) {
                openedWorld = true;
                PhysicsBruhh.LOGGER.info("CLIENTTEST a screen of mod loading warnings is in the way; opening the world from it");
                mc.createWorldOpenFlows().openWorld("selftest", () -> {});
            }
            if (System.nanoTime() - firstTick > 240_000_000_000L) finish(mc, "no world after four minutes");
            return;
        }
        tick++;
        try {
            if (tick == 0) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST world joined");
                commands(server,
                        "gamemode creative @a", "time set noon", "weather clear",
                        // the world comes from the server test: its pigs must not wander onto this stage
                        "kill @e[type=minecraft:pig]", "kill @e[type=minecraft:item]",
                        "fill 7 199 -3 23 199 12 minecraft:oak_planks",
                        "fill 14 200 9 14 201 9 minecraft:oak_planks",
                        "tp @a 14.5 202 9.5 180 30",
                        // two rows of sticky pistons facing each other, extended
                        "setblock 12 200 6 minecraft:sticky_piston[facing=east]", "setblock 11 200 6 minecraft:redstone_block",
                        "setblock 16 200 6 minecraft:sticky_piston[facing=west]", "setblock 17 200 6 minecraft:redstone_block",
                        "setblock 12 200 3 minecraft:sticky_piston[facing=east]", "setblock 11 200 3 minecraft:redstone_block",
                        "setblock 16 200 3 minecraft:sticky_piston[facing=west]", "setblock 17 200 3 minecraft:redstone_block",
                        // plain pistons, not powered yet; the slime in front of one is still an ordinary block
                        "setblock 11 200 0 minecraft:piston[facing=east]",
                        "setblock 18 200 0 minecraft:piston[facing=east]", "setblock 19 200 0 minecraft:slime_block");
            } else if (tick == 30) {
                // once more, now that everything around the stage is loaded
                commands(server, "kill @e[type=minecraft:pig]", "kill @e[type=minecraft:item]");
            } else if (tick == 40) {
                stage("goo placed");
                goo(server, 14, 6, "slime_block", 0, 1);
                goo(server, 14, 3, "honey_block", 0, 1);
                goo(server, 19, 5, "honey_block", 1, 0);
                goo(server, 19, 2, "slime_block", 1, 0);
            } else if (tick == 120) {
                stage("sticky pistons retract");
                commands(server, "setblock 11 200 6 minecraft:air", "setblock 17 200 6 minecraft:air",
                        "setblock 11 200 3 minecraft:air", "setblock 17 200 3 minecraft:air");
            } else if (tick == 165) {
                goo(server, 12, 0, "honey_block", 1, 0);
            } else if (tick == 175) {
                stage("plain pistons push");
                commands(server, "setblock 10 200 0 minecraft:redstone_block", "setblock 17 200 0 minecraft:redstone_block");
            } else if (tick == 230) {
                stage("clear look on");
                commands(server, "gamerule physicsbruhh:clear_goo true");
                goo(server, 10, 5, "honey_block", -1, 0);
                goo(server, 10, 2, "slime_block", -1, 0);
            } else if (tick == 250) {
                // one piece is taken away while the rest is still moving, and a pig stands in another
                stage("one piece removed, a pig in the slime");
                commands(server, "execute positioned 10.5 200 5.5 run goo clear 1.2", "summon minecraft:pig 10.5 200 2.5 {NoAI:1b,Silent:1b}");
            } else if (tick == 262) {
                removedOne = GooClient.centreNear(GooSim.HONEY, 10.5, 5.5) == null || Math.abs(GooClient.centreNear(GooSim.HONEY, 10.5, 5.5)[0] - 10.5) > 1.5;
            } else if (tick == 270) {
                stage("sticky pistons extend again");
                commands(server, "setblock 11 200 6 minecraft:redstone_block", "setblock 17 200 6 minecraft:redstone_block",
                        "setblock 11 200 3 minecraft:redstone_block", "setblock 17 200 3 minecraft:redstone_block");
            } else if (tick == 330) {
                stage("sticky pistons retract again");
                commands(server, "setblock 11 200 6 minecraft:air", "setblock 17 200 6 minecraft:air",
                        "setblock 11 200 3 minecraft:air", "setblock 17 200 3 minecraft:air");
            } else if (tick == 430) {
                stage("player wades into the honey puddle");
                double[] puddle = GooClient.centreNear(GooSim.HONEY, 19.5, 5.5);
                commands(server, "tp @a " + (puddle == null ? "19.5 200 5.5" : at(puddle, 0.0)) + " 180 30");
            } else if (tick == 450) {
                waded = GooTouch.inHoney;
                PhysicsBruhh.LOGGER.info("CLIENTTEST in honey: {} (contact {})", waded, GooClient.contact() & 0xFFFF);
                stage("player drops onto the slime");
                bouncesBefore = GooTouch.bounces;
                double[] jelly = GooClient.centreNear(GooSim.SLIME, 19.5, 2.5);
                commands(server, "tp @a " + (jelly == null ? "19.5 206 2.5" : at(jelly, 6.0)) + " 180 30");
            } else if (tick == 500) {
                bounced = GooTouch.bounces > bouncesBefore;
                PhysicsBruhh.LOGGER.info("CLIENTTEST bounced on slime: {} (contact {})", bounced, GooClient.contact() >>> 16);
                // ---- dispensers and hands, in a corner of their own
                stage("dispensers: honey bottles and slime balls");
                GooClient.watchAmounts = true;
                commands(server, "kill @e[type=minecraft:pig]", "goo clear", "gamemode survival @a", "clear @a",
                        "fill 20 200 8 23 203 12 minecraft:air",
                        // two dispensers with one honey bottle each (a dispenser picks a slot at random, and the empty bottle it keeps is a slot too)
                        "fill 22 200 10 22 200 11 minecraft:oak_planks",
                        "setblock 22 201 10 minecraft:dispenser[facing=west]{Items:[{Slot:0b,id:\"minecraft:honey_bottle\",count:1}]}",
                        "setblock 22 201 11 minecraft:dispenser[facing=west]{Items:[{Slot:0b,id:\"minecraft:honey_bottle\",count:1}]}",
                        "setblock 22 200 8 minecraft:dispenser[facing=west]{Items:[{Slot:0b,id:\"minecraft:slime_ball\",count:3}]}",
                        "tp @a 18.0 200 12.5 -135 11");
            } else if (tick == 515) {
                commands(server, "setblock 23 201 10 minecraft:redstone_block", "setblock 23 200 8 minecraft:redstone_block");
            } else if (tick == 535) {
                commands(server, "setblock 23 200 8 minecraft:air");
            } else if (tick == 545) {
                commands(server, "setblock 23 200 8 minecraft:redstone_block", "setblock 23 201 11 minecraft:redstone_block");
            } else if (tick == 600) {
                // by now both bottles have run out and both lumps have landed
                honeyPoured = GooClient.amountNear(GooSim.HONEY, 20.5, 11.0);
                slimeShot = GooClient.amountNear(GooSim.SLIME, 18.5, 8.5);
                // (a server level only hands out its block entities on its own thread)
                server.execute(() -> {
                    int bottles = 0;
                    for (int z = 10; z <= 11; z++) {
                        if (!(server.overworld().getBlockEntity(new BlockPos(22, 201, z)) instanceof net.minecraft.world.Container c)) continue;
                        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.GLASS_BOTTLE)) bottles += c.getItem(i).getCount();
                    }
                    emptyBottles = bottles;
                });
                PhysicsBruhh.LOGGER.info("CLIENTTEST dispensers: honey {} particles (markers say {}), slime {} particles", honeyPoured, honeyMarkers(), slimeShot);
                stage("hands: an empty bottle on the honey");
                double[] puddle = GooClient.centreNear(GooSim.HONEY, 20.5, 11.0);
                // stand next to the puddle and look down at its middle
                spot = puddle == null ? new double[] { 19.0, 11.0 } : new double[] { puddle[0] - 1.3, puddle[2] };
                commands(server, "tp @a " + String.format(java.util.Locale.ROOT, "%.2f 200 %.2f", spot[0], spot[1]) + " -90 48",
                        "item replace entity @a weapon.mainhand with minecraft:glass_bottle");
                step(1);
            } else if (step > 0 && step < 9) {
                hands(mc, server);
            } else if (step >= 20) {
                thickness(mc, server);
            } else if (step == 9 && tick == stepTick + 5) {
                stage("everything is cleared away");
                commands(server, "gamemode creative @a", "tp @a 14.5 202 9.5 180 30", "goo clear");
            } else if (tick > 0 && tick % 20 == 0 && step < 9) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST t={} {} band={} clear={} shader={} drawCalls={} liquid={} frames={} particles={} pack={} packDraws={}", tick, GooClient.describe(), slime(), GooClient.clearLook, GooRenderer.shader, GooRenderer.drawCalls,
                        GooFluid.inUse(), GooFluid.frames, GooClient.particleCount(), Packs.active, GooRenderer.packDraws);
            } else if (step == 9 && tick == stepTick + 45) {
                String state = GooClient.describe() + " restingWidth=" + restingWidth + " band=" + bandLeft + ".." + bandRight + " shader=" + GooRenderer.shader + " drawCalls=" + GooRenderer.drawCalls
                        + " liquid=" + GooFluid.inUse() + " liquidFrames=" + GooFluid.frames + " uses=" + GooTouch.uses;
                String problem = null;
                if (!GooClient.healthy()) problem = "simulation is not running";
                else if (!sawHoney || !sawSlime) problem = "no " + (sawHoney ? "slime" : "honey") + " surface";
                else if (GooRenderer.drawCalls == 0) problem = "the surface never reached the renderer";
                else if (restingWidth < 0.5F || restingWidth > 1.35F) problem = "slime did not sit still between the extended pistons (width " + restingWidth + ")";
                else if (bandLeft > 13.6F || bandRight < 15.4F) problem = "slime did not stay stretched between the retracted sticky pistons (" + bandLeft + ".." + bandRight + ")";
                else if (bandWidest > 3.5F) problem = "slime was flung apart (" + bandWidest + " blocks wide)";
                else if (!sawClear) problem = "the clear_goo game rule did not reach the client";
                else if (!removedOne) problem = "/goo clear with a radius did not remove the piece it was aimed at";
                else if (GooClient.failures != 0 || PhysicsBruhhClient.errors != 0) problem = "the mod caught errors of its own (" + GooClient.failures + " in the simulation tick, " + PhysicsBruhhClient.errors + " elsewhere); see the log";
                else if (!waded) problem = "standing in the honey puddle was not noticed";
                else if (!bounced) problem = "falling onto the slime did not bounce the player";
                else if (GooFluid.broken) problem = "the liquid look failed (see the log)";
                else if (liquidExpected && liquidFrames == 0) problem = "clear goo was never drawn as liquid";
                else if (honeyPoured < 2 * GooSim.BOTTLE - 60 || honeyPoured > 2 * GooSim.BOTTLE) problem = "the two dispensed honey bottles did not arrive as honey (" + honeyPoured + " particles of " + 2 * GooSim.BOTTLE + ")";
                else if (emptyBottles != 2) problem = "the dispensers did not keep their empty bottles (" + emptyBottles + ")";
                else if (stuck != null) problem = stuck;
                else if (slimeShot < 2 * GooSim.BALL - 30 || slimeShot > 2 * GooSim.BALL) problem = "the two dispensed slime balls did not arrive as slime (" + slimeShot + " particles of " + 2 * GooSim.BALL + ")";
                else if (!bottleFilled) problem = "an empty bottle used on the honey puddle was not filled";
                else if (honeyAfterBottle != GooSim.BOTTLE) problem = "filling a bottle did not take a bottle's worth of honey out of the puddle (" + 2 * GooSim.BOTTLE + " -> " + honeyAfterBottle + ")";
                else if (!pouredBack || honeyAfterPour != 2 * GooSim.BOTTLE) problem = "a honey bottle used while sneaking was not poured out (" + honeyAfterBottle + " -> " + honeyAfterPour + ")";
                else if (Math.abs(honeySimulated - honeyAfterPour) > 60) problem = "the simulated honey did not follow the markers (" + honeySimulated + " particles, the markers say " + honeyAfterPour + ")";
                else if (!thrown) problem = "a slime ball was not thrown";
                else if (!wheelTurned) problem = "sneaking and turning the wheel did not change the thickness of the block in hand";
                else if (!placedThick) problem = "the thick block placed by hand did not become thick goo";
                else if (!thicknessShows) problem = "thin, usual and thick goo did not come out different (" + thicknessSeen + ")";
                else if (Packs.active && GooRenderer.packDraws == 0) problem = "with a shader pack on, the goo was never handed to it";
                else if (GooClient.quadCount(0) != 0 || GooClient.quadCount(1) != 0 || GooClient.particleCount() != 0) problem = "goo is still drawn after /goo clear (" + GooClient.quadCount(0) + " + " + GooClient.quadCount(1) + " quads, " + GooClient.particleCount() + " particles)";
                PhysicsBruhh.LOGGER.info("CLIENTTEST final {}", state);
                finish(mc, problem);
            }
            if (tick == 225) { sawHoney = GooClient.quadCount(0) > 0; sawSlime = GooClient.quadCount(1) > 0; }
            if (tick == 420) {
                sawClear = GooClient.clearLook;
                liquidExpected = GooFluid.chosen && GooRenderer.shader && (!Packs.active || Packs.LIQUID_ON_TOP);
                liquidFrames = GooFluid.frames;
                // clear goo is drawn one way or the other
                if (liquidExpected && !GooFluid.broken) { sawHoney &= GooClient.particleCount() > 0; sawSlime &= GooClient.particleCount() > 0; }
                else { sawHoney &= GooClient.quadCount(0) > 0; sawSlime &= GooClient.quadCount(1) > 0; }
            }
            // before the first retraction every slime body is still about a block wide ...
            if (tick == 100) GooClient.traceRow = new int[] { Y, BAND_Z };
            if ((tick >= 114 && tick <= 150) || (tick >= 326 && tick <= 350)) PhysicsBruhh.LOGGER.info("CLIENTTEST trace t={}{}", tick, GooClient.trace());
            if (tick == 118) { float[] span = GooClient.slimeSpan(BAND_Z + 0.5); restingWidth = span[1] - span[0]; }
            // ... and a second after it the band spans the gap between the two retracted heads (bases at x = 12 and 16)
            if (tick >= 140 && tick <= 160) {
                float[] span = GooClient.slimeSpan(BAND_Z + 0.5);
                bandLeft = Math.min(bandLeft, span[0]); bandRight = Math.max(bandRight, span[1]); bandWidest = Math.max(bandWidest, span[1] - span[0]);
            }
        } catch (Throwable t) {
            PhysicsBruhh.LOGGER.error("CLIENTTEST crashed", t);
            finish(mc, "exception: " + t);
        }
    }

    private static void finish(Minecraft mc, String problem) {
        done = true;
        if (problem == null) PhysicsBruhh.LOGGER.info("CLIENTTEST PASS");
        else PhysicsBruhh.LOGGER.error("CLIENTTEST FAIL: {}", problem);
        mc.stop();
    }
}
