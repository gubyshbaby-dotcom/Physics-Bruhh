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
 * quick play, it builds a small stage (goo between two sticky pistons, goo in front of a piston,
 * a free honey block), lets the pistons run while an outside script takes screenshots, checks that
 * the simulation ran and its surface went through the renderer, prints
 * {@code CLIENTTEST PASS/FAIL} and closes the game.
 */
final class ClientSelfTest {
    private static final int Y = 200;
    private static int tick = -1;
    private static long firstTick;
    private static boolean done;

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

    private static void run() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (firstTick == 0L) firstTick = System.nanoTime();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            if (System.nanoTime() - firstTick > 240_000_000_000L) {
                finish(mc, "no world after four minutes, screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName()));
            }
            return;
        }
        tick++;
        try {
            if (tick == 0) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST world joined");
                commands(server,
                        "gamemode creative @a", "time set noon", "weather clear",
                        "fill 8 199 -3 23 199 13 minecraft:oak_planks",
                        "fill 14 200 12 16 201 12 minecraft:oak_planks",
                        "tp @a 15.5 202 12.5 180 32",
                        // two rows of sticky pistons facing each other, extended
                        "setblock 12 200 6 minecraft:sticky_piston[facing=east]", "setblock 11 200 6 minecraft:redstone_block",
                        "setblock 16 200 6 minecraft:sticky_piston[facing=west]", "setblock 17 200 6 minecraft:redstone_block",
                        "setblock 12 200 3 minecraft:sticky_piston[facing=east]", "setblock 11 200 3 minecraft:redstone_block",
                        "setblock 16 200 3 minecraft:sticky_piston[facing=west]", "setblock 17 200 3 minecraft:redstone_block",
                        // plain pistons with a block in front, not powered yet
                        "setblock 11 200 0 minecraft:piston[facing=east]", "setblock 12 200 0 minecraft:honey_block",
                        "setblock 18 200 0 minecraft:piston[facing=east]", "setblock 19 200 0 minecraft:slime_block");
            } else if (tick == 40) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST stage: goo placed");
                goo(server, 14, 6, "slime_block", 0, 1);
                goo(server, 14, 3, "honey_block", 0, 1);
                goo(server, 20, 6, "honey_block", 1, 0);
                goo(server, 21, 3, "slime_block", 1, 0);
            } else if (tick == 120) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST stage: sticky pistons retract; {}", GooClient.describe());
                commands(server, "setblock 11 200 6 minecraft:air", "setblock 17 200 6 minecraft:air",
                        "setblock 11 200 3 minecraft:air", "setblock 17 200 3 minecraft:air");
            } else if (tick == 170) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST stage: pistons push; {}", GooClient.describe());
                commands(server, "setblock 10 200 0 minecraft:redstone_block", "setblock 17 200 0 minecraft:redstone_block");
            } else if (tick > 0 && tick < 330 && tick % 20 == 0) {
                PhysicsBruhh.LOGGER.info("CLIENTTEST t={} {} drawCalls={} drawnQuads={}", tick, GooClient.describe(), GooRenderer.drawCalls, GooRenderer.drawnQuads);
            } else if (tick == 330) {
                String state = GooClient.describe() + " drawCalls=" + GooRenderer.drawCalls + " drawnQuads=" + GooRenderer.drawnQuads;
                String problem = null;
                if (!GooClient.healthy()) problem = "simulation is not running";
                else if (GooClient.quadCount(0) == 0) problem = "no honey surface";
                else if (GooClient.quadCount(1) == 0) problem = "no slime surface";
                else if (GooRenderer.drawCalls == 0) problem = "the surface never reached the renderer";
                PhysicsBruhh.LOGGER.info("CLIENTTEST final {}", state);
                finish(mc, problem);
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
