package com.crabmods.instantworldmirror.mixin;

import com.crabmods.instantworldmirror.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to suppress "Invalid block entity" errors in mirror dimensions.
 * 
 * This error occurs when chunk NBT contains block entity data for a position,
 * but the actual block at that position is air (due to async world copy/cleanup timing).
 * 
 * The vanilla BlockEntity.validateBlockState throws IllegalStateException when
 * the block doesn't match, which crashes the chunk loading process.
 * 
 * This mixin intercepts the validation and silently skips invalid block entities
 * in mirror dimensions instead of throwing an exception.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    /**
     * Intercept the validateBlockState call and suppress the exception for mirror dimensions.
     * 
     * We check if the current level is a mirror dimension, and if so, we catch the
     * validation failure and cancel the exception throw by returning early.
     */
    @Inject(
        method = "validateBlockState",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onValidateBlockState(BlockEntityType<?> type, Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        // Only apply this fix in mirror dimensions (use isAnyMirrorWorld to catch all mirror dimensions)
        if (level != null && ModDimensions.isAnyMirrorWorld(level.dimension())) {
            // Check if the block state is valid for this block entity type
            if (!type.isValid(state)) {
                // In mirror dimensions, silently skip invalid block entities instead of throwing
                // This prevents the IllegalStateException during chunk loading
                // The block entity simply won't be created, which is fine since the block is air anyway
                ci.cancel();
            }
        }
    }
}
