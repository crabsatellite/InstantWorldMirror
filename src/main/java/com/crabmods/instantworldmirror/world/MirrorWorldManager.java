package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.network.ClearMirrorEffectsPacket;
import com.crabmods.instantworldmirror.network.SyncMirrorEffectsPacket;
import com.crabmods.instantworldmirror.registry.ModEnchantments;
import com.crabmods.instantworldmirror.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Mirror World Teleportation Manager
 * Handles player entry and exit from the mirror world
 * Thread-safe implementation with session management
 * 
 * Design:
 * - Each player can only create ONE mirror session at a time
 * - Session is bound to a portal entity
 * - Other players can enter through the same portal
 * - When all players leave, the session is destroyed immediately
 */
public class MirrorWorldManager {

    // Persistent data keys
    private static final String SAVED_INVENTORY_KEY = InstantWorldMirror.MODID + "_saved_inventory";
    private static final String SAVED_ENDERCHEST_KEY = InstantWorldMirror.MODID + "_saved_enderchest";
    private static final String ORIGINAL_POS_KEY = InstantWorldMirror.MODID + "_original_pos";
    private static final String ORIGINAL_DIM_KEY = InstantWorldMirror.MODID + "_original_dim";
    private static final String SESSION_ID_KEY = InstantWorldMirror.MODID + "_session_id";
    private static final String MIRROR_KIND_KEY = InstantWorldMirror.MODID + "_mirror_kind";
    private static final String DIED_IN_MIRROR_KEY = InstantWorldMirror.MODID + "_died_in_mirror";
    private static final String SANDBOX_SESSION_KEY = InstantWorldMirror.MODID + "_sandbox_session";
    private static final String SAVED_GAME_MODE_PRESENT_KEY = InstantWorldMirror.MODID + "_saved_game_mode_present";
    private static final String SAVED_GAME_MODE_KEY = InstantWorldMirror.MODID + "_saved_game_mode";
    private static final String SAVED_HEALTH_KEY = InstantWorldMirror.MODID + "_saved_health";
    private static final String SAVED_ABSORPTION_KEY = InstantWorldMirror.MODID + "_saved_absorption";
    private static final String SAVED_FOOD_KEY = InstantWorldMirror.MODID + "_saved_food";
    private static final String SAVED_SATURATION_KEY = InstantWorldMirror.MODID + "_saved_saturation";
    private static final String SAVED_EXHAUSTION_KEY = InstantWorldMirror.MODID + "_saved_exhaustion";
    private static final String SAVED_XP_PROGRESS_KEY = InstantWorldMirror.MODID + "_saved_xp_progress";
    private static final String SAVED_XP_LEVEL_KEY = InstantWorldMirror.MODID + "_saved_xp_level";
    private static final String SAVED_XP_TOTAL_KEY = InstantWorldMirror.MODID + "_saved_xp_total";
    private static final String SAVED_FIRE_TICKS_KEY = InstantWorldMirror.MODID + "_saved_fire_ticks";
    private static final String SAVED_AIR_SUPPLY_KEY = InstantWorldMirror.MODID + "_saved_air_supply";
    private static final String SAVED_EFFECTS_KEY = InstantWorldMirror.MODID + "_saved_effects";
    private static final String SAVED_MIRROR_FORGE_CAPS_KEY = InstantWorldMirror.MODID + "_saved_mirror_forge_caps";
    private static final String SAVED_MIRROR_NEOFORGE_ATTACHMENTS_KEY = InstantWorldMirror.MODID + "_saved_mirror_neoforge_attachments";
    private static final String PLAYER_FORGE_CAPS_NBT_KEY = "ForgeCaps";
    private static final String PLAYER_NEOFORGE_ATTACHMENTS_NBT_KEY = "neoforge:attachments";

    // Lock for thread-safe session operations
    private static final ReadWriteLock sessionLock = new ReentrantReadWriteLock();

    // Active sessions: sessionId -> MirrorSession
    private static final Map<UUID, MirrorSession> activeSessions = new ConcurrentHashMap<>();

    // Portal to session mapping for O(1) lookup: portalEntityId -> sessionId
    private static final Map<UUID, UUID> portalToSession = new ConcurrentHashMap<>();

    // Player to session mapping: playerId -> sessionId (for players currently in mirror world)
    private static final Map<UUID, UUID> playerToSession = new ConcurrentHashMap<>();

    // Player to mirror kind mapping for item-transfer restoration paths that do not have a live session.
    private static final Map<UUID, MirrorKind> playerMirrorKinds = new ConcurrentHashMap<>();

    // Player's active session in overworld (created but not entered): creatorId -> sessionId
    private static final Map<UUID, UUID> playerOwnedSession = new ConcurrentHashMap<>();

    // Player's original position (thread-safe)
    private static final Map<UUID, BlockPos> playerOriginalPositions = new ConcurrentHashMap<>();
    private static final Map<UUID, ResourceKey<Level>> playerOriginalDimensions = new ConcurrentHashMap<>();

    // Player item transfer permission
    private static final Map<UUID, Boolean> playerItemTransferPermission = new ConcurrentHashMap<>();

    // Temporary sessions currently used as source data for a queued persistent save.
    private static final Map<UUID, Integer> persistentSaveSourceHolds = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> deferredPersistentSourceReleases = new ConcurrentHashMap<>();

    // Players denied access to mirror world
    private static final Map<UUID, Boolean> playerAccessDenied = new ConcurrentHashMap<>();

    // Players currently being teleported by the mod (bypass dimension travel block)
    // This prevents our own teleportation from being blocked by the dimension travel event
    private static final Set<UUID> playersBeingTeleported = ConcurrentHashMap.newKeySet();
    
    // Purge mode flag - when true, mirror creation is disabled
    private static volatile boolean purgeMode = false;

    // ==================== Purge Mode Management ====================
    
    /**
     * Enable purge mode - disables all mirror world creation
     * Call this before deleting mirror world files
     */
    public static void enablePurgeMode() {
        purgeMode = true;
        InstantWorldMirror.LOGGER.info("Purge mode enabled - mirror world creation is now disabled");
    }
    
    /**
     * Disable purge mode - re-enables mirror world creation
     * Note: This is typically not called since purge requires restart
     */
    public static void disablePurgeMode() {
        purgeMode = false;
        InstantWorldMirror.LOGGER.info("Purge mode disabled - mirror world creation is now enabled");
    }
    
    /**
     * Check if purge mode is active
     */
    public static boolean isPurgeModeActive() {
        return purgeMode;
    }

    // ==================== Session Management ======================================

    /**
     * Check if player already has an active session (either created or inside one)
     */
    public static boolean hasActiveSession(UUID playerId) {
        return playerOwnedSession.containsKey(playerId) || playerToSession.containsKey(playerId);
    }

    /**
     * Get player's owned session (the session they created but haven't entered yet)
     */
    public static Optional<MirrorSession> getPlayerOwnedSession(UUID playerId) {
        UUID sessionId = playerOwnedSession.get(playerId);
        if (sessionId != null) {
            return Optional.ofNullable(activeSessions.get(sessionId));
        }
        return Optional.empty();
    }

    /**
     * Get the session a player is currently in
     */
    public static Optional<MirrorSession> getPlayerCurrentSession(UUID playerId) {
        UUID sessionId = playerToSession.get(playerId);
        if (sessionId != null) {
            return Optional.ofNullable(activeSessions.get(sessionId));
        }
        return Optional.empty();
    }

    /**
     * Get session by ID
     */
    public static Optional<MirrorSession> getSession(UUID sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * Get session by portal entity ID
     * Optimized: Uses O(1) lookup instead of stream iteration
     */
    public static Optional<MirrorSession> getSessionByPortal(UUID portalEntityId) {
        UUID sessionId = portalToSession.get(portalEntityId);
        if (sessionId != null) {
            return Optional.ofNullable(activeSessions.get(sessionId));
        }
        return Optional.empty();
    }

    /**
     * Create a new session for a player
     * @return the created session, or empty if player already has an active session or no dimensions available
     */
    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos) {
        return createSession(player, sourcePos, false);
    }

    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos, boolean sandboxMode) {
        return createSession(player, sourcePos, sandboxMode, false);
    }

    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos, boolean sandboxMode,
                                                        boolean persistentAccess) {
        return createSession(player, sourcePos, MirrorKind.fromSandboxMode(sandboxMode), persistentAccess);
    }

    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos, MirrorKind kind,
                                                        boolean persistentAccess) {
        return createSession(player, sourcePos, kind, persistentAccess, false);
    }

    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos, MirrorKind kind,
                                                        boolean persistentAccess, boolean generatedContentRefresh) {
        // Check if purge mode is active
        if (purgeMode) {
            InstantWorldMirror.LOGGER.warn("Cannot create session - purge mode is active");
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.purge_mode_active"),
                    true
            );
            return Optional.empty();
        }

        if (!MirrorConfig.canAccessMirrorKind(player, kind)) {
            InstantWorldMirror.LOGGER.info("Player {} tried to create restricted mirror kind {}",
                    player.getName().getString(), kind.id());
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.mirror_kind_restricted",
                            Component.translatable(kind.translationKey())),
                    true
            );
            return Optional.empty();
        }
        
        sessionLock.writeLock().lock();
        try {
            // Check if player already has an active session
            if (hasActiveSession(player.getUUID())) {
                InstantWorldMirror.LOGGER.warn("Player {} already has an active session",
                        player.getName().getString());
                return Optional.empty();
            }

            // Check if the source position is in water (for underwater exploration support)
            // We check the block above sourcePos since that's where the player stands
            ServerLevel sourceLevel = (ServerLevel) player.level();
            BlockPos playerStandPos = sourcePos.above();
            boolean sourceInWater = isPositionInWater(sourceLevel, playerStandPos);
            
            if (sourceInWater) {
                InstantWorldMirror.LOGGER.info("Player {} creating mirror from underwater position at {}",
                        player.getName().getString(), sourcePos);
            }

            // Create session first to get the session ID
            MirrorSession session = new MirrorSession(
                    player.getUUID(),
                    sourcePos,
                    player.level().dimension(),
                    sourceInWater,
                    kind,
                    persistentAccess,
                    generatedContentRefresh
            );

            // Allocate a dimension from the pool using actual session ID and source dimension
            int dimIndex = DimensionPool.allocateDimension(session.getSessionId(), session.getSourceDimension());
            if (dimIndex < 0) {
                // Provide detailed information about why allocation failed
                int poolSize = ModDimensions.getPoolSize();
                int inUse = DimensionPool.getInUseCount();
                int cleaning = DimensionPool.getCleaningCount();
                
                InstantWorldMirror.LOGGER.warn("No available dimensions for player {} (pool: {}, in use: {}, cleaning: {})",
                        player.getName().getString(), poolSize, inUse, cleaning);
                
                // Show detailed message to player
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.no_dimensions_available_detail",
                                poolSize, inUse, cleaning),
                        false  // Show in chat, not action bar, so player can see the full message
                );
                return Optional.empty();
            }

            // Set dimension index on session
            session.setDimensionIndex(dimIndex);

            activeSessions.put(session.getSessionId(), session);
            playerOwnedSession.put(player.getUUID(), session.getSessionId());

            InstantWorldMirror.LOGGER.debug("Created session {} for player {} using dimension {}",
                    session.getSessionId(), player.getName().getString(), dimIndex);

            return Optional.of(session);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    public static boolean hasGeneratedContentRefresh(MinecraftServer server, ResourceKey<Level> dimension) {
        int dimensionIndex = ModDimensions.MIRROR_WORLD_POOL.indexOf(dimension);
        if (server == null || dimensionIndex < 0) {
            return false;
        }

        sessionLock.readLock().lock();
        try {
            for (MirrorSession session : activeSessions.values()) {
                if (!session.isDestroyed()
                        && session.getDimensionIndex() == dimensionIndex
                        && session.hasGeneratedContentRefresh()) {
                    return true;
                }
            }
            return false;
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public static Optional<MirrorKind> getMirrorKindForDimension(MinecraftServer server, ResourceKey<Level> dimension) {
        int temporaryIndex = ModDimensions.MIRROR_WORLD_POOL.indexOf(dimension);
        if (temporaryIndex >= 0) {
            sessionLock.readLock().lock();
            try {
                for (MirrorSession session : activeSessions.values()) {
                    if (!session.isDestroyed() && session.getDimensionIndex() == temporaryIndex) {
                        return Optional.of(session.getKind());
                    }
                }
            } finally {
                sessionLock.readLock().unlock();
            }
        }

        int persistentIndex = ModDimensions.getPersistentMirrorWorldIndex(dimension);
        if (server != null && persistentIndex >= 0) {
            return PersistentMirrorData.get(server)
                    .getRecordByDimensionIndex(persistentIndex)
                    .map(PersistentMirrorRecord::kind);
        }

        return Optional.empty();
    }

    public static boolean isMobSpawningEnabledForDimension(MinecraftServer server, ResourceKey<Level> dimension) {
        return getMirrorKindForDimension(server, dimension)
                .map(MirrorConfig::isMobSpawningEnabled)
                .orElse(false);
    }

    /**
     * Bind a portal entity to a session
     * Also maintains the portalToSession index for O(1) lookup
     */
    public static void bindPortalToSession(UUID sessionId, UUID portalEntityId) {
        MirrorSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setPortalEntityId(portalEntityId);
            portalToSession.put(portalEntityId, sessionId);
            InstantWorldMirror.LOGGER.debug("Bound portal {} to session {}", portalEntityId, sessionId);
        }
    }

    // ==================== Access Control ====================

    /**
     * Check if player is allowed to enter mirror world
     */
    public static boolean canAccessMirrorWorld(ServerPlayer player) {
        return !playerAccessDenied.getOrDefault(player.getUUID(), false);
    }

    /**
     * Set player access permission for mirror world
     */
    public static void setAccessPermission(UUID playerId, boolean allowed) {
        if (allowed) {
            playerAccessDenied.remove(playerId);
        } else {
            playerAccessDenied.put(playerId, true);
        }
    }

    /**
     * Get the original dimension a player came from before entering mirror world
     * Used to allow returning to non-overworld dimensions (End, Nether, modded dimensions)
     */
    public static ResourceKey<Level> getPlayerOriginalDimension(ServerPlayer player) {
        return playerOriginalDimensions.get(player.getUUID());
    }

    /**
     * Check if a player is currently being teleported by the mod
     * Used by dimension travel event to allow our own teleportation to pass through
     */
    public static boolean isBeingTeleportedByMod(UUID playerId) {
        return playersBeingTeleported.contains(playerId);
    }

    public static boolean isPlayerInSandboxSession(ServerPlayer player) {
        UUID sessionId = playerToSession.get(player.getUUID());
        MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;
        return (session != null && session.isSandboxMode()) || PersistentMirrorManager.isPlayerInSandboxMirror(player);
    }

    /**
     * Mark a player as being teleported by the mod (for external use)
     * Call this before teleporting a player from mirror world
     */
    public static void markPlayerBeingTeleported(UUID playerId) {
        playersBeingTeleported.add(playerId);
    }

    /**
     * Unmark a player as being teleported by the mod (for external use)
     * Call this after teleportation completes
     */
    public static void unmarkPlayerBeingTeleported(UUID playerId) {
        playersBeingTeleported.remove(playerId);
    }

    // ==================== Player Data Cleanup ====================

    /**
     * Clean up all tracking data for a player
     * Call this when player leaves mirror world by any means
     */
    private static void cleanupPlayerTrackingData(UUID playerId) {
        playerOriginalPositions.remove(playerId);
        playerOriginalDimensions.remove(playerId);
        playerToSession.remove(playerId);
        playerOwnedSession.remove(playerId);
        playerMirrorKinds.remove(playerId);
    }

    private static void rememberPlayerMirrorKind(ServerPlayer player, MirrorKind kind) {
        playerMirrorKinds.put(player.getUUID(), kind);
        player.getPersistentData().putString(MIRROR_KIND_KEY, kind.id());
    }

    private static MirrorKind getSavedMirrorKind(ServerPlayer player) {
        MirrorKind kind = playerMirrorKinds.get(player.getUUID());
        if (kind != null) {
            return kind;
        }

        CompoundTag persistentData = player.getPersistentData();
        if (persistentData.contains(MIRROR_KIND_KEY)) {
            kind = MirrorKind.byId(persistentData.getString(MIRROR_KIND_KEY));
            playerMirrorKinds.put(player.getUUID(), kind);
            return kind;
        }

        return MirrorKind.DIMENSION;
    }

    private static MirrorKind getPlayerMirrorKind(ServerPlayer player, MirrorSession session) {
        return session != null ? session.getKind() : getSavedMirrorKind(player);
    }

    public static boolean retainTemporarySourceForPersistentSave(MirrorSession session) {
        if (session == null || session.getDimensionIndex() < 0 || session.isDestroyed()) {
            return false;
        }

        sessionLock.writeLock().lock();
        try {
            MirrorSession activeSession = activeSessions.get(session.getSessionId());
            if (activeSession == null || activeSession.isDestroyed()) {
                return false;
            }

            persistentSaveSourceHolds.merge(session.getSessionId(), 1, Integer::sum);
            return true;
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    public static void releaseTemporarySourceAfterPersistentSave(UUID sourceSessionId, MinecraftServer server) {
        if (sourceSessionId == null) {
            return;
        }

        sessionLock.writeLock().lock();
        try {
            Integer holdCount = persistentSaveSourceHolds.get(sourceSessionId);
            if (holdCount == null) {
                return;
            }

            if (holdCount > 1) {
                persistentSaveSourceHolds.put(sourceSessionId, holdCount - 1);
                return;
            }

            persistentSaveSourceHolds.remove(sourceSessionId);
            Integer deferredDimIndex = deferredPersistentSourceReleases.remove(sourceSessionId);
            if (deferredDimIndex != null) {
                releaseDeferredPersistentSource(sourceSessionId, deferredDimIndex, server);
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    private static void releaseDeferredPersistentSource(UUID sessionId, int dimIndex, MinecraftServer server) {
        DimensionPool.releaseDimension(sessionId);

        if (server != null) {
            ServerLevel mirrorWorld = server.getLevel(ModDimensions.getMirrorWorld(dimIndex));
            if (mirrorWorld != null) {
                WorldCopyService.cleanupMirrorWorld(mirrorWorld, dimIndex);
            }
        }

        InstantWorldMirror.LOGGER.info(
                "Released temporary mirror dimension {} after persistent save source copy completed",
                dimIndex);
    }

    // ==================== World Copy ====================

    /**
     * Queue async world copy for a session
     * This is non-blocking - copy happens over multiple ticks
     * Returns the queue position (1 = processing now, 2+ = waiting)
     */
    public static int prepareWorldCopy(ServerPlayer player, MirrorSession session) {
        if (player.level().isClientSide) {
            return 0;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }

        // Get the mirror world dimension for this session
        ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, session.getDimensionIndex());
        if (mirrorWorld == null) {
            InstantWorldMirror.LOGGER.error("Mirror world dimension {} not found!", session.getDimensionIndex());
            return 0;
        }

        // Get source world reference
        ServerLevel sourceWorld = server.getLevel(session.getSourceDimension());
        if (sourceWorld == null) {
            sourceWorld = server.overworld();
        }

        // Queue async world copy (now uses session's dedicated dimension)
        int queuePosition = WorldCopyService.queueWorldCopy(session, sourceWorld);

        InstantWorldMirror.LOGGER.debug("Queued world copy for session {} to dimension {}",
                session.getSessionId(), session.getDimensionIndex());
        
        return queuePosition;
    }

    // ==================== Teleportation ====================

    /**
     * Teleport player to mirror world via a session
     * @param player The player to teleport
     * @param session The session to join
     * @return true if successful
     */
    public static boolean teleportToMirrorWorld(ServerPlayer player, MirrorSession session) {
        if (player.level().isClientSide) {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Phase 1: Data validation and session updates (requires lock)
        ServerLevel mirrorWorld;
        BlockPos baseTargetPos;
        boolean allowWater;
        
        sessionLock.writeLock().lock();
        try {
            // Check if session is still valid
            if (session.isDestroyed()) {
                InstantWorldMirror.LOGGER.warn("Cannot teleport to destroyed session {}", session.getSessionId());
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.session_expired"),
                        true
                );
                return false;
            }

            // Check if player is already in a session
            if (playerToSession.containsKey(player.getUUID())) {
                InstantWorldMirror.LOGGER.warn("Player {} is already in a session",
                        player.getName().getString());
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.already_in_session"),
                        true
                );
                return false;
            }

            // Save player's current position (thread-safe maps, but do inside lock for consistency)
            playerOriginalPositions.put(player.getUUID(), player.blockPosition());
            playerOriginalDimensions.put(player.getUUID(), player.level().dimension());

            // Get the session's dedicated mirror world dimension
            ResourceKey<Level> mirrorDimKey = session.getMirrorDimension();
            if (mirrorDimKey == null) {
                InstantWorldMirror.LOGGER.error("Session {} has no allocated dimension!", session.getSessionId());
                return false;
            }
            
            mirrorWorld = server.getLevel(mirrorDimKey);
            if (mirrorWorld == null) {
                InstantWorldMirror.LOGGER.error("Mirror world dimension {} not found!", mirrorDimKey);
                return false;
            }

            // Add player to session
            session.addPlayer(player.getUUID());
            playerToSession.put(player.getUUID(), session.getSessionId());

            // If this player owned this session (creator entering), remove from owned
            playerOwnedSession.remove(player.getUUID());
            
            // Cache values needed outside lock
            baseTargetPos = session.getSourcePosition().above();
            allowWater = session.isSourceInWater();
        } finally {
            sessionLock.writeLock().unlock();
        }
        
        // Phase 2: Player data operations and teleportation (no lock needed)
        // Save session ID to player's persistent data (for server restart recovery)
        CompoundTag persistentData = player.getPersistentData();
        persistentData.putUUID(SESSION_ID_KEY, session.getSessionId());
        rememberPlayerMirrorKind(player, session.getKind());
        saveMirrorEntryState(player);

        if (session.isSandboxMode()) {
            savePlayerSnapshot(player, true);
            prepareSandboxPlayer(player, session.getKind(), session.hasPersistentAccess());
        } else {
            savePlayerInventory(player);
        }

        // Find a safe position to teleport to (avoid being stuck in walls)
        // If source was in water, allow water positions (player is doing underwater exploration)
        BlockPos safePos = findSafeLandingPosition(mirrorWorld, baseTargetPos, allowWater);
        if (safePos == null) {
            // No safe position found - clear a 1x2 area at the base position
            // This only happens in mirror world, so it's safe to modify
            safePos = baseTargetPos;
            clearAreaForPlayer(mirrorWorld, safePos);
            InstantWorldMirror.LOGGER.info("Cleared 1x2 area for player {} at {} in mirror world", 
                    player.getName().getString(), safePos);
            // Notify player that area was cleared
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.area_cleared"),
                    true
            );
        }

        // Execute teleportation to safe position
        player.teleportTo(
                mirrorWorld,
                safePos.getX() + 0.5,
                safePos.getY(),
                safePos.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
        );
        
        // Record the time when player entered mirror world (for return portal protection)
        CompoundTag playerData = player.getPersistentData();
        playerData.putLong("MirrorWorldEnterTime", mirrorWorld.getGameTime());

        // Sync dimension effects to client
        syncDimensionEffectsToPlayer(player, session);

        // Auto-spawn return portal ONLY for the host (prevents duplicates)
        if (session.isHost(player.getUUID())) {
            spawnReturnPortal(mirrorWorld, player, safePos, session.getSessionId());
        }

        InstantWorldMirror.LOGGER.info("Player {} teleported to Mirror World dimension {} (session: {}, players: {})",
                player.getName().getString(), session.getDimensionIndex(), session.getSessionId(), session.getPlayerCount());

        return true;
    }

    /**
     * Teleport player from mirror world back to overworld
     */
    public static boolean returnToOverworld(ServerPlayer player) {
        if (player.level().isClientSide) {
            return false;
        }

        if (PersistentMirrorManager.isInPersistentMirror(player)) {
            return PersistentMirrorManager.leavePersistentMirror(player);
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Check if player is in mirror world (uses lenient check for pool size changes)
        if (!isInMirrorWorld(player)) {
            InstantWorldMirror.LOGGER.warn("returnToOverworld: player {} is not in mirror world", 
                    player.getName().getString());
            return false;
        }

        // Phase 1: Get session data (short lock scope)
        UUID sessionId;
        MirrorSession session;
        sessionLock.readLock().lock();
        try {
            sessionId = playerToSession.get(player.getUUID());
            session = sessionId != null ? activeSessions.get(sessionId) : null;
        } finally {
            sessionLock.readLock().unlock();
        }

        // Phase 2: Get original position (from memory or persistent data, no lock needed)
        BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
        ResourceKey<Level> originalDimension = playerOriginalDimensions.get(player.getUUID());
        
        if (originalPos == null || originalDimension == null) {
            CompoundTag persistentData = player.getPersistentData();
            
            if (persistentData.contains(ORIGINAL_POS_KEY + "_x")) {
                originalPos = new BlockPos(
                        persistentData.getInt(ORIGINAL_POS_KEY + "_x"),
                        persistentData.getInt(ORIGINAL_POS_KEY + "_y"),
                        persistentData.getInt(ORIGINAL_POS_KEY + "_z")
                );
            }
            
            if (persistentData.contains(ORIGINAL_DIM_KEY)) {
                ResourceLocation dimLoc = ResourceLocation.tryParse(persistentData.getString(ORIGINAL_DIM_KEY));
                if (dimLoc != null) {
                    originalDimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                }
            }
            
            if (originalPos != null && originalDimension != null) {
                InstantWorldMirror.LOGGER.info("Restored return position from persistent data for {}", 
                        player.getName().getString());
            }
        }

        // Determine target location (fallback to spawn if no saved position)
        ServerLevel targetLevel;
        BlockPos targetPos;

        if (originalPos != null && originalDimension != null) {
            targetLevel = server.getLevel(originalDimension);
            targetPos = originalPos;
        } else {
            targetLevel = server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
            InstantWorldMirror.LOGGER.warn("No saved position for {}, using spawn", player.getName().getString());
        }

        if (targetLevel == null) {
            targetLevel = server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
        }

        // Phase 3: Resolve inventory policy before the session is cleaned up.
        boolean allowItemTransfer = getItemTransferPermission(player.getUUID(), getPlayerMirrorKind(player, session));

        // Phase 4: Execute teleportation before inventory merging so overflow is
        // dropped at the player's return point rather than in the disposable world.
        playersBeingTeleported.add(player.getUUID());
        
        try {
            player.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
        } finally {
            playersBeingTeleported.remove(player.getUUID());
        }

        finishPlayerMirrorState(player, allowItemTransfer);
        
        // Verify teleport success
        if (isInMirrorWorld(player)) {
            InstantWorldMirror.LOGGER.error("Teleport FAILED - {} still in mirror world!", player.getName().getString());
            return false;
        }

        // Clear dimension effects on client
        if (session != null) {
            clearDimensionEffectsForPlayer(player, session.getDimensionIndex());
        }

        // Phase 5: Cleanup player tracking data and session (requires write lock)
        sessionLock.writeLock().lock();
        try {
            cleanupPlayerTrackingData(player.getUUID());

            // Handle session cleanup
            if (session != null) {
                boolean isHost = session.isHost(player.getUUID());
                
                if (isHost) {
                    // Host is leaving - kick all other players and destroy session
                    InstantWorldMirror.LOGGER.info("Host {} leaving session {}, kicking all other players",
                            player.getName().getString(), session.getSessionId());
                    kickAllPlayersFromSession(session, server, player.getUUID());
                    destroySession(session, server);
                } else {
                    // Non-host leaving - just remove from session
                    boolean sessionNowEmpty = session.removePlayer(player.getUUID());
                    if (sessionNowEmpty) {
                        destroySession(session, server);
                    }
                }
            }
        } finally {
            sessionLock.writeLock().unlock();
        }

        player.displayClientMessage(Component.translatable("message.instantworldmirror.returned"), true);
        InstantWorldMirror.LOGGER.debug("Player {} returned from mirror world", player.getName().getString());

        return true;
    }

    /**
     * Teleport player from mirror world back to overworld, considering the portal position
     * If the return portal is far from the original entry point, teleport to the corresponding overworld position
     * 
     * @param player The player to teleport
     * @param portalPos The position of the return portal used
     * @return true if teleportation was successful
     */
    public static boolean returnToOverworldFromPosition(ServerPlayer player, BlockPos portalPos) {
        if (player.level().isClientSide) {
            return false;
        }

        if (PersistentMirrorManager.isInPersistentMirror(player)) {
            return PersistentMirrorManager.leavePersistentMirrorFromPosition(player, portalPos);
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Get player's saved original position
        BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
        UUID sessionId = playerToSession.get(player.getUUID());
        MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

        if (session != null && session.isSandboxMode()) {
            if (originalPos != null && !portalPos.closerThan(originalPos, 16.0)) {
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.heaven_return_restricted"),
                        true
                );
                return false;
            }
            return returnToOverworld(player);
        }
        
        // If no original position or portal is close to original position (within 16 blocks), use normal return
        if (originalPos == null || portalPos.closerThan(originalPos, 16.0)) {
            return returnToOverworld(player);
        }
        
        // Portal is far from original entry point - teleport to corresponding overworld position
        InstantWorldMirror.LOGGER.info("Player {} using distant return portal at {} (original entry: {}), teleporting to corresponding overworld position",
                player.getName().getString(), portalPos, originalPos);
        
        return returnToOverworldAtPosition(player, portalPos);
    }
    
    /**
     * Teleport player from mirror world to a specific position in the overworld
     * Used when player uses a return portal far from their original entry point
     * 
     * @param player The player to teleport
     * @param targetMirrorPos The position in mirror world (will be converted to overworld coordinates)
     * @return true if teleportation was successful
     */
    private static boolean returnToOverworldAtPosition(ServerPlayer player, BlockPos targetMirrorPos) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Check if player is in mirror world
        if (!isInMirrorWorld(player)) {
            InstantWorldMirror.LOGGER.warn("returnToOverworldAtPosition: player {} is not in mirror world", 
                    player.getName().getString());
            return false;
        }

        sessionLock.writeLock().lock();
        try {
            // Get player's session
            UUID sessionId = playerToSession.get(player.getUUID());
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

            // Get original dimension (default to overworld)
            ResourceKey<Level> originalDimension = playerOriginalDimensions.get(player.getUUID());
            if (originalDimension == null) {
                CompoundTag persistentData = player.getPersistentData();
                if (persistentData.contains(ORIGINAL_DIM_KEY)) {
                    ResourceLocation dimLoc = ResourceLocation.tryParse(persistentData.getString(ORIGINAL_DIM_KEY));
                    if (dimLoc != null) {
                        originalDimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                    }
                }
            }
            if (originalDimension == null) {
                originalDimension = Level.OVERWORLD;
            }

            ServerLevel targetLevel = server.getLevel(originalDimension);
            if (targetLevel == null) {
                targetLevel = server.overworld();
            }

            // Check if player originally entered from water (allow water landing if so)
            boolean allowWater = session != null && session.isSourceInWater();

            // Find safe landing position at the corresponding overworld coordinates
            BlockPos safePos = findSafeLandingPosition(targetLevel, targetMirrorPos, allowWater);
            
            InstantWorldMirror.LOGGER.info("Found safe landing position {} for player {} (original target: {})",
                    safePos, player.getName().getString(), targetMirrorPos);

            // Resolve inventory policy before the session is cleaned up.
            boolean allowItemTransfer = getItemTransferPermission(player.getUUID(), getPlayerMirrorKind(player, session));

            // Mark player as being teleported by the mod
            playersBeingTeleported.add(player.getUUID());
            
            try {
                // Execute teleportation to safe position
                player.teleportTo(
                        targetLevel,
                        safePos.getX() + 0.5,
                        safePos.getY(),
                        safePos.getZ() + 0.5,
                        player.getYRot(),
                        player.getXRot()
                );
            } finally {
                playersBeingTeleported.remove(player.getUUID());
            }

            finishPlayerMirrorState(player, allowItemTransfer);
            
            // Verify teleport success
            if (isInMirrorWorld(player)) {
                InstantWorldMirror.LOGGER.error("Teleport FAILED - {} still in mirror world!", player.getName().getString());
                return false;
            }

            // Clear dimension effects on client
            if (session != null) {
                clearDimensionEffectsForPlayer(player, session.getDimensionIndex());
            }

            // Cleanup player tracking data
            cleanupPlayerTrackingData(player.getUUID());

            // Handle session cleanup
            if (session != null) {
                boolean isHost = session.isHost(player.getUUID());
                
                if (isHost) {
                    InstantWorldMirror.LOGGER.info("Host {} leaving session {}, kicking all other players",
                            player.getName().getString(), session.getSessionId());
                    kickAllPlayersFromSession(session, server, player.getUUID());
                    destroySession(session, server);
                } else {
                    boolean sessionNowEmpty = session.removePlayer(player.getUUID());
                    if (sessionNowEmpty) {
                        destroySession(session, server);
                    }
                }
            }

            player.displayClientMessage(Component.translatable("message.instantworldmirror.returned_to_corresponding_position"), true);
            InstantWorldMirror.LOGGER.info("Player {} returned from mirror world to corresponding position {}", 
                    player.getName().getString(), safePos);

            return true;
        } finally {
            sessionLock.writeLock().unlock();
        }
    }
    
    /**
     * Teleport a player to the mirror world spawn point (near where they originally entered).
     * This does NOT exit the mirror world - the player stays in the mirror world but is
     * teleported to the spawn location. The target position is offset slightly to avoid
     * triggering any return portals that may be at the exact spawn point.
     * 
     * Requirements:
     * - Safe position must be within 10 blocks of spawn
     * - Must be at the same Y level (player can see the portal)
     * - Must not be underground or underwater
     * - Must be at least 2 blocks away from spawn to avoid triggering portal
     * - If no valid position found, return player to overworld
     * 
     * @param player The player to teleport
     * @return true if teleportation was successful
     */
    public static boolean teleportToMirrorSpawn(ServerPlayer player) {
        if (player.level().isClientSide) {
            return false;
        }

        if (PersistentMirrorManager.isInPersistentMirror(player)) {
            return PersistentMirrorManager.teleportToMirrorSpawn(player);
        }

        // Check if player is in mirror world
        if (!isInMirrorWorld(player)) {
            InstantWorldMirror.LOGGER.warn("teleportToMirrorSpawn: player {} is not in mirror world", 
                    player.getName().getString());
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.not_in_mirror_world"),
                    true
            );
            return false;
        }

        // Get player's current session
        UUID sessionId = playerToSession.get(player.getUUID());
        MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

        if (session == null) {
            InstantWorldMirror.LOGGER.warn("teleportToMirrorSpawn: player {} has no active session", 
                    player.getName().getString());
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.no_active_session"),
                    true
            );
            return false;
        }

        ServerLevel mirrorLevel = (ServerLevel) player.level();
        
        // Get the spawn position (where they originally entered, one block above the clicked block)
        BlockPos spawnPos = session.getSourcePosition().above();
        int spawnY = spawnPos.getY();
        
        // Try to find a safe position at the same Y level, within 10 blocks, but at least 2 blocks away
        BlockPos safePos = findSafeSpawnNearbyPosition(mirrorLevel, spawnPos, 2, 10, spawnY);
        
        if (safePos == null) {
            // No safe position found - return player to overworld
            InstantWorldMirror.LOGGER.info("No safe spawn position found for {}, returning to overworld", 
                    player.getName().getString());
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.no_safe_spawn_returning"),
                    true
            );
            return returnToOverworld(player);
        }

        // Execute teleportation (stays in same dimension)
        player.teleportTo(
                safePos.getX() + 0.5,
                safePos.getY(),
                safePos.getZ() + 0.5
        );

        // Play teleport sound
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 
                0.8F, 1.0F);

        // Apply cooldown (same as using the mirror normally) - skip for creative mode
        if (!player.isCreative()) {
            DimensionMirrorItem.applyCooldown(player, DimensionMirrorItem.findMirrorStack(player));
        }

        player.displayClientMessage(
                Component.translatable("message.instantworldmirror.teleported_to_spawn"),
                true
        );
        
        InstantWorldMirror.LOGGER.info("Player {} teleported to mirror spawn at ({}, {}, {})",
                player.getName().getString(), safePos.getX(), safePos.getY(), safePos.getZ());

        return true;
    }
    
    /**
     * Find a safe position near spawn point for teleporting back.
     * Requirements:
     * - Within maxRadius blocks of spawn (horizontal distance)
     * - At least minRadius blocks away from spawn horizontally (to avoid portal)
     * - At the same Y level as spawn (so player can see portal)
     * - Not underground (has sky access)
     * - Not underwater
     * - Has solid ground below and air at position and above
     * 
     * @param level The level to search in
     * @param spawnPos The spawn position (portal location)
     * @param minRadius Minimum horizontal distance from spawn (to avoid portal trigger)
     * @param maxRadius Maximum horizontal distance from spawn
     * @param targetY The Y level to search at (same as spawn)
     * @return A safe position, or null if none found
     */
    private static BlockPos findSafeSpawnNearbyPosition(ServerLevel level, BlockPos spawnPos, int minRadius, int maxRadius, int targetY) {
        // Search in expanding square pattern, but verify horizontal distance
        for (int radius = minRadius; radius <= maxRadius; radius++) {
            // Check positions at approximately this radius
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Calculate actual horizontal distance
                    double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
                    
                    // Must be within [minRadius, maxRadius] horizontal distance
                    if (horizontalDistance < minRadius || horizontalDistance > maxRadius) {
                        continue;
                    }
                    
                    // For efficiency, only check positions at approximately this radius level
                    // (within 0.5 of the current radius we're searching)
                    if (Math.abs(horizontalDistance - radius) > 0.5 && radius < maxRadius) {
                        continue;
                    }
                    
                    BlockPos checkPos = new BlockPos(spawnPos.getX() + dx, targetY, spawnPos.getZ() + dz);
                    if (isValidSpawnNearbyPosition(level, checkPos, spawnPos)) {
                        return checkPos;
                    }
                }
            }
        }
        
        return null; // No valid position found
    }
    
    /**
     * Check if a position is valid for teleporting near spawn.
     * Must have:
     * - Solid ground below
     * - Air at position and above (space for player)
     * - Sky access (not underground)
     * - Not in water
     */
    private static boolean isValidSpawnNearbyPosition(ServerLevel level, BlockPos pos, BlockPos spawnPos) {
        // Check world bounds
        if (pos.getY() < level.getMinBuildHeight() + 1 || pos.getY() > level.getMaxBuildHeight() - 2) {
            return false;
        }
        
        BlockPos below = pos.below();
        BlockPos above = pos.above();
        
        net.minecraft.world.level.block.state.BlockState belowState = level.getBlockState(below);
        net.minecraft.world.level.block.state.BlockState atState = level.getBlockState(pos);
        net.minecraft.world.level.block.state.BlockState aboveState = level.getBlockState(above);
        
        // Block below must be solid
        if (!belowState.isSolid()) {
            return false;
        }
        
        // Check for dangerous blocks below
        if (isDangerousBlock(belowState)) {
            return false;
        }
        
        // Position and above must be air/passable (not solid, not water)
        if (atState.isSolid() || aboveState.isSolid()) {
            return false;
        }
        
        // Must not be in water
        if (atState.getFluidState().isSource() || aboveState.getFluidState().isSource()) {
            return false;
        }
        
        // Must have sky access (not underground) - check if can see sky
        if (!level.canSeeSky(pos)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if a position is in water (for underwater exploration detection)
     */
    private static boolean isPositionInWater(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        return state.getFluidState().isSource() && 
               state.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;
    }
    
    /**
     * Find a safe landing position near the target position
     * Checks for solid ground, avoids lava/void, and ensures there's space for the player
     * 
     * @param level The target level
     * @param targetPos The desired position
     * @param allowWater If true, water positions are considered safe (for underwater exploration)
     * @return A safe position for the player to land
     */
    private static BlockPos findSafeLandingPosition(ServerLevel level, BlockPos targetPos, boolean allowWater) {
        // First check if the target position is already safe
        if (isPositionSafe(level, targetPos, allowWater)) {
            return targetPos;
        }
        
        // Search in expanding radius for a safe position
        int maxRadius = 16;
        for (int radius = 1; radius <= maxRadius; radius++) {
            // Search in a spiral pattern at the same Y level first
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only check positions on the edge of current radius
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    
                    BlockPos checkPos = targetPos.offset(dx, 0, dz);
                    BlockPos safePos = findSafeYLevel(level, checkPos, allowWater);
                    if (safePos != null && isPositionSafe(level, safePos, allowWater)) {
                        return safePos;
                    }
                }
            }
        }
        
        // If no safe position found nearby, try to find any safe Y level at target X/Z
        BlockPos verticalSafe = findSafeYLevel(level, targetPos, allowWater);
        if (verticalSafe != null) {
            return verticalSafe;
        }
        
        // No safe position found, return null so caller can handle (e.g., clear area)
        return null;
    }
    
    /**
     * Find a safe Y level at the given X/Z coordinates
     * Scans from the target Y position to find solid ground with space above
     * Limited search range to avoid teleporting to extreme heights when chunks aren't loaded
     * 
     * @param level The level to search in
     * @param horizontalPos The X/Z position to search at
     * @param allowWater If true, water positions are considered safe
     * @return A safe Y position, or null if none found
     */
    private static BlockPos findSafeYLevel(ServerLevel level, BlockPos horizontalPos, boolean allowWater) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        
        // Start from the target Y or a reasonable height if target is too high/low
        int startY = Math.max(minY + 1, Math.min(horizontalPos.getY(), maxY - 3));
        
        // IMPORTANT: Limit the vertical search range to prevent teleporting to extreme heights
        // when mirror world chunks haven't been fully copied yet
        // Only search within 5 blocks up and down from the target Y
        int maxVerticalSearch = 5;
        int searchMinY = Math.max(minY + 1, startY - maxVerticalSearch);
        int searchMaxY = Math.min(maxY - 2, startY + maxVerticalSearch);
        
        // Search downward from start position (prioritize downward for safety)
        for (int y = startY; y >= searchMinY; y--) {
            BlockPos checkPos = new BlockPos(horizontalPos.getX(), y, horizontalPos.getZ());
            if (isPositionSafe(level, checkPos, allowWater)) {
                return checkPos;
            }
        }
        
        // Search upward from start position (limited range)
        for (int y = startY + 1; y <= searchMaxY; y++) {
            BlockPos checkPos = new BlockPos(horizontalPos.getX(), y, horizontalPos.getZ());
            if (isPositionSafe(level, checkPos, allowWater)) {
                return checkPos;
            }
        }
        
        return null;
    }
    
    /**
     * Check if a position is safe for player teleportation
     * Requirements:
     * - Block below must be solid (not air, liquid, or dangerous)
     * - Block at position and above must be air/passable
     * - Not in void
     * - Not in lava/fire
     * - Not in water (unless allowWater is true for underwater exploration)
     * 
     * @param level The level to check
     * @param pos The position to check
     * @param allowWater If true, water positions are considered safe
     * @return true if the position is safe
     */
    private static boolean isPositionSafe(ServerLevel level, BlockPos pos, boolean allowWater) {
        // Check if position is in valid world bounds
        if (pos.getY() < level.getMinBuildHeight() + 1 || pos.getY() > level.getMaxBuildHeight() - 2) {
            return false;
        }
        
        BlockPos below = pos.below();
        BlockPos above = pos.above();
        
        net.minecraft.world.level.block.state.BlockState belowState = level.getBlockState(below);
        net.minecraft.world.level.block.state.BlockState atState = level.getBlockState(pos);
        net.minecraft.world.level.block.state.BlockState aboveState = level.getBlockState(above);
        
        // For underwater exploration, check if we're in water and have space
        if (allowWater) {
            // If target position is in water, it's safe (player was in water when placing mirror)
            if (atState.getFluidState().isSource() && 
                atState.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER) {
                // Just need space for player (not blocked by solid blocks)
                if (!atState.isSolid() && !aboveState.isSolid()) {
                    // Not in dangerous blocks
                    if (!isDangerousBlock(atState) && !isDangerousBlock(aboveState)) {
                        return true;
                    }
                }
            }
        }
        
        // Standard safety check for non-water positions
        
        // Block below must be solid and not dangerous
        if (!belowState.isSolid()) {
            return false;
        }
        
        // Check for dangerous blocks below (lava, fire, magma, cactus, etc.)
        if (isDangerousBlock(belowState)) {
            return false;
        }
        
        // Position and above must be passable (air or non-solid)
        if (atState.isSolid() || aboveState.isSolid()) {
            return false;
        }
        
        // Check for dangerous blocks at position
        if (isDangerousBlock(atState) || isDangerousBlock(aboveState)) {
            return false;
        }
        
        // Must not be in water (underwater) - unless allowWater is true (handled above)
        if (atState.getFluidState().isSource() || aboveState.getFluidState().isSource()) {
            return false;
        }
        
        // Check if there's a portal entity nearby (avoid teleport loops)
        if (hasNearbyPortal(level, pos)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if there's a MirrorPortalEntity within range of the given position
     * This prevents teleport loops where a player lands on another portal
     * 
     * @param level The level to check
     * @param pos The position to check
     * @return true if there's a portal entity nearby
     */
    private static boolean hasNearbyPortal(ServerLevel level, BlockPos pos) {
        // Check in a 2 block radius around the position (portal entity size is 0.8x1.5)
        double checkRadius = 2.5;
        net.minecraft.world.phys.AABB searchArea = new net.minecraft.world.phys.AABB(
                pos.getX() - checkRadius, pos.getY() - 1, pos.getZ() - checkRadius,
                pos.getX() + checkRadius, pos.getY() + 3, pos.getZ() + checkRadius
        );
        
        java.util.List<MirrorPortalEntity> nearbyPortals = level.getEntitiesOfClass(
                MirrorPortalEntity.class, searchArea
        );
        
        return !nearbyPortals.isEmpty();
    }
    
    /**
     * Check if a block state represents a dangerous block
     */
    private static boolean isDangerousBlock(net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.world.level.block.Block block = state.getBlock();
        
        // Lava
        if (block == net.minecraft.world.level.block.Blocks.LAVA) {
            return true;
        }
        
        // Fire
        if (block == net.minecraft.world.level.block.Blocks.FIRE || 
            block == net.minecraft.world.level.block.Blocks.SOUL_FIRE) {
            return true;
        }
        
        // Magma block
        if (block == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) {
            return true;
        }
        
        // Cactus
        if (block == net.minecraft.world.level.block.Blocks.CACTUS) {
            return true;
        }
        
        // Sweet berry bush
        if (block == net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) {
            return true;
        }
        
        // Wither rose
        if (block == net.minecraft.world.level.block.Blocks.WITHER_ROSE) {
            return true;
        }
        
        // Powder snow
        if (block == net.minecraft.world.level.block.Blocks.POWDER_SNOW) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Clear a 1x2 area for player to safely land
     * Clears the player's standing position and above (2 blocks high for player)
     * Also ensures there's solid ground below
     * @param level The level to modify
     * @param pos The center position where player will stand
     */
    private static void clearAreaForPlayer(ServerLevel level, BlockPos pos) {
        // Clear 1x2 area at player height (2 blocks high for player body)
        BlockPos abovePos = pos.above();
        
        // Clear the two blocks where player body would be
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(abovePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        
        // Ensure solid ground below the center position
        BlockPos belowPos = pos.below();
        net.minecraft.world.level.block.state.BlockState belowState = level.getBlockState(belowPos);
        if (!belowState.isSolid() || isDangerousBlock(belowState)) {
            // Place a stone block as floor
            level.setBlock(belowPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        }
    }

    // ==================== Session Lifecycle ====================
    
    /**
     * Kick all players from a session (except the specified exclude player)
     * Called when the host leaves - all other players are forcibly returned
     * Must be called with sessionLock.writeLock held
     */
    private static void kickAllPlayersFromSession(MirrorSession session, MinecraftServer server, UUID excludePlayerId) {
        Set<UUID> playersToKick = new HashSet<>(session.getPlayers());
        playersToKick.remove(excludePlayerId); // Don't kick the player who is already leaving
        
        if (playersToKick.isEmpty()) {
            return;
        }
        
        InstantWorldMirror.LOGGER.info("Kicking {} players from session {} because host left",
                playersToKick.size(), session.getSessionId());
        
        ServerLevel overworld = server.overworld();
        BlockPos spawnPos = overworld.getSharedSpawnPos();
        
        for (UUID playerId : playersToKick) {
            ServerPlayer kickedPlayer = server.getPlayerList().getPlayer(playerId);
            if (kickedPlayer == null) {
                // Player is offline, just clean up tracking data
                cleanupPlayerTrackingData(playerId);
                session.removePlayer(playerId);
                continue;
            }
            
            // Restore inventory first
            restorePlayerInventory(kickedPlayer);
            
            // Get their original position (before cleanup)
            BlockPos originalPos = playerOriginalPositions.get(playerId);
            ResourceKey<Level> originalDim = playerOriginalDimensions.get(playerId);
            
            ServerLevel targetLevel = overworld;
            BlockPos targetPos = spawnPos;
            
            if (originalPos != null && originalDim != null) {
                ServerLevel level = server.getLevel(originalDim);
                if (level != null) {
                    targetLevel = level;
                    targetPos = originalPos;
                }
            }
            
            // Teleport back with whitelist protection
            playersBeingTeleported.add(playerId);
            try {
                kickedPlayer.teleportTo(
                        targetLevel,
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        kickedPlayer.getYRot(),
                        kickedPlayer.getXRot()
                );
            } finally {
                playersBeingTeleported.remove(playerId);
            }
            
            // Clear dimension effects
            clearDimensionEffectsForPlayer(kickedPlayer, session.getDimensionIndex());
            
            // Clean up tracking data
            cleanupPlayerTrackingData(playerId);
            session.removePlayer(playerId);
            
            // Notify the kicked player
            kickedPlayer.displayClientMessage(
                    Component.translatable("message.instantworldmirror.host_left"),
                    true
            );
            
            InstantWorldMirror.LOGGER.info("Kicked player {} from session because host left",
                    kickedPlayer.getName().getString());
        }
    }

    /**
     * Destroy a session and cleanup its resources
     * Must be called with sessionLock.writeLock held
     */
    private static void destroySession(MirrorSession session, MinecraftServer server) {
        if (session.isDestroyed()) {
            return;
        }

        session.markDestroyed();
        UUID sessionId = session.getSessionId();
        activeSessions.remove(sessionId);

        // Also cleanup playerOwnedSession if creator never entered
        playerOwnedSession.values().removeIf(id -> id.equals(sessionId));

        // Cleanup portal to session mapping and actively destroy the portal entity
        UUID portalId = session.getPortalEntityId();
        if (portalId != null) {
            portalToSession.remove(portalId);
            
            // Actively destroy the portal entity in the source dimension
            ServerLevel sourceLevel = server.getLevel(session.getSourceDimension());
            if (sourceLevel != null) {
                Entity portalEntity = sourceLevel.getEntity(portalId);
                if (portalEntity != null) {
                    // Discard the portal - PortalLightBlockEntity will auto-cleanup
                    portalEntity.discard();
                    InstantWorldMirror.LOGGER.debug("Destroyed portal entity {} for session {}", 
                            portalId, sessionId);
                }
                // Note: If portal entity not found, its PortalLightBlock will auto-remove
                // when it detects the portal entity no longer exists
            }
        }

        // Release dimension back to pool (starts cleanup)
        int dimIndex = session.getDimensionIndex();
        if (dimIndex >= 0) {
            if (persistentSaveSourceHolds.getOrDefault(sessionId, 0) > 0) {
                deferredPersistentSourceReleases.put(sessionId, dimIndex);
                InstantWorldMirror.LOGGER.info(
                        "Deferring cleanup of temporary mirror dimension {} until persistent save source copy completes",
                        dimIndex);
                return;
            }

            DimensionPool.releaseDimension(sessionId);
            
            // Queue aggressive async cleanup for the session's dedicated dimension
            ServerLevel mirrorWorld = server.getLevel(session.getMirrorDimension());
            if (mirrorWorld != null) {
                WorldCopyService.cleanupMirrorWorld(mirrorWorld, dimIndex);
            }
        }

        InstantWorldMirror.LOGGER.debug("Session {} destroyed, dimension {} queued for cleanup", 
                sessionId, dimIndex);
    }

    /**
     * Cancel a session that was created but player never entered
     * Called when portal times out without anyone entering
     */
    public static void cancelSession(UUID sessionId, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            MirrorSession session = activeSessions.get(sessionId);
            if (session != null) {
                // Remove from creator's owned sessions first
                playerOwnedSession.remove(session.getCreatorId());
                
                // Only cleanup world if session is empty (no one entered)
                if (session.isEmpty()) {
                    destroySession(session, server);
                } else {
                    // Session has players, just remove from activeSessions tracking
                    // but don't cleanup the world yet
                    InstantWorldMirror.LOGGER.info("Session {} portal timed out but has {} players, keeping session",
                            sessionId, session.getPlayerCount());
                }
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Handle player disconnect - remove from session
     */
    public static void handlePlayerDisconnect(ServerPlayer player, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            UUID playerId = player.getUUID();

            // Check if player owns a session (created but not entered)
            UUID ownedSessionId = playerOwnedSession.remove(playerId);
            if (ownedSessionId != null) {
                MirrorSession session = activeSessions.get(ownedSessionId);
                if (session != null && session.isEmpty()) {
                    destroySession(session, server);
                }
            }

            // Check if player is in a session
            UUID sessionId = playerToSession.get(playerId);
            if (sessionId != null) {
                MirrorSession session = activeSessions.get(sessionId);
                if (session != null) {
                    boolean sessionNowEmpty = session.removePlayer(playerId);
                    if (sessionNowEmpty) {
                        destroySession(session, server);
                    }
                }
            }

            // Cleanup all player tracking data
            cleanupPlayerTrackingData(playerId);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }
    
    /**
     * Handle player death in mirror world - set flag for deferred inventory restore.
     * IMPORTANT: Do NOT modify the player's inventory here. The player is mid-death processing
     * and inventory mutations will corrupt vanilla death logic (item drops, death message, etc.).
     * Inventory restoration is deferred to PlayerRespawnEvent via a persistent data flag.
     */
    public static void handleMirrorWorldDeath(ServerPlayer player, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            UUID playerId = player.getUUID();
            
            // Get the session before cleanup
            UUID sessionId = playerToSession.get(playerId);
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

            // Mark player as needing inventory restore on respawn.
            // Do NOT call restorePlayerInventory() here - it corrupts the death pipeline.
            player.getPersistentData().putBoolean(DIED_IN_MIRROR_KEY, true);

            // Clear dimension effects on client
            if (session != null) {
                clearDimensionEffectsForPlayer(player, session.getDimensionIndex());
                
                // Remove player from session
                boolean sessionNowEmpty = session.removePlayer(playerId);
                
                InstantWorldMirror.LOGGER.info("Player {} died and left session {} (players remaining: {})",
                        player.getName().getString(), sessionId, session.getPlayerCount());
                
                if (sessionNowEmpty) {
                    // Session is now empty, destroy it
                    destroySession(session, server);
                }
            }

            // Clean up session tracking data, but preserve saved inventory and position data
            // in persistent NBT - those are needed by the respawn handler.
            playerToSession.remove(playerId);
            playerOwnedSession.remove(playerId);
            // Deliberately NOT removing playerOriginalPositions and playerOriginalDimensions -
            // they will be cleaned up after respawn in handleMirrorDeathRespawn().

            InstantWorldMirror.LOGGER.info(
                    "Cleaned up mirror session data for {} after death (inventory restore deferred to respawn)",
                    player.getName().getString());
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Check if player died in mirror world and needs inventory restore on respawn.
     */
    public static boolean diedInMirrorWorld(ServerPlayer player) {
        return player.getPersistentData().getBoolean(DIED_IN_MIRROR_KEY);
    }

    /**
     * Clear the died-in-mirror flag and restore inventory. Call from respawn handler only.
     * Safe to call here because the player entity has been fully recreated after respawn.
     */
    public static void handleMirrorDeathRespawn(ServerPlayer player) {
        player.getPersistentData().remove(DIED_IN_MIRROR_KEY);
        restorePlayerInventory(player);

        // Now clean up the remaining position tracking data
        playerOriginalPositions.remove(player.getUUID());
        playerOriginalDimensions.remove(player.getUUID());
    }

    /**
     * Restore inventory on login when player has saved mirror data.
     * Unlike forceReturn() which requires the player to be in a mirror world,
     * this method works regardless of the player's current dimension.
     */
    public static void restorePlayerInventoryOnLogin(ServerPlayer player) {
        restorePlayerInventory(player);

        // Clean up position tracking data (may exist in memory if server didn't restart)
        playerOriginalPositions.remove(player.getUUID());
        playerOriginalDimensions.remove(player.getUUID());

        // Also clean up session tracking in case of stale entries
        cleanupPlayerTrackingData(player.getUUID());
    }

    /**
     * Handle player leaving mirror world through external means (commands, other mods, etc.)
     * This is called when PlayerChangedDimensionEvent detects leaving mirror world
     */
    public static void handleExternalExit(ServerPlayer player, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            UUID playerId = player.getUUID();
            
            // Check if player was in a session (might already be cleaned up by death handler)
            UUID sessionId = playerToSession.get(playerId);
            if (sessionId != null) {
                MirrorSession session = activeSessions.get(sessionId);
                if (session != null) {
                    InstantWorldMirror.LOGGER.info("Player {} externally left session {}", 
                            player.getName().getString(), sessionId);
                    
                    // Restore inventory (external exit should restore items like normal return)
                    boolean allowItemTransfer = getItemTransferPermission(playerId, session.getKind());
                    finishPlayerMirrorState(player, allowItemTransfer);
                    
                    boolean sessionNowEmpty = session.removePlayer(playerId);
                    if (sessionNowEmpty) {
                        // Session is now empty, clean it up
                        destroySession(session, server);
                    }
                }
                
                // Send notification
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.external_exit"),
                        false
                );
            }
            
            // Cleanup all player tracking data
            cleanupPlayerTrackingData(playerId);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Spawn a return portal at player's location in mirror world
     * This creates an auto-generated return portal that persists until the session ends
     * 
     * @param mirrorWorld the mirror world level
     * @param player the player (host) who entered
     * @param targetPos the position to spawn the portal (player's feet position, air block above ground)
     * @param sessionId the session ID this portal belongs to
     */
    private static void spawnReturnPortal(ServerLevel mirrorWorld, ServerPlayer player, BlockPos targetPos, UUID sessionId) {
        // targetPos is safePos (player's feet position, air block above ground)
        // groundBlockPos is the solid block below player's feet
        // Use same logic as DimensionMirrorItem.createReturnPortal for consistency
        BlockPos groundBlockPos = targetPos.below();
        
        // Play portal spawn sound (same pitch as manual placement: 1.2F)
        mirrorWorld.playSound(null, groundBlockPos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.2F);
        
        // Spawn portal particles (same as manual placement)
        spawnPortalParticles(mirrorWorld, groundBlockPos);
        
        // Use the unified factory method to create the portal
        // groundBlockPos is the solid block, factory method handles pos.above() internally
        MirrorPortalEntity.spawnReturnPortal(mirrorWorld, groundBlockPos, player.getUUID(), true, sessionId);
        
        // Track chunk for cleanup
        int dimIndex = ModDimensions.getMirrorWorldIndex(mirrorWorld.dimension());
        if (dimIndex >= 0) {
            WorldCopyService.trackModifiedChunk(dimIndex, groundBlockPos.getX() >> 4, groundBlockPos.getZ() >> 4);
        }
    }
    
    /**
     * Spawn portal particle effects (same as DimensionMirrorItem)
     */
    private static void spawnPortalParticles(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;

        for (int i = 0; i < 20; i++) {
            double offsetX = (level.random.nextDouble() - 0.5) * 2;
            double offsetY = level.random.nextDouble() * 2;
            double offsetZ = (level.random.nextDouble() - 0.5) * 2;

            level.sendParticles(
                    ParticleTypes.PORTAL,
                    x + offsetX, y + offsetY, z + offsetZ,
                    1, 0, 0, 0, 0
            );
        }
    }

    /**
     * Check if player is in any mirror world dimension
     * Uses lenient check to handle edge cases where pool size changed
     */
    public static boolean isInMirrorWorld(ServerPlayer player) {
        // Use the lenient check that includes all mirror dimensions regardless of current pool config
        // This ensures players can return even if pool size was reduced after they entered
        return ModDimensions.isAnyMirrorWorld(player.level().dimension());
    }

    public static void preparePlayerForMirrorEntry(ServerPlayer player, boolean sandboxMode) {
        preparePlayerForMirrorEntry(player, sandboxMode, false);
    }

    public static void preparePlayerForMirrorEntry(ServerPlayer player, boolean sandboxMode, boolean persistentAccess) {
        preparePlayerForMirrorEntry(player, MirrorKind.fromSandboxMode(sandboxMode), persistentAccess);
    }

    public static void preparePlayerForMirrorEntry(ServerPlayer player, MirrorKind kind, boolean persistentAccess) {
        playerOriginalPositions.put(player.getUUID(), player.blockPosition());
        playerOriginalDimensions.put(player.getUUID(), player.level().dimension());
        rememberPlayerMirrorKind(player, kind);
        saveMirrorEntryState(player);

        if (kind.isSandbox()) {
            savePlayerSnapshot(player, true);
            prepareSandboxPlayer(player, kind, persistentAccess);
        } else {
            savePlayerInventory(player);
        }
    }

    public static void restorePlayerForMirrorExit(ServerPlayer player) {
        boolean allowItemTransfer = getItemTransferPermission(player.getUUID(), getSavedMirrorKind(player));
        finishPlayerMirrorState(player, allowItemTransfer);
        cleanupPlayerTrackingData(player.getUUID());
    }

    public static BlockPos getSavedOriginalPosition(ServerPlayer player) {
        BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
        if (originalPos != null) {
            return originalPos;
        }

        CompoundTag persistentData = player.getPersistentData();
        if (!persistentData.contains(ORIGINAL_POS_KEY + "_x")) {
            return null;
        }

        return new BlockPos(
                persistentData.getInt(ORIGINAL_POS_KEY + "_x"),
                persistentData.getInt(ORIGINAL_POS_KEY + "_y"),
                persistentData.getInt(ORIGINAL_POS_KEY + "_z")
        );
    }

    public static ServerLevel getSavedOriginalLevel(ServerPlayer player, MinecraftServer server) {
        ResourceKey<Level> originalDimension = playerOriginalDimensions.get(player.getUUID());
        if (originalDimension == null) {
            CompoundTag persistentData = player.getPersistentData();
            if (persistentData.contains(ORIGINAL_DIM_KEY)) {
                ResourceLocation dimLoc = ResourceLocation.tryParse(persistentData.getString(ORIGINAL_DIM_KEY));
                if (dimLoc != null) {
                    originalDimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                }
            }
        }

        if (originalDimension == null) {
            return server.overworld();
        }

        ServerLevel level = server.getLevel(originalDimension);
        return level != null ? level : server.overworld();
    }

    public static BlockPos findMirrorLandingPosition(ServerLevel level, BlockPos targetPos, boolean allowWater) {
        return findSafeLandingPosition(level, targetPos, allowWater);
    }

    public static void clearMirrorLandingArea(ServerLevel level, BlockPos pos) {
        clearAreaForPlayer(level, pos);
    }

    /**
     * Set player item transfer permission
     */
    public static void setItemTransferPermission(UUID playerId, boolean allowed) {
        playerItemTransferPermission.put(playerId, allowed);
    }

    public static void clearItemTransferPermission(UUID playerId) {
        playerItemTransferPermission.remove(playerId);
    }

    /**
     * Get player item transfer permission
     */
    public static boolean getItemTransferPermission(UUID playerId, MirrorKind kind) {
        return playerItemTransferPermission.getOrDefault(playerId, MirrorConfig.isItemTransferEnabled(kind));
    }

    /**
     * Force return to overworld (for commands)
     */
    public static boolean forceReturn(ServerPlayer player) {
        return returnToOverworld(player);
    }

    /**
     * Get active session count (for debugging)
     */
    public static int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Get total players in mirror world (for debugging)
     */
    public static int getTotalPlayersInMirrorWorld() {
        return playerToSession.size();
    }

    // ==================== Dimension Effects Sync ====================
    
    /**
     * Sync dimension visual effects to a player entering a mirror world
     * This tells the client which sky/fog/etc. to render
     */
    private static void syncDimensionEffectsToPlayer(ServerPlayer player, MirrorSession session) {
        syncMirrorEffectsForPlayer(player, session.getSourceDimension(), ModDimensions.getMirrorEffectsKey(session.getMirrorDimension()));
    }

    public static void syncMirrorEffectsForPlayer(ServerPlayer player, ResourceKey<Level> sourceDim, int effectsKey) {
        // Get the source dimension's effects from its DimensionType
        // This works for vanilla and modded dimensions
        ServerLevel sourceLevel = player.getServer().getLevel(sourceDim);
        ResourceLocation effectsLoc;
        if (sourceLevel != null) {
            // Get effects directly from the dimension type
            effectsLoc = sourceLevel.dimensionType().effectsLocation();
        } else {
            // Fallback to overworld effects if source level not found
            effectsLoc = ResourceLocation.withDefaultNamespace("overworld");
        }
        
        // Send packet to the player
        SyncMirrorEffectsPacket packet = new SyncMirrorEffectsPacket(effectsKey, effectsLoc.toString());
        if (!NetworkRegistry.hasChannel(player.connection, SyncMirrorEffectsPacket.TYPE.id())) {
            InstantWorldMirror.LOGGER.debug("Skipping mirror effects sync for {} because the client cannot receive {}",
                    player.getName().getString(), SyncMirrorEffectsPacket.TYPE.id());
            return;
        }
        PacketDistributor.sendToPlayer(player, packet);
        
        InstantWorldMirror.LOGGER.debug("Synced mirror effects to {}: dim {} -> {}", 
                player.getName().getString(), effectsKey, effectsLoc);
    }
    
    /**
     * Clear dimension effects for a player leaving a mirror world
     */
    private static void clearDimensionEffectsForPlayer(ServerPlayer player, int dimIndex) {
        ClearMirrorEffectsPacket packet = new ClearMirrorEffectsPacket(dimIndex);
        if (!NetworkRegistry.hasChannel(player.connection, ClearMirrorEffectsPacket.TYPE.id())) {
            InstantWorldMirror.LOGGER.debug("Skipping mirror effects clear for {} because the client cannot receive {}",
                    player.getName().getString(), ClearMirrorEffectsPacket.TYPE.id());
            return;
        }
        PacketDistributor.sendToPlayer(player, packet);
        
        InstantWorldMirror.LOGGER.debug("Cleared mirror effects for {}: dim {}", 
                player.getName().getString(), dimIndex);
    }

    public static void clearMirrorEffectsForPlayer(ServerPlayer player, int effectsKey) {
        clearDimensionEffectsForPlayer(player, effectsKey);
    }

    // ==================== Inventory Management ====================

    private static void savePlayerInventory(ServerPlayer player) {
        savePlayerSnapshot(player, false);
    }

    /**
     * Save player inventory and state to persistent data for the shared item-transfer pipeline.
     */
    private static void savePlayerSnapshot(ServerPlayer player, boolean sandboxMode) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.putBoolean(SANDBOX_SESSION_KEY, sandboxMode);
        
        // Save inventory
        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);
        persistentData.put(SAVED_INVENTORY_KEY, inventoryTag);
        
        // Save ender chest contents (uses createTag instead of save)
        ListTag enderChestTag = player.getEnderChestInventory().createTag(player.registryAccess());
        persistentData.put(SAVED_ENDERCHEST_KEY, enderChestTag);

        // Save position
        BlockPos pos = player.blockPosition();
        persistentData.putInt(ORIGINAL_POS_KEY + "_x", pos.getX());
        persistentData.putInt(ORIGINAL_POS_KEY + "_y", pos.getY());
        persistentData.putInt(ORIGINAL_POS_KEY + "_z", pos.getZ());
        persistentData.putString(ORIGINAL_DIM_KEY, player.level().dimension().location().toString());

        if (sandboxMode) {
            saveSandboxState(player, persistentData);
        }
        saveMirrorModData(player, persistentData);

        InstantWorldMirror.LOGGER.info("Saved inventory and ender chest to persistent data for player {} ({} inventory items, {} ender chest items)",
                player.getName().getString(), inventoryTag.size(), enderChestTag.size());
    }

    /**
     * Restore inventory and ender chest from player's persistent data
     */
    private static void restorePlayerInventory(ServerPlayer player) {
        restorePlayerInventory(player, true);
    }

    private static void restorePlayerInventory(ServerPlayer player, boolean restoreModData) {
        CompoundTag persistentData = player.getPersistentData();
        boolean sandboxMode = persistentData.getBoolean(SANDBOX_SESSION_KEY);

        if (persistentData.contains(SAVED_INVENTORY_KEY)) {
            // Restore inventory
            ListTag savedInventory = persistentData.getList(SAVED_INVENTORY_KEY, 10);
            player.getInventory().clearContent();
            player.getInventory().load(savedInventory);
            
            // Restore ender chest contents (uses fromTag instead of load)
            // IMPORTANT: Must clear ender chest first to prevent items from mirror world being kept
            if (persistentData.contains(SAVED_ENDERCHEST_KEY)) {
                ListTag savedEnderChest = persistentData.getList(SAVED_ENDERCHEST_KEY, 10);
                player.getEnderChestInventory().clearContent(); // Clear before restoring
                player.getEnderChestInventory().fromTag(savedEnderChest, player.registryAccess());
                InstantWorldMirror.LOGGER.info("Restored inventory and ender chest from persistent data for player {}",
                        player.getName().getString());
            } else {
                // No saved ender chest data - clear it to prevent items from mirror world
                player.getEnderChestInventory().clearContent();
                InstantWorldMirror.LOGGER.info("Restored inventory from persistent data for player {} (cleared ender chest - no saved data)",
                        player.getName().getString());
            }

            if (sandboxMode) {
                restoreSandboxState(player, persistentData);
            } else {
                restoreSavedGameMode(player, persistentData);
            }
            if (restoreModData) {
                restoreMirrorModData(player, persistentData);
            }

            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();

            clearSavedData(player);
        } else {
            InstantWorldMirror.LOGGER.warn("No saved inventory found in persistent data for player {}",
                    player.getName().getString());
            restoreSavedGameMode(player, persistentData);
            clearSavedData(player);
        }
    }

    private static void finishPlayerMirrorState(ServerPlayer player, boolean allowItemTransfer) {
        CompoundTag persistentData = player.getPersistentData();
        boolean sandboxMode = persistentData.getBoolean(SANDBOX_SESSION_KEY);
        if (allowItemTransfer) {
            if (sandboxMode) {
                mergeSandboxItemsIntoSavedInventory(player, getSavedMirrorKind(player));
            } else {
                restoreSavedGameMode(player, persistentData);
                clearSavedData(player);
            }
        } else {
            restorePlayerInventory(player);
        }
    }

    private static void mergeSandboxItemsIntoSavedInventory(ServerPlayer player, MirrorKind kind) {
        List<ItemStack> inventoryItems = copyPlayerInventory(player);
        List<ItemStack> enderChestItems = copyContainer(player.getEnderChestInventory());
        boolean removedIssuedMirror = removeOneIssuedMirror(inventoryItems, kind);
        if (!removedIssuedMirror) {
            removeOneIssuedMirror(enderChestItems, kind);
        }

        restorePlayerInventory(player, false);

        for (ItemStack stack : inventoryItems) {
            addToPlayerInventoryOrDrop(player, stack);
        }
        for (ItemStack stack : enderChestItems) {
            ItemStack remainder = addToContainer(player.getEnderChestInventory(), stack);
            addToPlayerInventoryOrDrop(player, remainder);
        }

        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static List<ItemStack> copyPlayerInventory(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        copyNonEmptyStacks(player.getInventory().items, stacks);
        copyNonEmptyStacks(player.getInventory().armor, stacks);
        copyNonEmptyStacks(player.getInventory().offhand, stacks);
        return stacks;
    }

    private static List<ItemStack> copyContainer(Container container) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    private static void copyNonEmptyStacks(Iterable<ItemStack> source, List<ItemStack> target) {
        for (ItemStack stack : source) {
            if (!stack.isEmpty()) {
                target.add(stack.copy());
            }
        }
    }

    private static boolean removeOneIssuedMirror(List<ItemStack> stacks, MirrorKind kind) {
        for (int index = 0; index < stacks.size(); index++) {
            ItemStack stack = stacks.get(index);
            if (!stack.is(ModItems.mirrorItem(kind))) {
                continue;
            }
            if (stack.getCount() == 1) {
                stacks.remove(index);
            } else {
                stack.shrink(1);
            }
            return true;
        }
        return false;
    }

    private static ItemStack addToContainer(Container container, ItemStack source) {
        ItemStack remainder = source.copy();
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remainder)) {
                continue;
            }
            int moved = Math.min(remainder.getCount(),
                    Math.min(container.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount());
            if (moved > 0) {
                existing.grow(moved);
                remainder.shrink(moved);
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(remainder.getCount(),
                    Math.min(container.getMaxStackSize(), remainder.getMaxStackSize()));
            ItemStack placed = remainder.copy();
            placed.setCount(moved);
            container.setItem(slot, placed);
            remainder.shrink(moved);
        }
        container.setChanged();
        return remainder;
    }

    private static void addToPlayerInventoryOrDrop(ServerPlayer player, ItemStack source) {
        if (source.isEmpty()) {
            return;
        }
        ItemStack remainder = source.copy();
        player.getInventory().add(remainder);
        if (!remainder.isEmpty()) {
            player.drop(remainder, false);
        }
    }

    private static void saveMirrorEntryState(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.putBoolean(SAVED_GAME_MODE_PRESENT_KEY, true);
        persistentData.putInt(SAVED_GAME_MODE_KEY, player.gameMode.getGameModeForPlayer().getId());

        BlockPos pos = player.blockPosition();
        persistentData.putInt(ORIGINAL_POS_KEY + "_x", pos.getX());
        persistentData.putInt(ORIGINAL_POS_KEY + "_y", pos.getY());
        persistentData.putInt(ORIGINAL_POS_KEY + "_z", pos.getZ());
        persistentData.putString(ORIGINAL_DIM_KEY, player.level().dimension().location().toString());
    }

    private static void saveSandboxState(ServerPlayer player, CompoundTag persistentData) {
        persistentData.putInt(SAVED_GAME_MODE_KEY, player.gameMode.getGameModeForPlayer().getId());
        persistentData.putFloat(SAVED_HEALTH_KEY, player.getHealth());
        persistentData.putFloat(SAVED_ABSORPTION_KEY, player.getAbsorptionAmount());
        persistentData.putInt(SAVED_FOOD_KEY, player.getFoodData().getFoodLevel());
        persistentData.putFloat(SAVED_SATURATION_KEY, player.getFoodData().getSaturationLevel());
        persistentData.putFloat(SAVED_EXHAUSTION_KEY, player.getFoodData().getExhaustionLevel());
        persistentData.putFloat(SAVED_XP_PROGRESS_KEY, player.experienceProgress);
        persistentData.putInt(SAVED_XP_LEVEL_KEY, player.experienceLevel);
        persistentData.putInt(SAVED_XP_TOTAL_KEY, player.totalExperience);
        persistentData.putInt(SAVED_FIRE_TICKS_KEY, player.getRemainingFireTicks());
        persistentData.putInt(SAVED_AIR_SUPPLY_KEY, player.getAirSupply());

        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            Tag savedEffect = effect.save();
            if (savedEffect instanceof CompoundTag effectTag) {
                effects.add(effectTag);
            }
        }
        persistentData.put(SAVED_EFFECTS_KEY, effects);
    }

    private static void saveMirrorModData(ServerPlayer player, CompoundTag persistentData) {
        CompoundTag playerTag = player.saveWithoutId(new CompoundTag());
        saveMirrorModDataKey(playerTag, persistentData, PLAYER_FORGE_CAPS_NBT_KEY, SAVED_MIRROR_FORGE_CAPS_KEY);
        saveMirrorModDataKey(playerTag, persistentData, PLAYER_NEOFORGE_ATTACHMENTS_NBT_KEY, SAVED_MIRROR_NEOFORGE_ATTACHMENTS_KEY);
    }

    private static void saveMirrorModDataKey(CompoundTag playerTag, CompoundTag persistentData, String playerKey, String savedKey) {
        if (playerTag.contains(playerKey, Tag.TAG_COMPOUND)) {
            persistentData.put(savedKey, playerTag.getCompound(playerKey).copy());
        } else {
            persistentData.remove(savedKey);
        }
    }

    private static void prepareSandboxPlayer(ServerPlayer player, MirrorKind kind, boolean persistentAccess) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.removeAllEffects();
        player.setRemainingFireTicks(0);
        player.setAirSupply(player.getMaxAirSupply());
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        player.getFoodData().setExhaustion(0.0F);
        player.experienceProgress = 0.0F;
        player.experienceLevel = 0;
        player.totalExperience = 0;
        player.setGameMode(GameType.CREATIVE);
        giveSandboxMirror(player, kind, persistentAccess);
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static void giveSandboxMirror(ServerPlayer player, MirrorKind kind, boolean persistentAccess) {
        player.getInventory().selected = 0;
        ItemStack mirror = new ItemStack(ModItems.mirrorItem(kind));
        if (persistentAccess) {
            ModEnchantments.applyPermanence(player.level(), mirror);
        }
        player.getInventory().items.set(0, mirror);
    }

    private static void restoreSandboxState(ServerPlayer player, CompoundTag persistentData) {
        if (persistentData.contains(SAVED_GAME_MODE_KEY)) {
            GameType gameType = GameType.byId(persistentData.getInt(SAVED_GAME_MODE_KEY));
            if (gameType != null) {
                player.setGameMode(gameType);
            }
        }
        syncPlayerAbilitiesToGameMode(player);

        if (persistentData.contains(SAVED_HEALTH_KEY)) {
            player.setHealth(Math.min(persistentData.getFloat(SAVED_HEALTH_KEY), player.getMaxHealth()));
        }
        if (persistentData.contains(SAVED_ABSORPTION_KEY)) {
            player.setAbsorptionAmount(persistentData.getFloat(SAVED_ABSORPTION_KEY));
        }
        if (persistentData.contains(SAVED_FOOD_KEY)) {
            player.getFoodData().setFoodLevel(persistentData.getInt(SAVED_FOOD_KEY));
        }
        if (persistentData.contains(SAVED_SATURATION_KEY)) {
            player.getFoodData().setSaturation(persistentData.getFloat(SAVED_SATURATION_KEY));
        }
        if (persistentData.contains(SAVED_EXHAUSTION_KEY)) {
            player.getFoodData().setExhaustion(persistentData.getFloat(SAVED_EXHAUSTION_KEY));
        }
        if (persistentData.contains(SAVED_XP_PROGRESS_KEY)) {
            player.experienceProgress = persistentData.getFloat(SAVED_XP_PROGRESS_KEY);
        }
        if (persistentData.contains(SAVED_XP_LEVEL_KEY)) {
            player.experienceLevel = persistentData.getInt(SAVED_XP_LEVEL_KEY);
        }
        if (persistentData.contains(SAVED_XP_TOTAL_KEY)) {
            player.totalExperience = persistentData.getInt(SAVED_XP_TOTAL_KEY);
        }
        if (persistentData.contains(SAVED_FIRE_TICKS_KEY)) {
            player.setRemainingFireTicks(persistentData.getInt(SAVED_FIRE_TICKS_KEY));
        }
        if (persistentData.contains(SAVED_AIR_SUPPLY_KEY)) {
            player.setAirSupply(persistentData.getInt(SAVED_AIR_SUPPLY_KEY));
        }

        player.removeAllEffects();
        if (persistentData.contains(SAVED_EFFECTS_KEY)) {
            ListTag effects = persistentData.getList(SAVED_EFFECTS_KEY, 10);
            for (int i = 0; i < effects.size(); i++) {
                MobEffectInstance effect = MobEffectInstance.load(effects.getCompound(i));
                if (effect != null) {
                    player.addEffect(effect);
                }
            }
        }
    }

    private static void restoreMirrorModData(ServerPlayer player, CompoundTag persistentData) {
        CompoundTag playerTag = player.saveWithoutId(new CompoundTag());
        restoreMirrorModDataKey(playerTag, persistentData, PLAYER_FORGE_CAPS_NBT_KEY, SAVED_MIRROR_FORGE_CAPS_KEY);
        restoreMirrorModDataKey(playerTag, persistentData, PLAYER_NEOFORGE_ATTACHMENTS_NBT_KEY, SAVED_MIRROR_NEOFORGE_ATTACHMENTS_KEY);
        player.load(playerTag);
        syncPlayerAbilitiesToGameMode(player);
    }

    private static void restoreMirrorModDataKey(CompoundTag playerTag, CompoundTag persistentData, String playerKey, String savedKey) {
        if (persistentData.contains(savedKey, Tag.TAG_COMPOUND)) {
            playerTag.put(playerKey, persistentData.getCompound(savedKey).copy());
        } else {
            playerTag.remove(playerKey);
        }
    }

    private static void restoreSavedGameMode(ServerPlayer player, CompoundTag persistentData) {
        if (persistentData.getBoolean(SAVED_GAME_MODE_PRESENT_KEY) || persistentData.contains(SAVED_GAME_MODE_KEY)) {
            GameType gameType = GameType.byId(persistentData.getInt(SAVED_GAME_MODE_KEY));
            if (gameType != null) {
                player.setGameMode(gameType);
            }
        }
        syncPlayerAbilitiesToGameMode(player);
    }

    public static void syncPlayerAbilitiesToGameMode(ServerPlayer player) {
        player.gameMode.getGameModeForPlayer().updatePlayerAbilities(player.getAbilities());
        player.onUpdateAbilities();
    }

    /**
     * Check if player has saved inventory data
     */
    public static boolean hasSavedInventory(ServerPlayer player) {
        return player.getPersistentData().contains(SAVED_INVENTORY_KEY);
    }

    public static boolean hasSavedMirrorState(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        return persistentData.contains(SAVED_INVENTORY_KEY)
                || persistentData.getBoolean(SAVED_GAME_MODE_PRESENT_KEY)
                || persistentData.contains(SAVED_GAME_MODE_KEY)
                || persistentData.contains(ORIGINAL_DIM_KEY)
                || persistentData.contains(SAVED_MIRROR_FORGE_CAPS_KEY)
                || persistentData.contains(SAVED_MIRROR_NEOFORGE_ATTACHMENTS_KEY);
    }

    /**
     * Clear player's saved data (inventory, ender chest, position, etc.)
     */
    public static void clearSavedData(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.remove(SAVED_INVENTORY_KEY);
        persistentData.remove(SAVED_ENDERCHEST_KEY);
        persistentData.remove(SANDBOX_SESSION_KEY);
        persistentData.remove(SAVED_GAME_MODE_PRESENT_KEY);
        persistentData.remove(SAVED_GAME_MODE_KEY);
        persistentData.remove(SAVED_HEALTH_KEY);
        persistentData.remove(SAVED_ABSORPTION_KEY);
        persistentData.remove(SAVED_FOOD_KEY);
        persistentData.remove(SAVED_SATURATION_KEY);
        persistentData.remove(SAVED_EXHAUSTION_KEY);
        persistentData.remove(SAVED_XP_PROGRESS_KEY);
        persistentData.remove(SAVED_XP_LEVEL_KEY);
        persistentData.remove(SAVED_XP_TOTAL_KEY);
        persistentData.remove(SAVED_FIRE_TICKS_KEY);
        persistentData.remove(SAVED_AIR_SUPPLY_KEY);
        persistentData.remove(SAVED_EFFECTS_KEY);
        persistentData.remove(SAVED_MIRROR_FORGE_CAPS_KEY);
        persistentData.remove(SAVED_MIRROR_NEOFORGE_ATTACHMENTS_KEY);
        persistentData.remove(ORIGINAL_POS_KEY + "_x");
        persistentData.remove(ORIGINAL_POS_KEY + "_y");
        persistentData.remove(ORIGINAL_POS_KEY + "_z");
        persistentData.remove(ORIGINAL_DIM_KEY);
        persistentData.remove(SESSION_ID_KEY);
        persistentData.remove(MIRROR_KIND_KEY);
        persistentData.remove(DIED_IN_MIRROR_KEY);
        InstantWorldMirror.LOGGER.info("Cleared saved data for player {}", player.getName().getString());
    }
    
    /**
     * Get the number of players in a session
     */
    public static int getSessionPlayerCount(UUID sessionId) {
        sessionLock.readLock().lock();
        try {
            MirrorSession session = activeSessions.get(sessionId);
            if (session != null) {
                return session.getPlayerCount();
            }
            return 0;
        } finally {
            sessionLock.readLock().unlock();
        }
    }
    
    /**
     * Force clear a dimension - return all players to spawn and start cleanup
     * This method will ALWAYS trigger a full cleanup, even if the dimension appears empty
     * @return number of players returned
     */
    public static int forceClearDimension(int dimIndex, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            int playersReturned = 0;
            
            // Cancel any pending cleanup task first to restart fresh
            WorldCopyService.cancelCleanupTask(dimIndex);
            
            // First: Get all players DIRECTLY from the dimension (most reliable)
            ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, dimIndex);
            ResourceKey<Level> mirrorDimKey = ModDimensions.getMirrorWorld(dimIndex);
            
            // Get players from dimension AND from server player list (belt and suspenders)
            java.util.Set<ServerPlayer> allPlayersToMove = new java.util.HashSet<>();
            
            // Method 1: Get from the dimension's player list
            if (mirrorWorld != null) {
                allPlayersToMove.addAll(mirrorWorld.players());
            }
            
            // Method 2: Scan all server players and check their dimension
            for (ServerPlayer serverPlayer : server.getPlayerList().getPlayers()) {
                if (serverPlayer.level().dimension().equals(mirrorDimKey)) {
                    allPlayersToMove.add(serverPlayer);
                }
            }
            
            if (!allPlayersToMove.isEmpty()) {
                ServerLevel overworld = server.overworld();
                BlockPos defaultSpawnPoint = overworld.getSharedSpawnPos();
                
                for (ServerPlayer player : allPlayersToMove) {
                    UUID playerId = player.getUUID();
                    
                    // Get original position and dimension - first try memory, then persistent data
                    BlockPos originalPos = playerOriginalPositions.get(playerId);
                    ResourceKey<Level> originalDimension = playerOriginalDimensions.get(playerId);
                    
                    // If not in memory, try to restore from persistent data
                    if (originalPos == null || originalDimension == null) {
                        CompoundTag persistentData = player.getPersistentData();
                        
                        // Try to get position from persistent data
                        if (persistentData.contains(ORIGINAL_POS_KEY + "_x")) {
                            int x = persistentData.getInt(ORIGINAL_POS_KEY + "_x");
                            int y = persistentData.getInt(ORIGINAL_POS_KEY + "_y");
                            int z = persistentData.getInt(ORIGINAL_POS_KEY + "_z");
                            originalPos = new BlockPos(x, y, z);
                        }
                        
                        // Try to get dimension from persistent data
                        if (persistentData.contains(ORIGINAL_DIM_KEY)) {
                            String dimString = persistentData.getString(ORIGINAL_DIM_KEY);
                            ResourceLocation dimLoc = ResourceLocation.tryParse(dimString);
                            if (dimLoc != null) {
                                originalDimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                            }
                        }
                        
                        // Also restore to memory for the event handler to use
                        if (originalPos != null && originalDimension != null) {
                            playerOriginalPositions.put(playerId, originalPos);
                            playerOriginalDimensions.put(playerId, originalDimension);
                        }
                    }
                    
                    ServerLevel targetLevel;
                    BlockPos targetPos;
                    
                    if (originalPos != null && originalDimension != null) {
                        targetLevel = server.getLevel(originalDimension);
                        targetPos = originalPos;
                    } else {
                        // Fallback to overworld spawn - also set originalDimension so event handler allows it
                        targetLevel = overworld;
                        targetPos = defaultSpawnPoint;
                        originalDimension = Level.OVERWORLD;
                    }
                    
                    // Fallback to overworld spawn if target dimension is invalid
                    if (targetLevel == null) {
                        targetLevel = overworld;
                        targetPos = defaultSpawnPoint;
                        originalDimension = Level.OVERWORLD;
                    }
                    
                    // Set the original dimension in memory so the event handler allows the teleport
                    playerOriginalDimensions.put(playerId, originalDimension);
                    
                    // Teleport player to their original position/dimension with whitelist protection
                    playersBeingTeleported.add(playerId);
                    try {
                        player.teleportTo(targetLevel, 
                                targetPos.getX() + 0.5, 
                                targetPos.getY(), 
                                targetPos.getZ() + 0.5, 
                                java.util.Set.of(),
                                player.getYRot(), 
                                player.getXRot());
                    } finally {
                        playersBeingTeleported.remove(playerId);
                    }
                    
                    // Restore or clear their saved mirror state using the same rules as a normal return.
                    MirrorKind kind = getMirrorKindForDimension(server, mirrorDimKey)
                            .orElseGet(() -> getSavedMirrorKind(player));
                    boolean allowItemTransfer = getItemTransferPermission(playerId, kind);
                    finishPlayerMirrorState(player, allowItemTransfer);
                    
                    // Remove from tracking maps
                    cleanupPlayerTrackingData(playerId);
                    
                    // Notify player
                    player.displayClientMessage(
                            Component.translatable("message.instantworldmirror.force_returned"),
                            false
                    );
                    
                    playersReturned++;
                }
            }
            
            // Get the session using this dimension and clean up session data
            Optional<UUID> sessionIdOpt = DimensionPool.getDimensionSession(dimIndex);
            if (sessionIdOpt.isPresent()) {
                UUID sessionId = sessionIdOpt.get();
                MirrorSession session = activeSessions.get(sessionId);
                
                if (session != null) {
                    // Remove ownership tracking
                    for (Map.Entry<UUID, UUID> entry : new java.util.HashMap<>(playerOwnedSession).entrySet()) {
                        if (entry.getValue().equals(sessionId)) {
                            playerOwnedSession.remove(entry.getKey());
                        }
                    }
                    
                    // Destroy the session (this will trigger cleanup)
                    destroySession(session, server);
                }
            } else {
                // No active session - force cleanup anyway
                DimensionPool.DimensionState state = DimensionPool.getDimensionState(dimIndex);
                InstantWorldMirror.LOGGER.info("Force clearing dimension {} (state: {}) without active session", 
                        dimIndex, state);
                
                // Mark dimension as cleaning
                DimensionPool.markDimensionCleaning(dimIndex);
                
                // Queue aggressive async cleanup
                if (mirrorWorld != null) {
                    WorldCopyService.cleanupMirrorWorld(mirrorWorld, dimIndex);
                }
            }
            
            InstantWorldMirror.LOGGER.info("Force cleared dimension {} - returned {} players", 
                    dimIndex, playersReturned);
            return playersReturned;
            
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Requeue cleanup for temporary mirror dimensions left dirty by an interrupted
     * session, crash, or failed force clear. Returns the number of cleanup tasks
     * started or restarted.
     */
    public static int recoverTemporaryMirrorCleanups(MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            return recoverTemporaryMirrorCleanupsLocked(server);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    private static int recoverTemporaryMirrorCleanupsLocked(MinecraftServer server) {
        int recoveredDimensions = 0;
        int poolSize = ModDimensions.getPoolSize();
        for (int dimIndex = 0; dimIndex < poolSize; dimIndex++) {
            DimensionPool.DimensionState state = DimensionPool.getDimensionState(dimIndex);
            boolean markedForCleanup = DimensionPool.isMarkedForCleanup(dimIndex);
            boolean pendingCleanup = WorldCopyService.hasPendingCleanup(dimIndex);
            UUID sessionId = DimensionPool.getSessionForDimension(dimIndex);
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;
            boolean hasLiveSession = session != null && !session.isDestroyed();

            ServerLevel mirrorLevel = DimensionPool.getDimensionLevel(server, dimIndex);
            int playerCount = mirrorLevel != null ? mirrorLevel.players().size() : 0;

            if (hasLiveSession || playerCount > 0) {
                continue;
            }

            boolean shouldQueueCleanup = false;
            if (state == DimensionPool.DimensionState.IN_USE && (sessionId == null || session == null || session.isDestroyed())) {
                InstantWorldMirror.LOGGER.warn(
                        "Recovering orphaned temporary mirror dimension {} (state IN_USE, session {})",
                        dimIndex, sessionId);
                DimensionPool.forceReleaseDimension(dimIndex);
                state = DimensionPool.DimensionState.CLEANING;
                shouldQueueCleanup = true;
            } else if (markedForCleanup && state != DimensionPool.DimensionState.CLEANING) {
                InstantWorldMirror.LOGGER.warn(
                        "Recovering temporary mirror dimension {} from saved cleanup marker (state {})",
                        dimIndex, state);
                DimensionPool.markDimensionCleaning(dimIndex);
                state = DimensionPool.DimensionState.CLEANING;
                shouldQueueCleanup = true;
            } else if (state == DimensionPool.DimensionState.CLEANING && !pendingCleanup) {
                shouldQueueCleanup = true;
            }

            if (!shouldQueueCleanup || pendingCleanup) {
                continue;
            }

            if (mirrorLevel == null) {
                if (!DimensionPool.hasSavedCleanupWork(dimIndex)
                        && !DimensionPool.hasMirrorWorldRegionFiles(server, dimIndex)) {
                    DimensionPool.markDimensionAvailable(dimIndex);
                    recoveredDimensions++;
                    InstantWorldMirror.LOGGER.info(
                            "Released temporary mirror dimension {} without cleanup because no mirror world or saved cleanup work exists",
                            dimIndex);
                    continue;
                }

                InstantWorldMirror.LOGGER.warn(
                        "Temporary mirror dimension {} needs cleanup but is not loaded; recovery will retry later",
                        dimIndex);
                continue;
            }

            WorldCopyService.cleanupMirrorWorld(mirrorLevel, dimIndex);
            recoveredDimensions++;
            InstantWorldMirror.LOGGER.info("Re-queued cleanup for temporary mirror dimension {}", dimIndex);
        }

        return recoveredDimensions;
    }

    /**
     * Cleanup stale/orphaned sessions and dimensions
     * This is a fallback mechanism that runs periodically to catch:
     * 1. Sessions with no players that weren't properly cleaned up
     * 2. Dimensions marked IN_USE but have no active session
     * 3. Copy tasks that got stuck/orphaned
     * 
     * Called every 5 minutes from ModEvents
     */
    public static void cleanupStaleSessions(MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            int cleanedSessions = 0;
            int cleanedDimensions = 0;
            
            // 1. Find and destroy empty sessions (no players)
            java.util.List<UUID> emptySessionIds = new java.util.ArrayList<>();
            for (Map.Entry<UUID, MirrorSession> entry : activeSessions.entrySet()) {
                MirrorSession session = entry.getValue();
                if (session.isEmpty() && !session.isDestroyed()) {
                    // Check if session has been empty for a while (no owned by anyone)
                    UUID creatorId = session.getCreatorId();
                    if (!playerOwnedSession.containsKey(creatorId)) {
                        emptySessionIds.add(entry.getKey());
                    }
                }
            }
            
            for (UUID sessionId : emptySessionIds) {
                MirrorSession session = activeSessions.get(sessionId);
                if (session != null) {
                    InstantWorldMirror.LOGGER.warn("Cleaning up stale empty session: {}", sessionId);
                    destroySession(session, server);
                    cleanedSessions++;
                }
            }
            
            // 2. Find orphaned dimensions (IN_USE but no matching session)
            int poolSize = ModDimensions.getPoolSize();
            for (int dimIndex = 0; dimIndex < poolSize; dimIndex++) {
                DimensionPool.DimensionState state = DimensionPool.getDimensionState(dimIndex);
                if (state == DimensionPool.DimensionState.IN_USE) {
                    UUID sessionId = DimensionPool.getSessionForDimension(dimIndex);
                    if (sessionId == null || !activeSessions.containsKey(sessionId)) {
                        // Dimension is marked IN_USE but has no active session
                        // Check if there are any players in this dimension
                        ServerLevel mirrorLevel = DimensionPool.getDimensionLevel(server, dimIndex);
                        if (mirrorLevel != null && mirrorLevel.players().isEmpty()) {
                            InstantWorldMirror.LOGGER.warn("Cleaning up orphaned dimension {} (no session or players)", dimIndex);
                            
                            // Cancel any copy task
                            WorldCopyService.cancelCopyTask(dimIndex);
                            
                            // Force release and cleanup
                            DimensionPool.forceReleaseDimension(dimIndex);
                            WorldCopyService.cleanupMirrorWorld(mirrorLevel, dimIndex);
                            cleanedDimensions++;
                        }
                    }
                }
            }

            cleanedDimensions += recoverTemporaryMirrorCleanupsLocked(server);
            
            if (cleanedSessions > 0 || cleanedDimensions > 0) {
                InstantWorldMirror.LOGGER.info("Stale cleanup completed: {} sessions, {} dimensions", 
                        cleanedSessions, cleanedDimensions);
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Clear all sessions (for server shutdown)
     */
    public static void clearAllSessions() {
        clearAllSessions(null);
    }

    /**
     * Clear all sessions (for server shutdown) and persist cleanup state for any in-use dimensions.
     */
    public static void clearAllSessions(MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            for (MirrorSession session : activeSessions.values()) {
                int dimIndex = session.getDimensionIndex();
                if (dimIndex >= 0) {
                    DimensionPool.releaseDimension(session.getSessionId());
                }
                session.markDestroyed();
            }

            for (Map.Entry<UUID, Integer> entry : deferredPersistentSourceReleases.entrySet()) {
                UUID sessionId = entry.getKey();
                int dimIndex = entry.getValue();
                DimensionPool.releaseDimension(sessionId);
                if (DimensionPool.getDimensionState(dimIndex) != DimensionPool.DimensionState.CLEANING) {
                    DimensionPool.markDimensionCleaning(dimIndex);
                }
            }

            activeSessions.clear();
            portalToSession.clear();
            playerToSession.clear();
            playerOwnedSession.clear();
            playerOriginalPositions.clear();
            playerOriginalDimensions.clear();
            playerMirrorKinds.clear();
            playerItemTransferPermission.clear();
            persistentSaveSourceHolds.clear();
            deferredPersistentSourceReleases.clear();
            
            // Clear dimension pool
            DimensionPool.clearAll();
            
            InstantWorldMirror.LOGGER.info("All sessions and dimension pool cleared");
        } finally {
            sessionLock.writeLock().unlock();
        }
    }
}
