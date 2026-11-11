package physicsbruhh;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import physicsbruhh.sim.GooSim;

/**
 * How thick a block of honey or slime will be once it is goo. The number sits on the item itself
 * (the {@code physicsbruhh:thickness} component), so every stack can have its own, and it goes with
 * the block when it is placed, when a dispenser puts it out, and back onto the item when the goo
 * is punched.
 *
 * <p>It is turned with the mouse wheel while sneaking, block in hand: one notch is a factor of √2,
 * four notches either way, from a quarter of the usual to four times it. Thick honey melts later,
 * flows slowly and stays a heap; thin honey runs out like syrup. Thick slime is a stiff jelly, thin
 * slime soft and saggy. Thicker goo also holds on to whatever wades through it harder.
 */
public final class GooThickness {
    private GooThickness() {}

    /** Notches of the wheel either side of the usual thickness. */
    public static final int NOTCHES = 4;

    /** Whether this item's thickness can be set: blocks of honey and of slime. */
    public static boolean adjustable(ItemStack stack) {
        return stack.is(Items.HONEY_BLOCK) || stack.is(Items.SLIME_BLOCK);
    }

    /** The thickness this stack carries; 1 if it carries none. */
    public static float of(ItemStack stack) {
        Float value = stack.get(PhysicsBruhh.THICKNESS.get());
        return value == null ? 1.0F : sane(value);
    }

    /** Brings any number into the range goo can have; nonsense becomes the usual 1. */
    public static float sane(float thick) {
        return Float.isFinite(thick) && thick > 0.0F ? Math.max(GooSim.THICK_MIN, Math.min(GooSim.THICK_MAX, thick)) : 1.0F;
    }

    /** How many notches of the wheel away from the usual a thickness is. */
    public static int notch(float thick) {
        return Math.max(-NOTCHES, Math.min(NOTCHES, Math.round((float) (Math.log(sane(thick)) / Math.log(2.0) * 2.0))));
    }

    public static float ofNotch(int notch) {
        return (float) Math.pow(2.0, Math.max(-NOTCHES, Math.min(NOTCHES, notch)) / 2.0);
    }

    /** Puts a thickness on a stack. The usual thickness is no component at all, so such a stack is again like any other. */
    public static void set(ItemStack stack, float thick) {
        int notch = notch(thick);
        if (notch == 0) stack.remove(PhysicsBruhh.THICKNESS.get());
        else stack.set(PhysicsBruhh.THICKNESS.get(), ofNotch(notch));
    }

    /** A block of honey or slime as an item, as thick as the goo it came from. */
    public static ItemStack block(int material, float thick) {
        ItemStack stack = new ItemStack(material == GooSim.SLIME ? Items.SLIME_BLOCK : Items.HONEY_BLOCK);
        set(stack, thick);
        return stack;
    }

    /** "Thickness: ×1.41 (thicker)", for the tooltip and the line above the hotbar. */
    public static Component describe(float thick) {
        int notch = notch(thick);
        String factor = String.format(java.util.Locale.ROOT, "×%.2f", ofNotch(notch));
        String word = notch == 0 ? "usual" : notch > 0 ? (notch >= 3 ? "much_thicker" : "thicker") : (notch <= -3 ? "much_thinner" : "thinner");
        return Component.translatableWithFallback("physicsbruhh.thickness", "Thickness: %s (%s)", factor,
                Component.translatableWithFallback("physicsbruhh.thickness." + word, word.replace('_', ' ')));
    }

    // ---- what thickness does to those who wade through the goo (the usual honey block: speed 0.4, jump 0.5)

    /** Share of its speed something keeps while deep in honey this thick. */
    public static double honeySpeed(float thick) {
        return Math.pow(0.4, Math.pow(sane(thick), 0.6));
    }

    /** Share of its jump something keeps while deep in honey this thick. */
    public static double honeyJump(float thick) {
        return Math.pow(0.5, Math.pow(sane(thick), 0.6));
    }

    /** Share of the speed of a fall that slime this thick throws back: soft slime swallows part of it. */
    public static double slimeBounce(float thick) {
        return Math.min(1.0, Math.sqrt(sane(thick)));
    }

    // ---- the wheel

    /** From the client: the wheel was turned by this many notches (up is thicker) while sneaking with a block in hand. */
    public record Turn(int notches) implements CustomPacketPayload {
        public static final Type<Turn> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "thickness"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Turn> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Turn::notches, Turn::new);

        @Override
        public Type<Turn> type() { return TYPE; }
    }

    static void handle(Turn turn, IPayloadContext context) {
        turn(context.player(), turn.notches());
    }

    /** Turns the thickness of the block of honey or slime the player holds (main hand first). Returns the new thickness, or 0 if no such block is held. */
    public static float turn(Player player, int notches) {
        if (player.isSpectator()) return 0.0F;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!adjustable(stack)) continue;
            int before = notch(of(stack)), after = Math.max(-NOTCHES, Math.min(NOTCHES, before + Math.max(-2 * NOTCHES, Math.min(2 * NOTCHES, notches))));
            set(stack, ofNotch(after));
            player.sendOverlayMessage(describe(ofNotch(after)));
            if (after != before) {
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        stack.is(Items.SLIME_BLOCK) ? SoundEvents.SLIME_BLOCK_STEP : SoundEvents.HONEY_BLOCK_STEP, SoundSource.PLAYERS, 0.5F, 0.8F + 0.1F * after);
            }
            return ofNotch(after);
        }
        return 0.0F;
    }
}
