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

            InstantWorldMirror.LOGGER.info("Created session {} for player {} using dimension {}",
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

            // Calculate target position with a small offset to avoid portal re-trigger
            // This prevents issues with Nether portals that would otherwise teleport player back
            BlockPos targetPos = session.getSourcePosition().above();
            
            // Add offset in the direction player is facing to move them out of any portal
            float yaw = player.getYRot();
            double offsetX = -Math.sin(Math.toRadians(yaw)) * 1.5;
            double offsetZ = Math.cos(Math.toRadians(yaw)) * 1.5;

            // Execute teleportation with offset
            player.teleportTo(
                    mirrorWorld,
                    targetPos.getX() + 0.5 + offsetX,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5 + offsetZ,
                    player.getYRot(),
                    player.getXRot()
            );

            // Sync dimension effects to client
            syncDimensionEffectsToPlayer(player, session);

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

            // Execute teleportation
            player.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
            
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
            playerOriginalPositions.remove(player.getUUID());
            playerOriginalDimensions.remove(player.getUUID());
            playerToSession.remove(player.getUUID());
            playerItemTransferPermission.remove(player.getUUID());

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
            InstantWorldMirror.LOGGER.info("Player {} returned from mirror world", player.getName().getString());

            return true;
        } finally {
            sessionLock.writeLock().unlock();
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
                playerOriginalPositions.remove(playerId);
                playerOriginalDimensions.remove(playerId);
                playerToSession.remove(playerId);
                playerItemTransferPermission.remove(playerId);
                session.removePlayer(playerId);
                continue;
            }
            
            // Restore inventory first
            restorePlayerInventory(kickedPlayer);
            
            // Get their original position
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
            
            // Teleport back
            kickedPlayer.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    kickedPlayer.getYRot(),
                    kickedPlayer.getXRot()
            );
            
            // Clear dimension effects
            clearDimensionEffectsForPlayer(kickedPlayer, session.getDimensionIndex());
            
            // Clean up tracking data
            playerOriginalPositions.remove(playerId);
            playerOriginalDimensions.remove(playerId);
            playerToSession.remove(playerId);
            playerItemTransferPermission.remove(playerId);
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

        // Cleanup portal to session mapping
        UUID portalId = session.getPortalEntityId();
        if (portalId != null) {
            portalToSession.remove(portalId);
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

        InstantWorldMirror.LOGGER.info("Session {} destroyed, dimension {} queued for cleanup", 
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
    
    /**
     * Handle player death in mirror world - immediately cleanup session
     * This is called from the death event to ensure clean state before respawn
     */
    public static void handleMirrorWorldDeath(ServerPlayer player, MinecraftServer server) {
        sessionLock.writeLock().lock();
        try {
            UUID playerId = player.getUUID();
            
            // Get the session before cleanup
            UUID sessionId = playerToSession.remove(playerId);
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
            playerOriginalPositions.remove(playerId);
            playerOriginalDimensions.remove(playerId);
            playerItemTransferPermission.remove(playerId);
            playerOwnedSession.remove(playerId);
            
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
            UUID sessionId = playerToSession.remove(playerId);
            if (sessionId != null) {
                MirrorSession session = activeSessions.get(sessionId);
                if (session != null) {
                    InstantWorldMirror.LOGGER.info("Player {} externally left session {}", 
                            player.getName().getString(), sessionId);
                    
                    boolean sessionNowEmpty = session.removePlayer(playerId);
                    if (sessionNowEmpty) {
                        // Session is now empty, clean it up
                        destroySession(session, server);
                    }
                }
            }
            
            // Cleanup position data (player is already in the target dimension)
            playerOriginalPositions.remove(playerId);
            playerOriginalDimensions.remove(playerId);
            playerItemTransferPermission.remove(playerId);
            
            // Only send notification if we actually cleaned up a session
            if (sessionId != null) {
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.external_exit"),
                        false
                );
            }
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
                portalBase.getY() + 1.5,
                portalBase.getZ() + 0.5,
                player.getUUID(),
                true // is return portal
        );
        
        mirrorWorld.addFreshEntity(returnPortal);
        
        // Track the chunk where the return portal is spawned for cleanup
        int dimIndex = ModDimensions.getMirrorWorldIndex(mirrorWorld.dimension());
        if (dimIndex >= 0) {
            WorldCopyService.trackModifiedChunk(dimIndex, targetPos.getX() >> 4, targetPos.getZ() >> 4);
        }
        
        InstantWorldMirror.LOGGER.info("Auto-spawned return portal for player {} at {}",
                player.getName().getString(), targetPos);
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
                    
                    // Teleport player to their original position/dimension
                    player.teleportTo(targetLevel, 
                            targetPos.getX() + 0.5, 
                            targetPos.getY(), 
                            targetPos.getZ() + 0.5, 
                            java.util.Set.of(),
                            player.getYRot(), 
                            player.getXRot());
                    
                    // Clear their saved data
                    clearSavedData(player);
                    
                    // Remove from tracking maps
                    playerToSession.remove(playerId);
                    playerOriginalPositions.remove(playerId);
                    playerOriginalDimensions.remove(playerId);
                    
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
