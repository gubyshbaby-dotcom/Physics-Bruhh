package physicsbruhh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import physicsbruhh.GooEntity;
import physicsbruhh.PhysicsBruhh;

/** Client entry point: wires the goo simulation and its renderer into the game. */
@Mod(value = PhysicsBruhh.MODID, dist = Dist.CLIENT)
public class PhysicsBruhhClient {
    /** Shakes off the goo that sticks to the player (R unless changed in the controls). Water and rain do it by themselves. */
    static final net.minecraft.client.KeyMapping SHAKE = new net.minecraft.client.KeyMapping("key.physicsbruhh.shake", 82, net.minecraft.client.KeyMapping.Category.GAMEPLAY);

    private static void shake(Minecraft mc) {
        boolean pressed = false;
        while (SHAKE.consumeClick()) pressed = true;
        if (!pressed || mc.player == null || mc.level == null) return;
        boolean sticky = GooClient.contact() != 0;
        GooClient.shakeOff();
        if (sticky) mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), net.minecraft.sounds.SoundEvents.HONEY_BLOCK_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.3F, false);
    }

    public PhysicsBruhhClient(IEventBus modBus, ModContainer container) {
        // the marker entities draw nothing themselves; all goo is drawn as one merged surface
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
            e.registerEntityRenderer(PhysicsBruhh.HONEY_GOO.get(), NoopRenderer::new);
            e.registerEntityRenderer(PhysicsBruhh.SLIME_GOO.get(), NoopRenderer::new);
        });
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (e.getLevel().isClientSide() && e.getEntity() instanceof GooEntity goo) GooClient.join(goo);
        });
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e) -> {
            if (e.getLevel().isClientSide() && e.getEntity() instanceof GooEntity goo) GooClient.leave(goo);
        });
        modBus.addListener(GooPipelines::register);
        modBus.addListener(GooFluid::register);
        modBus.addListener(GasGpu::register);
        modBus.addListener(GasDraw::register);
        modBus.addListener(GasHide::models);
        modBus.addListener(GasHide::particles);
        NeoForge.EVENT_BUS.addListener(GasHide::screen);
        modBus.addListener((net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent e) -> {
            e.register(physicsbruhh.GasRules.State.TYPE, (state, context) -> GasClient.onState(state));
            e.register(physicsbruhh.GasRules.Blast.TYPE, (blast, context) -> GasClient.onBlast(blast));
        });
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) -> e.register(SHAKE));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> GooFluid.render(e));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> GasClient.render(e));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> { Packs.tick(); GooClient.tick(); guarded(0, "player contact", () -> GooTouch.tick(Minecraft.getInstance())); shake(Minecraft.getInstance()); GasClient.tick(Minecraft.getInstance()); });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered e) -> guarded(1, "click", () -> GooTouch.onClick(e)));
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent e) -> guarded(1, "wheel", () -> GooWheel.onScroll(e)));
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent e) -> GooWheel.onTooltip(e));
        NeoForge.EVENT_BUS.addListener(GooLook::registerCommand);
        GooLook.load();
        NeoForge.EVENT_BUS.addListener(GasClient::registerCommand);
        GasClient.load();
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> { GooClient.reset(); GooClient.failures = 0; java.util.Arrays.fill(FAILED, 0); GasClient.reset(); });
        NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent e) -> guarded(2, "drawing", () -> GooRenderer.submit(e)));
        ClientSelfTest.register();
    }

    private static final int[] FAILED = new int[3];
    /** Errors caught by {@link #guarded} since the game started (the smoke test wants none). */
    static volatile int errors;

    /**
     * Runs one of the mod's per-tick or per-frame jobs. The goo is decoration: if one of them throws,
     * that is logged and, after three times, the job stays off until the world is left. The game goes on.
     */
    private static void guarded(int job, String what, Runnable body) {
        if (FAILED[job] >= 3) return;
        try {
            body.run();
        } catch (Throwable t) {
            errors++;
            FAILED[job]++;
            PhysicsBruhh.LOGGER.error("Goo {} failed ({} of 3 before it stays off in this world)", what, FAILED[job], t);
        }
    }
}
