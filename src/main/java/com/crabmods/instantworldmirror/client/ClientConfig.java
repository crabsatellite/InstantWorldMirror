package com.crabmods.instantworldmirror.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client-side configuration for visual and UI settings
 * These settings only affect the local client
 */
@OnlyIn(Dist.CLIENT)
public class ClientConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ==================== HUD Settings ====================
    
    public static final ForgeConfigSpec.BooleanValue SHOW_COOLDOWN_HUD = BUILDER
            .comment("Show cooldown timer HUD in the top-left corner (default: false)",
                    "When enabled, displays the remaining cooldown time for the Dimension Mirror.",
                    "The item bar already shows a charging indicator, so this is optional.")
            .define("showCooldownHud", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
    
    // ==================== Helper Methods ====================
    
    /**
     * Check if cooldown HUD should be displayed
     */
    public static boolean shouldShowCooldownHud() {
        return SHOW_COOLDOWN_HUD.get();
    }
}
