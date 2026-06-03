package com.crabmods.instantworldmirror;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod configuration class
 * All settings can be changed in the config file or via /config command
 */
public class MirrorConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ==================== Dimension Pool Settings ====================
    
    public static final ModConfigSpec.IntValue DIMENSION_POOL_SIZE = BUILDER
            .comment("Number of mirror world dimensions in the pool (default: 4, max: 8)",
                    "Each concurrent session needs its own dimension.",
                    "Increase this if you need more players using mirror worlds simultaneously.",
                    "Note: Requires server restart to take effect.")
            .defineInRange("dimensionPoolSize", 4, 1, 8);

    // ==================== World Copy Settings ====================
    
    public static final ModConfigSpec.IntValue COPY_CHUNK_RADIUS = BUILDER
            .comment("Mirror world copy radius in chunks (default: 10)",
                    "Higher values copy more area but take longer to complete.",
                    "Total chunks copied = (radius*2+1)^2, so radius 10 = 441 chunks")
            .defineInRange("copyChunkRadius", 10, 1, 32);

    public static final ModConfigSpec.IntValue COPY_CHUNKS_PER_TICK = BUILDER
            .comment("Number of chunks to copy per tick (default: 2)",
                    "Higher values = faster copy but more lag")
            .defineInRange("copyChunksPerTick", 2, 1, 10);

    public static final ModConfigSpec.IntValue CLEANUP_CHUNKS_PER_TICK = BUILDER
            .comment("Number of chunks to cleanup per tick (default: 4)",
                    "Higher values = faster cleanup. Cleanup uses optimized section-level clearing",
                    "which is much faster than block-by-block operations.")
            .defineInRange("cleanupChunksPerTick", 4, 1, 20);

    public static final ModConfigSpec.IntValue EDGE_CLEANUP_RADIUS = BUILDER
            .comment("Maximum extra radius (in chunks) for BFS edge cleanup scan (default: 3)",
                    "After main cleanup, uses BFS to find and clean structures extending beyond copy area.",
                    "BFS expands outward only when blocks are found, up to this maximum radius.",
                    "Higher values = can handle larger edge structures but with safety limit.",
                    "Set to 0 to disable edge cleanup scanning.")
            .defineInRange("edgeCleanupRadius", 3, 0, 16);

    // ==================== Portal Settings ====================
    
    public static final ModConfigSpec.IntValue ENTRY_PORTAL_LIFETIME = BUILDER
            .comment("Entry portal lifetime in seconds after world copy completes (default: 300 = 5 minutes)",
                    "This is how long the portal stays open in the overworld.",
                    "Set to -1 for permanent portals (until session ends)")
            .defineInRange("entryPortalLifetime", 300, -1, 3600);

    public static final ModConfigSpec.IntValue RETURN_PORTAL_LIFETIME = BUILDER
            .comment("Return portal lifetime in seconds (default: -1 = permanent)",
                    "This is how long the return portal stays in the mirror world.",
                    "Set to -1 for permanent portals (until player returns)")
            .defineInRange("returnPortalLifetime", -1, -1, 3600);

    public static final ModConfigSpec.IntValue MAX_PORTAL_LOADING_TIME = BUILDER
            .comment("Maximum time in seconds for portal to stay in loading state (default: 600 = 10 minutes)",
                    "If world copy doesn't complete within this time, portal is removed.",
                    "Increase this for slower servers or larger copy radius.")
            .defineInRange("maxPortalLoadingTime", 600, 60, 3600);

    // ==================== Item Rules ====================
    
    public static final ModConfigSpec.BooleanValue ALLOW_ITEM_TRANSFER = BUILDER
            .comment("Allow items to be transferred back to overworld by default (default: false)",
                    "When false, players lose all items gained in mirror world on return.",
                    "Can be overridden per-player with /mirror itemtransfer command")
            .define("allowItemTransfer", false);

    public static final ModConfigSpec.IntValue MIRROR_COOLDOWN = BUILDER
            .comment("World Reflection Mirror cooldown in seconds (default: 300 = 5 minutes)",
                    "This is how long players must wait between using the World Reflection Mirror.",
                    "Can be reduced with Efficiency enchantment (each level reduces by 20%).",
                    "Efficiency 5 = minimum cooldown of 1 minute.",
                    "Creative mode players have no cooldown.")
            .defineInRange("mirrorCooldown", 300, 0, 3600);

    // ==================== Mob Spawning ====================
    
    public static final ModConfigSpec.BooleanValue COPY_ENTITIES = BUILDER
            .comment("Copy entities (mobs, animals, etc.) when creating mirror world (default: false)",
                    "When false, only blocks are copied - the mirror world starts without mobs.",
                    "When true, all entities in the copied area will be duplicated.")
            .define("copyEntities", false);

    public static final ModConfigSpec.BooleanValue COPY_DECORATION_ENTITIES = BUILDER
            .comment("Copy decoration entities (paintings, item frames, armor stands) when creating mirror world (default: true)",
                    "These entities are typically used for decoration and are important to preserve.",
                    "This setting works independently of copyEntities - decoration entities are always copied when true.")
            .define("copyDecorationEntities", true);

    public static final ModConfigSpec.BooleanValue ENABLE_MOB_SPAWNING = BUILDER
            .comment("Enable natural mob spawning in mirror world (default: false)",
                    "When false, no mobs will spawn naturally in mirror worlds.",
                    "This is separate from copying existing mobs.")
            .define("enableMobSpawning", false);

    // ==================== Biome and Environment Settings ====================
    
    public static final ModConfigSpec.BooleanValue COPY_BIOMES = BUILDER
            .comment("Copy biome data when creating mirror world (default: true)",
                    "When true, biome data from the source world is copied to the mirror world.",
                    "This affects grass/foliage colors, mob spawning rules, and weather.",
                    "Important for mods like Twilight Forest that use custom biomes for sky effects.",
                    "Disable this only if you experience compatibility issues.")
            .define("copyBiomes", true);

    public static final ModConfigSpec.BooleanValue COPY_STRUCTURES = BUILDER
            .comment("Copy structure data when creating mirror world (default: true)",
                    "When true, structure starts and references are copied to the mirror world.",
                    "This is important for mods like Twilight Forest that track structure 'conquered' status.",
                    "Also affects vanilla structure detection (villages, strongholds, etc.).")
            .define("copyStructures", true);

    public static final ModConfigSpec.BooleanValue COPY_HEIGHTMAPS = BUILDER
            .comment("Copy heightmap data when creating mirror world (default: true)",
                    "When true, heightmaps are properly copied/regenerated for the mirror world.",
                    "Affects mob spawning locations and light calculations.")
            .define("copyHeightmaps", true);

    // ==================== Server Limits ====================
    
    public static final ModConfigSpec.IntValue MAX_MIRROR_WORLDS_PER_PLAYER = BUILDER
            .comment("Maximum concurrent mirror world sessions per player (default: 1)",
                    "Usually 1 is enough since players can only be in one world at a time.")
            .defineInRange("maxMirrorWorldsPerPlayer", 1, 1, 5);

    public static final ModConfigSpec.IntValue STALE_SESSION_CLEANUP_INTERVAL = BUILDER
            .comment("Interval in seconds for stale session cleanup check (default: 300 = 5 minutes)",
                    "This is a fallback mechanism to clean up orphaned sessions/dimensions.",
                    "Lower values = more frequent checks but slightly more CPU usage.",
                    "Set to 0 to disable automatic stale session cleanup.")
            .defineInRange("staleSessionCleanupInterval", 300, 0, 3600);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ==================== Runtime State ====================
    // These can be changed at runtime via commands, separate from persistent config
    
    // Runtime mob spawning state - can be toggled with /iwm mob on/off
    // null means use config value, true/false means override config
    private static Boolean runtimeMobSpawningEnabled = null;

    // ==================== Helper Methods ====================
    
    /**
     * Check if mob spawning is enabled in mirror world
     * Uses runtime override if set, otherwise falls back to config
     */
    public static boolean isMobSpawningEnabled() {
        if (runtimeMobSpawningEnabled != null) {
            return runtimeMobSpawningEnabled;
        }
        return ENABLE_MOB_SPAWNING.get();
    }
    
    /**
     * Set runtime mob spawning state (for /iwm mob command)
     * @param enabled true to enable, false to disable, null to use config default
     */
    public static void setRuntimeMobSpawning(Boolean enabled) {
        runtimeMobSpawningEnabled = enabled;
    }
    
    /**
     * Get the current mob spawning state (for status display)
     * @return "on" if enabled, "off" if disabled, "config" if using config default
     */
    public static String getMobSpawningStatus() {
        if (runtimeMobSpawningEnabled != null) {
            return runtimeMobSpawningEnabled ? "on (runtime)" : "off (runtime)";
        }
        return ENABLE_MOB_SPAWNING.get() ? "on (config)" : "off (config)";
    }

    /**
     * Get entry portal lifetime in ticks
     */
    public static int getEntryPortalLifetimeTicks() {
        int seconds = ENTRY_PORTAL_LIFETIME.get();
        return seconds == -1 ? -1 : seconds * 20;
    }

    /**
     * Get return portal lifetime in ticks
     */
    public static int getReturnPortalLifetimeTicks() {
        int seconds = RETURN_PORTAL_LIFETIME.get();
        return seconds == -1 ? -1 : seconds * 20;
    }

    /**
     * Get max portal loading time in ticks
     */
    public static int getMaxPortalLoadingTicks() {
        return MAX_PORTAL_LOADING_TIME.get() * 20;
    }
    
    /**
     * Get mirror cooldown in ticks
     */
    public static int getMirrorCooldownTicks() {
        return MIRROR_COOLDOWN.get() * 20;
    }
    
    /**
     * Get stale session cleanup interval in ticks
     * Returns 0 if disabled
     */
    public static int getStaleSessionCleanupTicks() {
        int seconds = STALE_SESSION_CLEANUP_INTERVAL.get();
        return seconds == 0 ? 0 : seconds * 20;
    }
}
