package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Dimension Registry Class
 * Uses a pool of mirror world dimensions for session isolation
 * 
 * Note: We pre-define 8 dimensions (max allowed), but only use the configured amount.
 * This allows config changes without needing to regenerate dimension files.
 */
public class ModDimensions {
    
    // Maximum number of mirror world dimensions (must match JSON files)
    public static final int MAX_MIRROR_WORLD_POOL_SIZE = 8;
    
    // Configured pool size (read from config, capped at MAX)
    private static int configuredPoolSize = 4; // Default, updated on config load
    
    // Mirror World Dimension Type (shared by all instances)
    public static final ResourceKey<DimensionType> MIRROR_WORLD_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(InstantWorldMirror.MODID, "mirror_world")
    );
    
    // Pool of all possible mirror world dimensions (pre-defined)
    public static final List<ResourceKey<Level>> MIRROR_WORLD_POOL = new ArrayList<>();
    
    static {
        // Initialize all possible dimensions (up to max)
        for (int i = 0; i < MAX_MIRROR_WORLD_POOL_SIZE; i++) {
            ResourceKey<Level> dimension = ResourceKey.create(
                    Registries.DIMENSION,
                    new ResourceLocation(InstantWorldMirror.MODID, "mirror_world_" + i)
            );
            MIRROR_WORLD_POOL.add(dimension);
        }
    }
    
    /**
     * Update pool size from config (call during mod initialization)
     */
    public static void updatePoolSizeFromConfig() {
        int configured = MirrorConfig.DIMENSION_POOL_SIZE.get();
        configuredPoolSize = Math.min(configured, MAX_MIRROR_WORLD_POOL_SIZE);
        InstantWorldMirror.LOGGER.info("Mirror dimension pool size set to {}", configuredPoolSize);
    }
    
    /**
     * Get the configured pool size
     */
    public static int getPoolSize() {
        return configuredPoolSize;
    }
    
    /**
     * Get dimension by index (must be within configured pool size)
     */
    public static ResourceKey<Level> getMirrorWorld(int index) {
        if (index < 0 || index >= configuredPoolSize) {
            return MIRROR_WORLD_POOL.get(0);
        }
        return MIRROR_WORLD_POOL.get(index);
    }
    
    /**
     * Check if a dimension is an active mirror world (within configured pool)
     */
    public static boolean isMirrorWorld(ResourceKey<Level> dimension) {
        int index = MIRROR_WORLD_POOL.indexOf(dimension);
        return index >= 0 && index < configuredPoolSize;
    }
    
    /**
     * Check if a dimension is ANY mirror world dimension (regardless of pool size config)
     * Used for emergency returns when pool size was changed after player entered
     */
    public static boolean isAnyMirrorWorld(ResourceKey<Level> dimension) {
        return MIRROR_WORLD_POOL.contains(dimension);
    }
    
    /**
     * Get the index of a mirror world dimension (-1 if not a mirror world or out of pool)
     */
    public static int getMirrorWorldIndex(ResourceKey<Level> dimension) {
        int index = MIRROR_WORLD_POOL.indexOf(dimension);
        if (index >= 0 && index < configuredPoolSize) {
            return index;
        }
        return -1;
    }
}
