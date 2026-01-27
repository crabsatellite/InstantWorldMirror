package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.network.ClearMirrorEffectsPacket;
import com.crabmods.instantworldmirror.network.SyncMirrorEffectsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
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

    // Lock for thread-safe session operations
    private static final ReadWriteLock sessionLock = new ReentrantReadWriteLock();

    // Active sessions: sessionId -> MirrorSession
    private static final Map<UUID, MirrorSession> activeSessions = new ConcurrentHashMap<>();

    // Portal to session mapping for O(1) lookup: portalEntityId -> sessionId
    private static final Map<UUID, UUID> portalToSession = new ConcurrentHashMap<>();

    // Player to session mapping: playerId -> sessionId (for players currently in mirror world)
    private static final Map<UUID, UUID> playerToSession = new ConcurrentHashMap<>();

    // Player's active session in overworld (created but not entered): creatorId -> sessionId
    private static final Map<UUID, UUID> playerOwnedSession = new ConcurrentHashMap<>();

    // Player's original position (thread-safe)
    private static final Map<UUID, BlockPos> playerOriginalPositions = new ConcurrentHashMap<>();
    private static final Map<UUID, ResourceKey<Level>> playerOriginalDimensions = new ConcurrentHashMap<>();

    // Player item transfer permission
    private static final Map<UUID, Boolean> playerItemTransferPermission = new ConcurrentHashMap<>();

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
        // Check if purge mode is active
        if (purgeMode) {
            InstantWorldMirror.LOGGER.warn("Cannot create session - purge mode is active");
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.purge_mode_active"),
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
                    sourceInWater
            );

            // Allocate a dimension from the pool using actual session ID and source dimension
            int dimIndex = DimensionPool.allocateDimension(session.getSessionId(), session.getSourceDimension());
            if (dimIndex < 0) {
                InstantWorldMirror.LOGGER.warn("No available dimensions for player {}",
                        player.getName().getString());
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.no_dimensions_available"),
                        true
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
        playerItemTransferPermission.remove(playerId);
        playerOwnedSession.remove(playerId);
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

            // Save player's current position
            playerOriginalPositions.put(player.getUUID(), player.blockPosition());
            playerOriginalDimensions.put(player.getUUID(), player.level().dimension());

            // Save session ID to player's persistent data (for server restart recovery)
            CompoundTag persistentData = player.getPersistentData();
            persistentData.putUUID(SESSION_ID_KEY, session.getSessionId());

            // Save player inventory
            savePlayerInventory(player);

            // Get the session's dedicated mirror world dimension
            ResourceKey<Level> mirrorDimKey = session.getMirrorDimension();
            if (mirrorDimKey == null) {
                InstantWorldMirror.LOGGER.error("Session {} has no allocated dimension!", session.getSessionId());
                return false;
            }
            
            ServerLevel mirrorWorld = server.getLevel(mirrorDimKey);
            if (mirrorWorld == null) {
                InstantWorldMirror.LOGGER.error("Mirror world dimension {} not found!", mirrorDimKey);
                return false;
            }

            // Add player to session
            session.addPlayer(player.getUUID());
            playerToSession.put(player.getUUID(), session.getSessionId());

            // If this player owned this session (creator entering), remove from owned
            playerOwnedSession.remove(player.getUUID());

            // Calculate target position (source position + 1 block up)
            BlockPos baseTargetPos = session.getSourcePosition().above();
            
            // Find a safe position to teleport to (avoid being stuck in walls)
            // If source was in water, allow water positions (player is doing underwater exploration)
            boolean allowWater = session.isSourceInWater();
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
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Teleport player from mirror world back to overworld
     */
    public static boolean returnToOverworld(ServerPlayer player) {
        if (player.level().isClientSide) {
            return false;
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

        sessionLock.writeLock().lock();
        try {
            // Get player's session (may be null after server restart)
            UUID sessionId = playerToSession.get(player.getUUID());
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

            // Get original position - first try memory, then persistent data (for server restart recovery)
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

            // Handle inventory
            boolean allowItemTransfer = playerItemTransferPermission.getOrDefault(player.getUUID(), false);
            if (allowItemTransfer) {
                clearSavedData(player);
            } else {
                restorePlayerInventory(player);
            }

            // Mark player as being teleported by the mod (to bypass dimension travel block)
            playersBeingTeleported.add(player.getUUID());
            
            try {
                // Execute teleportation
                player.teleportTo(
                        targetLevel,
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        player.getYRot(),
                        player.getXRot()
                );
            } finally {
                // Always remove from teleport whitelist
                playersBeingTeleported.remove(player.getUUID());
            }
            
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

            player.displayClientMessage(Component.translatable("message.instantworldmirror.returned"), true);
            InstantWorldMirror.LOGGER.debug("Player {} returned from mirror world", player.getName().getString());

            return true;
        } finally {
            sessionLock.writeLock().unlock();
        }
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

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Get player's saved original position
        BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
        
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

            // Handle inventory
            boolean allowItemTransfer = playerItemTransferPermission.getOrDefault(player.getUUID(), false);
            if (allowItemTransfer) {
                clearSavedData(player);
            } else {
                restorePlayerInventory(player);
            }

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
            // Use base cooldown (30 seconds minimum, since we don't have the item stack here)
            int baseCooldownSeconds = MirrorConfig.getMirrorCooldownTicks() / 20;
            int finalCooldownSeconds = Math.max(30, baseCooldownSeconds);
            com.crabmods.instantworldmirror.item.DimensionMirrorItem.setCooldown(player.getUUID(), finalCooldownSeconds);
            com.crabmods.instantworldmirror.item.DimensionMirrorItem.syncCooldownToClient(player);
            InstantWorldMirror.LOGGER.debug("Applied cooldown {} seconds to {} for teleport to spawn",
                    finalCooldownSeconds, player.getName().getString());
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
     * Handle player death in mirror world - immediately cleanup session
     * This is called from the death event to ensure clean state before respawn
     */
    public static void handleMirrorWorldDeath(ServerPlayer player, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            UUID playerId = player.getUUID();
            
            // Get the session before cleanup
            UUID sessionId = playerToSession.get(playerId);
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;
            
            // Restore player's original inventory (they died, so items stay in mirror world)
            restorePlayerInventory(player);
            
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
            
            // Cleanup all player data
            cleanupPlayerTrackingData(playerId);
            
            InstantWorldMirror.LOGGER.info("Cleaned up mirror world session data for {} after death",
                    player.getName().getString());
        } finally {
            sessionLock.writeLock().unlock();
        }
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
                    boolean allowItemTransfer = playerItemTransferPermission.getOrDefault(playerId, false);
                    if (allowItemTransfer) {
                        clearSavedData(player);
                    } else {
                        restorePlayerInventory(player);
                    }
                    
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
     * @param targetPos the position to spawn the portal
     * @param sessionId the session ID this portal belongs to
     */
    private static void spawnReturnPortal(ServerLevel mirrorWorld, ServerPlayer player, BlockPos targetPos, UUID sessionId) {
        BlockPos portalBase = targetPos.below();
        
        mirrorWorld.playSound(null, targetPos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.5F);
        
        // Create auto-generated return portal (permanent until session ends)
        MirrorPortalEntity returnPortal = new MirrorPortalEntity(
                mirrorWorld,
                portalBase.getX() + 0.5,
                portalBase.getY() + 1.5,
                portalBase.getZ() + 0.5,
                player.getUUID(),
                true,      // is return portal
                true,      // is auto-generated
                sessionId  // session ID for tracking
        );
        
        mirrorWorld.addFreshEntity(returnPortal);
        
        // Track chunk for cleanup
        int dimIndex = ModDimensions.getMirrorWorldIndex(mirrorWorld.dimension());
        if (dimIndex >= 0) {
            WorldCopyService.trackModifiedChunk(dimIndex, targetPos.getX() >> 4, targetPos.getZ() >> 4);
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

    /**
     * Set player item transfer permission
     */
    public static void setItemTransferPermission(UUID playerId, boolean allowed) {
        playerItemTransferPermission.put(playerId, allowed);
    }

    /**
     * Get player item transfer permission
     */
    public static boolean getItemTransferPermission(UUID playerId) {
        return playerItemTransferPermission.getOrDefault(playerId, MirrorConfig.ALLOW_ITEM_TRANSFER.get());
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
        ResourceKey<Level> sourceDim = session.getSourceDimension();
        int dimIndex = session.getDimensionIndex();
        
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
        SyncMirrorEffectsPacket packet = new SyncMirrorEffectsPacket(dimIndex, effectsLoc.toString());
        PacketDistributor.sendToPlayer(player, packet);
        
        InstantWorldMirror.LOGGER.debug("Synced mirror effects to {}: dim {} -> {}", 
                player.getName().getString(), dimIndex, effectsLoc);
    }
    
    /**
     * Clear dimension effects for a player leaving a mirror world
     */
    private static void clearDimensionEffectsForPlayer(ServerPlayer player, int dimIndex) {
        ClearMirrorEffectsPacket packet = new ClearMirrorEffectsPacket(dimIndex);
        PacketDistributor.sendToPlayer(player, packet);
        
        InstantWorldMirror.LOGGER.debug("Cleared mirror effects for {}: dim {}", 
                player.getName().getString(), dimIndex);
    }

    // ==================== Inventory Management ====================

    /**
     * Save player inventory and ender chest to player's persistent data
     * Only saves for survival mode players (creative/spectator players don't need inventory restore)
     */
    private static void savePlayerInventory(ServerPlayer player) {
        // Only save inventory for survival mode players
        if (!player.gameMode.isSurvival()) {
            InstantWorldMirror.LOGGER.info("Skipping inventory save for non-survival player {}",
                    player.getName().getString());
            return;
        }
        
        CompoundTag persistentData = player.getPersistentData();
        
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

        InstantWorldMirror.LOGGER.info("Saved inventory and ender chest to persistent data for player {} ({} inventory items, {} ender chest items)",
                player.getName().getString(), inventoryTag.size(), enderChestTag.size());
    }

    /**
     * Restore inventory and ender chest from player's persistent data
     * Only restores for survival mode players
     */
    private static void restorePlayerInventory(ServerPlayer player) {
        // Only restore inventory for survival mode players
        if (!player.gameMode.isSurvival()) {
            InstantWorldMirror.LOGGER.info("Skipping inventory restore for non-survival player {}",
                    player.getName().getString());
            clearSavedData(player);
            return;
        }
        
        CompoundTag persistentData = player.getPersistentData();

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

            clearSavedData(player);
        } else {
            InstantWorldMirror.LOGGER.warn("No saved inventory found in persistent data for player {}",
                    player.getName().getString());
        }
    }

    /**
     * Check if player has saved inventory data
     */
    public static boolean hasSavedInventory(ServerPlayer player) {
        return player.getPersistentData().contains(SAVED_INVENTORY_KEY);
    }

    /**
     * Clear player's saved data (inventory, ender chest, position, etc.)
     */
    public static void clearSavedData(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.remove(SAVED_INVENTORY_KEY);
        persistentData.remove(SAVED_ENDERCHEST_KEY);
        persistentData.remove(ORIGINAL_POS_KEY + "_x");
        persistentData.remove(ORIGINAL_POS_KEY + "_y");
        persistentData.remove(ORIGINAL_POS_KEY + "_z");
        persistentData.remove(ORIGINAL_DIM_KEY);
        persistentData.remove(SESSION_ID_KEY);
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
                    
                    // Clear their saved data
                    clearSavedData(player);
                    
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
        sessionLock.writeLock().lock();
        try {
            for (MirrorSession session : activeSessions.values()) {
                session.markDestroyed();
            }
            activeSessions.clear();
            portalToSession.clear();
            playerToSession.clear();
            playerOwnedSession.clear();
            playerOriginalPositions.clear();
            playerOriginalDimensions.clear();
            playerItemTransferPermission.clear();
            
            // Clear dimension pool
            DimensionPool.clearAll();
            
            InstantWorldMirror.LOGGER.info("All sessions and dimension pool cleared");
        } finally {
            sessionLock.writeLock().unlock();
        }
    }
}
