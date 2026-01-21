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
            .comment("Number of chunks to cleanup per tick (default: 1)",
                    "Lower values = less lag during cleanup")
            .defineInRange("cleanupChunksPerTick", 1, 1, 10);

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

    public static final ModConfigSpec.IntValue PORTAL_DURATION = BUILDER
            .comment("(Deprecated) Use entryPortalLifetime instead. Portal activation duration in seconds (default: 5)")
            .defineInRange("portalDuration", 5, 1, 60);

    public static final ModConfigSpec.IntValue TELEPORT_COOLDOWN = BUILDER
            .comment("Teleport cooldown in ticks (default: 60 = 3 seconds)",
                    "Prevents instant return after entering mirror world")
            .defineInRange("teleportCooldown", 60, 0, 200);

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

    // ==================== Mob Spawning ====================
    
    public static final ModConfigSpec.BooleanValue COPY_ENTITIES = BUILDER
            .comment("Copy entities (mobs, animals, etc.) when creating mirror world (default: false)",
                    "When false, only blocks are copied - the mirror world starts without mobs.",
                    "When true, all entities in the copied area will be duplicated.")
            .define("copyEntities", false);

    public static final ModConfigSpec.BooleanValue ENABLE_MOB_SPAWNING = BUILDER
            .comment("Enable natural mob spawning in mirror world (default: false)",
                    "When false, no mobs will spawn naturally in mirror worlds.",
                    "This is separate from copying existing mobs.")
            .define("enableMobSpawning", false);

    // ==================== Server Limits ====================
    
    public static final ModConfigSpec.IntValue MAX_MIRROR_WORLDS_PER_PLAYER = BUILDER
            .comment("Maximum concurrent mirror world sessions per player (default: 1)",
                    "Usually 1 is enough since players can only be in one world at a time.")
            .defineInRange("maxMirrorWorldsPerPlayer", 1, 1, 5);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ==================== Helper Methods ====================
    
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
}
