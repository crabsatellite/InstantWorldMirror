package com.crabmods.instantworldmirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.IItemDecorator;

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
    private static final int BAR_BG_COLOR = 0xFF000000;   // Black background (like vanilla)
    
    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        // Check if there's an active cooldown
        if (!ClientCooldownTracker.hasCooldown()) {
            return false;
        }
        
        long remainingMillis = ClientCooldownTracker.getRemainingCooldownMillis();
        long totalCooldownMillis = ClientCooldownTracker.getTotalCooldownMillis();
        
        if (remainingMillis <= 0 || totalCooldownMillis <= 0) {
            return false;
        }
        
        // Calculate charge progress (0.0 = empty/just started, 1.0 = fully charged/ready to use)
        // This is the INVERSE of remaining time - as time passes, charge increases
        float chargeProgress = 1.0f - Math.min(1.0f, (float) remainingMillis / totalCooldownMillis);
        
        // Bar dimensions - same as vanilla durability bar
        // Vanilla: starts at x+2, y+13, width 13, height 2 (1px background + 1px bar)
        int barX = xOffset + 2;
        int barY = yOffset + 13;
        int barWidth = 13;
        
        // Push pose to render above item (z = 200 like vanilla overlays)
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 200);
        
        // Disable depth test to render on top
        RenderSystem.disableDepthTest();
        
        // Draw black background bar (full width, 2px height like vanilla)
        guiGraphics.fill(barX, barY, barX + barWidth, barY + 2, BAR_BG_COLOR);
        
        // Draw cyan charging bar (1px height, on top of background)
        // Bar fills from left to right as the item charges
        int filledWidth = Math.round(barWidth * chargeProgress);
        if (filledWidth > 0) {
            guiGraphics.fill(barX, barY, barX + filledWidth, barY + 1, BAR_COLOR);
        }
        
        RenderSystem.enableDepthTest();
        guiGraphics.pose().popPose();
        
        return true; // We modified render state
    }
}
