package physicsbruhh;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server's small part in the smoke and fire: it tells each client whether the game rule
 * {@code physicsbruhh:physical_gas} is on where that player is, and where something has just
 * exploded. The gas itself is simulated and drawn on each client ({@code physicsbruhh.client.GasClient}).
 */
public final class GasRules {
    private GasRules() {}

    /** To a client: whether fire and smoke are physical gas where it is. */
    public record State(boolean on) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "gas_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, State::on, State::new);

        @Override
        public Type<State> type() { return TYPE; }
    }

    /** To the clients near it: an explosion, where and how strong (TNT is 4). */
    public record Blast(float x, float y, float z, float power) implements CustomPacketPayload {
        public static final Type<Blast> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "gas_blast"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Blast> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Blast::x, ByteBufCodecs.FLOAT, Blast::y, ByteBufCodecs.FLOAT, Blast::z, ByteBufCodecs.FLOAT, Blast::power, Blast::new);

        @Override
        public Type<Blast> type() { return TYPE; }
    }

    /** What each player was last told. */
    private static final Map<UUID, Boolean> TOLD = new HashMap<>();
    /** Explosions told to clients since the server started (self test). */
    public static volatile int blastsTold;

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            boolean on = player.level().getGameRules().get(PhysicsBruhh.PHYSICAL_GAS.get());
            Boolean told = TOLD.get(player.getUUID());
            if (told == null || told != on) {
                PacketDistributor.sendToPlayer(player, new State(on));
                TOLD.put(player.getUUID(), on);
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        TOLD.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TOLD.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getGameRules().get(PhysicsBruhh.PHYSICAL_GAS.get())) return;
        Vec3 at = event.getExplosion().center();
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, 96.0, new Blast((float) at.x, (float) at.y, (float) at.z, event.getExplosion().radius()));
        blastsTold++;
    }
}
