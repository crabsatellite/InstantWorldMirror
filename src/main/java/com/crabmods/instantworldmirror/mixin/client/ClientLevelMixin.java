package com.crabmods.instantworldmirror.mixin.client;

import com.crabmods.instantworldmirror.client.MirrorDimensionEffectsManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to override dimension effects for mirror worlds
 * This allows mirror worlds to display the visual effects of their source dimension
 * (e.g., End sky, Nether fog, etc.)
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    
    /**
     * Intercept the effects() call to return source dimension effects for mirror worlds
     */
    @Inject(method = "effects", at = @At("HEAD"), cancellable = true)
    private void onGetEffects(CallbackInfoReturnable<DimensionSpecialEffects> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        DimensionSpecialEffects overriddenEffects = MirrorDimensionEffectsManager.getEffectsForLevel(self);
        if (overriddenEffects != null) {
            cir.setReturnValue(overriddenEffects);
        }
    }
}
