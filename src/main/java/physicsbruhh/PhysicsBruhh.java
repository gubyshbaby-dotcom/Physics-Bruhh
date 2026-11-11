package physicsbruhh;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.mojang.logging.LogUtils;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameRuleCategoryEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

/**
 * Honey and slime blocks stop being rigid cubes: honey melts and flows, slime wobbles, pistons
 * squash both and pull them into threads.
 *
 * <p>The server only decides <em>when</em> a block turns into goo and keeps a small marker entity
 * for it (so it is saved, tracked and can be punched back into an item). The goo itself is
 * simulated on each client, see {@code physicsbruhh.client}.
 */
@Mod(PhysicsBruhh.MODID)
public class PhysicsBruhh {
    public static final String MODID = "physicsbruhh";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<GooEntity>> HONEY_GOO = ENTITIES.registerEntityType(
            "honey_goo", GooEntity::new, MobCategory.MISC, b -> b.sized(0.9F, 0.3F).clientTrackingRange(8).updateInterval(20));
    public static final DeferredHolder<EntityType<?>, EntityType<GooEntity>> SLIME_GOO = ENTITIES.registerEntityType(
            "slime_goo", GooEntity::new, MobCategory.MISC, b -> b.sized(0.9F, 0.9F).clientTrackingRange(8).updateInterval(20));

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    /** On a block of honey or slime as an item: how thick the goo it turns into will be, see {@link GooThickness}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> THICKNESS = COMPONENTS.registerComponentType(
            "thickness", builder -> builder.persistent(Codec.FLOAT).networkSynchronized(ByteBufCodecs.FLOAT));

    public static final GameRuleCategory RULE_CATEGORY = new GameRuleCategory(Identifier.fromNamespaceAndPath(MODID, "goo"));
    private static GameRule<Boolean> physicalGoo;
    /** {@code /gamerule physicsbruhh:physical_goo false} turns honey and slime back into ordinary blocks. */
    public static final Supplier<GameRule<Boolean>> PHYSICAL_GOO = () -> physicalGoo;
    private static GameRule<Boolean> clearGoo;
    /** {@code /gamerule physicsbruhh:clear_goo true} draws honey and slime as clear liquid instead of with the blocks' textures. */
    public static final Supplier<GameRule<Boolean>> CLEAR_GOO = () -> clearGoo;
    private static GameRule<Boolean> physicalGas;
    /** {@code /gamerule physicsbruhh:physical_gas false} leaves fire and smoke as the game draws them. */
    public static final Supplier<GameRule<Boolean>> PHYSICAL_GAS = () -> physicalGas;

    public PhysicsBruhh(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener((RegisterPayloadHandlersEvent e) -> {
            e.registrar("1").playToServer(GooThickness.Turn.TYPE, GooThickness.Turn.CODEC, GooThickness::handle);
            // what the client does with these two is registered by the client (PhysicsBruhhClient)
            e.registrar("1").playToClient(GasRules.State.TYPE, GasRules.State.CODEC);
            e.registrar("1").playToClient(GasRules.Blast.TYPE, GasRules.Blast.CODEC);
        });
        // GameRules.registerBoolean puts the rule into the registry itself, so it must not go through a DeferredRegister
        modBus.addListener((RegisterEvent e) -> {
            if (e.getRegistryKey().equals(Registries.GAME_RULE)) {
                physicalGoo = GameRules.registerBoolean(MODID + ":physical_goo", RULE_CATEGORY, true);
                clearGoo = GameRules.registerBoolean(MODID + ":clear_goo", RULE_CATEGORY, false);
                physicalGas = GameRules.registerBoolean(MODID + ":physical_gas", RULE_CATEGORY, true);
            }
        });
        modBus.addListener((RegisterGameRuleCategoryEvent e) -> e.register(RULE_CATEGORY));
        // dispenser behaviours are plain static state of the game: set once it has registered its own
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(GooDispense::register));
        NeoForge.EVENT_BUS.register(GooEvents.class);
        NeoForge.EVENT_BUS.register(GooCommands.class);
        NeoForge.EVENT_BUS.register(GasRules.class);
        SelfTest.register();
    }
}
