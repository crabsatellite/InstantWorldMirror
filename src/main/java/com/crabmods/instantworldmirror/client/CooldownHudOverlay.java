package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * HUD overlay that displays the Dimension Mirror cooldown timer
 * Shows a countdown timer in the corner when the player has a mirror on cooldown
 * 
 * Design inspired by Waystones mod's cooldown display, but with:
 * - Different color scheme (cyan/blue tones instead of pink)
 * - Additional mirror icon indicator
 * - Shadow effect for better readability
 */
@OnlyIn(Dist.CLIENT)
public class CooldownHudOverlay implements IGuiOverlay {
    
    public static final ResourceLocation OVERLAY_ID = new ResourceLocation(
            InstantWorldMirror.MODID, "cooldown_hud");
    
    // Color scheme - using cyan/blue tones to differentiate from Waystones
    private static final int TIMER_COLOR = 0xFF88DDFF;         // Light cyan for timer text
    private static final int TIMER_SHADOW_COLOR = 0xFF224466;  // Dark blue for shadow
    
    // Position offset from top-left corner
    private static final int OFFSET_X = 10;
    private static final int OFFSET_Y = 10;
    
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // Check if HUD display is enabled in client config
        if (!ClientConfig.shouldShowCooldownHud()) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        
        // Don't render if player doesn't exist or is in spectator mode
        if (player == null || player.isSpectator()) {
            return;
        }
        
        // Only show if player has a Dimension Mirror in their inventory
        if (!hasModMirrorInInventory(player)) {
            return;
        }
        
        // Check if there's an active cooldown
        if (!ClientCooldownTracker.hasCooldown()) {
            return;
        }
        
        // Get formatted time
        String timeText = ClientCooldownTracker.formatRemainingTime();
        if (timeText == null) {
            return;
        }
        
        Font font = mc.font;
        
        // Render icon (small mirror indicator) - using a simple text prefix
        String displayText = "◈ " + timeText;  // Diamond symbol as mirror indicator
        
        // Draw shadow first (offset by 1 pixel)
        guiGraphics.drawString(font, displayText, OFFSET_X + 1, OFFSET_Y + 1, TIMER_SHADOW_COLOR, false);
        
        // Draw main text
        guiGraphics.drawString(font, displayText, OFFSET_X, OFFSET_Y, TIMER_COLOR, false);
    }
    
    /**
     * Check if the player has a Dimension Mirror in their inventory
     */
    private boolean hasModMirrorInInventory(LocalPlayer player) {
        // Check main inventory
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ModItems.DIMENSION_MIRROR.get())) {
                return true;
            }
        }
        
        // Check offhand
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.is(ModItems.DIMENSION_MIRROR.get())) {
            return true;
        }
        
        return false;
    }
}
