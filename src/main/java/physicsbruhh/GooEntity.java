package physicsbruhh;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import physicsbruhh.sim.GooSim;

/**
 * Server-side stand-in for one block worth of goo. It has no physics of its own: it marks where
 * the goo started, how old it is and what it was made of. Clients turn it into simulated goo when
 * it enters their view. Hitting it gives the original block back.
 */
public class GooEntity extends Entity implements IEntityWithComplexSpawn {
    private int ageTicks;
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

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            ageTicks++;
            // keep the marker resting on something, so it stays near the goo that slid down
            if (ageTicks % 10 == 0) {
                BlockPos below = BlockPos.containing(this.getX(), this.getY() - 0.05, this.getZ());
                if (this.level().getBlockState(below).getCollisionShape(this.level(), below).isEmpty() && this.getY() > this.level().getMinY()) {
                    this.setPos(this.getX(), Math.floor(this.getY() - 0.05), this.getZ());
                }
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
