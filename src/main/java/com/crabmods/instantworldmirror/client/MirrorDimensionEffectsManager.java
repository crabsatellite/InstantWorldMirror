package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.ModDimensions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side manager for mirror dimension visual effects
 * Tracks which source dimension each mirror world should mimic
 * Dynamically supports any dimension (vanilla or modded)
 */
public class MirrorDimensionEffectsManager {
    
    // Map of mirror dimension effects key to source dimension effects key.
    // Temporary mirrors use their pool index; persistent mirrors use 10000 + pool index.
    private static final Map<Integer, ResourceLocation> mirrorToSourceEffects = new ConcurrentHashMap<>();
    
    // Cached DimensionSpecialEffects instances
    private static final Map<ResourceLocation, DimensionSpecialEffects> effectsCache = new ConcurrentHashMap<>();
    
    /**
     * Set the source dimension effects for a mirror dimension
     * Called when receiving sync packet from server
     * @param mirrorDimIndex The mirror dimension index
     * @param sourceEffects The effects location from the source dimension's DimensionType
     */
    public static void setSourceEffects(int mirrorDimIndex, ResourceLocation sourceEffects) {
        mirrorToSourceEffects.put(mirrorDimIndex, sourceEffects);
        InstantWorldMirror.LOGGER.debug("Set mirror dimension {} to use effects: {}", 
                mirrorDimIndex, sourceEffects);
    }
    
    /**
     * Clear the source effects for a mirror dimension (when session ends)
     */
    public static void clearSourceEffects(int mirrorDimIndex) {
        mirrorToSourceEffects.remove(mirrorDimIndex);
    }
    
    /**
     * Clear all cached effects (e.g., on disconnect)
     */
    public static void clearAll() {
        mirrorToSourceEffects.clear();
        effectsCache.clear();
    }
    
    /**
     * Get the dimension effects for a client level
     * Returns null if this is not a mirror world or if no override is set
     */
    public static DimensionSpecialEffects getEffectsForLevel(ClientLevel level) {
        if (level == null) return null;
        
        ResourceKey<Level> dimension = level.dimension();
        
        // Check if this is a mirror dimension
        int effectsKey = ModDimensions.getMirrorEffectsKey(dimension);
        if (effectsKey < 0) {
            return null; // Not a mirror dimension
        }
        
        // Get the source effects for this mirror dimension
        ResourceLocation sourceEffects = mirrorToSourceEffects.get(effectsKey);
        if (sourceEffects == null) {
            return null; // No override set, use default
        }
        
        // Get or create the DimensionSpecialEffects
        return getOrCreateEffects(sourceEffects);
    }
    
    /**
     * Get or create DimensionSpecialEffects for a given effects key
     * Uses Forge's DimensionSpecialEffectsManager which supports all registered effects
     */
    private static DimensionSpecialEffects getOrCreateEffects(ResourceLocation effectsKey) {
        return effectsCache.computeIfAbsent(effectsKey, key -> {
            // Use Forge 1.20.1's DimensionSpecialEffectsManager for effects lookup
            // This works for vanilla (overworld, the_nether, the_end) and any mod-registered effects
            return net.minecraftforge.client.DimensionSpecialEffectsManager.getForType(key);
        });
    }
}
