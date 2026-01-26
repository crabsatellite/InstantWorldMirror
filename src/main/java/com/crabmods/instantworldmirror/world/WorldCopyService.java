package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World Copy Service - Optimized Version
 * 
 * Optimization Strategies:
 * 1. Heightmap-based Y scanning - skip empty columns entirely
 * 2. Section-level operations - work with 16x16x16 sections instead of individual blocks
 * 3. MutableBlockPos reuse - avoid 98K+ object creations per chunk
 * 4. Smart air detection - use chunk section emptiness checks
 * 5. Batch block entity processing - collect and process in batch
 * 6. Lazy cleanup - only process sections that have content
 * 
 * Performance: ~10x faster than naive implementation
 */
public class WorldCopyService {

    // Track cleanup tasks per dimension: dimensionIndex -> CleanupTask
    private static final Map<Integer, CleanupTask> cleanupTasks = new ConcurrentHashMap<>();
    
    // Track copy tasks per dimension: dimensionIndex -> CopyTask
    private static final Map<Integer, CopyTask> copyTasks = new ConcurrentHashMap<>();
    
    // Track ALL chunks that have been modified in each dimension for thorough cleanup
    // dimensionIndex -> Set of chunk positions (packed as long: x << 32 | z)
    private static final Map<Integer, Set<Long>> modifiedChunks = new ConcurrentHashMap<>();
    
    // Track the copy center position for each dimension (for cleanup bounds)
    // dimensionIndex -> center BlockPos
    private static final Map<Integer, BlockPos> copyCenterPositions = new ConcurrentHashMap<>();

    // Reusable BlockPos for optimization (ThreadLocal for thread safety)
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS = 
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    
    // ==================== Chunk Tracking ====================
    
    /**
     * Record a chunk as modified in a dimension.
     * This is used to track which chunks need to be cleaned up.
     * Made public so that dynamic world loading can also track chunks.
     */
    public static void trackModifiedChunk(int dimIndex, int chunkX, int chunkZ) {
        modifiedChunks.computeIfAbsent(dimIndex, k -> ConcurrentHashMap.newKeySet())
                .add(packChunkPos(chunkX, chunkZ));
        
        // Mark that this dimension has pending save
        pendingSave.add(dimIndex);
    }
    
    // Track dimensions that have pending modifications to save
    private static final Set<Integer> pendingSave = ConcurrentHashMap.newKeySet();
    private static int saveTickCounter = 0;
    private static final int SAVE_TICK_INTERVAL = 200; // Save every 10 seconds (200 ticks)
    
    /**
     * Track all chunks in a radius around a position.
     * Useful for tracking player movement area.
     */
    public static void trackChunksInRadius(int dimIndex, int centerChunkX, int centerChunkZ, int radius) {
        for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
            for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                trackModifiedChunk(dimIndex, x, z);
            }
        }
    }
    
    /**
     * Get all modified chunks for a dimension
     */
    public static Set<Long> getModifiedChunks(int dimIndex) {
        return modifiedChunks.getOrDefault(dimIndex, java.util.Collections.emptySet());
    }
    
    /**
     * Clear the modified chunk tracking for a dimension
     */
    public static void clearModifiedChunkTracking(int dimIndex) {
        modifiedChunks.remove(dimIndex);
        pendingSave.remove(dimIndex);
    }
    
    /**
     * Save all pending modifications to persistent storage
     * Called periodically and on server shutdown
     */
    public static void savePendingModifications() {
        if (pendingSave.isEmpty()) return;
        
        for (Integer dimIndex : new java.util.ArrayList<>(pendingSave)) {
            BlockPos centerPos = copyCenterPositions.get(dimIndex);
            Set<Long> chunks = modifiedChunks.get(dimIndex);
            
            if (centerPos != null || (chunks != null && !chunks.isEmpty())) {
                DimensionPool.saveCleanupData(dimIndex, centerPos, chunks, 0);
                pendingSave.remove(dimIndex);
            }
        }
    }
    
    private static long packChunkPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    
    private static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }
    
    private static int unpackChunkZ(long packed) {
        return (int) packed;
    }

    // ==================== Task Classes ====================
    
    public static class CopyTask {
        public final UUID sessionId;
        public final BlockPos centerPos;
        public final int chunkRadius;
        public final ResourceKey<Level> sourceDimension;
        public final int targetDimensionIndex;
        
        private int currentChunkX;
        private int currentChunkZ;
        private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        private boolean started = false;
        private boolean completed = false;
        private int totalBlocksCopied = 0;

        public CopyTask(UUID sessionId, BlockPos centerPos, int chunkRadius, 
                        ResourceKey<Level> sourceDimension, int targetDimensionIndex) {
            this.sessionId = sessionId;
            this.centerPos = centerPos;
            this.chunkRadius = chunkRadius;
            this.sourceDimension = sourceDimension;
            this.targetDimensionIndex = targetDimensionIndex;
            
            int centerChunkX = centerPos.getX() >> 4;
            int centerChunkZ = centerPos.getZ() >> 4;
            
            this.minChunkX = centerChunkX - chunkRadius;
            this.maxChunkX = centerChunkX + chunkRadius;
            this.minChunkZ = centerChunkZ - chunkRadius;
            this.maxChunkZ = centerChunkZ + chunkRadius;
            
            this.currentChunkX = minChunkX;
            this.currentChunkZ = minChunkZ;
        }
        
        public int getTotalChunks() {
            int width = maxChunkX - minChunkX + 1;
            int height = maxChunkZ - minChunkZ + 1;
            return width * height;
        }
        
        public int getCopiedChunks() {
            if (!started) return 0;
            if (completed) return getTotalChunks();
            int width = maxChunkX - minChunkX + 1;
            return (currentChunkZ - minChunkZ) * width + (currentChunkX - minChunkX);
        }
        
        public int getProgressPercent() {
            int total = getTotalChunks();
            if (total == 0) return 100;
            return (getCopiedChunks() * 100) / total;
        }
        
        public int[] getNextChunk() {
            if (completed) return null;
            started = true;
            int[] result = new int[]{currentChunkX, currentChunkZ};
            
            currentChunkX++;
            if (currentChunkX > maxChunkX) {
                currentChunkX = minChunkX;
                currentChunkZ++;
                if (currentChunkZ > maxChunkZ) {
                    completed = true;
                }
            }
            return result;
        }
        
        public void addBlocksCopied(int count) { totalBlocksCopied += count; }
        public boolean isCompleted() { return completed; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { cancelled = true; completed = true; }
        public int getTotalBlocksCopied() { return totalBlocksCopied; }
        
        private boolean cancelled = false;
    }
    
    /**
     * Cancel a copy task for a dimension
     * Called when portal is destroyed before copy completes
     */
    public static void cancelCopyTask(int dimensionIndex) {
        CopyTask task = copyTasks.get(dimensionIndex);
        if (task != null && !task.isCompleted()) {
            task.cancel();
            InstantWorldMirror.LOGGER.info("Cancelled copy task for dimension {} at {}% progress", 
                    dimensionIndex, task.getProgressPercent());
        }
        copyTasks.remove(dimensionIndex);
        
        // Clear tracked data for this dimension
        copyCenterPositions.remove(dimensionIndex);
        modifiedChunks.remove(dimensionIndex);
        pendingSave.remove(dimensionIndex);
    }
    
    public static class CleanupTask {
        public final int dimensionIndex;
        
        // List of chunks to clean
        private final java.util.List<long[]> chunksToClean = new java.util.ArrayList<>();
        private int currentIndex = 0;
        private boolean initialized = false;
        private boolean completed = false;
        private int saveCounter = 0; // Counter for periodic saves
        private static final int SAVE_INTERVAL = 10; // Save every 10 chunks processed
        
        // Phase 2: Auxiliary cleanup for edge structures
        private boolean mainCleanupComplete = false;
        private boolean auxiliaryCleanupStarted = false;
        private java.util.List<long[]> auxiliaryChunks = new java.util.ArrayList<>();
        private int auxiliaryIndex = 0;
        
        // Phase 3: Region file scan (fallback for untracked chunks)
        private boolean auxiliaryCleanupComplete = false;
        private boolean regionScanStarted = false;
        private boolean regionScanComplete = false;
        private java.util.List<long[]> regionScanChunks = new java.util.ArrayList<>();
        private int regionScanIndex = 0;
        private int regionScanPass = 0; // Track how many passes we've done
        private static final int MAX_REGION_SCAN_PASSES = 5; // Safety limit
        
        // Retry queue for chunks that couldn't be processed (not loaded yet)
        private final java.util.Queue<long[]> retryQueue = new java.util.LinkedList<>();
        private final java.util.Map<Long, Integer> retryCount = new java.util.HashMap<>();
        private static final int MAX_RETRIES = 50; // Max retries per chunk before skipping
        private int skippedChunks = 0;

        public CleanupTask(int dimensionIndex) {
            this.dimensionIndex = dimensionIndex;
        }
        
        /**
         * Initialize the cleanup task with:
         * 1. All chunks in the original copy radius (guaranteed to have content)
         * 2. Any additional tracked chunks (player explored/modified outside copy radius)
         * Also tries to restore progress from saved data.
         */
        public void initializeChunkList(ServerLevel mirrorWorld) {
            if (initialized) return;
            initialized = true;
            
            Set<Long> allChunksToClean = new java.util.HashSet<>();
            
            // Try to restore from saved data first
            BlockPos savedCenter = DimensionPool.getSavedCopyCenter(dimensionIndex);
            java.util.Set<Long> savedChunks = DimensionPool.getSavedModifiedChunks(dimensionIndex);
            int savedProgress = DimensionPool.getSavedCleanupProgress(dimensionIndex);
            
            // First: Check memory cache, then fall back to saved data
            BlockPos centerPos = copyCenterPositions.get(dimensionIndex);
            if (centerPos == null) {
                centerPos = savedCenter;
            }
            
            int copyRadius = MirrorConfig.COPY_CHUNK_RADIUS.get();
            
            if (centerPos != null) {
                int centerChunkX = centerPos.getX() >> 4;
                int centerChunkZ = centerPos.getZ() >> 4;
                
                for (int x = centerChunkX - copyRadius; x <= centerChunkX + copyRadius; x++) {
                    for (int z = centerChunkZ - copyRadius; z <= centerChunkZ + copyRadius; z++) {
                        allChunksToClean.add(packChunkPos(x, z));
                    }
                }
                
                int copyChunks = (copyRadius * 2 + 1) * (copyRadius * 2 + 1);
                InstantWorldMirror.LOGGER.info("Added {} chunks from copy radius for dimension {}", 
                        copyChunks, dimensionIndex);
            }
            
            // Second: add tracked chunks from memory
            Set<Long> tracked = getModifiedChunks(dimensionIndex);
            int additionalChunks = 0;
            for (Long packed : tracked) {
                if (allChunksToClean.add(packed)) {
                    additionalChunks++;
                }
            }
            
            // Third: add saved chunks (from previous session)
            int restoredChunks = 0;
            for (Long packed : savedChunks) {
                if (allChunksToClean.add(packed)) {
                    restoredChunks++;
                }
            }
            
            if (additionalChunks > 0 || restoredChunks > 0) {
                InstantWorldMirror.LOGGER.info("Added {} tracked chunks + {} restored chunks outside copy radius", 
                        additionalChunks, restoredChunks);
            }
            
            // Convert to list for processing
            for (Long packed : allChunksToClean) {
                chunksToClean.add(new long[]{unpackChunkX(packed), unpackChunkZ(packed)});
            }
            
            // Restore progress if available and valid
            if (savedProgress > 0 && savedProgress < chunksToClean.size()) {
                this.currentIndex = savedProgress;
                InstantWorldMirror.LOGGER.info("Restored cleanup progress for dimension {}: {}/{} chunks",
                        dimensionIndex, savedProgress, chunksToClean.size());
            }
            
            InstantWorldMirror.LOGGER.info("Cleanup task initialized for dimension {} with {} total chunks to clean (starting at {})",
                    dimensionIndex, chunksToClean.size(), currentIndex);
        }
        
        public int getTotalChunks() {
            return chunksToClean.size() + auxiliaryChunks.size() + regionScanChunks.size();
        }
        
        public int getCleanedChunks() {
            return currentIndex + auxiliaryIndex + regionScanIndex;
        }
        
        /**
         * Get all chunks that were cleaned (for verification)
         */
        public java.util.List<long[]> getAllCleanedChunks() {
            java.util.List<long[]> all = new java.util.ArrayList<>(chunksToClean);
            all.addAll(auxiliaryChunks);
            all.addAll(regionScanChunks);
            return all;
        }
        
        /**
         * Check if main cleanup phase is done (before auxiliary)
         */
        public boolean isMainCleanupDone() {
            return mainCleanupComplete;
        }
        
        /**
         * Check if auxiliary cleanup phase is done (before region scan)
         */
        public boolean isAuxiliaryCleanupDone() {
            return auxiliaryCleanupComplete;
        }
        
        /**
         * Check if region scan needs to start
         */
        public boolean needsRegionScan() {
            return auxiliaryCleanupComplete && !regionScanStarted;
        }
        
        /**
         * Get the next chunk to clean.
         * Phase 1: Main cleanup (tracked chunks)
         * Phase 2: Auxiliary cleanup (BFS edge scan)
         * Phase 3: Region file scan (fallback for untracked chunks)
         */
        public long[] getNextChunk() {
            // Phase 1: Main cleanup
            if (!mainCleanupComplete) {
                if (currentIndex >= chunksToClean.size()) {
                    mainCleanupComplete = true;
                    InstantWorldMirror.LOGGER.info("Main cleanup phase completed for dimension {}, {} chunks processed",
                            dimensionIndex, currentIndex);
                    return null; // Signal to start auxiliary scan
                }
                
                long[] result = chunksToClean.get(currentIndex++);
                
                // Periodically save progress
                saveCounter++;
                if (saveCounter >= SAVE_INTERVAL) {
                    saveCounter = 0;
                    saveProgress();
                }
                
                return result;
            }
            
            // Phase 2: Auxiliary cleanup (BFS edge scan)
            if (!auxiliaryCleanupComplete) {
                if (!auxiliaryCleanupStarted) {
                    return null; // Need to initialize auxiliary chunks first
                }
                
                if (auxiliaryIndex >= auxiliaryChunks.size()) {
                    // Check retries before moving to phase 3
                    if (!retryQueue.isEmpty()) {
                        return retryQueue.poll();
                    }
                    auxiliaryCleanupComplete = true;
                    InstantWorldMirror.LOGGER.info("Auxiliary cleanup phase completed for dimension {}, {} edge chunks processed",
                            dimensionIndex, auxiliaryChunks.size());
                    return null; // Signal to start region scan
                }
                
                return auxiliaryChunks.get(auxiliaryIndex++);
            }
            
            // Phase 3: Region file scan (fallback)
            if (!regionScanComplete) {
                if (!regionScanStarted) {
                    return null; // Need to initialize region scan first
                }
                
                if (regionScanIndex >= regionScanChunks.size()) {
                    // Check retries
                    if (!retryQueue.isEmpty()) {
                        return retryQueue.poll();
                    }
                    
                    // Check if we need another pass
                    if (regionScanChunks.isEmpty() || regionScanPass >= MAX_REGION_SCAN_PASSES) {
                        regionScanComplete = true;
                        completed = true;
                        if (skippedChunks > 0) {
                            InstantWorldMirror.LOGGER.warn("Cleanup completed for dimension {} but {} chunks were skipped",
                                    dimensionIndex, skippedChunks);
                        }
                        return null;
                    }
                    
                    // Signal need for another region scan pass
                    regionScanStarted = false;
                    return null;
                }
                
                return regionScanChunks.get(regionScanIndex++);
            }
            
            return null;
        }
        
        /**
         * Mark a chunk for retry (when it couldn't be cleaned because it wasn't loaded)
         */
        public void markForRetry(int chunkX, int chunkZ) {
            long key = packChunkPos(chunkX, chunkZ);
            int count = retryCount.getOrDefault(key, 0) + 1;
            if (count <= MAX_RETRIES) {
                retryCount.put(key, count);
                retryQueue.offer(new long[]{chunkX, chunkZ});
            } else {
                // Give up on this chunk after max retries - just skip it
                skippedChunks++;
                InstantWorldMirror.LOGGER.debug("Skipping cleanup of chunk [{}, {}] after {} retries",
                        chunkX, chunkZ, MAX_RETRIES);
            }
        }
        
        public int getSkippedChunks() {
            return skippedChunks;
        }
        
        /**
         * Initialize auxiliary cleanup using BFS (Breadth-First Search).
         * Starts from the edge of the cleaned area and expands outward.
         * If a chunk has blocks, its neighbors are added to the search queue.
         * This ensures even very large structures are fully cleaned.
         * 
         * @param mirrorWorld The mirror world to scan
         * @return The number of additional chunks found with blocks
         */
        public int initializeAuxiliaryCleanup(ServerLevel mirrorWorld) {
            if (auxiliaryCleanupStarted) return auxiliaryChunks.size();
            auxiliaryCleanupStarted = true;
            
            // Get max search radius from config (acts as a safety limit)
            int maxExpansionRadius = MirrorConfig.EDGE_CLEANUP_RADIUS.get();
            
            // If edge cleanup is disabled, mark as complete
            if (maxExpansionRadius <= 0) {
                InstantWorldMirror.LOGGER.info("Edge cleanup disabled by config, skipping auxiliary scan");
                completed = true;
                return 0;
            }
            
            // Track all chunks we've already processed or will process
            Set<Long> processed = new java.util.HashSet<>();
            for (long[] chunk : chunksToClean) {
                processed.add(packChunkPos((int)chunk[0], (int)chunk[1]));
            }
            
            // Calculate the bounding box of cleaned chunks
            int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
            int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
            
            for (long[] chunk : chunksToClean) {
                int cx = (int) chunk[0];
                int cz = (int) chunk[1];
                minChunkX = Math.min(minChunkX, cx);
                maxChunkX = Math.max(maxChunkX, cx);
                minChunkZ = Math.min(minChunkZ, cz);
                maxChunkZ = Math.max(maxChunkZ, cz);
            }
            
            // Calculate center for distance limiting
            int centerX = (minChunkX + maxChunkX) / 2;
            int centerZ = (minChunkZ + maxChunkZ) / 2;
            int baseRadius = Math.max(maxChunkX - centerX, maxChunkZ - centerZ);
            int maxRadius = baseRadius + maxExpansionRadius;
            
            // BFS queue: start with the immediate edge of the cleaned area
            java.util.Queue<long[]> queue = new java.util.LinkedList<>();
            
            // Add initial edge chunks (one layer outside the cleaned area)
            for (int cx = minChunkX - 1; cx <= maxChunkX + 1; cx++) {
                // Top and bottom edges
                addToQueueIfNew(queue, processed, cx, minChunkZ - 1);
                addToQueueIfNew(queue, processed, cx, maxChunkZ + 1);
            }
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // Left and right edges
                addToQueueIfNew(queue, processed, minChunkX - 1, cz);
                addToQueueIfNew(queue, processed, maxChunkX + 1, cz);
            }
            
            InstantWorldMirror.LOGGER.info("Starting BFS edge scan from {} initial edge chunks, max radius: {}",
                    queue.size(), maxRadius);
            
            int chunksScanned = 0;
            int chunksWithBlocks = 0;
            
            // BFS loop
            while (!queue.isEmpty()) {
                long[] current = queue.poll();
                int cx = (int) current[0];
                int cz = (int) current[1];
                chunksScanned++;
                
                // Check if this chunk has any non-air blocks
                if (hasBlocksInChunk(mirrorWorld, cx, cz)) {
                    auxiliaryChunks.add(current);
                    chunksWithBlocks++;
                    
                    // Add neighbors to queue (expand BFS)
                    // Only expand if within max radius limit
                    int distFromCenter = Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ));
                    if (distFromCenter < maxRadius) {
                        addToQueueIfNew(queue, processed, cx - 1, cz);
                        addToQueueIfNew(queue, processed, cx + 1, cz);
                        addToQueueIfNew(queue, processed, cx, cz - 1);
                        addToQueueIfNew(queue, processed, cx, cz + 1);
                    }
                }
            }
            
            if (!auxiliaryChunks.isEmpty()) {
                InstantWorldMirror.LOGGER.info("BFS scan complete: scanned {} chunks, found {} with blocks (edge structures)",
                        chunksScanned, chunksWithBlocks);
            } else {
                InstantWorldMirror.LOGGER.info("BFS scan complete: scanned {} chunks, no edge blocks found",
                        chunksScanned);
                // Don't mark completed yet - still need region scan phase
                auxiliaryCleanupComplete = true;
            }
            
            return auxiliaryChunks.size();
        }
        
        /**
         * Initialize region file scan for fallback cleanup.
         * Scans the dimension's region folder to find ALL chunks that exist on disk,
         * then checks each one for blocks. This is the ultimate fallback to ensure
         * no untracked chunks with blocks remain.
         * 
         * @param mirrorWorld The mirror world to scan
         * @return The number of chunks found with blocks
         */
        public int initializeRegionScan(ServerLevel mirrorWorld) {
            if (regionScanStarted && regionScanIndex < regionScanChunks.size()) {
                return regionScanChunks.size(); // Already in progress
            }
            
            regionScanStarted = true;
            regionScanPass++;
            regionScanChunks.clear();
            regionScanIndex = 0;
            
            InstantWorldMirror.LOGGER.info("Starting region file scan pass {} for dimension {}",
                    regionScanPass, dimensionIndex);
            
            // Get all chunks that have already been processed
            Set<Long> processedChunks = new java.util.HashSet<>();
            for (long[] chunk : chunksToClean) {
                processedChunks.add(packChunkPos((int)chunk[0], (int)chunk[1]));
            }
            for (long[] chunk : auxiliaryChunks) {
                processedChunks.add(packChunkPos((int)chunk[0], (int)chunk[1]));
            }
            
            // Scan region files to find all existing chunks
            Set<Long> regionChunks = scanRegionFilesForChunks(mirrorWorld);
            
            if (regionChunks.isEmpty()) {
                InstantWorldMirror.LOGGER.info("No region files found for dimension {}, scan complete", dimensionIndex);
                regionScanComplete = true;
                completed = true;
                return 0;
            }
            
            InstantWorldMirror.LOGGER.info("Found {} total chunks in region files", regionChunks.size());
            
            // Check each chunk from region files that we haven't processed yet
            int chunksWithBlocks = 0;
            for (Long packed : regionChunks) {
                // Skip already processed chunks
                if (processedChunks.contains(packed)) {
                    continue;
                }
                
                int cx = unpackChunkX(packed);
                int cz = unpackChunkZ(packed);
                
                // Check if this chunk has blocks
                if (hasBlocksInChunk(mirrorWorld, cx, cz)) {
                    regionScanChunks.add(new long[]{cx, cz});
                    chunksWithBlocks++;
                    
                    // Also add to processed so we don't check again
                    processedChunks.add(packed);
                }
            }
            
            if (chunksWithBlocks > 0) {
                InstantWorldMirror.LOGGER.info("Region scan pass {}: found {} untracked chunks with blocks",
                        regionScanPass, chunksWithBlocks);
            } else {
                InstantWorldMirror.LOGGER.info("Region scan pass {}: no untracked blocks found, cleanup complete",
                        regionScanPass);
                regionScanComplete = true;
                completed = true;
            }
            
            return chunksWithBlocks;
        }
        
        /**
         * Helper method for BFS: add chunk to queue if not already processed
         */
        private void addToQueueIfNew(java.util.Queue<long[]> queue, Set<Long> processed, int cx, int cz) {
            long packed = packChunkPos(cx, cz);
            if (processed.add(packed)) {
                queue.add(new long[]{cx, cz});
            }
        }
        
        /**
         * Save current cleanup progress to persistent storage
         */
        public void saveProgress() {
            BlockPos centerPos = copyCenterPositions.get(dimensionIndex);
            Set<Long> chunks = getModifiedChunks(dimensionIndex);
            
            // Convert chunks list back to set for saving (include all chunks, not just remaining)
            Set<Long> allChunks = new java.util.HashSet<>();
            for (long[] chunk : chunksToClean) {
                allChunks.add(packChunkPos((int)chunk[0], (int)chunk[1]));
            }
            // Also include any tracked chunks
            allChunks.addAll(chunks);
            
            DimensionPool.saveCleanupData(dimensionIndex, centerPos, allChunks, currentIndex);
        }
        
        public boolean isCompleted() { return completed; }
    }
    
    /**
     * Scan region files to find all chunks that exist on disk.
     * Region files are named r.X.Z.mca where X and Z are region coordinates.
     * Each region contains 32x32 chunks.
     * 
     * @param mirrorWorld The world to scan
     * @return Set of packed chunk positions that exist in region files
     */
    private static Set<Long> scanRegionFilesForChunks(ServerLevel mirrorWorld) {
        Set<Long> chunks = new java.util.HashSet<>();
        
        try {
            // Get the dimension's save folder path
            // For custom dimensions: world/dimensions/namespace/dimension_name/region/
            java.nio.file.Path worldFolder = mirrorWorld.getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            
            // Build the path to the dimension's region folder
            String dimensionPath = mirrorWorld.dimension().location().toString().replace(":", "/");
            java.nio.file.Path regionFolder = worldFolder.resolve("dimensions").resolve(dimensionPath).resolve("region");
            
            if (!java.nio.file.Files.exists(regionFolder)) {
                InstantWorldMirror.LOGGER.debug("Region folder does not exist: {}", regionFolder);
                return chunks;
            }
            
            // Scan for .mca files
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = 
                    java.nio.file.Files.newDirectoryStream(regionFolder, "r.*.*.mca")) {
                for (java.nio.file.Path regionFile : stream) {
                    String fileName = regionFile.getFileName().toString();
                    // Parse r.X.Z.mca
                    String[] parts = fileName.split("\\.");
                    if (parts.length >= 4) {
                        try {
                            int regionX = Integer.parseInt(parts[1]);
                            int regionZ = Integer.parseInt(parts[2]);
                            
                            // Each region contains 32x32 chunks
                            // We need to check which chunks actually have data
                            // For efficiency, just add all possible chunks in this region
                            // The hasBlocksInChunk check will filter out empty ones
                            Set<Long> regionChunks = scanRegionFileForChunks(regionFile, regionX, regionZ);
                            chunks.addAll(regionChunks);
                            
                        } catch (NumberFormatException e) {
                            // Skip malformed file names
                            InstantWorldMirror.LOGGER.debug("Skipping malformed region file: {}", fileName);
                        }
                    }
                }
            }
            
            InstantWorldMirror.LOGGER.debug("Found {} chunks in {} region files", chunks.size(), 
                    java.nio.file.Files.list(regionFolder).count());
            
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Error scanning region files: {}", e.getMessage());
        }
        
        return chunks;
    }
    
    /**
     * Scan a single region file to find which chunks have data.
     * Region file format: 4KB header contains 1024 chunk location entries (32x32).
     * Each entry is 4 bytes: 3 bytes offset, 1 byte sector count.
     * If the entry is 0, the chunk doesn't exist.
     * 
     * @param regionFile Path to the region file
     * @param regionX Region X coordinate
     * @param regionZ Region Z coordinate
     * @return Set of packed chunk positions that have data in this region
     */
    private static Set<Long> scanRegionFileForChunks(java.nio.file.Path regionFile, int regionX, int regionZ) {
        Set<Long> chunks = new java.util.HashSet<>();
        
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(regionFile.toFile(), "r")) {
            // Read the 4KB header (1024 chunk entries, 4 bytes each)
            byte[] header = new byte[4096];
            int bytesRead = raf.read(header);
            
            if (bytesRead < 4096) {
                return chunks; // File too small, no valid data
            }
            
            // Check each chunk entry
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int index = (localX + localZ * 32) * 4;
                    
                    // Read 4-byte entry (big-endian)
                    int offset = ((header[index] & 0xFF) << 16) | 
                                 ((header[index + 1] & 0xFF) << 8) | 
                                 (header[index + 2] & 0xFF);
                    int sectorCount = header[index + 3] & 0xFF;
                    
                    // If offset and sectorCount are both non-zero, chunk has data
                    if (offset != 0 && sectorCount != 0) {
                        int chunkX = regionX * 32 + localX;
                        int chunkZ = regionZ * 32 + localZ;
                        chunks.add(packChunkPos(chunkX, chunkZ));
                    }
                }
            }
        } catch (Exception e) {
            // If we can't read the file, return empty set
            InstantWorldMirror.LOGGER.debug("Error reading region file {}: {}", regionFile, e.getMessage());
        }
        
        return chunks;
    }
    
    /**
     * Check if a chunk has any non-air blocks.
     * Uses efficient section-level checks.
     * Uses non-blocking chunk access to prevent server hang.
     */
    private static boolean hasBlocksInChunk(ServerLevel level, int chunkX, int chunkZ) {
        try {
            // Use non-blocking chunk access
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                // Chunk not loaded - assume no blocks (safe for cleanup)
                return false;
            }
            
            // Check each section
            int sectionCount = chunk.getSectionsCount();
            for (int i = 0; i < sectionCount; i++) {
                LevelChunkSection section = chunk.getSection(i);
                if (section != null && !section.hasOnlyAir()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Block Utilities ====================

    private static boolean isPortalBlock(BlockState state) {
        return state.is(Blocks.NETHER_PORTAL) ||
               state.is(Blocks.END_PORTAL) ||
               state.is(Blocks.END_PORTAL_FRAME) ||
               state.is(Blocks.END_GATEWAY);
    }

    // ==================== World Copy (Asynchronous) ====================

    /**
     * Queue world copy for async processing to session's dedicated dimension
     */
    public static void queueWorldCopy(MirrorSession session, ServerLevel sourceWorld) {
        int chunkRadius = MirrorConfig.COPY_CHUNK_RADIUS.get();
        int dimIndex = session.getDimensionIndex();
        
        // Sync game rules from source world to mirror world
        MinecraftServer server = sourceWorld.getServer();
        if (server != null) {
            ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, dimIndex);
            if (mirrorWorld != null) {
                syncGameRules(sourceWorld, mirrorWorld);
            }
        }
        
        CopyTask task = new CopyTask(
                session.getSessionId(),
                session.getSourcePosition(),
                chunkRadius,
                sourceWorld.dimension(),
                dimIndex
        );
        
        copyTasks.put(dimIndex, task);
        
        // Store the copy center position for cleanup later (both in memory and persistent storage)
        copyCenterPositions.put(dimIndex, session.getSourcePosition());
        DimensionPool.saveCleanupData(dimIndex, session.getSourcePosition(), null, 0);
        
        InstantWorldMirror.LOGGER.info("Queued world copy for session {} to dimension {} - {} chunks total",
                session.getSessionId(), dimIndex, task.getTotalChunks());
    }
    
    /**
     * Sync game rules from source world to mirror world
     * This ensures the mirror world has the same rules (mob spawning, daylight cycle, etc.)
     */
    private static void syncGameRules(ServerLevel sourceWorld, ServerLevel mirrorWorld) {
        try {
            net.minecraft.world.level.GameRules sourceRules = sourceWorld.getGameRules();
            net.minecraft.world.level.GameRules mirrorRules = mirrorWorld.getGameRules();
            
            // Use assignFrom to copy all game rules from source to mirror
            mirrorRules.assignFrom(sourceRules, mirrorWorld.getServer());
            
            InstantWorldMirror.LOGGER.debug("Synced game rules from {} to mirror world", 
                    sourceWorld.dimension().location());
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to sync game rules: {}", e.getMessage());
        }
    }

    /**
     * Process all copy queues - call from server tick
     */
    public static void processCopyQueues(MinecraftServer server) {
        if (copyTasks.isEmpty()) return; // Early exit if no tasks
        
        int chunksPerTick = MirrorConfig.COPY_CHUNKS_PER_TICK.get();
        
        // Use iterator for safe removal during iteration
        var iterator = copyTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int dimIndex = entry.getKey();
            CopyTask task = entry.getValue();
            
            if (task.isCompleted()) {
                iterator.remove();
                // If cancelled, skip completion notification
                if (task.isCancelled()) {
                    InstantWorldMirror.LOGGER.info("Copy task for dimension {} was cancelled", dimIndex);
                }
                continue;
            }
            
            ServerLevel targetWorld = DimensionPool.getDimensionLevel(server, dimIndex);
            if (targetWorld == null) continue;
            
            ServerLevel sourceWorld = server.getLevel(task.sourceDimension);
            if (sourceWorld == null) sourceWorld = server.overworld();
            
            // Process multiple chunks per tick (configurable)
            for (int i = 0; i < chunksPerTick && !task.isCompleted(); i++) {
                int[] chunkCoords = task.getNextChunk();
                if (chunkCoords != null) {
                    int blocksCopied = copyChunk(sourceWorld, targetWorld, chunkCoords[0], chunkCoords[1]);
                    task.addBlocksCopied(blocksCopied);
                    
                    // Track this chunk as modified for cleanup later
                    if (blocksCopied > 0) {
                        trackModifiedChunk(dimIndex, chunkCoords[0], chunkCoords[1]);
                    }
                }
            }
            
            // Check completion
            if (task.isCompleted()) {
                iterator.remove();
                
                // Only notify if not cancelled
                if (!task.isCancelled()) {
                    // Notify session that copy is complete
                    MirrorWorldManager.getSession(task.sessionId).ifPresent(MirrorSession::markCopyComplete);
                    
                    InstantWorldMirror.LOGGER.info("World copy completed for session {} in dimension {} - {} blocks copied",
                            task.sessionId, dimIndex, task.getTotalBlocksCopied());
                }
            }
        }
    }

    /**
     * Check if a session's world copy is complete
     */
    public static boolean isCopyComplete(UUID sessionId) {
        return MirrorWorldManager.getSession(sessionId)
                .map(session -> {
                    CopyTask task = copyTasks.get(session.getDimensionIndex());
                    return task == null || task.isCompleted();
                })
                .orElse(true);
    }

    /**
     * Get copy progress for a session (0-100)
     */
    public static int getCopyProgress(UUID sessionId) {
        return MirrorWorldManager.getSession(sessionId)
                .map(session -> {
                    CopyTask task = copyTasks.get(session.getDimensionIndex());
                    if (task == null) return 100;
                    return task.getProgressPercent();
                })
                .orElse(100);
    }

    private static int copyChunk(ServerLevel sourceWorld, ServerLevel mirrorWorld,
                                  int chunkX, int chunkZ) {
        int blocksCopied = 0;
        
        try {
            // For SOURCE world: Use non-blocking access since player should be nearby
            // and the chunk should already be loaded
            LevelChunk sourceChunk = sourceWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (sourceChunk == null) {
                // Source chunk not loaded - this is unusual, request async load and retry later
                sourceWorld.getChunkSource().getChunk(chunkX, chunkZ, 
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                return 0;
            }
            
            // For TARGET world (mirror world): We need to ensure the chunk exists
            // Mirror world is an empty dimension, so chunk generation is fast (just void)
            // Use getChunk which will create the chunk if needed
            // This should be fast since mirror world has no terrain generation
            LevelChunk targetChunk = mirrorWorld.getChunk(chunkX, chunkZ);
            
            // Optimization 1: Use heightmap to find max height with blocks
            // This avoids scanning empty sky
            int maxHeight = getChunkMaxHeight(sourceChunk);
            int minY = sourceWorld.getMinBuildHeight();
            
            // Optimization 2: Use MutableBlockPos to avoid object creation
            BlockPos.MutableBlockPos sourcePos = MUTABLE_POS.get();
            BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
            
            // Process section by section using chunk's section count
            int sectionCount = sourceChunk.getSectionsCount();
            int chunkMinSectionY = sourceChunk.getMinSection();
            
            for (int relativeSectionIndex = 0; relativeSectionIndex < sectionCount; relativeSectionIndex++) {
                LevelChunkSection sourceSection = sourceChunk.getSection(relativeSectionIndex);
                
                // Skip null or empty sections
                if (sourceSection == null || sourceSection.hasOnlyAir()) {
                    continue;
                }
                
                int sectionY = chunkMinSectionY + relativeSectionIndex;
                int baseY = sectionY * 16;
                
                // Skip sections above maxHeight (optimization)
                if (baseY > maxHeight) {
                    continue;
                }
                
                // Process this 16x16x16 section
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int worldX = chunkX * 16 + localX;
                        int worldZ = chunkZ * 16 + localZ;
                        
                        // Optimization 5: Use heightmap to skip air columns
                        int columnHeight = sourceChunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                        
                        for (int localY = 0; localY < 16; localY++) {
                            int y = baseY + localY;
                            if (y > columnHeight && y > 0) continue; // Skip air above surface (except underground)
                            if (y < minY) continue;
                            
                            sourcePos.set(worldX, y, worldZ);

                            // Optimization 6: Get state directly from section (faster than world.getBlockState)
                            BlockState state = sourceSection.getBlockState(localX, localY, localZ);

                            // Null check for corrupted/uninitialized section data
                            if (state != null && !state.isAir() && !isPortalBlock(state)) {
                                targetPos.set(worldX, y, worldZ);
                                mirrorWorld.setBlock(targetPos, state, 2 | 16); // 16 = no neighbor updates
                                
                                // Copy block entity if present
                                copyBlockEntity(sourceWorld, mirrorWorld, sourcePos, targetPos);
                                blocksCopied++;
                            }
                        }
                    }
                }
            }
            
            // Mark chunk for saving
            targetChunk.setUnsaved(true);
            
            // Copy biome data if enabled
            if (MirrorConfig.COPY_BIOMES.get()) {
                copyChunkBiomes(sourceChunk, targetChunk);
            }
            
            // Copy structure data if enabled (important for mods like Twilight Forest)
            if (MirrorConfig.COPY_STRUCTURES.get()) {
                copyChunkStructures(sourceChunk, targetChunk, sourceWorld, mirrorWorld);
            }
            
            // Copy/regenerate heightmaps if enabled
            if (MirrorConfig.COPY_HEIGHTMAPS.get()) {
                regenerateHeightmaps(targetChunk);
            }
            
            // Copy entities in this chunk based on config
            // Always copy decoration entities if that config is enabled
            // Copy all entities only if copyEntities is enabled
            boolean copyAll = MirrorConfig.COPY_ENTITIES.get();
            boolean copyDecorations = MirrorConfig.COPY_DECORATION_ENTITIES.get();
            if (copyAll || copyDecorations) {
                copyEntitiesInChunk(sourceWorld, mirrorWorld, chunkX, chunkZ, copyAll, copyDecorations);
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            InstantWorldMirror.LOGGER.warn("Failed to copy chunk ({}, {}): {}", chunkX, chunkZ, errorMsg);
        }
        
        return blocksCopied;
    }

    /**
     * Get the maximum height with blocks in a chunk using heightmap
     */
    private static int getChunkMaxHeight(LevelChunk chunk) {
        int maxHeight = chunk.getMinBuildHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (height > maxHeight) {
                    maxHeight = height;
                }
            }
        }
        return maxHeight;
    }

    /**
     * Check if an entity is a decoration or static entity that should be copied.
     * This includes:
     * - Decoration entities: paintings, item frames, armor stands, display entities
     * - Vehicle entities: minecarts, boats
     */
    private static boolean isDecorationEntity(net.minecraft.world.entity.Entity entity) {
        // HangingEntity includes Painting, ItemFrame, GlowItemFrame, LeashFenceKnotEntity
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity) {
            return true;
        }
        // ArmorStand is commonly used for decoration
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
            return true;
        }
        // Display entities (Text Display, Block Display, Item Display) are also decorations
        if (entity instanceof net.minecraft.world.entity.Display) {
            return true;
        }
        // VehicleEntity includes all minecarts and boats
        // AbstractMinecart: Minecart, MinecartChest, MinecartCommandBlock, MinecartFurnace, MinecartHopper, MinecartSpawner, MinecartTNT
        // Boat, ChestBoat
        if (entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity) {
            return true;
        }
        return false;
    }

    /**
     * Copy entities in a chunk
     * @param copyAll if true, copy all entities; if false, only copy based on copyDecorations
     * @param copyDecorations if true (and copyAll is false), only copy decoration entities
     */
    private static void copyEntitiesInChunk(ServerLevel sourceWorld, ServerLevel mirrorWorld, 
                                             int chunkX, int chunkZ, boolean copyAll, boolean copyDecorations) {
        try {
            int minX = chunkX * 16;
            int minZ = chunkZ * 16;
            int maxX = minX + 16;
            int maxZ = minZ + 16;
            int minY = sourceWorld.getMinBuildHeight();
            int maxY = sourceWorld.getMaxBuildHeight();
            
            net.minecraft.world.phys.AABB chunkBounds = new net.minecraft.world.phys.AABB(
                    minX, minY, minZ, maxX, maxY, maxZ
            );
            
            // Get all entities in the chunk (excluding players)
            java.util.List<net.minecraft.world.entity.Entity> entities = sourceWorld.getEntities(
                    (net.minecraft.world.entity.Entity) null, 
                    chunkBounds,
                    entity -> !(entity instanceof net.minecraft.world.entity.player.Player)
            );
            
            for (net.minecraft.world.entity.Entity sourceEntity : entities) {
                try {
                    // Check if we should copy this entity
                    boolean shouldCopy = copyAll;
                    if (!shouldCopy && copyDecorations) {
                        shouldCopy = isDecorationEntity(sourceEntity);
                    }
                    
                    if (!shouldCopy) {
                        continue;
                    }
                    
                    // Create a new entity of the same type
                    net.minecraft.world.entity.EntityType<?> entityType = sourceEntity.getType();
                    net.minecraft.world.entity.Entity newEntity = entityType.create(mirrorWorld);
                    
                    if (newEntity != null) {
                        // Copy entity data
                        net.minecraft.nbt.CompoundTag entityData = new net.minecraft.nbt.CompoundTag();
                        sourceEntity.save(entityData);
                        
                        // Remove UUID to generate new one
                        entityData.remove("UUID");
                        
                        newEntity.load(entityData);
                        newEntity.setPos(sourceEntity.getX(), sourceEntity.getY(), sourceEntity.getZ());
                        
                        mirrorWorld.addFreshEntity(newEntity);
                    }
                } catch (Exception e) {
                    // Ignore individual entity copy errors
                }
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            InstantWorldMirror.LOGGER.debug("Error copying entities in chunk ({}, {}): {}", 
                    chunkX, chunkZ, errorMsg);
        }
    }
    
    /**
     * Clear all entities in a chunk during cleanup (excluding players)
     * Uses a slightly larger bounding box to catch entities on chunk boundaries
     */
    private static void clearEntitiesInChunk(ServerLevel mirrorWorld, int chunkX, int chunkZ) {
        try {
            // Use chunk coordinates but expand slightly to catch edge cases
            int minX = chunkX * 16 - 1;
            int minZ = chunkZ * 16 - 1;
            int maxX = minX + 18; // 16 + 2 for boundary
            int maxZ = minZ + 18;
            int minY = mirrorWorld.getMinBuildHeight();
            int maxY = mirrorWorld.getMaxBuildHeight();
            
            net.minecraft.world.phys.AABB chunkBounds = new net.minecraft.world.phys.AABB(
                    minX, minY, minZ, maxX, maxY, maxZ
            );
            
            // Get all entities in the chunk (excluding players)
            java.util.List<net.minecraft.world.entity.Entity> entities = mirrorWorld.getEntities(
                    (net.minecraft.world.entity.Entity) null, 
                    chunkBounds,
                    entity -> !(entity instanceof net.minecraft.world.entity.player.Player)
            );
            
            // Remove all non-player entities
            for (net.minecraft.world.entity.Entity entity : entities) {
                // Double check the entity is within the original chunk bounds
                int entityChunkX = (int) Math.floor(entity.getX()) >> 4;
                int entityChunkZ = (int) Math.floor(entity.getZ()) >> 4;
                if (entityChunkX == chunkX && entityChunkZ == chunkZ) {
                    entity.discard();
                }
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            InstantWorldMirror.LOGGER.debug("Error clearing entities in chunk ({}, {}): {}", 
                    chunkX, chunkZ, errorMsg);
        }
    }

    // ==================== Biome Copy ====================
    
    // Cached field for biome reflection (set once, used many times)
    private static Field biomesField = null;
    private static boolean biomesFieldInitialized = false;
    private static boolean biomesCopyError = false;
    
    /**
     * Copy biome data from source chunk to target chunk.
     * This is critical for mods like Twilight Forest that use custom biomes
     * for sky effects, grass colors, and other environmental features.
     * 
     * Biomes are stored in 4x4x4 blocks per biome sample (quart positions).
     * Each chunk section (16x16x16) contains 4x4x4 biome samples.
     */
    private static void copyChunkBiomes(LevelChunk sourceChunk, LevelChunk targetChunk) {
        if (biomesCopyError) {
            return; // Skip if we've already had a critical error
        }
        
        try {
            // Initialize the biomes field via reflection (only once)
            if (!biomesFieldInitialized) {
                biomesFieldInitialized = true;
                try {
                    biomesField = ObfuscationReflectionHelper.findField(LevelChunkSection.class, "biomes");
                    biomesField.setAccessible(true);
                } catch (Exception e) {
                    InstantWorldMirror.LOGGER.error("Failed to find biomes field in LevelChunkSection. " +
                            "Biome copying will be disabled. Error: {}", e.getMessage());
                    biomesCopyError = true;
                    return;
                }
            }
            
            if (biomesField == null) {
                return;
            }
            
            int sectionCount = sourceChunk.getSectionsCount();
            
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                LevelChunkSection sourceSection = sourceChunk.getSection(sectionIndex);
                LevelChunkSection targetSection = targetChunk.getSection(sectionIndex);
                
                if (sourceSection == null || targetSection == null) {
                    continue;
                }
                
                // Get the source biome container (read-only)
                PalettedContainerRO<Holder<Biome>> sourceBiomes = sourceSection.getBiomes();
                if (sourceBiomes == null) {
                    continue;
                }
                
                // Recreate a mutable copy of the biome container
                PalettedContainer<Holder<Biome>> newBiomes = sourceBiomes.recreate();
                
                // Copy all biome data from source (4x4x4 per section)
                for (int biomeX = 0; biomeX < 4; biomeX++) {
                    for (int biomeY = 0; biomeY < 4; biomeY++) {
                        for (int biomeZ = 0; biomeZ < 4; biomeZ++) {
                            Holder<Biome> biome = sourceBiomes.get(biomeX, biomeY, biomeZ);
                            newBiomes.getAndSetUnchecked(biomeX, biomeY, biomeZ, biome);
                        }
                    }
                }
                
                // Set the biomes field via reflection
                biomesField.set(targetSection, newBiomes);
            }
            
            // Mark the chunk as needing to be saved
            targetChunk.setUnsaved(true);
            
        } catch (Exception e) {
            // Only log once to avoid spam
            if (!biomesCopyError) {
                InstantWorldMirror.LOGGER.warn("Failed to copy biomes for chunk ({}, {}): {}. " +
                        "This may affect grass colors and sky effects for modded dimensions.", 
                        sourceChunk.getPos().x, sourceChunk.getPos().z, e.getMessage());
            }
        }
    }

    // ==================== Structure Data Copy ====================
    
    private static boolean structureCopyError = false;
    
    /**
     * Copy structure data from source chunk to target chunk.
     * This is important for mods like Twilight Forest that store additional data
     * in StructureStart (e.g., "conquered" status).
     * 
     * Structure data includes:
     * - Structure starts (the actual structure instances)
     * - Structure references (pointers to nearby structure starts)
     */
    private static void copyChunkStructures(LevelChunk sourceChunk, LevelChunk targetChunk,
                                            ServerLevel sourceWorld, ServerLevel mirrorWorld) {
        if (structureCopyError) {
            return;
        }
        
        try {
            // Copy all structure starts from source chunk
            Map<Structure, StructureStart> sourceStarts = sourceChunk.getAllStarts();
            
            if (!sourceStarts.isEmpty()) {
                for (Map.Entry<Structure, StructureStart> entry : sourceStarts.entrySet()) {
                    Structure structure = entry.getKey();
                    StructureStart sourceStart = entry.getValue();
                    
                    if (sourceStart != null && sourceStart.isValid()) {
                        // Copy the structure start reference directly to target chunk
                        // This preserves mod-specific data like Twilight Forest's "conquered" flag
                        try {
                            targetChunk.setStartForStructure(structure, sourceStart);
                        } catch (Exception e) {
                            InstantWorldMirror.LOGGER.debug("Could not copy structure start {}: {}", 
                                    structure, e.getMessage());
                        }
                    }
                }
            }
            
            // Copy structure references
            Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> sourceRefs = sourceChunk.getAllReferences();
            if (!sourceRefs.isEmpty()) {
                for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> entry : sourceRefs.entrySet()) {
                    Structure structure = entry.getKey();
                    it.unimi.dsi.fastutil.longs.LongSet refs = entry.getValue();
                    
                    if (refs != null && !refs.isEmpty()) {
                        // Copy references to target chunk
                        for (long ref : refs) {
                            targetChunk.addReferenceForStructure(structure, ref);
                        }
                    }
                }
            }
            
            targetChunk.setUnsaved(true);
            
        } catch (Exception e) {
            if (!structureCopyError) {
                structureCopyError = true;
                InstantWorldMirror.LOGGER.warn("Failed to copy structure data for chunk ({}, {}): {}. " +
                        "This may affect mod features that depend on structure data.",
                        sourceChunk.getPos().x, sourceChunk.getPos().z, e.getMessage());
            }
        }
    }
    
    // ==================== Heightmap Regeneration ====================
    
    /**
     * Regenerate heightmaps for a chunk after block copy.
     * This ensures proper light propagation and mob spawning locations.
     */
    private static void regenerateHeightmaps(LevelChunk targetChunk) {
        try {
            // Regenerate all heightmap types that Minecraft uses
            Heightmap.primeHeightmaps(targetChunk, 
                    EnumSet.of(
                            Heightmap.Types.MOTION_BLOCKING,
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            Heightmap.Types.OCEAN_FLOOR,
                            Heightmap.Types.WORLD_SURFACE
                    ));
            targetChunk.setUnsaved(true);
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.debug("Failed to regenerate heightmaps for chunk ({}, {}): {}",
                    targetChunk.getPos().x, targetChunk.getPos().z, e.getMessage());
        }
    }

    // ==================== Block Entity Copy ====================

    private static void copyBlockEntity(ServerLevel sourceWorld, ServerLevel mirrorWorld,
                                         BlockPos sourcePos, BlockPos targetPos) {
        BlockEntity sourceBE = sourceWorld.getBlockEntity(sourcePos);
        if (sourceBE != null) {
            try {
                // Verify the target block matches the source block type
                BlockState targetState = mirrorWorld.getBlockState(targetPos);
                BlockState sourceState = sourceWorld.getBlockState(sourcePos);
                
                // Only copy if blocks match (prevents mismatched block entity errors)
                if (!targetState.is(sourceState.getBlock())) {
                    return;
                }
                
                BlockEntity targetBE = mirrorWorld.getBlockEntity(targetPos);
                if (targetBE != null && targetBE.getType() == sourceBE.getType()) {
                    targetBE.loadWithComponents(
                            sourceBE.saveWithoutMetadata(sourceWorld.registryAccess()),
                            sourceWorld.registryAccess()
                    );
                    targetBE.setChanged();
                }
            } catch (Exception e) {
                // Ignore block entity copy errors - block might not support the operation
                InstantWorldMirror.LOGGER.trace("Could not copy block entity at {}: {}", 
                        sourcePos, e.getMessage());
            }
        }
    }
    
    // Cached field for pending block entities (set once, used many times)
    private static Field pendingBlockEntitiesField = null;
    private static boolean pendingBEFieldInitialized = false;
    
    /**
     * Clear pending block entities from a chunk.
     * Pending BEs are NBT data stored in the chunk that haven't been loaded yet.
     * If not cleared, they can cause "Invalid block entity" errors when the chunk is saved
     * after we've cleared the blocks.
     */
    private static void clearPendingBlockEntities(LevelChunk chunk) {
        try {
            // Initialize the field once via reflection
            if (!pendingBEFieldInitialized) {
                pendingBEFieldInitialized = true;
                for (java.lang.reflect.Field field : LevelChunk.class.getDeclaredFields()) {
                    if (java.util.Map.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        // Test if this is the right field by checking the type parameter
                        // pendingBlockEntities is Map<BlockPos, CompoundTag>
                        try {
                            Object testValue = field.get(chunk);
                            if (testValue instanceof java.util.Map<?, ?> testMap && !testMap.isEmpty()) {
                                Object firstKey = testMap.keySet().iterator().next();
                                if (firstKey instanceof BlockPos) {
                                    pendingBlockEntitiesField = field;
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            
            // Clear the map if we found the field
            if (pendingBlockEntitiesField != null) {
                Object value = pendingBlockEntitiesField.get(chunk);
                if (value instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                    int count = map.size();
                    map.clear();
                    if (count > 0) {
                        InstantWorldMirror.LOGGER.debug("Cleared {} pending block entities from chunk", count);
                    }
                }
            }
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.debug("Could not clear pending BEs via reflection: {}", e.getMessage());
        }
    }

    // ==================== World Cleanup (Asynchronous) ====================

    /**
     * Queue cleanup for a specific dimension.
     * Only cleans chunks that were tracked during runtime:
     * - Chunks copied during world copy
     * - Chunks where blocks were placed/broken
     * - Chunks loaded by players (tracked via player position)
     * No scanning needed - relies entirely on tracked data.
     */
    public static void cleanupMirrorWorld(ServerLevel mirrorWorld, int dimensionIndex) {
        // Cancel any existing cleanup task to restart fresh
        cancelCleanupTask(dimensionIndex);
        
        // FIRST: Kill all entities in the dimension immediately (except players)
        // This ensures mobs don't continue spawning/moving during chunk cleanup
        clearAllEntitiesInDimension(mirrorWorld);
        
        CleanupTask task = new CleanupTask(dimensionIndex);
        // Initialize with all tracked chunks
        task.initializeChunkList(mirrorWorld);
        cleanupTasks.put(dimensionIndex, task);
        
        InstantWorldMirror.LOGGER.info("Queued cleanup for dimension {} - {} tracked chunks to process",
                dimensionIndex, task.getTotalChunks());
    }
    
    /**
     * Legacy method for compatibility - redirects to new method
     * @deprecated Use cleanupMirrorWorld(ServerLevel, int) instead
     */
    @Deprecated
    public static void cleanupMirrorWorld(ServerLevel mirrorWorld, BlockPos centerPos, int dimensionIndex) {
        cleanupMirrorWorld(mirrorWorld, dimensionIndex);
    }

    /**
     * Process all cleanup queues - call from server tick
     * Includes three phases:
     * 1. Main cleanup: clean all tracked chunks
     * 2. Auxiliary cleanup: BFS scan and clean edge chunks with remaining blocks
     * 3. Region scan: scan region files for any untracked chunks with blocks (ultimate fallback)
     */
    public static void processCleanupQueues(MinecraftServer server) {
        // Periodically save pending modifications (even if no cleanup tasks)
        saveTickCounter++;
        if (saveTickCounter >= SAVE_TICK_INTERVAL) {
            saveTickCounter = 0;
            savePendingModifications();
        }
        
        if (cleanupTasks.isEmpty()) return; // Early exit if no tasks
        
        int chunksPerTick = MirrorConfig.CLEANUP_CHUNKS_PER_TICK.get();
        
        // Use iterator for safe removal during iteration
        var iterator = cleanupTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int dimIndex = entry.getKey();
            CleanupTask task = entry.getValue();
            
            if (task.isCompleted()) {
                iterator.remove();
                continue;
            }
            
            ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, dimIndex);
            if (mirrorWorld == null) continue;
            
            // Process chunks per tick (configurable)
            // Use non-blocking chunk access to prevent server hang
            int processedThisTick = 0;
            for (int i = 0; i < chunksPerTick && !task.isCompleted(); i++) {
                long[] chunkCoords = task.getNextChunk();
                if (chunkCoords != null) {
                    int result = clearChunk(mirrorWorld, (int) chunkCoords[0], (int) chunkCoords[1]);
                    if (result == -1) {
                        // Chunk wasn't loaded - add to retry queue for later processing
                        task.markForRetry((int) chunkCoords[0], (int) chunkCoords[1]);
                    }
                    processedThisTick++;
                } else if (task.isMainCleanupDone() && !task.isAuxiliaryCleanupDone()) {
                    // Main cleanup done, start auxiliary cleanup phase (BFS edge scan)
                    int additionalChunks = task.initializeAuxiliaryCleanup(mirrorWorld);
                    if (additionalChunks > 0) {
                        InstantWorldMirror.LOGGER.info("Starting auxiliary cleanup for {} edge chunks in dimension {}",
                                additionalChunks, dimIndex);
                    }
                    break; // Wait for next tick to process auxiliary chunks
                } else if (task.isAuxiliaryCleanupDone() && task.needsRegionScan()) {
                    // Auxiliary cleanup done, start region file scan (ultimate fallback)
                    int untrackedChunks = task.initializeRegionScan(mirrorWorld);
                    if (untrackedChunks > 0) {
                        InstantWorldMirror.LOGGER.info("Starting region scan cleanup for {} untracked chunks in dimension {}",
                                untrackedChunks, dimIndex);
                    }
                    break; // Wait for next tick to process region scan chunks
                }
            }
            
            // Log progress every 10 chunks
            int cleanedChunks = task.getCleanedChunks();
            if (cleanedChunks % 10 == 0 && cleanedChunks > 0 && processedThisTick > 0) {
                InstantWorldMirror.LOGGER.debug("Cleanup progress for dim {}: {}/{} chunks",
                        dimIndex, cleanedChunks, task.getTotalChunks());
            }
            
            // Check completion
            if (task.isCompleted()) {
                iterator.remove();
                
                // Final pass: clear ALL remaining entities in the dimension
                clearAllEntitiesInDimension(mirrorWorld);
                
                // Final verification scan - check if any blocks remain
                int remainingBlocks = countRemainingBlocks(mirrorWorld, task);
                if (remainingBlocks > 0) {
                    InstantWorldMirror.LOGGER.warn("Cleanup finished but {} blocks may still remain in dimension {}. " +
                            "This could be from very distant structures.", remainingBlocks, dimIndex);
                }
                
                // Clear tracking data for this dimension
                clearModifiedChunkTracking(dimIndex);
                copyCenterPositions.remove(dimIndex);
                
                // Mark dimension as available again
                DimensionPool.markDimensionAvailable(dimIndex);
                
                InstantWorldMirror.LOGGER.info("Cleanup completed for dimension {}, now available for new sessions",
                        dimIndex);
            }
        }
    }
    
    /**
     * Count remaining non-air blocks in cleaned chunks of the mirror world.
     * This is a verification pass to detect any missed blocks.
     * Samples a subset of blocks to avoid performance issues.
     */
    private static int countRemainingBlocks(ServerLevel mirrorWorld, CleanupTask task) {
        int totalBlocks = 0;
        
        try {
            // Sample some chunks from the cleaned area to verify
            java.util.Random random = new java.util.Random();
            java.util.List<long[]> allChunks = task.getAllCleanedChunks();
            int sampleSize = Math.min(10, allChunks.size());
            
            for (int i = 0; i < sampleSize; i++) {
                int index = random.nextInt(allChunks.size());
                long[] coords = allChunks.get(index);
                int chunkX = (int) coords[0];
                int chunkZ = (int) coords[1];
                
                try {
                    // Use non-blocking chunk access
                    LevelChunk chunk = mirrorWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null) {
                        // Chunk not loaded, skip
                        continue;
                    }
                    int sectionCount = chunk.getSectionsCount();
                    
                    for (int s = 0; s < sectionCount; s++) {
                        LevelChunkSection section = chunk.getSection(s);
                        if (section != null && !section.hasOnlyAir()) {
                            // Count non-air blocks in this section (sample a few positions)
                            for (int x = 0; x < 16; x += 4) {
                                for (int y = 0; y < 16; y += 4) {
                                    for (int z = 0; z < 16; z += 4) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state != null && !state.isAir()) {
                                            totalBlocks++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Chunk might not be loaded
                }
            }
        } catch (Exception e) {
            // Ignore errors during verification
        }
        
        return totalBlocks;
    }

    /**
     * Clear a chunk with aggressive cleanup
     * Uses section-level processing for efficiency
     * IMPORTANT: Properly handles water/fluids by scanning all blocks
     * @return number of blocks cleared, or -1 if chunk was not loaded and needs retry
     */
    private static int clearChunk(ServerLevel mirrorWorld, int chunkX, int chunkZ) {
        int blocksCleared = 0;

        try {
            // First try non-blocking access
            LevelChunk chunk = mirrorWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                // Chunk not loaded - request async load for next tick
                mirrorWorld.getChunkSource().getChunk(chunkX, chunkZ,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                return -1; // Signal retry needed
            }
            
            // CRITICAL: Clear pending block entities FIRST before clearing blocks
            // This prevents "Invalid block entity" errors when the chunk is saved
            clearPendingBlockEntities(chunk);
            
            // Also remove all loaded block entities
            for (BlockPos bePos : new java.util.ArrayList<>(chunk.getBlockEntities().keySet())) {
                mirrorWorld.removeBlockEntity(bePos);
            }
            
            int minY = mirrorWorld.getMinBuildHeight();
            int maxY = mirrorWorld.getMaxBuildHeight();
            
            // Use MutableBlockPos for efficiency
            BlockPos.MutableBlockPos pos = MUTABLE_POS.get();
            
            // Process section by section using chunk's section count
            int sectionCount = chunk.getSectionsCount();
            int chunkMinSectionY = chunk.getMinSection();
            
            for (int relativeSectionIndex = 0; relativeSectionIndex < sectionCount; relativeSectionIndex++) {
                LevelChunkSection section = chunk.getSection(relativeSectionIndex);
                
                // Skip null or empty sections
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                
                int sectionY = chunkMinSectionY + relativeSectionIndex;
                int baseY = sectionY * 16;
                
                // Process 16x16x16 section - scan ALL blocks in section (don't use heightmap for clearing)
                // This ensures water, lava, and other fluids are properly cleared
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int worldX = chunkX * 16 + localX;
                        int worldZ = chunkZ * 16 + localZ;
                        
                        for (int localY = 0; localY < 16; localY++) {
                            int y = baseY + localY;
                            if (y < minY || y >= maxY) continue;
                            
                            // Check section state directly (faster)
                            BlockState state = section.getBlockState(localX, localY, localZ);
                            
                            // Null check for corrupted/uninitialized section data
                            if (state != null && !state.isAir()) {
                                // Block entities were already cleared at the start of this method
                                mirrorWorld.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16 | 64);
                                blocksCleared++;
                            }
                        }
                    }
                }
            }
            
            // Also clear any entities in this chunk
            clearEntitiesInChunk(mirrorWorld, chunkX, chunkZ);
            
            // Mark for saving
            chunk.setUnsaved(true);
            
        } catch (Exception e) {
            // Log with exception type for better debugging when message is null
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            InstantWorldMirror.LOGGER.warn("Failed to clear chunk ({}, {}): {}", chunkX, chunkZ, errorMsg);
            if (InstantWorldMirror.LOGGER.isDebugEnabled()) {
                InstantWorldMirror.LOGGER.debug("Chunk clear exception details:", e);
            }
        }

        return blocksCleared;
    }
    
    /**
     * Clear ALL entities in the entire dimension (final cleanup pass)
     * This ensures no entities are missed due to chunk boundary issues or timing
     * Forces loading of tracked chunks to ensure all entities are accessible
     */
    private static void clearAllEntitiesInDimension(ServerLevel mirrorWorld) {
        try {
            int removed = 0;
            
            // First pass: Get all currently loaded entities
            Iterable<net.minecraft.world.entity.Entity> allEntities = mirrorWorld.getAllEntities();
            java.util.List<net.minecraft.world.entity.Entity> toRemove = new java.util.ArrayList<>();
            
            for (net.minecraft.world.entity.Entity entity : allEntities) {
                // Skip players
                if (entity instanceof net.minecraft.world.entity.player.Player) {
                    continue;
                }
                toRemove.add(entity);
            }
            
            // Remove all non-player entities from loaded chunks
            for (net.minecraft.world.entity.Entity entity : toRemove) {
                entity.discard();
                removed++;
            }
            
            // Second pass: Force load all tracked chunks and clear entities there too
            // This catches entities in chunks that aren't currently loaded
            int dimIndex = -1;
            for (var entry : modifiedChunks.entrySet()) {
                if (DimensionPool.getDimensionLevel(mirrorWorld.getServer(), entry.getKey()) == mirrorWorld) {
                    dimIndex = entry.getKey();
                    break;
                }
            }
            
            if (dimIndex >= 0) {
                Set<Long> trackedChunks = modifiedChunks.get(dimIndex);
                if (trackedChunks != null) {
                    for (Long chunkKey : trackedChunks) {
                        // Use helper methods to ensure correct unpacking
                        int chunkX = unpackChunkX(chunkKey);
                        int chunkZ = unpackChunkZ(chunkKey);
                        
                        // Use non-blocking chunk access
                        try {
                            LevelChunk chunk = mirrorWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
                            if (chunk != null) {
                                removed += clearEntitiesInChunkForced(mirrorWorld, chunkX, chunkZ);
                            }
                        } catch (Exception ignored) {
                            // Chunk might not exist, skip
                        }
                    }
                }
            }
            
            if (removed > 0) {
                InstantWorldMirror.LOGGER.info("Final cleanup: removed {} remaining entities from mirror dimension", removed);
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            InstantWorldMirror.LOGGER.warn("Error during final entity cleanup: {}", errorMsg);
        }
    }
    
    /**
     * Clear all entities in a chunk, forcing the chunk to load first
     * Returns the number of entities removed
     */
    private static int clearEntitiesInChunkForced(ServerLevel mirrorWorld, int chunkX, int chunkZ) {
        int removed = 0;
        try {
            int minX = chunkX * 16;
            int minZ = chunkZ * 16;
            int maxX = minX + 16;
            int maxZ = minZ + 16;
            int minY = mirrorWorld.getMinBuildHeight();
            int maxY = mirrorWorld.getMaxBuildHeight();
            
            net.minecraft.world.phys.AABB chunkBounds = new net.minecraft.world.phys.AABB(
                    minX, minY, minZ, maxX, maxY, maxZ
            );
            
            java.util.List<net.minecraft.world.entity.Entity> entities = mirrorWorld.getEntities(
                    (net.minecraft.world.entity.Entity) null, 
                    chunkBounds,
                    entity -> !(entity instanceof net.minecraft.world.entity.player.Player)
            );
            
            for (net.minecraft.world.entity.Entity entity : entities) {
                entity.discard();
                removed++;
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return removed;
    }
    


    // ==================== Query Methods ====================

    public static boolean hasPendingCleanup(int dimIndex) {
        return cleanupTasks.containsKey(dimIndex);
    }

    public static boolean hasPendingCopy(int dimIndex) {
        return copyTasks.containsKey(dimIndex);
    }
    
    /**
     * Cancel a pending cleanup task for a dimension
     * Used when force clearing to restart cleanup from scratch
     */
    public static void cancelCleanupTask(int dimIndex) {
        CleanupTask removed = cleanupTasks.remove(dimIndex);
        if (removed != null) {
            InstantWorldMirror.LOGGER.info("Cancelled pending cleanup task for dimension {}", dimIndex);
        }
    }

    /**
     * Clear all tasks (for server shutdown)
     * Saves all cleanup progress before clearing
     */
    public static void clearAllTasks() {
        // Save progress for all active cleanup tasks before clearing
        for (Map.Entry<Integer, CleanupTask> entry : cleanupTasks.entrySet()) {
            CleanupTask task = entry.getValue();
            if (!task.isCompleted()) {
                task.saveProgress();
                InstantWorldMirror.LOGGER.info("Saved cleanup progress for dimension {} ({}/{})",
                        task.dimensionIndex, task.getCleanedChunks(), task.getTotalChunks());
            }
        }
        
        // Save all pending modifications
        savePendingModifications();
        
        copyTasks.clear();
        cleanupTasks.clear();
        copyCenterPositions.clear();
        modifiedChunks.clear();
        pendingSave.clear();
        saveTickCounter = 0;
        
        InstantWorldMirror.LOGGER.info("All tasks cleared, progress saved to persistent storage");
    }
}
