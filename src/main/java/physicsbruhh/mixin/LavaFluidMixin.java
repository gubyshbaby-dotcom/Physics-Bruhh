package physicsbruhh.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import physicsbruhh.LavaFlow;

/**
 * The one place the mod reaches into the game's own code. When lava is about to put a copy of
 * itself into the block next to it, {@link LavaFlow} is asked first; if the lava is a whole block
 * near a player, the block lets go and flows out as a body instead, and nothing is put anywhere.
 * (There is no event for this. The hook is not required: where it cannot be put in, lava simply
 * flows the way it always did.)
 */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true, require = 0)
    private void physicsbruhh$ooze(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo info) {
        if (LavaFlow.ooze(level, pos, direction)) info.cancel();
    }
}
