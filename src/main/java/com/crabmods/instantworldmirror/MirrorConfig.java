package com.crabmods.instantworldmirror;

import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration class
 * All settings can be changed in the config file or via /config command
 */
public class MirrorConfig {
    public static final int CONFIG_PERMISSION_LEVEL = 3;
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ==================== Dimension Pool Settings ====================
    
    public static final ForgeConfigSpec.IntValue DIMENSION_POOL_SIZE = BUILDER
            .comment("Number of mirror world dimensions in the pool (default: 4, max: 8)",
                    "Each concurrent session needs its own dimension.",
                    "Increase this if you need more players using mirror worlds simultaneously.",
                    "Note: Requires server restart to take effect.")
            .defineInRange("dimensionPoolSize", 4, 1, 8);

    // ==================== World Copy Settings ====================
    
    public static final ForgeConfigSpec.IntValue COPY_CHUNK_RADIUS = BUILDER
            .comment("Legacy/global mirror world copy radius in chunks (default: 10).",
                    "New worlds use the per-mirror copy radius settings below.",
                    "This value is kept so old config files can be migrated without losing their previous behavior.")
            .defineInRange("copyChunkRadius", 10, 1, 32);

    public static final ForgeConfigSpec.IntValue COPY_CHUNKS_PER_TICK = BUILDER
            .comment("Number of chunks to copy per tick (default: 2)",
                    "Higher values = faster copy but more lag")
            .defineInRange("copyChunksPerTick", 2, 1, 10);

    public static final ForgeConfigSpec.IntValue CLEANUP_CHUNKS_PER_TICK = BUILDER
            .comment("Number of chunks to cleanup per tick (default: 4)",
                    "Higher values = faster cleanup. Cleanup uses optimized section-level clearing",
                    "which is much faster than block-by-block operations.")
            .defineInRange("cleanupChunksPerTick", 4, 1, 20);

    public static final ForgeConfigSpec.IntValue EDGE_CLEANUP_RADIUS = BUILDER
            .comment("Maximum extra radius (in chunks) for BFS edge cleanup scan (default: 3)",
                    "After main cleanup, uses BFS to find and clean structures extending beyond copy area.",
                    "BFS expands outward only when blocks are found, up to this maximum radius.",
                    "Higher values = can handle larger edge structures but with safety limit.",
                    "Set to 0 to disable edge cleanup scanning.")
            .defineInRange("edgeCleanupRadius", 3, 0, 16);

    // ==================== Portal Settings ====================
    
    public static final ForgeConfigSpec.IntValue ENTRY_PORTAL_LIFETIME = BUILDER
            .comment("Entry portal lifetime in seconds after world copy completes (default: 300 = 5 minutes)",
                    "This is how long the portal stays open in the overworld.",
                    "Set to -1 for permanent portals (until session ends)")
            .defineInRange("entryPortalLifetime", 300, -1, 3600);

    public static final ForgeConfigSpec.IntValue RETURN_PORTAL_LIFETIME = BUILDER
            .comment("Return portal lifetime in seconds (default: -1 = permanent)",
                    "This is how long the return portal stays in the mirror world.",
                    "Set to -1 for permanent portals (until player returns)")
            .defineInRange("returnPortalLifetime", -1, -1, 3600);

    public static final ForgeConfigSpec.IntValue MAX_PORTAL_LOADING_TIME = BUILDER
            .comment("Maximum time in seconds for portal to stay in loading state (default: 600 = 10 minutes)",
                    "If world copy doesn't complete within this time, portal is removed.",
                    "Increase this for slower servers or larger copy radius.")
            .defineInRange("maxPortalLoadingTime", 600, 60, 3600);

    // ==================== Item Rules ====================
    
    public static final ForgeConfigSpec.BooleanValue ALLOW_ITEM_TRANSFER = BUILDER
            .comment("Legacy/global item transfer default (default: false).",
                    "New worlds use the per-mirror item transfer settings below.",
                    "This value is kept so old config files can be migrated without losing their previous behavior.",
                    "When false, players lose all items gained in mirror world on return.",
                    "Can be overridden per-player with /iwm itemtransfer command")
            .define("allowItemTransfer", false);

    public static final ForgeConfigSpec.IntValue MIRROR_COOLDOWN = BUILDER
            .comment("World Reflection Mirror cooldown in seconds (default: 300 = 5 minutes)",
                    "This is how long players must wait between using the World Reflection Mirror.",
                    "Can be reduced with Efficiency enchantment (each level reduces by 20%).",
                    "Efficiency 5 = minimum cooldown of 30 seconds.",
                    "Creative mode players have no cooldown.")
            .defineInRange("mirrorCooldown", 300, 0, 3600);

    // ==================== Mirror Access ====================

    public static final ForgeConfigSpec.EnumValue<MirrorAccess> WORLD_REFLECTION_MIRROR_ACCESS = BUILDER
            .comment("Who can use the World Reflection Mirror (default: ALL)",
                    "Allowed values: NONE, ADMIN, ALL.",
                    "NONE: no player can create or enter this mirror kind.",
                    "ADMIN: only server operators/admins can create or enter this mirror kind.",
                    "ALL: all players can create or enter this mirror kind.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineEnum("worldReflectionMirrorAccess", MirrorAccess.ALL);

    public static final ForgeConfigSpec.EnumValue<MirrorAccess> HEAVEN_MIRROR_ACCESS = BUILDER
            .comment("Who can use the Heaven Mirror (default: ALL)",
                    "Allowed values: NONE, ADMIN, ALL.",
                    "NONE: no player can create or enter this mirror kind.",
                    "ADMIN: only server operators/admins can create or enter this mirror kind.",
                    "ALL: all players can create or enter this mirror kind.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineEnum("heavenMirrorAccess", MirrorAccess.ALL);

    public static final ForgeConfigSpec.EnumValue<MirrorAccess> FIRST_DREAM_MIRROR_ACCESS = BUILDER
            .comment("Who can use the First Dream Mirror (default: ALL)",
                    "Allowed values: NONE, ADMIN, ALL.",
                    "NONE: no player can create or enter this mirror kind.",
                    "ADMIN: only server operators/admins can create or enter this mirror kind.",
                    "ALL: all players can create or enter this mirror kind.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineEnum("firstDreamMirrorAccess", MirrorAccess.ALL);

    public static final ForgeConfigSpec.BooleanValue WORLD_REFLECTION_MIRROR_MOB_SPAWNING = BUILDER
            .comment("Enable natural mob spawning for the World Reflection Mirror (default: false).",
                    "This is configurable from the in-game mirror settings screen.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("worldReflectionMirrorMobSpawning", false);

    public static final ForgeConfigSpec.BooleanValue HEAVEN_MIRROR_MOB_SPAWNING = BUILDER
            .comment("Enable natural mob spawning for the Heaven Mirror (default: false).",
                    "This is configurable from the in-game mirror settings screen.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("heavenMirrorMobSpawning", false);

    public static final ForgeConfigSpec.BooleanValue FIRST_DREAM_MIRROR_MOB_SPAWNING = BUILDER
            .comment("Enable natural mob spawning for the First Dream Mirror (default: true).",
                    "This is configurable from the in-game mirror settings screen.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("firstDreamMirrorMobSpawning", true);

    public static final ForgeConfigSpec.BooleanValue WORLD_REFLECTION_MIRROR_ITEM_TRANSFER = BUILDER
            .comment("Allow items gained in the World Reflection Mirror to leave the mirror (default: false).",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("worldReflectionMirrorItemTransfer", false);

    public static final ForgeConfigSpec.BooleanValue HEAVEN_MIRROR_ITEM_TRANSFER = BUILDER
            .comment("Allow items gained in the Heaven Mirror to leave the mirror (default: false).",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("heavenMirrorItemTransfer", false);

    public static final ForgeConfigSpec.BooleanValue FIRST_DREAM_MIRROR_ITEM_TRANSFER = BUILDER
            .comment("Allow items gained in the First Dream Mirror to leave the mirror (default: false).",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .define("firstDreamMirrorItemTransfer", false);

    public static final ForgeConfigSpec.IntValue WORLD_REFLECTION_MIRROR_COPY_CHUNK_RADIUS = BUILDER
            .comment("Copy radius in chunks for the World Reflection Mirror (default: 10).",
                    "Total chunks copied = (radius*2+1)^2.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineInRange("worldReflectionMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS, 1, 32);

    public static final ForgeConfigSpec.IntValue HEAVEN_MIRROR_COPY_CHUNK_RADIUS = BUILDER
            .comment("Copy radius in chunks for the Heaven Mirror (default: 10).",
                    "Total chunks copied = (radius*2+1)^2.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineInRange("heavenMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS, 1, 32);

    public static final ForgeConfigSpec.IntValue FIRST_DREAM_MIRROR_COPY_CHUNK_RADIUS = BUILDER
            .comment("Copy radius in chunks for the First Dream Mirror (default: 10).",
                    "Total chunks copied = (radius*2+1)^2.",
                    "Note: Changes saved from the in-game config screen require a server restart to take effect.")
            .defineInRange("firstDreamMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS, 1, 32);

    // ==================== Mob Spawning ====================
    
    public static final ForgeConfigSpec.BooleanValue COPY_ENTITIES = BUILDER
            .comment("Copy entities (mobs, animals, etc.) when creating mirror world (default: false)",
                    "When false, only blocks are copied - the mirror world starts without mobs.",
                    "When true, all entities in the copied area will be duplicated.")
            .define("copyEntities", false);

    public static final ForgeConfigSpec.BooleanValue COPY_DECORATION_ENTITIES = BUILDER
            .comment("Copy decoration entities (paintings, item frames, armor stands) when creating mirror world (default: true)",
                    "These entities are typically used for decoration and are important to preserve.",
                    "This setting works independently of copyEntities - decoration entities are always copied when true.")
            .define("copyDecorationEntities", true);

    public static final ForgeConfigSpec.BooleanValue ENABLE_MOB_SPAWNING = BUILDER
            .comment("Legacy/global natural mob spawning setting in mirror worlds (default: false)",
                    "When false, no mobs will spawn naturally in mirror worlds.",
                    "This is separate from copying existing mobs.",
                    "This value is kept so old config files can be migrated without losing their previous behavior.")
            .define("enableMobSpawning", false);

    // ==================== Biome and Environment Settings ====================
    
    public static final ForgeConfigSpec.BooleanValue COPY_BIOMES = BUILDER
            .comment("Copy biome data when creating mirror world (default: true)",
                    "When true, biome data from the source world is copied to the mirror world.",
                    "This affects grass/foliage colors, mob spawning rules, and weather.",
                    "Important for mods like Twilight Forest that use custom biomes for sky effects.",
                    "Disable this only if you experience compatibility issues.")
            .define("copyBiomes", true);

    public static final ForgeConfigSpec.BooleanValue COPY_STRUCTURES = BUILDER
            .comment("Copy structure data when creating mirror world (default: true)",
                    "When true, structure starts and references are copied to the mirror world.",
                    "This is important for mods like Twilight Forest that track structure 'conquered' status.",
                    "Also affects vanilla structure detection (villages, strongholds, etc.).")
            .define("copyStructures", true);

    public static final ForgeConfigSpec.BooleanValue COPY_HEIGHTMAPS = BUILDER
            .comment("Copy heightmap data when creating mirror world (default: true)",
                    "When true, heightmaps are properly copied/regenerated for the mirror world.",
                    "Affects mob spawning locations and light calculations.")
            .define("copyHeightmaps", true);

    // ==================== Server Limits ====================
    
    public static final ForgeConfigSpec.IntValue MAX_MIRROR_WORLDS_PER_PLAYER = BUILDER
            .comment("Maximum concurrent mirror world sessions per player (default: 1)",
                    "Usually 1 is enough since players can only be in one world at a time.")
            .defineInRange("maxMirrorWorldsPerPlayer", 1, 1, 5);

    public static final ForgeConfigSpec.IntValue STALE_SESSION_CLEANUP_INTERVAL = BUILDER
            .comment("Interval in seconds for stale session cleanup check (default: 300 = 5 minutes)",
                    "This is a fallback mechanism to clean up orphaned sessions/dimensions.",
                    "Lower values = more frequent checks but slightly more CPU usage.",
                    "Set to 0 to disable automatic stale session cleanup.")
            .defineInRange("staleSessionCleanupInterval", 300, 0, 3600);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // ==================== Runtime State ====================
    // These can be changed at runtime via commands, separate from persistent config
    
    // Runtime mob spawning state - can be toggled with /iwm mob on/off
    // null means use config value, true/false means override config
    private static Boolean runtimeMobSpawningEnabled = null;
    private static MirrorConfigState activeMirrorConfig = MirrorConfigState.defaults();

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

    public static boolean isMobSpawningEnabled(MirrorKind kind) {
        if (runtimeMobSpawningEnabled != null) {
            return runtimeMobSpawningEnabled;
        }
        return activeMirrorConfig.get(kind).mobSpawning();
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
        return ENABLE_MOB_SPAWNING.get() ? "on (legacy config)" : "off (legacy config)";
    }

    public static void refreshServerConfigSnapshot() {
        activeMirrorConfig = configuredMirrorConfigState();
    }

    public static MirrorConfigState configuredMirrorConfigState() {
        return new MirrorConfigState(
                new MirrorKindSettings(
                        WORLD_REFLECTION_MIRROR_ACCESS.get(),
                        WORLD_REFLECTION_MIRROR_MOB_SPAWNING.get(),
                        WORLD_REFLECTION_MIRROR_ITEM_TRANSFER.get(),
                        WORLD_REFLECTION_MIRROR_COPY_CHUNK_RADIUS.get()
                ),
                new MirrorKindSettings(
                        HEAVEN_MIRROR_ACCESS.get(),
                        HEAVEN_MIRROR_MOB_SPAWNING.get(),
                        HEAVEN_MIRROR_ITEM_TRANSFER.get(),
                        HEAVEN_MIRROR_COPY_CHUNK_RADIUS.get()
                ),
                new MirrorKindSettings(
                        FIRST_DREAM_MIRROR_ACCESS.get(),
                        FIRST_DREAM_MIRROR_MOB_SPAWNING.get(),
                        FIRST_DREAM_MIRROR_ITEM_TRANSFER.get(),
                        FIRST_DREAM_MIRROR_COPY_CHUNK_RADIUS.get()
                )
        );
    }

    public static MirrorConfigState activeMirrorConfigState() {
        return activeMirrorConfig;
    }

    public static void saveMirrorConfigState(MirrorConfigState state) {
        saveMirrorKindSettings(MirrorKind.DIMENSION, state.worldReflectionMirror());
        saveMirrorKindSettings(MirrorKind.HEAVEN, state.heavenMirror());
        saveMirrorKindSettings(MirrorKind.FIRST_DREAM, state.firstDreamMirror());
        SPEC.save();
    }

    private static void saveMirrorKindSettings(MirrorKind kind, MirrorKindSettings settings) {
        switch (kind) {
            case DIMENSION -> {
                WORLD_REFLECTION_MIRROR_ACCESS.set(settings.access());
                WORLD_REFLECTION_MIRROR_MOB_SPAWNING.set(settings.mobSpawning());
                WORLD_REFLECTION_MIRROR_ITEM_TRANSFER.set(settings.itemTransfer());
                WORLD_REFLECTION_MIRROR_COPY_CHUNK_RADIUS.set(settings.copyChunkRadius());
            }
            case HEAVEN -> {
                HEAVEN_MIRROR_ACCESS.set(settings.access());
                HEAVEN_MIRROR_MOB_SPAWNING.set(settings.mobSpawning());
                HEAVEN_MIRROR_ITEM_TRANSFER.set(settings.itemTransfer());
                HEAVEN_MIRROR_COPY_CHUNK_RADIUS.set(settings.copyChunkRadius());
            }
            case FIRST_DREAM -> {
                FIRST_DREAM_MIRROR_ACCESS.set(settings.access());
                FIRST_DREAM_MIRROR_MOB_SPAWNING.set(settings.mobSpawning());
                FIRST_DREAM_MIRROR_ITEM_TRANSFER.set(settings.itemTransfer());
                FIRST_DREAM_MIRROR_COPY_CHUNK_RADIUS.set(settings.copyChunkRadius());
            }
        }
    }

    public static boolean canAccessMirrorKind(ServerPlayer player, MirrorKind kind) {
        return activeMirrorConfig.get(kind).access().allows(isMirrorAdmin(player));
    }

    public static boolean canManageConfig(ServerPlayer player) {
        return isMirrorAdmin(player);
    }

    public static boolean isMirrorKindEnabled(MirrorKind kind) {
        return activeMirrorConfig.get(kind).access() != MirrorAccess.NONE;
    }

    public static boolean isItemTransferEnabled(MirrorKind kind) {
        return activeMirrorConfig.get(kind).itemTransfer();
    }

    public static int copyChunkRadius(MirrorKind kind) {
        return activeMirrorConfig.get(kind).copyChunkRadius();
    }

    public static int maxConfiguredCopyChunkRadius() {
        int max = 1;
        for (MirrorKind kind : MirrorKind.values()) {
            max = Math.max(max, copyChunkRadius(kind));
        }
        return max;
    }

    public static void setActiveMirrorConfigStateForTesting(MirrorConfigState state) {
        activeMirrorConfig = state;
    }

    public static void setConfiguredMirrorConfigStateForTesting(MirrorConfigState state) {
        saveMirrorKindSettings(MirrorKind.DIMENSION, state.worldReflectionMirror());
        saveMirrorKindSettings(MirrorKind.HEAVEN, state.heavenMirror());
        saveMirrorKindSettings(MirrorKind.FIRST_DREAM, state.firstDreamMirror());
    }

    public static MirrorAccess configuredMirrorAccess(MirrorKind kind) {
        return configuredMirrorConfigState().get(kind).access();
    }

    public static MirrorKindSettings configuredMirrorSettings(MirrorKind kind) {
        return configuredMirrorConfigState().get(kind);
    }

    private static boolean isMirrorAdmin(ServerPlayer player) {
        return player.hasPermissions(CONFIG_PERMISSION_LEVEL)
                || player.getServer() != null
                && player.getServer().getPlayerList().isOp(player.getGameProfile());
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
