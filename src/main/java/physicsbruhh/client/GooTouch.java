package physicsbruhh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import physicsbruhh.GooEntity;
import physicsbruhh.sim.GooSim;

/**
 * What the player feels and hears of the goo, on their own client where its real shape is known.
 * It keeps what the original blocks did:
 * <ul>
 * <li>honey: you wade through it at 40 % speed and jump half as high (the honey block's own factors),
 *     with the block's sliding sound and particles;</li>
 * <li>slime: falling into it throws you back up unless you sneak, walking through it is slow;</li>
 * <li>stepping sounds of the two blocks while moving through either;</li>
 * <li>a punch anywhere on the goo hits it, not only where the block once stood.</li>
 * </ul>
 * Fall damage is the server's business (see {@code GooEvents.onFall}). All sounds are the game's own.
 */
final class GooTouch {
    /** Particles of goo pressed against the player's box from which it counts as touching, and at which the full effect is reached. */
    private static final int TOUCH = 12, DEEP_HONEY = 150, DEEP_SLIME = 120;
    private static boolean wasOnGround;
    private static double lastFall;
    private static int bounceWait, stepWait, slideWait;
    private static double walked;
    /** For the smoke test: what the last tick decided. */
    static volatile boolean inHoney, inSlime;
    static volatile int bounces;

    private GooTouch() {}

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) return;
        int contact = GooClient.contact();
        int honey = contact & 0xFFFF, slime = contact >>> 16;
        boolean free = player.isSpectator() || player.getAbilities().flying;
        inHoney = honey >= TOUCH && !free;
        inSlime = slime >= TOUCH && !free;
        if (bounceWait > 0) bounceWait--;
        if (stepWait > 0) stepWait--;
        if (slideWait > 0) slideWait--;
        Vec3 v = player.getDeltaMovement();
        if (inSlime) {
            // falling through it, or having just hit the ground under it at speed (goo is not solid: a fast fall goes straight through)
            double fall = v.y < -0.25 ? v.y : player.onGround() && !wasOnGround && lastFall < -0.25 ? lastFall : 0.0;
            if (fall < 0.0 && bounceWait == 0 && !player.isSuppressingBounce()) {
                // SlimeBlock.updateEntityMovementAfterFallOn
                player.setDeltaMovement(v.x, -fall, v.z);
                player.resetFallDistance();
                bounceWait = 5;
                bounces++;
                sound(mc, player, SoundEvents.SLIME_BLOCK_FALL, 1.0F, 1.0F);
            } else if (player.onGround() && Math.abs(v.y) < 0.1 && !player.isSteppingCarefully()) {
                // SlimeBlock.stepOn, in proportion to how deep one stands in it
                double f = 1.0 - (1.0 - (0.4 + Math.abs(v.y) * 0.2)) * Math.min(1.0, slime / (double) DEEP_SLIME);
                player.setDeltaMovement(v.x * f, v.y, v.z * f);
            }
            v = player.getDeltaMovement();
        }
        if (inHoney) {
            double in = Math.min(1.0, honey / (double) DEEP_HONEY);
            double speed = 1.0 - 0.6 * in;                 // HoneyBlock speed factor 0.4
            double vy = v.y;
            if (wasOnGround && !player.onGround() && vy > 0.2) vy *= 1.0 - 0.5 * in; // HoneyBlock jump factor 0.5
            player.setDeltaMovement(v.x * speed, vy, v.z * speed);
            double moved = Math.sqrt(v.x * v.x + v.z * v.z);
            if (moved > 0.03 && slideWait == 0) {
                slideWait = 14;
                sound(mc, player, SoundEvents.HONEY_BLOCK_SLIDE, 0.7F, 1.0F);
                HoneyBlock.showSlideParticles(player);
            }
        }
        if (inHoney || inSlime) {
            walked += Math.sqrt(v.x * v.x + v.z * v.z);
            if (walked > 0.55 && stepWait == 0 && player.onGround()) {
                walked = 0.0;
                stepWait = 6;
                sound(mc, player, inSlime ? SoundEvents.SLIME_BLOCK_STEP : SoundEvents.HONEY_BLOCK_STEP, 0.5F, 1.0F);
            }
        } else {
            walked = 0.0;
        }
        wasOnGround = player.onGround();
        lastFall = player.getDeltaMovement().y;

        // pistons that start to move with goo in front of them
        for (BlockPos head : GooClient.strokes) {
            int material = GooClient.materialNear(head);
            if (material < 0) continue;
            SoundEvent event = material == GooSim.SLIME ? SoundEvents.SLIME_BLOCK_HIT : SoundEvents.HONEY_BLOCK_SLIDE;
            mc.level.playLocalSound(head.getX() + 0.5, head.getY() + 0.5, head.getZ() + 0.5, event, SoundSource.BLOCKS, 0.8F, 0.85F + mc.level.getRandom().nextFloat() * 0.3F, false);
        }
        GooClient.strokes.clear();
    }

    private static void sound(Minecraft mc, LocalPlayer player, SoundEvent event, float volume, float pitch) {
        mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), event, SoundSource.BLOCKS, volume, pitch, false);
    }

    /** Left click: if the line of sight meets goo before anything else, the goo is what gets hit. */
    static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!event.isAttack() || player == null || mc.gameMode == null || player.isSpectator()) return;
        Vec3 from = player.getEyePosition(1.0F);
        double reach = player.entityInteractionRange();
        Vec3 to = from.add(player.getViewVector(1.0F).scale(reach));
        HitResult aimed = mc.hitResult;
        double limit = aimed != null && aimed.getType() != HitResult.Type.MISS ? aimed.getLocation().distanceTo(from) : reach;
        GooEntity goo = GooClient.pick(from, to, limit);
        if (goo == null) return;
        mc.gameMode.attack(player, goo);
        event.setSwingHand(true);
        event.setCanceled(true);
    }
}
