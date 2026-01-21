package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the pool of mirror world dimensions
 * Each session gets its own dedicated dimension for complete isolation
 * 
 * States:
 * - AVAILABLE: Dimension is empty and ready for use
 * - IN_USE: Dimension is assigned to an active session
 * - CLEANING: Dimension is being cleaned up after a session ended
 */
public class DimensionPool {

    public enum DimensionState {
        AVAILABLE,  // Ready for new session
        IN_USE,     // Assigned to active session
        CLEANING    // Being cleaned up
    }

    // Dimension index -> state
    private static final Map<Integer, DimensionState> dimensionStates = new ConcurrentHashMap<>();
    
    // Dimension index -> session ID (when IN_USE)
    private static final Map<Integer, UUID> dimensionToSession = new ConcurrentHashMap<>();
    
    // Session ID -> dimension index
    private static final Map<UUID, Integer> sessionToDimension = new ConcurrentHashMap<>();

    // Whether pool has been initialized
    private static boolean initialized = false;

    /**
     * Initialize the pool (call after config is loaded)
     */
    public static synchronized void initialize() {
        if (!initialized) {
            int poolSize = ModDimensions.getPoolSize();
            for (int i = 0; i < poolSize; i++) {
                dimensionStates.put(i, DimensionState.AVAILABLE);
            }
            initialized = true;
            InstantWorldMirror.LOGGER.info("Dimension pool initialized with {} dimensions", poolSize);
        }
    }

    /**
     * Allocate a dimension for a new session
     * @return dimension index, or -1 if no dimensions available
     */
    public static synchronized int allocateDimension(UUID sessionId) {
        int poolSize = ModDimensions.getPoolSize();
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.AVAILABLE) {
                dimensionStates.put(i, DimensionState.IN_USE);
                dimensionToSession.put(i, sessionId);
                sessionToDimension.put(sessionId, i);
                
                InstantWorldMirror.LOGGER.info("Allocated dimension {} for session {}", i, sessionId);
                return i;
            }
        }
        
        InstantWorldMirror.LOGGER.warn("No available dimensions for session {} (pool size: {})", 
                sessionId, poolSize);
        return -1;
    }

    /**
     * Release a dimension when session ends (starts cleanup)
     */
    public static synchronized void releaseDimension(UUID sessionId) {
        Integer dimIndex = sessionToDimension.remove(sessionId);
        if (dimIndex != null) {
            dimensionToSession.remove(dimIndex);
            dimensionStates.put(dimIndex, DimensionState.CLEANING);
            
            InstantWorldMirror.LOGGER.info("Released dimension {} from session {}, starting cleanup", 
                    dimIndex, sessionId);
        }
    }

    /**
     * Update the session ID for an allocated dimension (for when session ID changes)
     * Does NOT change the dimension state
     */
    public static synchronized void updateDimensionSession(int dimIndex, UUID oldSessionId, UUID newSessionId) {
        if (dimensionToSession.get(dimIndex) != null && dimensionToSession.get(dimIndex).equals(oldSessionId)) {
            sessionToDimension.remove(oldSessionId);
            dimensionToSession.put(dimIndex, newSessionId);
            sessionToDimension.put(newSessionId, dimIndex);
            InstantWorldMirror.LOGGER.debug("Updated dimension {} session from {} to {}", 
                    dimIndex, oldSessionId, newSessionId);
        }
    }

    /**
     * Mark dimension as available after cleanup completes
     */
    public static synchronized void markDimensionAvailable(int dimIndex) {
        int poolSize = ModDimensions.getPoolSize();
        if (dimIndex >= 0 && dimIndex < poolSize) {
            dimensionStates.put(dimIndex, DimensionState.AVAILABLE);
            InstantWorldMirror.LOGGER.info("Dimension {} cleanup complete, now available", dimIndex);
        }
    }

    /**
     * Get the dimension assigned to a session
     */
    public static Optional<ResourceKey<Level>> getSessionDimension(UUID sessionId) {
        Integer dimIndex = sessionToDimension.get(sessionId);
        if (dimIndex != null) {
            return Optional.of(ModDimensions.getMirrorWorld(dimIndex));
        }
        return Optional.empty();
    }

    /**
     * Get the dimension index assigned to a session
     */
    public static int getSessionDimensionIndex(UUID sessionId) {
        return sessionToDimension.getOrDefault(sessionId, -1);
    }

    /**
     * Get the session using a dimension
     */
    public static Optional<UUID> getDimensionSession(int dimIndex) {
        return Optional.ofNullable(dimensionToSession.get(dimIndex));
    }

    /**
     * Get dimension state
     */
    public static DimensionState getDimensionState(int dimIndex) {
        return dimensionStates.getOrDefault(dimIndex, DimensionState.AVAILABLE);
    }

    /**
     * Check if any dimensions are available
     */
    public static boolean hasAvailableDimension() {
        int poolSize = ModDimensions.getPoolSize();
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.AVAILABLE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get number of available dimensions
     */
    public static int getAvailableCount() {
        int poolSize = ModDimensions.getPoolSize();
        int count = 0;
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.AVAILABLE) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get number of dimensions in use
     */
    public static int getInUseCount() {
        int poolSize = ModDimensions.getPoolSize();
        int count = 0;
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.IN_USE) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get number of dimensions being cleaned
     */
    public static int getCleaningCount() {
        int poolSize = ModDimensions.getPoolSize();
        int count = 0;
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.CLEANING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get ServerLevel for a dimension index
     */
    public static ServerLevel getDimensionLevel(MinecraftServer server, int dimIndex) {
        int poolSize = ModDimensions.getPoolSize();
        if (dimIndex < 0 || dimIndex >= poolSize) {
            return null;
        }
        return server.getLevel(ModDimensions.getMirrorWorld(dimIndex));
    }

    /**
     * Clear all allocations (for server shutdown)
     */
    public static synchronized void clearAll() {
        dimensionToSession.clear();
        sessionToDimension.clear();
        int poolSize = ModDimensions.getPoolSize();
        for (int i = 0; i < poolSize; i++) {
            dimensionStates.put(i, DimensionState.AVAILABLE);
        }
        InstantWorldMirror.LOGGER.info("Dimension pool cleared");
    }

    /**
     * Debug info
     */
    public static String getDebugInfo() {
        int poolSize = ModDimensions.getPoolSize();
        StringBuilder sb = new StringBuilder("DimensionPool Status (pool size: " + poolSize + "):\n");
        for (int i = 0; i < poolSize; i++) {
            DimensionState state = dimensionStates.get(i);
            UUID session = dimensionToSession.get(i);
            sb.append(String.format("  [%d] %s", i, state));
            if (session != null) {
                sb.append(" (session: ").append(session.toString().substring(0, 8)).append("...)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
