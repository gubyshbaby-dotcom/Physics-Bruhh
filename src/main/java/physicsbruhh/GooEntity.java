package physicsbruhh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import physicsbruhh.sim.GooSim;

/**
 * Server-side stand-in for one piece of goo: at most a block's worth. It has no physics of its own:
 * it marks where the goo is, how old it is, what it is made of and how much of it there is. Clients
 * turn it into simulated goo when it enters their view. Hitting it gives back what it is made of.
 *
 * <p>A piece is either a block that turned into goo, or goo that was poured or thrown (out of a
 * dispenser, out of a bottle). More can be poured into a piece until it holds a block's worth, and
 * goo can be taken out of it; the amount, and where and how the last of it came or went, is what
 * the clients are told, and they let the same happen to their simulated goo.
 */
public class GooEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Boolean> CLEAR = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.BOOLEAN);
    /** How much goo, in particles of the simulation ({@link GooSim#BLOCK} is a whole block). */
    private static final EntityDataAccessor<Integer> AMOUNT = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.INT);
    /**
     * Where the amount last changed (a spout, or the place something was scooped from): a block and
     * the place within it. (Not relative to the marker: that moves, and on a client it lags behind.)
     * And the velocity goo came in with.
     */
    private static final EntityDataAccessor<BlockPos> SPOT_BLOCK = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Vector3fc> SPOT = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3fc> PUSH = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.VECTOR3);
    /** The last goo came in as one lump (a thrown slime ball) rather than as a stream. */
    private static final EntityDataAccessor<Boolean> LUMP = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.BOOLEAN);
    /** How thick this piece is, as a factor on the usual: it comes from the block it was made of, see {@link GooThickness}. */
    private static final EntityDataAccessor<Float> THICK = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.FLOAT);
    /** Poured or thrown rather than a block that turned into goo. */
    private boolean poured;
    private int ageTicks;
    private int shiftIn, shiftX, shiftY, shiftZ;
    /** Client side: age at the moment this entity was sent to us, in ticks. */
    private int spawnAgeTicks;
    /** Client side: horizontal direction a fresh honey block leans towards. */
    private float leanX = 1.0F, leanZ = 0.0F;

    public GooEntity(EntityType<? extends GooEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public int material() {
        return this.getType() == PhysicsBruhh.SLIME_GOO.get() ? GooSim.SLIME : GooSim.HONEY;
    }

    public int spawnAgeTicks() { return spawnAgeTicks; }

    public float leanX() { return leanX; }

    public float leanZ() { return leanZ; }

    public void setLean(float x, float z) { this.leanX = x; this.leanZ = z; }

    /** How much goo this piece holds, in particles ({@link GooSim#BLOCK} is a whole block). */
    public int amount() { return this.entityData.get(AMOUNT); }

    /** How thick this piece is; 1 is the usual honey or slime. */
    public float thick() { return this.entityData.get(THICK); }

    /** Server side, before the marker is added to the world. */
    public void setThick(float thick) { this.entityData.set(THICK, GooThickness.sane(thick)); }

    /** Poured or thrown, rather than a block that turned into goo. */
    public boolean poured() { return poured; }

    /** Where goo last came into this piece or was taken out of it. */
    public Vec3 spot() {
        Vector3fc spot = this.entityData.get(SPOT);
        BlockPos block = this.entityData.get(SPOT_BLOCK);
        return new Vec3(block.getX() + spot.x(), block.getY() + spot.y(), block.getZ() + spot.z());
    }

    private void setSpot(Vec3 at) {
        BlockPos block = BlockPos.containing(at);
        this.entityData.set(SPOT_BLOCK, block);
        this.entityData.set(SPOT, new Vector3f((float) (at.x - block.getX()), (float) (at.y - block.getY()), (float) (at.z - block.getZ())));
    }

    /** The velocity the last goo came in with, in blocks per second. */
    public Vector3fc push() { return this.entityData.get(PUSH); }

    public boolean lump() { return this.entityData.get(LUMP); }

    /** Server side, before the marker is added to the world: this piece starts empty and is filled by {@link #pour}. */
    public void startEmpty() {
        this.poured = true;
        this.entityData.set(AMOUNT, 0);
    }

    /**
     * Server side: lets up to {@code count} more particles of goo into this piece, coming from
     * {@code from} with a velocity (blocks per second), as a stream or as one lump. Returns how
     * many it had room for.
     */
    public int pour(Vec3 from, Vec3 velocity, int count, boolean lump) {
        int now = amount(), fits = Math.max(0, Math.min(count, GooSim.BLOCK - now));
        if (fits == 0) return 0;
        setSpot(from);
        this.entityData.set(PUSH, new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
        this.entityData.set(LUMP, lump);
        this.entityData.set(AMOUNT, now + fits);
        return fits;
    }

    /** Server side: takes up to {@code count} particles of goo out of this piece, near a place. The piece goes when nothing is left. Returns how many were taken. */
    public int scoop(Vec3 at, int count) {
        int now = amount(), taken = Math.max(0, Math.min(count, now));
        if (taken == 0) return 0;
        setSpot(at);
        this.entityData.set(AMOUNT, now - taken);
        if (now - taken < GooSim.BALL / 4) this.discard(); // a few last drops are not worth a marker
        return taken;
    }

    /** Drawn as clear liquid rather than with the block texture. Follows the game rule. */
    public boolean clear() { return this.entityData.get(CLEAR); }

    /** Server side: takes the look from the game rule (cheap enough to do every tick). */
    public void applyLook(ServerLevel level) {
        boolean want = level.getGameRules().get(PhysicsBruhh.CLEAR_GOO.get());
        if (want != this.entityData.get(CLEAR)) this.entityData.set(CLEAR, want);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CLEAR, false);
        builder.define(AMOUNT, GooSim.BLOCK);
        builder.define(SPOT_BLOCK, BlockPos.ZERO);
        builder.define(SPOT, new Vector3f());
        builder.define(PUSH, new Vector3f());
        builder.define(LUMP, false);
        builder.define(THICK, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            ageTicks++;
            applyLook(serverLevel);
            if (shiftIn > 0 && --shiftIn == 0) this.setPos(this.getX() + shiftX, this.getY() + shiftY, this.getZ() + shiftZ);
            touch(serverLevel);
            // keep the marker resting on something, so it stays near the goo that slid down
            if (ageTicks % 10 == 0) {
                BlockPos below = BlockPos.containing(this.getX(), this.getY() - 0.05, this.getZ());
                if (this.level().getBlockState(below).getCollisionShape(this.level(), below).isEmpty() && this.getY() > this.level().getMinY()) {
                    this.setPos(this.getX(), Math.floor(this.getY() - 0.05), this.getZ());
                }
            }
        }
    }

    /**
     * Where the goo roughly is, as far as the server can tell: the block it started in for slime,
     * a wider and flatter patch for honey once it has melted. (The real shape only exists on the clients.)
     */
    public AABB reach() {
        double x = this.getX(), y = this.getY(), z = this.getZ();
        // less than a block of it: a smaller lump, a smaller puddle
        double part = Math.max(0.05, Math.min(1.0, amount() / (double) GooSim.BLOCK));
        if (material() == GooSim.SLIME) {
            double side = Math.cbrt(part);
            return new AABB(x - 0.5 * side, y, z - 0.5 * side, x + 0.5 * side, y + side, z + 0.5 * side);
        }
        double melted = poured ? 1.0 : Math.min(1.0, ageTicks / 80.0);
        double r = (0.5 + 0.55 * melted) * Math.sqrt(part), h = Math.max(0.2, (1.0 - 0.65 * melted) * Math.sqrt(part));
        return new AABB(x - r, y, z - r, x + r, y + h, z + r);
    }

    /** The marker follows a piston that shoves or drags the goo; a fresh one waits until the clients have seen where it started. */
    public void shift(Direction direction, boolean later) {
        if (later) { shiftIn = 8; shiftX = direction.getStepX(); shiftY = direction.getStepY(); shiftZ = direction.getStepZ(); }
        else this.setPos(this.getX() + direction.getStepX(), this.getY() + direction.getStepY(), this.getZ() + direction.getStepZ());
    }

    /**
     * What the original blocks do to things that touch them, for everything but players (those feel
     * the real goo on their own client): slime bounces what falls on it and slows what walks on it,
     * honey slows everything down.
     */
    private void touch(ServerLevel level) {
        boolean slime = material() == GooSim.SLIME;
        // players feel the goo on their own client; here only their fall is forgotten, as landing on a slime block would do
        if (slime) for (Player player : level.getEntitiesOfClass(Player.class, reach(), p -> !p.isSpectator() && !p.isSuppressingBounce())) player.resetFallDistance();
        for (Entity e : level.getEntities(this, reach(), other -> !(other instanceof GooEntity) && !(other instanceof Player) && !other.isSpectator() && other.isAlive())) {
            Vec3 v = e.getDeltaMovement();
            if (slime) {
                if (v.y < -0.12 && !e.isSuppressingBounce()) {
                    e.setDeltaMovement(v.x, -v.y * (e instanceof LivingEntity ? 1.0 : 0.8) * GooThickness.slimeBounce(thick()), v.z);
                    e.resetFallDistance();
                    level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.SLIME_BLOCK_FALL, SoundSource.BLOCKS, 0.6F, 1.0F);
                } else if (e.onGround() && Math.abs(v.y) < 0.1 && !e.isSteppingCarefully()) {
                    double f = 0.4 + Math.abs(v.y) * 0.2;
                    e.setDeltaMovement(v.x * f, v.y, v.z * f);
                }
            } else {
                double slow = GooThickness.honeySpeed(thick());
                e.setDeltaMovement(v.x * slow, v.y > 0.0 ? v.y * GooThickness.honeyJump(thick()) : v.y, v.z * slow);
            }
        }
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (this.isRemoved()) return false;
        boolean slime = material() == GooSim.SLIME;
        // the block's own breaking sound and particles
        level.levelEvent(null, 2001, this.blockPosition(), Block.getId((slime ? Blocks.SLIME_BLOCK : Blocks.HONEY_BLOCK).defaultBlockState()));
        // a whole block's worth comes back as the block; less slime as slime balls; less honey is lost (it takes a bottle to keep honey)
        int amount = amount();
        if (amount > GooSim.BLOCK - GooSim.BALL / 2) Block.popResource(level, this.blockPosition(), GooThickness.block(material(), thick()));
        else if (slime && amount >= GooSim.BALL / 2) Block.popResource(level, this.blockPosition(), new ItemStack(Items.SLIME_BALL, Math.max(1, Math.round(amount / (float) GooSim.BALL))));
        this.discard();
        return true;
    }

    /**
     * Right click on the goo (anywhere on it: the client says where its line of sight met the real goo).
     * Honey: an empty bottle is filled, a honey bottle is poured in, an empty hand takes a taste.
     * Slime: an empty hand tears a slime ball off, a slime ball is pressed on.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        ItemStack stack = player.getItemInHand(hand);
        boolean honey = material() == GooSim.HONEY, bare = stack.isEmpty() && hand == InteractionHand.MAIN_HAND;
        boolean ours = honey ? bare || stack.is(Items.GLASS_BOTTLE) || stack.is(Items.HONEY_BOTTLE) : bare || stack.is(Items.SLIME_BALL);
        if (!ours || this.isRemoved()) return InteractionResult.PASS;
        if (!(this.level() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        Vec3 at = this.position().add(location);
        Vec3 hand0 = player.getEyePosition().add(player.getLookAngle().scale(0.45)).add(0.0, -0.22, 0.0);
        if (honey && stack.is(Items.GLASS_BOTTLE)) {
            if (amount() < GooSim.BOTTLE / 2) return InteractionResult.PASS;
            scoop(at, GooSim.BOTTLE);
            sound(level, at, SoundEvents.BOTTLE_FILL, 1.0F);
            player.setItemInHand(hand, net.minecraft.world.item.ItemUtils.createFilledResult(stack, player, new ItemStack(Items.HONEY_BOTTLE)));
        } else if (honey && stack.is(Items.HONEY_BOTTLE)) {
            Vec3 velocity = at.subtract(hand0).normalize().scale(2.0);
            if (GooEvents.pour(level, GooSim.HONEY, hand0, velocity, at, GooSim.BOTTLE, false) == 0) return InteractionResult.PASS;
            sound(level, at, SoundEvents.BOTTLE_EMPTY, 1.0F);
            player.setItemInHand(hand, net.minecraft.world.item.ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
        } else if (honey) {
            // a taste: a third of a bottle
            scoop(at, GooSim.BOTTLE / 3);
            player.getFoodData().eat(2, 0.1F);
            sound(level, at, SoundEvents.HONEY_BLOCK_SLIDE, 0.9F);
        } else if (stack.is(Items.SLIME_BALL)) {
            Vec3 velocity = at.subtract(hand0).normalize().scale(3.0);
            if (GooEvents.pour(level, GooSim.SLIME, hand0.add(player.getLookAngle().scale(0.35)), velocity, at, GooSim.BALL, true) == 0) return InteractionResult.PASS;
            sound(level, at, SoundEvents.SLIME_BLOCK_PLACE, 0.9F);
            stack.consume(1, player);
        } else {
            if (amount() < GooSim.BALL / 2) return InteractionResult.PASS;
            scoop(at, GooSim.BALL);
            sound(level, at, SoundEvents.SLIME_BLOCK_BREAK, 0.8F);
            if (!player.getInventory().add(new ItemStack(Items.SLIME_BALL))) Block.popResource(level, BlockPos.containing(at), new ItemStack(Items.SLIME_BALL));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent event, float pitch) {
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.BLOCKS, 0.8F, pitch);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        ageTicks = input.getIntOr("Age", 0);
        poured = input.getBooleanOr("Poured", false);
        this.entityData.set(THICK, GooThickness.sane(input.getFloatOr("Thick", 1.0F)));
        this.entityData.set(AMOUNT, Math.max(1, Math.min(GooSim.BLOCK, input.getIntOr("Amount", GooSim.BLOCK))));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("Age", ageTicks);
        output.putBoolean("Poured", poured);
        output.putFloat("Thick", thick());
        output.putInt("Amount", amount());
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(ageTicks);
        buffer.writeFloat(leanX);
        buffer.writeFloat(leanZ);
        buffer.writeBoolean(poured);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        spawnAgeTicks = buffer.readVarInt();
        leanX = buffer.readFloat();
        leanZ = buffer.readFloat();
        poured = buffer.readBoolean();
    }
}
