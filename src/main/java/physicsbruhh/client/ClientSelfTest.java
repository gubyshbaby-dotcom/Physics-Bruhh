package physicsbruhh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import physicsbruhh.GooEvents;
import physicsbruhh.PhysicsBruhh;

/**
 * Client smoke test for CI: with {@code -Dphysicsbruhh.clienttest=true} and a world opened through
 * quick play, it builds a small stage (goo between two pairs of sticky pistons, goo in front of
 * plain pistons, free blocks), runs the pistons twice, first with the blocky look and then with
 * the clear one, while an outside script takes screenshots. It checks that the simulation ran, its
 * surface went through the renderer and the slime stayed stretched between the retracted pistons,
 * prints {@code CLIENTTEST PASS/FAIL} and closes the game.
 */
final class ClientSelfTest {
    private static final int Y = 200;
    private static int tick = -1;
    private static long firstTick;
    private static boolean done;
    private static float restingWidth = -1.0F, bandLeft = 99.0F, bandRight = -99.0F, bandWidest;

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

    private static String slime() {
        float[] span = GooClient.widestSlime();
        return String.format(java.util.Locale.ROOT, "%.2f..%.2f", span[0], span[1]);
    }

    private static void stage(String what) {
        PhysicsBruhh.LOGGER.info("CLIENTTEST stage: {}; {} widestSlime={}", what, GooClient.describe(), slime());
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
            } else if (tick == 270) {
                stage("sticky pistons extend again");
                commands(server, "setblock 11 200 6 minecraft:redstone_block", "setblock 17 200 6 minecraft:redstone_block",
                        "setblock 11 200 3 minecraft:redstone_block", "setblock 17 200 3 minecraft:redstone_block");
            } else if (tick == 330) {
                stage("sticky pistons retract again");
                commands(server, "setblock 11 200 6 minecraft:air", "setblock 17 200 6 minecraft:air",
                        "setblock 11 200 3 minecraft:air", "setblock 17 200 3 minecraft:air");
            } else if (tick > 0 && tick < 420 && tick % 20 == 0) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST t={} {} widestSlime={} clear={} drawCalls={}", tick, GooClient.describe(), slime(), GooClient.clearLook, GooRenderer.drawCalls);
            } else if (tick == 420) {
                String state = GooClient.describe() + " widestSlime=" + slime() + " restingWidth=" + restingWidth + " band=" + bandLeft + ".." + bandRight + " clear=" + GooClient.clearLook + " drawCalls=" + GooRenderer.drawCalls;
                String problem = null;
                if (!GooClient.healthy()) problem = "simulation is not running";
                else if (GooClient.quadCount(0) == 0) problem = "no honey surface";
                else if (GooClient.quadCount(1) == 0) problem = "no slime surface";
                else if (GooRenderer.drawCalls == 0) problem = "the surface never reached the renderer";
                else if (restingWidth > 1.35F) problem = "slime did not sit still between the extended pistons (width " + restingWidth + ")";
                else if (bandLeft > 13.6F || bandRight < 15.4F) problem = "slime did not stay stretched between the retracted sticky pistons (" + bandLeft + ".." + bandRight + ")";
                else if (bandWidest > 3.5F) problem = "slime was flung apart (" + bandWidest + " blocks wide)";
                else if (!GooClient.clearLook) problem = "the clear_goo game rule did not reach the client";
                PhysicsBruhh.LOGGER.info("CLIENTTEST final {}", state);
                finish(mc, problem);
            }
            // before the first retraction every slime body is still about a block wide ...
            if (tick == 118) { float[] span = GooClient.widestSlime(); restingWidth = span[1] - span[0]; }
            // ... and a second after it the band spans the gap between the two retracted heads (bases at x = 12 and 16)
            if (tick >= 140 && tick <= 160) {
                float[] span = GooClient.widestSlime();
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
