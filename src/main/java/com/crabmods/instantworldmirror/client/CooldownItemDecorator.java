package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

/**
 * Item decorator that renders a charging bar on the World Reflection Mirror item
 * Similar to vanilla durability bar style
 * 
 * Shows a cyan charging bar at the bottom of the item that fills up as cooldown expires
 * When fully charged (bar full), the mirror can be used again
 */
@OnlyIn(Dist.CLIENT)
public class CooldownItemDecorator implements IItemDecorator {
    
    // Bar colors - cyan theme to match the HUD
    private static final int BAR_COLOR = 0xFF00CCFF;      // Bright cyan
    private static final int RENEWAL_BAR_COLOR = 0xFF7CFF4A;
    private static final int BAR_BG_COLOR = 0xFF000000;   // Black background (like vanilla)
    
    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        long remainingMillis = ClientCooldownTracker.getRemainingCooldownMillis();
        long totalCooldownMillis = ClientCooldownTracker.getTotalCooldownMillis();
        long renewalRemainingMillis = DimensionMirrorItem.getGeneratedContentRefreshRemainingMillis(stack);
        boolean hasMirrorCooldown = ClientCooldownTracker.hasCooldown()
                && remainingMillis > 0
                && totalCooldownMillis > 0;
        boolean hasRenewalCooldown = renewalRemainingMillis > 0;
        
        if (!hasMirrorCooldown && !hasRenewalCooldown) {
            return false;
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 200);
        RenderSystem.disableDepthTest();

        if (hasRenewalCooldown) {
            long renewalCooldownDurationMillis = DimensionMirrorItem.getGeneratedContentRefreshCooldownDurationMillis(stack);
            float renewalProgress = 1.0f - Math.min(1.0f,
                    (float) renewalRemainingMillis / renewalCooldownDurationMillis);
            renderBar(guiGraphics, xOffset + 2, yOffset + 10, renewalProgress, RENEWAL_BAR_COLOR);
        }

        if (hasMirrorCooldown) {
            float chargeProgress = 1.0f - Math.min(1.0f, (float) remainingMillis / totalCooldownMillis);
            renderBar(guiGraphics, xOffset + 2, yOffset + 13, chargeProgress, BAR_COLOR);
        }
        
        RenderSystem.enableDepthTest();
        guiGraphics.pose().popPose();
        
        return true; // We modified render state
    }

    private static void renderBar(GuiGraphics guiGraphics, int barX, int barY, float progress, int color) {
        int barWidth = 13;
        guiGraphics.fill(barX, barY, barX + barWidth, barY + 2, BAR_BG_COLOR);
        int filledWidth = Math.round(barWidth * progress);
        if (filledWidth > 0) {
            guiGraphics.fill(barX, barY, barX + filledWidth, barY + 1, color);
        }
    }
}
