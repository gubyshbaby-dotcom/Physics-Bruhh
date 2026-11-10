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
 * {@code /goolook shader} and {@code /goolook plain}: a switch on this client only, kept in
 * {@code config/physicsbruhh-client.properties}. "plain" draws the goo without the mod's own shader,
 * for the machine on which that shader does not work.
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
            GooRenderer.shader = !"plain".equalsIgnoreCase(properties.getProperty("look", "shader"));
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not read the goo look setting: {}", e.toString());
        }
    }

    private static int set(boolean shader) {
        GooRenderer.shader = shader;
        try {
            Properties properties = new Properties();
            properties.setProperty("look", shader ? "shader" : "plain");
            try (var out = Files.newOutputStream(file())) { properties.store(out, "Physics Bruhh, this client only. look = shader | plain"); }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not save the goo look setting: {}", e.toString());
        }
        return 1;
    }

    static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("goolook")
                .then(Commands.literal("shader").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.shader", "Goo is drawn with its own shader"), false);
                    return set(true);
                }))
                .then(Commands.literal("plain").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.look.plain", "Goo is drawn the plain way, without its own shader"), false);
                    return set(false);
                })));
    }
}
