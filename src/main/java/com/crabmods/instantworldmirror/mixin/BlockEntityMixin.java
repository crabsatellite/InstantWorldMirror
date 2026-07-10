package com.crabmods.instantworldmirror.mixin;

import com.crabmods.instantworldmirror.world.ModDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Mixin to suppress "Invalid block entity" errors in mirror dimensions.
 * 
 * This error occurs when chunk NBT contains block entity data for a position,
 * but the actual block at that position is air (due to async world copy/cleanup timing).
 * 
 * The vanilla BlockEntity.validateBlockState throws IllegalStateException when
 * the block doesn't match, which crashes the chunk loading process.
 * 
 * This mixin intercepts the isValidBlockState check and returns true for mirror
 * dimensions to prevent the exception from being thrown.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Shadow @Nullable protected Level level;

    /**
     * Intercept the isValidBlockState check and return true for mirror dimensions.
     * 
     * Since validateBlockState is called from the constructor before level is set,
     * this must read the backing field instead of dispatching getLevel() to a subclass
     * whose constructor has not completed yet.
     * 
     * For the constructor call, level will be null, so we let the normal check happen.
     * For setBlockState calls in mirror dimensions, we return true to skip validation.
     */
    @Inject(
        method = "isValidBlockState",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsValidBlockState(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        Level currentLevel = this.level;
        // Only apply this fix in mirror dimensions when level is already set
        if (currentLevel != null && ModDimensions.isAnyMirrorWorld(currentLevel.dimension())) {
            // Return true to skip validation in mirror dimensions
            // This prevents IllegalStateException during chunk loading/block entity updates
            cir.setReturnValue(true);
        }
    }
}
