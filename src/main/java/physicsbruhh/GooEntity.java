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
import physicsbruhh.sim.GooSim;

/**
 * Server-side stand-in for one block worth of goo. It has no physics of its own: it marks where
 * the goo started, how old it is and what it was made of. Clients turn it into simulated goo when
 * it enters their view. Hitting it gives the original block back.
 */
public class GooEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Boolean> CLEAR = SynchedEntityData.defineId(GooEntity.class, EntityDataSerializers.BOOLEAN);
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
        if (material() == GooSim.SLIME) return new AABB(x - 0.5, y, z - 0.5, x + 0.5, y + 1.0, z + 0.5);
        double melted = Math.min(1.0, ageTicks / 80.0);
        double r = 0.5 + 0.55 * melted, h = 1.0 - 0.65 * melted;
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
                    e.setDeltaMovement(v.x, -v.y * (e instanceof LivingEntity ? 1.0 : 0.8), v.z);
                    e.resetFallDistance();
                    level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.SLIME_BLOCK_FALL, SoundSource.BLOCKS, 0.6F, 1.0F);
                } else if (e.onGround() && Math.abs(v.y) < 0.1 && !e.isSteppingCarefully()) {
                    double f = 0.4 + Math.abs(v.y) * 0.2;
                    e.setDeltaMovement(v.x * f, v.y, v.z * f);
                }
            } else {
                e.setDeltaMovement(v.x * 0.4, v.y > 0.0 ? v.y * 0.5 : v.y, v.z * 0.4);
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
        // the block's own breaking sound and particles
        level.levelEvent(null, 2001, this.blockPosition(), Block.getId((material() == GooSim.SLIME ? Blocks.SLIME_BLOCK : Blocks.HONEY_BLOCK).defaultBlockState()));
        Block.popResource(level, this.blockPosition(), new ItemStack(material() == GooSim.SLIME ? Items.SLIME_BLOCK : Items.HONEY_BLOCK));
        this.discard();
        return true;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        ageTicks = input.getIntOr("Age", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("Age", ageTicks);
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(ageTicks);
        buffer.writeFloat(leanX);
        buffer.writeFloat(leanZ);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        spawnAgeTicks = buffer.readVarInt();
        leanX = buffer.readFloat();
        leanZ = buffer.readFloat();
    }
}
