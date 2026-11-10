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
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import physicsbruhh.GooEntity;
import physicsbruhh.PhysicsBruhh;

/** Client entry point: wires the goo simulation and its renderer into the game. */
@Mod(value = PhysicsBruhh.MODID, dist = Dist.CLIENT)
public class PhysicsBruhhClient {
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
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> { GooClient.tick(); GooTouch.tick(Minecraft.getInstance()); });
        NeoForge.EVENT_BUS.addListener(GooTouch::onClick);
        NeoForge.EVENT_BUS.addListener(GooLook::registerCommand);
        GooLook.load();
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> GooClient.reset());
        NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent e) -> GooRenderer.submit(e));
        ClientSelfTest.register();
    }
}
