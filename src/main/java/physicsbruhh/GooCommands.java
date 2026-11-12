package physicsbruhh;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /goo clear} removes all honey and slime goo in the loaded world, {@code /goo clear <radius>}
 * only what lies within that many blocks of whoever runs it. Nothing is dropped. Needs the same
 * permission as {@code /gamerule}.
 */
public final class GooCommands {
    private GooCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("goo")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("clear")
                        .executes(context -> clear(context.getSource(), -1.0))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> clear(context.getSource(), DoubleArgumentType.getDouble(context, "radius"))))));
    }

    /** Removes goo markers; a negative radius means everywhere. Returns how many were removed. */
    public static int clear(CommandSourceStack source, double radius) {
        int removed = 0;
        if (radius < 0.0) {
            for (ServerLevel level : source.getServer().getAllLevels()) removed += clear(level, null, 0.0);
        } else {
            removed = clear(source.getLevel(), source.getPosition(), radius);
        }
        final int count = removed;
        source.sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.clear", "Removed %s pieces of goo", count), true);
        return count;
    }

    public static int clear(ServerLevel level, Vec3 centre, double radius) {
        int removed = 0;
        for (var type : java.util.List.of(PhysicsBruhh.HONEY_GOO.get(), PhysicsBruhh.SLIME_GOO.get(), PhysicsBruhh.LAVA_GOO.get())) {
            for (GooEntity goo : level.getEntities(type, e -> centre == null || e.position().distanceToSqr(centre) <= radius * radius)) {
                goo.discard();
                removed++;
            }
        }
        return removed;
    }
}
