package physicsbruhh.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import physicsbruhh.GooThickness;

/**
 * Sneak and turn the mouse wheel with a block of honey or slime in hand: that sets how thick the
 * goo of that very stack will be (up is thicker). The server does the setting; this side only
 * passes the turns on, keeps the wheel from flipping through the hotbar meanwhile, and shows the
 * thickness in the item's tooltip.
 */
final class GooWheel {
    private GooWheel() {}

    /** Turns passed on to the server so far (smoke test). */
    static volatile int turns;

    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        int notches = (int) Math.signum(event.getScrollDeltaY());
        if (notches != 0 && turn(mc, notches)) event.setCanceled(true);
    }

    /** Passes a turn of the wheel on, if the player is sneaking with such a block in hand. (The event only comes while no screen is open.) */
    static boolean turn(Minecraft mc, int notches) {
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || !player.isShiftKeyDown()) return false;
        if (!GooThickness.adjustable(player.getMainHandItem()) && !GooThickness.adjustable(player.getOffhandItem())) return false;
        turns++;
        ClientPacketDistributor.sendToServer(new GooThickness.Turn(notches));
        return true;
    }

    static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!GooThickness.adjustable(stack)) return;
        float thick = GooThickness.of(stack);
        event.getToolTip().add(GooThickness.describe(thick).copy().withStyle(thick == 1.0F ? ChatFormatting.GRAY : ChatFormatting.GOLD));
        event.getToolTip().add(Component.translatableWithFallback("physicsbruhh.thickness.hint", "Sneak + mouse wheel in hand: thicker or thinner goo").withStyle(ChatFormatting.DARK_GRAY));
    }
}
