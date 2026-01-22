package com.crabmods.instantworldmirror.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side cooldown tracker for the Dimension Mirror
 * Stores the cooldown end timestamp synced from the server
 * Used by the HUD overlay to display remaining cooldown time
 */
@OnlyIn(Dist.CLIENT)
public class ClientCooldownTracker {
    
    // Cooldown end timestamp in milliseconds since epoch (0 = no cooldown)
    private static long cooldownEndTimestamp = 0;
    
    /**
     * Set the cooldown end timestamp (called from network packet handler)
     * @param timestamp The timestamp when the cooldown ends, or 0 to clear
     */
    public static void setCooldownEndTimestamp(long timestamp) {
        cooldownEndTimestamp = timestamp;
    }
    
    /**
     * Get the remaining cooldown time in milliseconds
     * @return Remaining time in milliseconds, or 0 if no cooldown
     */
    public static long getRemainingCooldownMillis() {
        if (cooldownEndTimestamp == 0) {
            return 0;
        }
        long remaining = cooldownEndTimestamp - System.currentTimeMillis();
        if (remaining <= 0) {
            cooldownEndTimestamp = 0; // Clean up expired cooldown
            return 0;
        }
        return remaining;
    }
    
    /**
     * Check if there is an active cooldown
     * @return true if there is an active cooldown
     */
    public static boolean hasCooldown() {
        return getRemainingCooldownMillis() > 0;
    }
    
    /**
     * Clear the cooldown (called when disconnecting)
     */
    public static void clear() {
        cooldownEndTimestamp = 0;
    }
    
    /**
     * Format the remaining cooldown time as MM:SS
     * @return Formatted time string, or null if no cooldown
     */
    public static String formatRemainingTime() {
        long millisLeft = getRemainingCooldownMillis();
        if (millisLeft <= 0) {
            return null;
        }
        
        long secondsLeft = millisLeft / 1000;
        long minutesLeft = secondsLeft / 60;
        secondsLeft %= 60;
        
        return String.format("%02d:%02d", minutesLeft, secondsLeft);
    }
}
