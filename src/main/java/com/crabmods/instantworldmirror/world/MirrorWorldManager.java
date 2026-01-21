package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Optional;
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
    private static final String ORIGINAL_POS_KEY = InstantWorldMirror.MODID + "_original_pos";
    private static final String ORIGINAL_DIM_KEY = InstantWorldMirror.MODID + "_original_dim";
    private static final String SESSION_ID_KEY = InstantWorldMirror.MODID + "_session_id";

    // Lock for thread-safe session operations
    private static final ReadWriteLock sessionLock = new ReentrantReadWriteLock();

    // Active sessions: sessionId -> MirrorSession
    private static final Map<UUID, MirrorSession> activeSessions = new ConcurrentHashMap<>();

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

    // ==================== Session Management ====================

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
     */
    public static Optional<MirrorSession> getSessionByPortal(UUID portalEntityId) {
        return activeSessions.values().stream()
                .filter(s -> portalEntityId.equals(s.getPortalEntityId()))
                .findFirst();
    }

    /**
     * Create a new session for a player
     * @return the created session, or empty if player already has an active session or no dimensions available
     */
    public static Optional<MirrorSession> createSession(ServerPlayer player, BlockPos sourcePos) {
        sessionLock.writeLock().lock();
        try {
            // Check if player already has an active session
            if (hasActiveSession(player.getUUID())) {
                InstantWorldMirror.LOGGER.warn("Player {} already has an active session",
                        player.getName().getString());
                return Optional.empty();
            }

            // Create session first to get the session ID
            MirrorSession session = new MirrorSession(
                    player.getUUID(),
                    sourcePos,
                    player.level().dimension()
            );

            // Allocate a dimension from the pool using actual session ID
            int dimIndex = DimensionPool.allocateDimension(session.getSessionId());
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

            InstantWorldMirror.LOGGER.info("Created session {} for player {} using dimension {}",
                    session.getSessionId(), player.getName().getString(), dimIndex);

            return Optional.of(session);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Bind a portal entity to a session
     */
    public static void bindPortalToSession(UUID sessionId, UUID portalEntityId) {
        MirrorSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setPortalEntityId(portalEntityId);
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

    // ==================== World Copy ====================

    /**
     * Queue async world copy for a session
     * This is non-blocking - copy happens over multiple ticks
     */
    public static void prepareWorldCopy(ServerPlayer player, MirrorSession session) {
        if (player.level().isClientSide) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        ServerLevel mirrorWorld = server.getLevel(ModDimensions.MIRROR_WORLD);
        if (mirrorWorld == null) {
            InstantWorldMirror.LOGGER.error("Mirror world dimension not found!");
            return;
        }

        // Get source world reference
        ServerLevel sourceWorld = server.getLevel(session.getSourceDimension());
        if (sourceWorld == null) {
            sourceWorld = server.overworld();
        }

        // Queue async world copy (now uses session's dedicated dimension)
        WorldCopyService.queueWorldCopy(session, sourceWorld);

        InstantWorldMirror.LOGGER.info("Queued async world copy for session {} to dimension {}",
                session.getSessionId(), session.getDimensionIndex());
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

            // Calculate target position
            BlockPos targetPos = session.getSourcePosition().above();

            // Execute teleportation
            player.teleportTo(
                    mirrorWorld,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );

            // Auto-spawn return portal at player's feet
            spawnReturnPortal(mirrorWorld, player, targetPos);

            InstantWorldMirror.LOGGER.info("Player {} teleported to Mirror World dimension {} (session: {}, players in session: {})",
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

        // Check if player is in mirror world
        if (!isInMirrorWorld(player)) {
            return false;
        }

        sessionLock.writeLock().lock();
        try {
            // Get player's session
            UUID sessionId = playerToSession.get(player.getUUID());
            MirrorSession session = sessionId != null ? activeSessions.get(sessionId) : null;

            // Get original position
            BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
            ResourceKey<Level> originalDimension = playerOriginalDimensions.get(player.getUUID());

            ServerLevel targetLevel;
            BlockPos targetPos;

            if (originalPos != null && originalDimension != null) {
                targetLevel = server.getLevel(originalDimension);
                targetPos = originalPos;
            } else {
                targetLevel = server.overworld();
                targetPos = targetLevel.getSharedSpawnPos();
            }

            if (targetLevel == null) {
                targetLevel = server.overworld();
                targetPos = targetLevel.getSharedSpawnPos();
            }

            // Check if item transfer is allowed
            boolean allowItemTransfer = playerItemTransferPermission.getOrDefault(player.getUUID(), false);

            if (allowItemTransfer) {
                clearSavedData(player);
                InstantWorldMirror.LOGGER.info("Player {} allowed to keep mirror world items",
                        player.getName().getString());
            } else {
                restorePlayerInventory(player);
            }

            // Execute teleportation
            player.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );

            // Cleanup player data
            playerOriginalPositions.remove(player.getUUID());
            playerOriginalDimensions.remove(player.getUUID());
            playerToSession.remove(player.getUUID());
            playerItemTransferPermission.remove(player.getUUID());

            // Remove player from session and check if empty
            if (session != null) {
                boolean sessionNowEmpty = session.removePlayer(player.getUUID());

                InstantWorldMirror.LOGGER.info("Player {} left session {} (players remaining: {})",
                        player.getName().getString(), session.getSessionId(), session.getPlayerCount());

                if (sessionNowEmpty) {
                    // Destroy session immediately
                    destroySession(session, server);
                }
            }

            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.returned"),
                    true
            );

            InstantWorldMirror.LOGGER.info("Player {} returned to Overworld", player.getName().getString());

            return true;
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    // ==================== Session Lifecycle ====================

    /**
     * Destroy a session and cleanup its resources
     * Must be called with sessionLock.writeLock held
     */
    private static void destroySession(MirrorSession session, MinecraftServer server) {
        if (session.isDestroyed()) {
            return;
        }

        session.markDestroyed();
        activeSessions.remove(session.getSessionId());

        // Also cleanup playerOwnedSession if creator never entered
        playerOwnedSession.values().removeIf(id -> id.equals(session.getSessionId()));

        // Release dimension back to pool (starts cleanup)
        int dimIndex = session.getDimensionIndex();
        if (dimIndex >= 0) {
            DimensionPool.releaseDimension(session.getSessionId());
            
            // Queue cleanup for the session's dedicated dimension
            ServerLevel mirrorWorld = server.getLevel(session.getMirrorDimension());
            if (mirrorWorld != null) {
                WorldCopyService.cleanupMirrorWorld(mirrorWorld, session.getSourcePosition(), dimIndex);
            }
        }

        InstantWorldMirror.LOGGER.info("Session {} destroyed, dimension {} queued for cleanup", 
                session.getSessionId(), dimIndex);
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
            UUID sessionId = playerToSession.remove(playerId);
            if (sessionId != null) {
                MirrorSession session = activeSessions.get(sessionId);
                if (session != null) {
                    boolean sessionNowEmpty = session.removePlayer(playerId);
                    if (sessionNowEmpty) {
                        destroySession(session, server);
                    }
                }
            }

            // Cleanup position data
            playerOriginalPositions.remove(playerId);
            playerOriginalDimensions.remove(playerId);
            playerItemTransferPermission.remove(playerId);
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Spawn a return portal at player's location in mirror world
     */
    private static void spawnReturnPortal(ServerLevel mirrorWorld, ServerPlayer player, BlockPos targetPos) {
        // Find a solid block below the player to place portal on
        BlockPos portalBase = targetPos.below();
        
        // Play sound
        mirrorWorld.playSound(null, targetPos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.5F);
        
        // Create return portal entity
        MirrorPortalEntity returnPortal = new MirrorPortalEntity(
                mirrorWorld,
                portalBase.getX() + 0.5,
                portalBase.getY() + 1.0,
                portalBase.getZ() + 0.5,
                player.getUUID(),
                true // is return portal
        );
        
        mirrorWorld.addFreshEntity(returnPortal);
        
        InstantWorldMirror.LOGGER.info("Auto-spawned return portal for player {} at {}",
                player.getName().getString(), targetPos);
    }

    /**
     * Check if player is in any mirror world dimension
     */
    public static boolean isInMirrorWorld(ServerPlayer player) {
        return ModDimensions.isMirrorWorld(player.level().dimension());
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

    // ==================== Inventory Management ====================

    /**
     * Save player inventory to player's persistent data
     */
    private static void savePlayerInventory(ServerPlayer player) {
        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);

        CompoundTag persistentData = player.getPersistentData();
        persistentData.put(SAVED_INVENTORY_KEY, inventoryTag);

        BlockPos pos = player.blockPosition();
        persistentData.putInt(ORIGINAL_POS_KEY + "_x", pos.getX());
        persistentData.putInt(ORIGINAL_POS_KEY + "_y", pos.getY());
        persistentData.putInt(ORIGINAL_POS_KEY + "_z", pos.getZ());
        persistentData.putString(ORIGINAL_DIM_KEY, player.level().dimension().location().toString());

        InstantWorldMirror.LOGGER.info("Saved inventory to persistent data for player {} ({} items)",
                player.getName().getString(), inventoryTag.size());
    }

    /**
     * Restore inventory from player's persistent data
     */
    private static void restorePlayerInventory(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();

        if (persistentData.contains(SAVED_INVENTORY_KEY)) {
            ListTag savedInventory = persistentData.getList(SAVED_INVENTORY_KEY, 10);

            player.getInventory().clearContent();
            player.getInventory().load(savedInventory);

            clearSavedData(player);

            InstantWorldMirror.LOGGER.info("Restored inventory from persistent data for player {}",
                    player.getName().getString());
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
     * Clear player's saved data
     */
    public static void clearSavedData(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.remove(SAVED_INVENTORY_KEY);
        persistentData.remove(ORIGINAL_POS_KEY + "_x");
        persistentData.remove(ORIGINAL_POS_KEY + "_y");
        persistentData.remove(ORIGINAL_POS_KEY + "_z");
        persistentData.remove(ORIGINAL_DIM_KEY);
        persistentData.remove(SESSION_ID_KEY);
        InstantWorldMirror.LOGGER.info("Cleared saved data for player {}", player.getName().getString());
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
