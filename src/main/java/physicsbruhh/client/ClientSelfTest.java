package physicsbruhh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
 * felt both kinds of goo, prints {@code CLIENTTEST PASS/FAIL} and closes the game.
 */
final class ClientSelfTest {
    private static final int Y = 200;
    private static int tick = -1;
    private static long firstTick;
    private static boolean done;
    private static float restingWidth = -1.0F, bandLeft = 99.0F, bandRight = -99.0F, bandWidest;
    private static boolean sawHoney, sawSlime, sawClear, waded, bounced, removedOne;
    private static int bouncesBefore;

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
                stage("everything is cleared away");
                commands(server, "tp @a 14.5 202 9.5 180 30", "kill @e[type=minecraft:pig]", "goo clear");
            } else if (tick > 0 && tick < 540 && tick % 20 == 0) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST t={} {} band={} clear={} shader={} drawCalls={}", tick, GooClient.describe(), slime(), GooClient.clearLook, GooRenderer.shader, GooRenderer.drawCalls);
            } else if (tick == 540) {
                String state = GooClient.describe() + " restingWidth=" + restingWidth + " band=" + bandLeft + ".." + bandRight + " shader=" + GooRenderer.shader + " drawCalls=" + GooRenderer.drawCalls;
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
                else if (GooClient.quadCount(0) != 0 || GooClient.quadCount(1) != 0) problem = "goo is still drawn after /goo clear (" + GooClient.quadCount(0) + " + " + GooClient.quadCount(1) + " quads)";
                PhysicsBruhh.LOGGER.info("CLIENTTEST final {}", state);
                finish(mc, problem);
            }
            if (tick == 420) { sawHoney = GooClient.quadCount(0) > 0; sawSlime = GooClient.quadCount(1) > 0; sawClear = GooClient.clearLook; }
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
