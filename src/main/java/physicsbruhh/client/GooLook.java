package physicsbruhh.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import physicsbruhh.PhysicsBruhh;

/**
 * {@code /goolook liquid}, {@code /goolook shader} and {@code /goolook plain}: a switch on this client
 * only, kept in {@code config/physicsbruhh-client.properties}. "liquid" (the default) draws clear goo
 * as a liquid that bends the view of what is behind it; "shader" draws it as a glossy surface, as
 * goo with the block textures always is; "plain" draws everything without the mod's own shaders, for
 * the machine on which they do not work. {@code /goolook info} says what the simulation is doing:
 * which core, how many threads, how many pieces, how long an update takes and whether it keeps up
 * with real time.
 */
final class GooLook {
    private GooLook() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("physicsbruhh-client.properties");
    }

    static void load() {
        if (System.getProperty("physicsbruhh.shader") != null) return; // set on the command line: that wins
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            Properties properties = new Properties();
            try (var in = Files.newInputStream(file)) { properties.load(in); }
            String look = properties.getProperty("look", "liquid");
            GooRenderer.shader = !"plain".equalsIgnoreCase(look);
            GooFluid.chosen = "liquid".equalsIgnoreCase(look);
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not read the goo look setting: {}", e.toString());
        }
    }

    private static int set(String look) {
        GooRenderer.shader = !"plain".equals(look);
        GooFluid.chosen = "liquid".equals(look);
        try {
            Properties properties = new Properties();
            properties.setProperty("look", look);
            try (var out = Files.newOutputStream(file())) { properties.store(out, "Physics Bruhh, this client only. look = liquid | shader | plain"); }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not save the goo look setting: {}", e.toString());
        }
        return 1;
    }

    static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("goolook")
                .then(Commands.literal("liquid").executes(context -> {
                    if (GooFluid.broken) {
                        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.liquid.broken", "The liquid look did not work on this machine (see the log); goo is drawn as a glossy surface"), false);
                    } else {
                        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.liquid", "Clear goo is drawn as a liquid"), false);
                    }
                    return set("liquid");
                }))
                .then(Commands.literal("shader").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.shader", "Goo is drawn as a glossy surface"), false);
                    return set("shader");
                }))
                .then(Commands.literal("info").executes(context -> {
                    Object[] info = GooClient.info();
                    if (info[0] == null) {
                        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.info.off", "No goo is being simulated right now"), false);
                    } else {
                        String ms = String.format(java.util.Locale.ROOT, "%.1f", (Float) info[4]);
                        String speed = String.valueOf(Math.round((Float) info[5] * 100.0F));
                        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.info",
                                "Goo: %s core on %s threads, %s pieces (%s moving), %s ms per update, running at %s %% of real time",
                                info[0], info[1], info[2], info[3], ms, speed), false);
                        if (GooRenderer.quadsInAll > 0) {
                            String handover = String.format(java.util.Locale.ROOT, "%.2f", GooRenderer.handoverMillis);
                            int inView = GooRenderer.quadsInView, inAll = GooRenderer.quadsInAll;
                            context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.info.draw",
                                    "Drawing: %s of %s quads of the surface are in view; handing them to the game takes %s ms a frame (sorting and drawing them is the game's and comes on top)",
                                    inView, inAll, handover), false);
                        }
                    }
                    if (Packs.active) {
                        context.getSource().sendSuccess(() -> GooFluid.inUse()
                                ? Component.translatableWithFallback("commands.physicsbruhh.look.info.pack.liquid",
                                        "A shader pack is on: clear goo is drawn as a liquid over the pack's picture; goo with the block textures is handed to the pack (/goolook shader hands over both)")
                                : Component.translatableWithFallback("commands.physicsbruhh.look.info.pack",
                                        "A shader pack is on: goo is handed to it as ordinary translucent geometry, and the pack lights it"), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("plain").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.plain", "Goo is drawn the plain way, without its own shader"), false);
                    return set("plain");
                })));
    }
}
