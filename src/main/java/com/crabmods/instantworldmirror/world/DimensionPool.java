package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

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
    
    // Dimension index -> source dimension (for syncing time/weather/effects)
    private static final Map<Integer, ResourceKey<Level>> dimensionToSource = new ConcurrentHashMap<>();

    // Whether pool has been initialized
    private static boolean initialized = false;
    
    // Reference to server for saving
    private static MinecraftServer serverRef = null;

    /**
     * Initialize the pool (call after config is loaded)
     * Will check and restore CLEANING states from persistent data
     */
    public static synchronized void initialize() {
        if (!initialized) {
            int poolSize = ModDimensions.getPoolSize();
            for (int i = 0; i < poolSize; i++) {
                dimensionStates.put(i, DimensionState.AVAILABLE);
            }
            initialized = true;
            InstantWorldMirror.LOGGER.debug("Dimension pool initialized with {} dimensions", poolSize);
        }
    }
    
    /**
     * Initialize pool with server reference and restore states
     * Should be called when server starts after dimensions are loaded
     */
    public static synchronized void initializeWithServer(MinecraftServer server) {
        serverRef = server;
        initialize();
        
        // Load saved states from overworld's data storage
        ServerLevel overworld = server.overworld();
        DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                DimensionPoolData.factory(),
                DimensionPoolData.DATA_NAME
        );
        
        // Restore CLEANING states
        int poolSize = ModDimensions.getPoolSize();
        for (int i = 0; i < poolSize; i++) {
            if (data.isMarkedForCleanup(i)) {
                dimensionStates.put(i, DimensionState.CLEANING);
                InstantWorldMirror.LOGGER.debug("Restored dimension {} to CLEANING state", i);
                
                // Queue cleanup task for this dimension
                ServerLevel mirrorWorld = server.getLevel(ModDimensions.getMirrorWorld(i));
                if (mirrorWorld != null) {
                    WorldCopyService.cleanupMirrorWorld(mirrorWorld, i);
                    InstantWorldMirror.LOGGER.debug("Re-queued cleanup task for dimension {}", i);
                }
            }
        }
    }

    /**
     * Allocate a dimension for a new session
     * @param sessionId The session ID
     * @param sourceDimension The source dimension to copy from (for syncing effects)
     * @return dimension index, or -1 if no dimensions available
     */
    public static synchronized int allocateDimension(UUID sessionId, ResourceKey<Level> sourceDimension) {
        int poolSize = ModDimensions.getPoolSize();
        for (int i = 0; i < poolSize; i++) {
            if (dimensionStates.get(i) == DimensionState.AVAILABLE) {
                dimensionStates.put(i, DimensionState.IN_USE);
                dimensionToSession.put(i, sessionId);
                sessionToDimension.put(sessionId, i);
                dimensionToSource.put(i, sourceDimension);

                // Treat every allocated temporary dimension as dirty until cleanup completes.
                markCleanupInProgress(i, true);
                
                InstantWorldMirror.LOGGER.info("Allocated dimension {} for session {} (source: {})", 
                        i, sessionId, sourceDimension.location());
                return i;
            }
        }
        
        InstantWorldMirror.LOGGER.warn("No available dimensions for session {} (pool size: {})", 
                sessionId, poolSize);
        return -1;
    }
    
    /**
     * Allocate a dimension for a new session (overload for backwards compatibility)
     * @return dimension index, or -1 if no dimensions available
     */
    public static synchronized int allocateDimension(UUID sessionId) {
        return allocateDimension(sessionId, Level.OVERWORLD);
    }

    /**
     * Release a dimension when session ends (starts cleanup)
     */
    public static synchronized void releaseDimension(UUID sessionId) {
        Integer dimIndex = sessionToDimension.remove(sessionId);
        if (dimIndex != null) {
            dimensionToSession.remove(dimIndex);
            dimensionToSource.remove(dimIndex);
            dimensionStates.put(dimIndex, DimensionState.CLEANING);
            
            // Save cleanup state to persistent storage
            markCleanupInProgress(dimIndex, true);
            
            InstantWorldMirror.LOGGER.debug("Released dimension {} from session {}", dimIndex, sessionId);
        }
    }
    
    /**
     * Get the source dimension for a mirror dimension
     * Used to sync time, weather, and other dimension-specific effects
     */
    public static ResourceKey<Level> getSourceDimension(int dimIndex) {
        return dimensionToSource.getOrDefault(dimIndex, Level.OVERWORLD);
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
            
            // Clear cleanup state from persistent storage
            markCleanupInProgress(dimIndex, false);
            
            InstantWorldMirror.LOGGER.debug("Dimension {} now available", dimIndex);
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
     * Get the session UUID for a dimension (returns null if none)
     * Convenience method for null checks
     */
    public static UUID getSessionForDimension(int dimIndex) {
        return dimensionToSession.get(dimIndex);
    }
    
    /**
     * Force release a dimension without having a session reference
     * Used for cleanup of orphaned dimensions
     */
    public static synchronized void forceReleaseDimension(int dimIndex) {
        int poolSize = ModDimensions.getPoolSize();
        if (dimIndex >= 0 && dimIndex < poolSize) {
            UUID sessionId = dimensionToSession.remove(dimIndex);
            if (sessionId != null) {
                sessionToDimension.remove(sessionId);
            }
            dimensionToSource.remove(dimIndex);
            dimensionStates.put(dimIndex, DimensionState.CLEANING);
            
            // Save cleanup state to persistent storage
            markCleanupInProgress(dimIndex, true);
            
            InstantWorldMirror.LOGGER.debug("Force released orphaned dimension {}", dimIndex);
        }
    }

    /**
     * Get dimension state
     */
    public static DimensionState getDimensionState(int dimIndex) {
        return dimensionStates.getOrDefault(dimIndex, DimensionState.AVAILABLE);
    }

    /**
     * Force mark a dimension as CLEANING state (for forceclear command)
     */
    public static synchronized void markDimensionCleaning(int dimIndex) {
        int poolSize = ModDimensions.getPoolSize();
        if (dimIndex >= 0 && dimIndex < poolSize) {
            dimensionStates.put(dimIndex, DimensionState.CLEANING);
            // Clear any session mapping
            UUID sessionId = dimensionToSession.remove(dimIndex);
            if (sessionId != null) {
                sessionToDimension.remove(sessionId);
            }
            
            // Save cleanup state to persistent storage
            markCleanupInProgress(dimIndex, true);
            
            InstantWorldMirror.LOGGER.debug("Dimension {} force marked as CLEANING", dimIndex);
        }
    }

    /**
     * Check if any dimensions are available
     * Optimized: Uses cached values from ConcurrentHashMap instead of iterating
     */
    public static boolean hasAvailableDimension() {
        return dimensionStates.containsValue(DimensionState.AVAILABLE);
    }

    /**
     * Get number of available dimensions
     * Optimized: Uses stream with parallel-safe operations
     */
    public static int getAvailableCount() {
        return (int) dimensionStates.values().stream()
                .filter(state -> state == DimensionState.AVAILABLE)
                .count();
    }

    /**
     * Get number of dimensions in use
     * Optimized: Uses stream with parallel-safe operations
     */
    public static int getInUseCount() {
        return (int) dimensionStates.values().stream()
                .filter(state -> state == DimensionState.IN_USE)
                .count();
    }

    /**
     * Get number of dimensions being cleaned
     * Optimized: Uses stream with parallel-safe operations
     */
    public static int getCleaningCount() {
        return (int) dimensionStates.values().stream()
                .filter(state -> state == DimensionState.CLEANING)
                .count();
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
     * Note: Does NOT clear persistent cleanup states - they should persist across restarts
     */
    public static synchronized void clearAll() {
        dimensionToSession.clear();
        sessionToDimension.clear();
        // Don't reset states here - let initializeWithServer handle it on next startup
        serverRef = null;
        initialized = false;
        InstantWorldMirror.LOGGER.debug("Dimension pool cleared");
    }
    
    /**
     * Helper to mark cleanup state in persistent storage
     */
    private static void markCleanupInProgress(int dimIndex, boolean cleaning) {
        if (serverRef != null) {
            ServerLevel overworld = serverRef.overworld();
            DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                    DimensionPoolData.factory(),
                    DimensionPoolData.DATA_NAME
            );
            data.setCleanupState(dimIndex, cleaning);
        }
    }

    /**
     * Save cleanup data (copy center, modified chunks, progress) for a dimension
     * Call this during cleanup task processing to persist progress
     */
    public static void saveCleanupData(int dimIndex, net.minecraft.core.BlockPos copyCenter, 
                                       java.util.Set<Long> modifiedChunks, int cleanupProgress) {
        if (serverRef != null) {
            ServerLevel overworld = serverRef.overworld();
            DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                    DimensionPoolData.factory(),
                    DimensionPoolData.DATA_NAME
            );
            data.setCopyCenter(dimIndex, copyCenter);
            data.setModifiedChunks(dimIndex, modifiedChunks);
            data.setCleanupProgress(dimIndex, cleanupProgress);
        }
    }
    
    /**
     * Get saved copy center for a dimension
     */
    public static net.minecraft.core.BlockPos getSavedCopyCenter(int dimIndex) {
        if (serverRef != null) {
            ServerLevel overworld = serverRef.overworld();
            DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                    DimensionPoolData.factory(),
                    DimensionPoolData.DATA_NAME
            );
            return data.getCopyCenter(dimIndex);
        }
        return null;
    }
    
    /**
     * Get saved modified chunks for a dimension
     */
    public static java.util.Set<Long> getSavedModifiedChunks(int dimIndex) {
        if (serverRef != null) {
            ServerLevel overworld = serverRef.overworld();
            DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                    DimensionPoolData.factory(),
                    DimensionPoolData.DATA_NAME
            );
            return data.getModifiedChunks(dimIndex);
        }
        return java.util.Collections.emptySet();
    }
    
    /**
     * Get saved cleanup progress for a dimension
     */
    public static int getSavedCleanupProgress(int dimIndex) {
        if (serverRef != null) {
            ServerLevel overworld = serverRef.overworld();
            DimensionPoolData data = overworld.getDataStorage().computeIfAbsent(
                    DimensionPoolData.factory(),
                    DimensionPoolData.DATA_NAME
            );
            return data.getCleanupProgress(dimIndex);
        }
        return 0;
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
    
    // ==================== Persistent Data Storage ====================
    
    /**
     * SavedData class to persist dimension cleanup states across server restarts
     */
    public static class DimensionPoolData extends SavedData {
        public static final String DATA_NAME = InstantWorldMirror.MODID + "_dimension_pool";
        
        // Stores which dimensions are marked for cleanup
        private final Map<Integer, Boolean> cleanupStates = new ConcurrentHashMap<>();
        
        // Stores copy center positions for cleanup (dimIndex -> BlockPos)
        private final Map<Integer, net.minecraft.core.BlockPos> copyCenterPositions = new ConcurrentHashMap<>();
        
        // Stores modified chunks for each dimension (dimIndex -> Set<Long>)
        private final Map<Integer, java.util.Set<Long>> modifiedChunks = new ConcurrentHashMap<>();
        
        // Stores cleanup progress (dimIndex -> current chunk index)
        private final Map<Integer, Integer> cleanupProgress = new ConcurrentHashMap<>();
        
        public DimensionPoolData() {
        }
        
        public static DimensionPoolData load(CompoundTag tag, HolderLookup.Provider provider) {
            DimensionPoolData data = new DimensionPoolData();
            
            // Load cleanup states
            CompoundTag states = tag.getCompound("cleanup_states");
            for (String key : states.getAllKeys()) {
                try {
                    int dimIndex = Integer.parseInt(key);
                    data.cleanupStates.put(dimIndex, states.getBoolean(key));
                } catch (NumberFormatException ignored) {
                }
            }
            
            // Load copy center positions
            CompoundTag centers = tag.getCompound("copy_centers");
            for (String key : centers.getAllKeys()) {
                try {
                    int dimIndex = Integer.parseInt(key);
                    long[] pos = centers.getLongArray(key);
                    if (pos.length >= 3) {
                        data.copyCenterPositions.put(dimIndex, new net.minecraft.core.BlockPos((int)pos[0], (int)pos[1], (int)pos[2]));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            
            // Load modified chunks
            CompoundTag chunks = tag.getCompound("modified_chunks");
            for (String key : chunks.getAllKeys()) {
                try {
                    int dimIndex = Integer.parseInt(key);
                    long[] chunkArray = chunks.getLongArray(key);
                    java.util.Set<Long> chunkSet = ConcurrentHashMap.newKeySet();
                    for (long packed : chunkArray) {
                        chunkSet.add(packed);
                    }
                    data.modifiedChunks.put(dimIndex, chunkSet);
                } catch (NumberFormatException ignored) {
                }
            }
            
            // Load cleanup progress
            CompoundTag progress = tag.getCompound("cleanup_progress");
            for (String key : progress.getAllKeys()) {
                try {
                    int dimIndex = Integer.parseInt(key);
                    data.cleanupProgress.put(dimIndex, progress.getInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
            
            return data;
        }
        
        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            // Save cleanup states
            CompoundTag states = new CompoundTag();
            for (Map.Entry<Integer, Boolean> entry : cleanupStates.entrySet()) {
                states.putBoolean(String.valueOf(entry.getKey()), entry.getValue());
            }
            tag.put("cleanup_states", states);
            
            // Save copy center positions
            CompoundTag centers = new CompoundTag();
            for (Map.Entry<Integer, net.minecraft.core.BlockPos> entry : copyCenterPositions.entrySet()) {
                net.minecraft.core.BlockPos pos = entry.getValue();
                centers.putLongArray(String.valueOf(entry.getKey()), new long[]{pos.getX(), pos.getY(), pos.getZ()});
            }
            tag.put("copy_centers", centers);
            
            // Save modified chunks
            CompoundTag chunks = new CompoundTag();
            for (Map.Entry<Integer, java.util.Set<Long>> entry : modifiedChunks.entrySet()) {
                long[] chunkArray = entry.getValue().stream().mapToLong(Long::longValue).toArray();
                chunks.putLongArray(String.valueOf(entry.getKey()), chunkArray);
            }
            tag.put("modified_chunks", chunks);
            
            // Save cleanup progress
            CompoundTag progress = new CompoundTag();
            for (Map.Entry<Integer, Integer> entry : cleanupProgress.entrySet()) {
                progress.putInt(String.valueOf(entry.getKey()), entry.getValue());
            }
            tag.put("cleanup_progress", progress);
            
            return tag;
        }
        
        public boolean isMarkedForCleanup(int dimIndex) {
            return cleanupStates.getOrDefault(dimIndex, false);
        }
        
        public void setCleanupState(int dimIndex, boolean cleaning) {
            if (cleaning) {
                cleanupStates.put(dimIndex, true);
            } else {
                cleanupStates.remove(dimIndex);
                // Also clear related data when cleanup completes
                copyCenterPositions.remove(dimIndex);
                modifiedChunks.remove(dimIndex);
                cleanupProgress.remove(dimIndex);
            }
            setDirty();
        }
        
        // Copy center position methods
        public net.minecraft.core.BlockPos getCopyCenter(int dimIndex) {
            return copyCenterPositions.get(dimIndex);
        }
        
        public void setCopyCenter(int dimIndex, net.minecraft.core.BlockPos pos) {
            if (pos != null) {
                copyCenterPositions.put(dimIndex, pos);
            } else {
                copyCenterPositions.remove(dimIndex);
            }
            setDirty();
        }
        
        // Modified chunks methods
        public java.util.Set<Long> getModifiedChunks(int dimIndex) {
            return modifiedChunks.getOrDefault(dimIndex, java.util.Collections.emptySet());
        }
        
        public void setModifiedChunks(int dimIndex, java.util.Set<Long> chunks) {
            if (chunks != null && !chunks.isEmpty()) {
                modifiedChunks.put(dimIndex, new java.util.HashSet<>(chunks));
            } else {
                modifiedChunks.remove(dimIndex);
            }
            setDirty();
        }
        
        // Cleanup progress methods
        public int getCleanupProgress(int dimIndex) {
            return cleanupProgress.getOrDefault(dimIndex, 0);
        }
        
        public void setCleanupProgress(int dimIndex, int progress) {
            if (progress > 0) {
                cleanupProgress.put(dimIndex, progress);
            } else {
                cleanupProgress.remove(dimIndex);
            }
            setDirty();
        }
        
        public static Factory<DimensionPoolData> factory() {
            return new Factory<>(DimensionPoolData::new, DimensionPoolData::load);
        }
    }
}
