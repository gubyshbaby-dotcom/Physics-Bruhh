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
            default -> {
                // ---- blocks put down by hand, the way a player builds: in the open, at the feet, against a wall, in a pit, next to and on top of other goo
                stage("by hand: slime blocks in every sort of place");
                commands(server, "goo clear", "gamemode creative @a", "clear @a",
                        "fill 26 198 0 34 199 8 minecraft:grass_block", "fill 26 200 0 34 204 8 minecraft:air",
                        "fill 32 200 1 32 201 3 minecraft:stone", "fill 27 200 2 27 201 2 minecraft:stone",
                        "setblock 29 199 6 minecraft:air",
                        // panes of glass between the camera and the two pieces that come last: goo behind glass, for the screenshots
                        "setblock 33 200 4 minecraft:glass", "setblock 33 200 6 minecraft:glass",
                        "fill 30 200 -2 30 202 -2 minecraft:oak_planks",
                        "item replace entity @a weapon.mainhand with minecraft:slime_block 16", "tp @a 29.5 200 3.5 0 40");
                step(40);
            }
        }
    }

    /** One block put down by hand: when (ticks into its stage), the point clicked, on which face of which block, and the cell the new block takes. */
    private record Put(int at, Vec3 hit, net.minecraft.core.Direction face, BlockPos on, BlockPos cell, String where) {}

    private static final net.minecraft.core.Direction UP = net.minecraft.core.Direction.UP;
    private static final Put[] SLIME_PUTS = {
        new Put(0, new Vec3(30.5, 200.0, 5.5), UP, new BlockPos(30, 199, 5), new BlockPos(30, 200, 5), "on open ground"),
        new Put(5, new Vec3(29.5, 200.0, 2.5), UP, new BlockPos(29, 199, 2), new BlockPos(29, 200, 2), "at the player's feet"),
        new Put(10, new Vec3(32.0, 200.5, 2.5), net.minecraft.core.Direction.WEST, new BlockPos(32, 200, 2), new BlockPos(31, 200, 2), "against a wall"),
        new Put(15, new Vec3(29.5, 199.0, 6.5), UP, new BlockPos(29, 198, 6), new BlockPos(29, 199, 6), "in a pit"),
        new Put(20, new Vec3(28.5, 200.0, 2.5), UP, new BlockPos(28, 199, 2), new BlockPos(28, 200, 2), "between a post and other slime"),
        new Put(45, new Vec3(28.0, 201.5, 2.5), net.minecraft.core.Direction.EAST, new BlockPos(27, 201, 2), new BlockPos(28, 201, 2), "on top of other slime"),
    };
    private static final Put[] HONEY_PUTS = {
        new Put(0, new Vec3(27.5, 200.0, 5.5), UP, new BlockPos(27, 199, 5), new BlockPos(27, 200, 5), "on open ground"),
        new Put(12, new Vec3(27.5, 200.0, 6.5), UP, new BlockPos(27, 199, 6), new BlockPos(27, 200, 6), "next to fresh honey"),
    };
    /** Where the test keeps a block standing in this client's world although the server has long made goo of it, and until when. */
    private static final java.util.Map<BlockPos, net.minecraft.world.level.block.Block> LINGER = new java.util.HashMap<>();
    private static int lingerFrom, lingerUntil, lingerOver = -1, bornBefore, bornByHand = -1, bornLingering = -1;
    private static String handProblem;
    private static final StringBuilder HAND_SEEN = new StringBuilder();

    /** Before the mod's own tick: the lingering blocks are (still) there when it looks. */
    private static void beforeTick() {
        Minecraft mc = Minecraft.getInstance();
        if (LINGER.isEmpty() || mc.level == null) return;
        // the blocks stay until the goo of each has arrived (however long the server's answer takes on a slow machine) and six ticks more
        if (lingerUntil == Integer.MAX_VALUE && (GooClient.bornInsideBlock - bornBefore >= LINGER.size() || tick > lingerFrom + 200)) lingerUntil = tick + 6;
        boolean over = tick >= lingerUntil;
        for (var e : LINGER.entrySet()) mc.level.setBlock(e.getKey(), (over ? net.minecraft.world.level.block.Blocks.AIR : e.getValue()).defaultBlockState(), 18);
        if (over) { LINGER.clear(); lingerOver = tick; }
    }

    /** How a piece of goo that came from a block lies: x and z extent, height, and how far its middle is from the middle of its cell. */
    private static double[] lies(int material, BlockPos cell) {
        double[] b = GooClient.boundsAt(material, cell);
        if (b == null && cell.getY() > 200) {
            // a marker with nothing solid under it comes down to the goo it rests on, and when that happens depends on the
            // server's clock: what has to hold is that all the goo of this kind in the column, taken together, is still a pile in its place
            for (double[] piece : GooClient.boundsInColumn(material, cell.getX(), cell.getZ())) {
                if (b == null) { b = piece.clone(); continue; }
                for (int k = 0; k < 3; k++) { b[k] = Math.min(b[k], piece[k]); b[k + 3] = Math.max(b[k + 3], piece[k + 3]); }
            }
        }
        if (b == null) return null;
        return new double[] { b[3] - b[0], b[5] - b[2], b[4] - b[1], Math.max(Math.abs((b[0] + b[3]) / 2 - cell.getX() - 0.5), Math.abs((b[2] + b[5]) / 2 - cell.getZ() - 0.5)) };
    }

    /** Checks one piece: slime is still a cube where it was put, fresh honey still stands as a block. */
    private static void whole(int material, BlockPos cell, String where, boolean loose) {
        double[] l = lies(material, cell);
        String kind = material == GooSim.SLIME ? "slime" : "honey";
        String seen = l == null ? "no goo there (" + GooClient.boundsInColumn(material, cell.getX(), cell.getZ()).size() + " pieces in its column)"
                : String.format(java.util.Locale.ROOT, "%.2f x %.2f wide, %.2f high, %.2f off its cell", l[0], l[1], l[2], l[3]);
        HAND_SEEN.append(kind).append(' ').append(where).append(": ").append(seen).append("; ");
        boolean ok;
        if (l == null) ok = false;
        else if (material == GooSim.SLIME) ok = loose ? l[0] <= 1.45 && l[1] <= 1.45 && l[2] >= 0.5 && l[2] <= (cell.getY() > 200 ? 2.2 : 1.2) : l[0] <= 1.25 && l[1] <= 1.25 && l[2] >= 0.6 && l[2] <= (cell.getY() > 200 ? 2.2 : 1.15) && l[3] <= 0.35;
        else ok = l[0] <= 1.9 && l[1] <= 1.9 && l[2] >= 0.55;
        if (!ok && handProblem == null) handProblem = "a block of " + kind + " put down " + where + " did not stay in one piece (" + seen + ")";
    }

    private static void put(Minecraft mc, Put p) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(p.hit(), p.face(), p.on(), false));
    }

    /** Blocks put down by hand in every sort of place, then two that are made to linger in this client's world after they have turned into goo. */
    private static void byHand(Minecraft mc, MinecraftServer server) {
        var held = mc.player.getMainHandItem();
        boolean late = tick > stepTick + 200;
        int into = tick - stepTick - 1; // ticks since the first one of this step
        switch (step) {
            case 40 -> { // the place is built, the player stands in it with slime blocks in hand
                boolean built = mc.level.getBlockState(new BlockPos(32, 201, 2)).is(net.minecraft.world.level.block.Blocks.STONE) && mc.level.getBlockState(new BlockPos(29, 199, 6)).isAir()
                        && mc.level.getBlockState(new BlockPos(29, 198, 6)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
                if (into > 10 && built && held.is(Items.SLIME_BLOCK) && standsAt(mc, 29.5, 3.5)) { bornBefore = GooClient.bornInsideBlock; step(41); }
                else if (late) { handProblem = "the place for putting blocks down by hand was never ready"; step(46); }
            }
            case 41 -> { // six slime blocks, a few ticks apart; two seconds after the last one they must all be cubes still
                for (Put p : SLIME_PUTS) if (p.at() == into) put(mc, p);
                if (into == SLIME_PUTS[SLIME_PUTS.length - 1].at() + 40) {
                    for (Put p : SLIME_PUTS) whole(GooSim.SLIME, p.cell(), p.where(), p.cell().getY() == 201);
                    stage("by hand: honey blocks");
                    commands(server, "item replace entity @a weapon.mainhand with minecraft:honey_block 16");
                    step(42);
                }
            }
            case 42 -> {
                if (into > 5 && held.is(Items.HONEY_BLOCK)) step(43);
                else if (late) { handProblem = "the player never got honey blocks in hand"; step(46); }
            }
            case 43 -> { // two honey blocks side by side; a second after each it must still stand as a block
                for (Put p : HONEY_PUTS) {
                    if (p.at() == into) put(mc, p);
                    if (p.at() + 20 == into) whole(GooSim.HONEY, p.cell(), p.where(), false);
                }
                if (into == HONEY_PUTS[HONEY_PUTS.length - 1].at() + 2) commands(server, "tp @a 30.5 203 -1.5 0 50");
                if (into == HONEY_PUTS[HONEY_PUTS.length - 1].at() + 21) {
                    bornByHand = GooClient.bornInsideBlock - bornBefore;
                    // Whether the block is still there when its goo arrives is a matter of a few milliseconds between the server's
                    // tick and this client's. So once more with the block made to stay: six ticks after the goo is already there.
                    stage("by hand: a block that lingers where its goo already is");
                    bornBefore = GooClient.bornInsideBlock;
                    LINGER.put(new BlockPos(33, 200, 5), net.minecraft.world.level.block.Blocks.SLIME_BLOCK);
                    LINGER.put(new BlockPos(33, 200, 7), net.minecraft.world.level.block.Blocks.HONEY_BLOCK);
                    lingerFrom = tick; lingerUntil = Integer.MAX_VALUE; lingerOver = -1;
                    commands(server, "setblock 33 200 5 minecraft:slime_block", "setblock 33 200 7 minecraft:honey_block");
                    server.execute(() -> {
                        GooEvents.convert(server.overworld(), new BlockPos(33, 200, 5), 0.0F, 1.0F);
                        GooEvents.convert(server.overworld(), new BlockPos(33, 200, 7), 0.0F, 1.0F);
                    });
                    step(44);
                }
            }
            case 44 -> {
                // a second after the blocks are gone the honey must still stand as a block, and two seconds after it the slime
                if (lingerOver >= 0 && tick == lingerOver + 20) whole(GooSim.HONEY, new BlockPos(33, 200, 7), "with its block lingering", false);
                if (lingerOver >= 0 && tick == lingerOver + 40) {
                    whole(GooSim.SLIME, new BlockPos(33, 200, 5), "with its block lingering", false);
                    bornLingering = GooClient.bornInsideBlock - bornBefore;
                    PhysicsBruhh.LOGGER.info("CLIENTTEST by hand: {} of 8 pieces arrived while their block was still standing here, {} of 2 with the block made to linger; {}", bornByHand, bornLingering, HAND_SEEN);
                    if (bornLingering != 2 && handProblem == null) handProblem = "the test did not manage to keep a block standing where its goo arrived (" + bornLingering + " of 2)";
                    step(45);
                }
            }
            case 45 -> {
                if (into >= 30) step(46);
            }
            default -> step(60);
        }
    }

    // ---- fire and smoke
    private static String gasProblem = "the fire and smoke part never ran";
    private static int gasStepsBefore, gasBlastsBefore;
    private static boolean sawBurning;

    /**
     * Fire and smoke as gas: a campfire, a block of fire, a soul fire and a torch on a stone floor. The solver has to find
     * them, burn them hot and send their smoke up; then an explosion and a burning pig have to reach it; then the game
     * rule has to switch it off and on again. It runs by the solver's steps, not by the clock: frames are slow here.
     */
    private static void gas(Minecraft mc, MinecraftServer server) {
        int into = tick - stepTick;
        switch (step) {
            case 60 -> {
                if (into == 1) {
                    stage("gas: a campfire, a fire, a soul fire and a torch");
                    commands(server, "goo clear", "gamemode creative @a", "time set noon",
                            "fill 40 199 -3 54 199 12 minecraft:stone", "fill 40 200 -3 54 212 12 minecraft:air",
                            "setblock 44 200 6 minecraft:campfire",
                            "setblock 50 199 6 minecraft:netherrack", "setblock 50 200 6 minecraft:fire",
                            "setblock 47 199 9 minecraft:soul_sand", "setblock 47 200 9 minecraft:soul_fire",
                            "setblock 47 200 4 minecraft:torch",
                            "tp @a 47.5 200 -1.5 0 -12");
                }
                if (GasClient.sources >= 4 && into >= 20) {
                    gasStepsBefore = GasClient.stepsDone;
                    step(61);
                } else if (into > 300) {
                    gasProblem = !GasClient.ruleOn ? "the game rule physical_gas did not reach the client"
                            : "the client did not find the fires in the world (" + GasClient.sources + " of 4, gas broken: " + GasClient.broken + ")";
                    step(66);
                }
            }
            case 61 -> {
                int steps = GasClient.stepsDone - gasStepsBefore;
                if (into % 400 == 0) PhysicsBruhh.LOGGER.info("CLIENTTEST gas t={} steps={} sums={} drawn={} part={}", tick, steps, head(GasClient.sums()), GasDraw.drawn, GasClient.partOfBox);
                if (steps >= 140 || GasClient.broken || into > 3000) {
                    float[] sums = GasClient.sums();
                    float smoke = sums.length > 0 ? sums[0] : 0.0F, hottest = sums.length > 2 ? sums[2] : 0.0F, above = 0.0F;
                    for (int y = Math.max(0, 204 - GasClient.boxY()); 3 + y < sums.length; y++) above += sums[3 + y];
                    String by = GasClient.solverInUse(), want = "cpu".equals(GasClient.solver) ? "processor" : "compute".equals(GasClient.solver) ? "graphics card (compute shaders)" : "graphics card";
                    PhysicsBruhh.LOGGER.info("CLIENTTEST gas: computed on the {}, {} steps; smoke {}, hottest {}, {} of the smoke more than three blocks over the fires; a step takes {} ms, the drawing {} ms; drawn in {} frames, sums read back {} times; {} of the box is computed",
                            by, steps, smoke, hottest, above, GasClient.stepMs(), GasClient.drawMs(), GasDraw.drawn, GasDraw.readBacks, GasClient.partOfBox);
                    if (GasClient.broken) gasProblem = "fire and smoke as gas failed (see the log)";
                    else if (steps < 140) gasProblem = "the gas solver made only " + steps + " steps in " + into + " ticks";
                    else if (!by.equals(want)) gasProblem = "the gas was computed on the " + by + ", not on the " + want;
                    else if (sums.length == 0) gasProblem = "the sums of the gas never came back";
                    else if (smoke < 0.3F) gasProblem = "four fires made no smoke (" + smoke + ")";
                    else if (hottest < 0.5F) gasProblem = "four fires made no flame (hottest " + hottest + ")";
                    else if (above < 0.05F * smoke) gasProblem = "the smoke did not rise (" + above + " of " + smoke + " more than three blocks over the fires)";
                    else if (GasDraw.drawn == 0) gasProblem = "the gas was never drawn";
                    else gasProblem = null;
                    step(gasProblem == null ? 62 : 66);
                }
            }
            case 62 -> {
                if (into == 1) {
                    stage("gas: an explosion, a burning pig");
                    gasBlastsBefore = GasClient.blastsSeen;
                    sawBurning = false;
                    // by night: fire is what lights its smoke then
                    commands(server, "time set midnight", "summon minecraft:pig 50.5 200 6.5 {NoAI:1b,Silent:1b}", "summon minecraft:tnt 47.5 207.0 6.5 {fuse:0}");
                }
                sawBurning |= GasClient.burning > 0;
                if (GasClient.blastsSeen > gasBlastsBefore && sawBurning) {
                    PhysicsBruhh.LOGGER.info("CLIENTTEST gas: the explosion and the burning pig reached the solver");
                    step(63);
                } else if (into > 400) {
                    gasProblem = GasClient.blastsSeen > gasBlastsBefore ? "a pig standing in fire was not taken as burning" : "an explosion did not reach the gas solver";
                    step(66);
                }
            }
            case 63 -> {
                // let the fireball be seen
                if (into > 160) step(64);
            }
            case 64 -> {
                if (into == 1) {
                    stage("gas: the game rule off, and on again");
                    commands(server, "gamerule physicsbruhh:physical_gas false");
                }
                if (!GasClient.ruleOn && GasClient.solverInUse().equals("none")) {
                    commands(server, "gamerule physicsbruhh:physical_gas true");
                    step(65);
                } else if (into > 200) {
                    gasProblem = "with the game rule physical_gas off the gas was still computed";
                    step(66);
                }
            }
            case 65 -> {
                if (GasClient.ruleOn) step(66);
                else if (into > 200) { gasProblem = "the game rule physical_gas did not come back on"; step(66); }
            }
            default -> {
                commands(server, "time set noon", "kill @e[type=minecraft:pig]", "kill @e[type=minecraft:item]", "fill 40 199 -3 54 212 12 minecraft:air");
                step(70);
            }
        }
    }

    // ---- lava
    private static String lavaProblem = "the lava part never ran";
    private static int lavaFramesBefore;

    /**
     * Lava as the mod draws it: a lake in a basin, a spring on flat ground, a spring on a ledge that falls off it, and lava
     * behind glass. The mod has to take the lava over from the game (unless a shader pack is on: then it has to leave it
     * alone), draw it, follow it as it spreads, give it back when switched off and take it again when switched on.
     */
    private static void lava(Minecraft mc, MinecraftServer server) {
        int into = tick - stepTick;
        switch (step) {
            case 70 -> {
                if (into == 1) {
                    stage("lava: a lake, a spring on flat ground, a lava-fall, lava behind glass");
                    lavaFramesBefore = LavaClient.frames;
                    // (so that the little lake here bubbles within the seconds this takes, as a large one does within minutes)
                    LavaBubbles.rate = 40.0F;
                    commands(server, "goo clear", "gamemode creative @a", "time set noon",
                            "fill 60 197 -8 86 199 20 minecraft:stone", "fill 60 200 -8 86 212 20 minecraft:air",
                            "fill 64 198 1 69 199 6 minecraft:lava",
                            "setblock 75 200 3 minecraft:lava",
                            "fill 76 200 9 78 203 11 minecraft:stone", "setblock 77 204 10 minecraft:lava",
                            "fill 64 200 9 68 201 13 minecraft:glass", "fill 65 200 10 67 201 12 minecraft:lava",
                            // (a post to look down from)
                            "fill 71 200 -8 71 205 -8 minecraft:stone", "tp @a 71.5 206 -7.5 0 42");
                }
                boolean taken = LavaClient.active && LavaClient.sections > 0 && LavaClient.drawnQuads > 0;
                if (into % 100 == 0) PhysicsBruhh.LOGGER.info("CLIENTTEST lava t={} active={} sections={} faces={} drawn={} of {} frames={} bubbles={} burst={} broken={}", tick, LavaClient.active, LavaClient.sections, LavaClient.quads, LavaClient.drawnQuads, LavaClient.drawnSections, LavaClient.frames - lavaFramesBefore, LavaBubbles.risen, LavaBubbles.popped, LavaClient.broken);
                if (Packs.active) {
                    // a pack draws the lava its own way: the mod must keep its hands off
                    if (into > 100) {
                        lavaProblem = LavaClient.active || LavaClient.sections != 0 ? "with a shader pack on, the mod still drew the lava" : null;
                        PhysicsBruhh.LOGGER.info("CLIENTTEST lava: left to the shader pack");
                        step(74);
                    }
                } else if (into == 200 && taken) {
                    // something heavy falls into the lake
                    commands(server, "summon minecraft:falling_block 66.5 206 3.5 {BlockState:{Name:\"minecraft:anvil\"},Time:1,DropItem:0b}", "summon minecraft:pig 67.5 205 4.5 {NoAI:0b,Silent:1b}");
                } else if (into == 250 && taken) {
                    // from the bank of the lake, looking across it: the bubbles and what the surface does
                    commands(server, "tp @a 71.5 200 3.5 90 14");
                } else if (into == 350 && taken) {
                    // from close by and low down: the front of the flow and the fall
                    commands(server, "tp @a 71.5 200 -1.5 -35 12");
                } else if (into == 430 && taken) {
                    // by night it is its own light
                    commands(server, "time set midnight", "tp @a 71.5 200 3.5 90 14");
                } else if (into == 500 && taken) {
                    commands(server, "tp @a 71.5 206 -7.5 0 42");
                } else if (into >= 560 && taken) {
                    PhysicsBruhh.LOGGER.info("CLIENTTEST lava: drawn by the mod in {} sections, {} faces ({} in view), in {} frames; taking up what changed costs {} ms a frame; {} buffers sent to the graphics card; {} bubbles came up in the lake, {} have burst",
                            LavaClient.sections, LavaClient.quads, LavaClient.drawnQuads, LavaClient.frames - lavaFramesBefore, LavaClient.prepareMillis, LavaClient.uploads, LavaBubbles.risen, LavaBubbles.popped);
                    PhysicsBruhh.LOGGER.info("CLIENTTEST lava waves: {} steps over {} columns of lava, pushed {} times, {} calm blocks cut finer for them, {} splashes{}",
                            LavaWaves.steps, LavaWaves.lavaColumns, LavaWaves.pushed, LavaClient.cutQuads, LavaWaves.splashes, LavaWaves.off ? "; THE WAVES ARE OFF (see the log)" : "");
                    if (LavaClient.quads < 200) { lavaProblem = "the lava's surface is only " + LavaClient.quads + " faces"; step(74); }
                    else if (LavaBubbles.popped == 0) { lavaProblem = "no bubble came up and burst in the lake (" + LavaBubbles.risen + " came up)"; step(74); }
                    else if (LavaWaves.off || LavaWaves.steps == 0 || LavaWaves.pushed == 0) { lavaProblem = "the waves of the lava's surface were not worked out (" + LavaWaves.steps + " steps, " + LavaWaves.pushed + " pushes, off: " + LavaWaves.off + ")"; step(74); }
                    else if (LavaClient.cutQuads == 0) { lavaProblem = "no calm block of the lake was cut finer for the waves"; step(74); }
                    else { LavaClient.chosen = false; step(71); }
                } else if (into > 900 || LavaClient.broken) {
                    lavaProblem = LavaClient.broken ? "the mod's lava failed (see the log)"
                            : !LavaHide.hooked ? "the game's lava models could not be made to ask the mod"
                            : !LavaClient.active ? "the mod's lava never came on"
                            : LavaClient.sections == 0 ? "no section with lava ever reached the mod" : "the lava was never drawn";
                    step(74);
                }
            }
            case 71 -> { // switched off: the game has its lava back
                if (into == 1) stage("lava: switched off, and on again");
                if (!LavaClient.active && LavaClient.sections == 0 && into > 40) { LavaClient.chosen = true; step(72); }
                else if (into > 300) { lavaProblem = "switched off, the mod still held on to the lava"; LavaClient.chosen = true; step(74); }
            }
            case 72 -> { // and on again
                if (LavaClient.active && LavaClient.sections > 0 && LavaClient.drawnQuads > 0 && into > 40) { lavaProblem = LavaClient.broken ? "the mod's lava failed (see the log)" : null; step(lavaProblem == null ? 75 : 74); }
                else if (into > 400) { lavaProblem = "switched on again, the mod did not take the lava back"; step(74); }
            }
            case 75 -> lavaBody(mc, server, into);
            default -> {
                LavaBubbles.rate = 1.0F;
                commands(server, "time set noon", "goo clear", "kill @e[type=minecraft:pig]", "kill @e[type=minecraft:item]", "kill @e[type=minecraft:falling_block]", "fill 60 200 -8 86 212 20 minecraft:air", "fill 60 197 -8 86 199 20 minecraft:air");
                step(9);
            }
        }
    }

    private static int bodyReleased, bodyPooled, bodyBurnt, bodyFrames, bodyPlain, bodyMostParticles, bodyMostLights, bodyMostQuads;

    /**
     * Lava that flows as a body. Lava stands shut in behind a wall in two places, two blocks above the floor: one block a
     * block deep in the wall, and two blocks behind one another. The block in front of each is taken away: the lava has
     * to let go of its block (the two one after the other), ooze out through the hole, pour down the wall and lie on the
     * floor as a lobe, drawn from its particles all the way (or, where that is not possible, as a plain surface). A pig
     * stands where the first comes down and has to burn; under the second there is a pit, which it has to fill and become
     * a block of lava again.
     */
    private static void lavaBody(Minecraft mc, MinecraftServer server, int into) {
        if (into == 1) {
            stage("lava that flows as a body: out of a wall, down it, over the floor");
            bodyReleased = physicsbruhh.LavaFlow.released; bodyPooled = physicsbruhh.LavaFlow.pooled; bodyBurnt = physicsbruhh.LavaFlow.burnt;
            bodyFrames = LavaGoo.frames; bodyPlain = GooRenderer.lavaPlainDraws; bodyMostParticles = 0; bodyMostLights = 0; bodyMostQuads = 0;
            commands(server, "goo clear", "kill @e[type=minecraft:pig]", "kill @e[type=minecraft:item]", "kill @e[type=minecraft:falling_block]", "time set midnight",
                    "fill 60 200 -8 86 212 20 minecraft:air", "fill 60 197 -8 86 199 20 minecraft:stone",
                    "fill 70 200 0 72 204 12 minecraft:stone", "setblock 71 202 3 minecraft:lava",
                    // (the second: two blocks behind one another, the front one flush with the wall behind a stone that sticks out)
                    "setblock 73 202 9 minecraft:stone", "setblock 72 202 9 minecraft:lava", "setblock 71 202 9 minecraft:lava",
                    "setblock 73 199 9 minecraft:air",
                    "summon minecraft:pig 73.9 200 3.5 {NoAI:1b,Silent:1b}",
                    "tp @a 76.5 200.6 4.5 105 -8");
        } else if (into == 40) {
            // the block in front of each is broken
            commands(server, "setblock 72 202 3 minecraft:air", "setblock 73 202 9 minecraft:air");
        } else if (into == 300) {
            // from the side and above: the rope down the wall, the lobe on the floor
            commands(server, "tp @a 75.3 202.2 6.6 140 32");
        } else if (into == 520) {
            commands(server, "tp @a 76.5 200.6 4.5 105 -8");
        }
        bodyMostParticles = Math.max(bodyMostParticles, LavaGoo.drawnParticles);
        bodyMostLights = Math.max(bodyMostLights, LavaGoo.drawnLights);
        bodyMostQuads = Math.max(bodyMostQuads, GooClient.quadCount(GooSim.LAVA));
        int released = physicsbruhh.LavaFlow.released - bodyReleased, pooled = physicsbruhh.LavaFlow.pooled - bodyPooled, burnt = physicsbruhh.LavaFlow.burnt - bodyBurnt;
        if (into % 100 == 0) PhysicsBruhh.LOGGER.info("CLIENTTEST lava body t={} let go={} pieces={} particles={} lights={} frames={} plain draws={} quads={} burnt={} pooled={} reports={} in use={} broken={}",
                tick, released, LavaGoo.pieces, LavaGoo.drawnParticles, LavaGoo.drawnLights, LavaGoo.frames - bodyFrames, GooRenderer.lavaPlainDraws - bodyPlain, GooClient.quadCount(GooSim.LAVA), burnt, pooled, LavaGoo.reports, LavaGoo.inUse(), LavaGoo.broken);
        if (into < 900) return;
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        boolean block = level != null && level.getFluidState(new BlockPos(73, 199, 9)).isSource();
        boolean flowed = level != null && (!level.getFluidState(new BlockPos(72, 202, 3)).isEmpty() || !level.getFluidState(new BlockPos(73, 202, 3)).isEmpty() || !level.getFluidState(new BlockPos(73, 201, 3)).isEmpty());
        boolean drawn = LavaGoo.inUse() ? LavaGoo.frames - bodyFrames > 20 && bodyMostParticles > 1000 && bodyMostLights > 0 : GooRenderer.lavaPlainDraws - bodyPlain > 20 && bodyMostQuads > 200;
        PhysicsBruhh.LOGGER.info("CLIENTTEST lava body: {} blocks let go, {} particles and {} lights at the most, drawn {} in {} frames; the pig was burnt {} times; the pit {}; told the server {} times",
                released, bodyMostParticles, bodyMostLights, LavaGoo.inUse() ? "from its particles" : "as a plain surface (" + bodyMostQuads + " faces)",
                LavaGoo.inUse() ? LavaGoo.frames - bodyFrames : GooRenderer.lavaPlainDraws - bodyPlain, burnt, block ? "is a block of lava again (" + pooled + " pooled)" : "is not a block of lava", LavaGoo.reports);
        lavaProblem = !physicsbruhh.LavaFlow.asked ? "the game never asked the mod before lava spread (the hook is not in place)"
                : released != 3 ? "the three blocks of lava behind the wall did not let go when the wall was opened (" + released + " did)"
                : flowed ? "lava was put into the blocks in front of the hole the game's way"
                : LavaGoo.broken ? "drawing lava from its particles failed (see the log)"
                : !drawn ? "the lava that came out of the wall was not drawn (" + bodyMostParticles + " particles, " + bodyMostLights + " lights, " + (LavaGoo.frames - bodyFrames) + " frames, " + (GooRenderer.lavaPlainDraws - bodyPlain) + " plain draws, " + bodyMostQuads + " faces)"
                : burnt == 0 ? "the pig the lava came down on was not burnt"
                : !block || pooled == 0 ? "the lava that ran into the pit did not become a block again (" + pooled + " pooled)"
                : null;
        step(74);
    }

    private static String head(float[] sums) {
        return sums.length < 3 ? "(none)" : String.format(java.util.Locale.ROOT, "smoke %.2f heat %.2f hottest %.2f", sums[0], sums[1], sums[2]);
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
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> beforeTick());
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
            } else if (step >= 70) {
                lava(mc, server);
            } else if (step >= 60) {
                gas(mc, server);
            } else if (step >= 40) {
                byHand(mc, server);
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
                else if (handProblem != null) problem = handProblem;
                else if (gasProblem != null) problem = gasProblem;
                else if (lavaProblem != null) problem = lavaProblem;
                else if (bornLingering < 0) problem = "the blocks put down by hand were never looked at";
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
