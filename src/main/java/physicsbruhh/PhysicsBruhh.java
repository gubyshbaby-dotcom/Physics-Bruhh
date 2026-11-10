package physicsbruhh;

import com.mojang.logging.LogUtils;
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

    public static final GameRuleCategory RULE_CATEGORY = new GameRuleCategory(Identifier.fromNamespaceAndPath(MODID, "goo"));
    public static final DeferredRegister<GameRule<?>> GAME_RULES = DeferredRegister.create(Registries.GAME_RULE, MODID);
    /** {@code /gamerule physicsbruhh:physical_goo false} turns honey and slime back into ordinary blocks. */
    public static final DeferredHolder<GameRule<?>, GameRule<Boolean>> PHYSICAL_GOO = GAME_RULES.register(
            "physical_goo", id -> GameRules.registerBoolean(id.toString(), RULE_CATEGORY, true));

    public PhysicsBruhh(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        GAME_RULES.register(modBus);
        modBus.addListener((RegisterGameRuleCategoryEvent e) -> e.register(RULE_CATEGORY));
        NeoForge.EVENT_BUS.register(GooEvents.class);
        SelfTest.register();
    }
}
