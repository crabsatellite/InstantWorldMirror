package com.crabmods.instantworldmirror.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single mirror world session
 * Each session is created by one player and can be joined by others through the same portal
 * Each session has its own dedicated dimension from the dimension pool
 */
public class MirrorSession {
    
    // Unique session ID
    private final UUID sessionId;
    
    // The player who created this session
    private final UUID creatorId;
    
    // The portal entity ID that this session is bound to
    private volatile UUID portalEntityId;
    
    // The source position in overworld where the world was copied from
    private final BlockPos sourcePosition;
    
    // The source dimension
    private final ResourceKey<Level> sourceDimension;
    
    // The allocated mirror world dimension index (-1 if not allocated)
    private volatile int dimensionIndex = -1;
    
    // Set of player UUIDs currently in this session
    private final Set<UUID> playersInSession = ConcurrentHashMap.newKeySet();
    
    // Whether this session has been destroyed
    private volatile boolean destroyed = false;
    
    // Whether world copy is complete
    private volatile boolean copyComplete = false;
    
    // Whether the creator (host) has entered the mirror world
    private volatile boolean hostEntered = false;
    
    // Whether the source position was in water (affects teleportation safety check)
    // If true, water positions are considered safe (player is doing underwater exploration)
    private final boolean sourceInWater;
    
    // Creation timestamp for debugging
    private final long createdAt;
    
    public MirrorSession(UUID creatorId, BlockPos sourcePosition, ResourceKey<Level> sourceDimension, boolean sourceInWater) {
        this.sessionId = UUID.randomUUID();
        this.creatorId = creatorId;
        this.sourcePosition = sourcePosition;
        this.sourceDimension = sourceDimension;
        this.sourceInWater = sourceInWater;
        this.createdAt = System.currentTimeMillis();
    }
    
    public UUID getSessionId() {
        return sessionId;
    }
    
    public UUID getCreatorId() {
        return creatorId;
    }
    
    public int getDimensionIndex() {
        return dimensionIndex;
    }
    
    public void setDimensionIndex(int dimensionIndex) {
        this.dimensionIndex = dimensionIndex;
    }
    
    /**
     * Get the mirror world dimension for this session
     */
    public ResourceKey<Level> getMirrorDimension() {
        if (dimensionIndex < 0) {
            return null;
        }
        return ModDimensions.getMirrorWorld(dimensionIndex);
    }
    
    public UUID getPortalEntityId() {
        return portalEntityId;
    }
    
    public void setPortalEntityId(UUID portalEntityId) {
        this.portalEntityId = portalEntityId;
    }
    
    public BlockPos getSourcePosition() {
        return sourcePosition;
    }
    
    public ResourceKey<Level> getSourceDimension() {
        return sourceDimension;
    }
    
    /**
     * Check if the source position was in water
     * If true, water positions should be considered safe during teleportation
     * (player is doing underwater exploration)
     */
    public boolean isSourceInWater() {
        return sourceInWater;
    }
    
    /**
     * Add a player to this session
     * @return true if player was added, false if session is destroyed
     */
    public boolean addPlayer(UUID playerId) {
        if (destroyed) {
            return false;
        }
        return playersInSession.add(playerId);
    }
    
    /**
     * Remove a player from this session
     * @return true if this was the last player (session should be destroyed)
     */
    public boolean removePlayer(UUID playerId) {
        playersInSession.remove(playerId);
        return playersInSession.isEmpty();
    }
    
    /**
     * Check if a player is in this session
     */
    public boolean hasPlayer(UUID playerId) {
        return playersInSession.contains(playerId);
    }
    
    /**
     * Get the number of players in this session
     */
    public int getPlayerCount() {
        return playersInSession.size();
    }
    
    /**
     * Get an unmodifiable view of players in this session
     */
    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(playersInSession);
    }
    
    /**
     * Check if this session is empty (no players)
     */
    public boolean isEmpty() {
        return playersInSession.isEmpty();
    }
    
    /**
     * Mark this session as destroyed
     */
    public void markDestroyed() {
        this.destroyed = true;
        this.playersInSession.clear();
    }
    
    /**
     * Check if this session has been destroyed
     */
    public boolean isDestroyed() {
        return destroyed;
    }
    
    /**
     * Mark world copy as complete
     */
    public void markCopyComplete() {
        this.copyComplete = true;
    }
    
    /**
     * Check if world copy is complete
     */
    public boolean isCopyComplete() {
        return copyComplete;
    }
    
    /**
     * Mark that the host has entered the mirror world
     */
    public void markHostEntered() {
        this.hostEntered = true;
    }
    
    /**
     * Check if the host has entered the mirror world
     */
    public boolean hasHostEntered() {
        return hostEntered;
    }
    
    /**
     * Check if the given player is the host (creator) of this session
     */
    public boolean isHost(UUID playerId) {
        return creatorId.equals(playerId);
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    @Override
    public String toString() {
        return "MirrorSession{" +
                "sessionId=" + sessionId +
                ", creatorId=" + creatorId +
                ", sourcePosition=" + sourcePosition +
                ", playerCount=" + playersInSession.size() +
                ", destroyed=" + destroyed +
                '}';
    }
}
