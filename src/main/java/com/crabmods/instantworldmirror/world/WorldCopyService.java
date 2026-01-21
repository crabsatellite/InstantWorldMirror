package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World Copy Service
 * Responsible for copying blocks from overworld to mirror world
 * 
 * Design Philosophy:
 * - Each session has its own dedicated mirror dimension
 * - Copy is ASYNCHRONOUS - spread across ticks to prevent lag
 * - Cleanup is also asynchronous
 * - When cleanup completes, dimension is returned to pool
 * 
 * Configurable via MirrorConfig:
 * - copyChunksPerTick: chunks processed per tick during copy
 * - cleanupChunksPerTick: chunks processed per tick during cleanup
 */
public class WorldCopyService {

    // Track cleanup tasks per dimension: dimensionIndex -> CleanupTask
    private static final Map<Integer, CleanupTask> cleanupTasks = new ConcurrentHashMap<>();
    
    // Track copy tasks per dimension: dimensionIndex -> CopyTask
    private static final Map<Integer, CopyTask> copyTasks = new ConcurrentHashMap<>();

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
        public int getTotalBlocksCopied() { return totalBlocksCopied; }
    }
    
    public static class CleanupTask {
        public final BlockPos centerPos;
        public final int chunkRadius;
        public final int dimensionIndex;
        
        private int currentChunkX;
        private int currentChunkZ;
        private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        private boolean started = false;
        private boolean completed = false;
        private int totalBlocksCleared = 0;

        public CleanupTask(BlockPos centerPos, int chunkRadius, int dimensionIndex) {
            this.centerPos = centerPos;
            this.chunkRadius = chunkRadius;
            this.dimensionIndex = dimensionIndex;
            
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
        
        public int getCleanedChunks() {
            if (!started) return 0;
            if (completed) return getTotalChunks();
            int width = maxChunkX - minChunkX + 1;
            return (currentChunkZ - minChunkZ) * width + (currentChunkX - minChunkX);
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
        
        public void addBlocksCleared(int count) { totalBlocksCleared += count; }
        public boolean isCompleted() { return completed; }
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
        
        CopyTask task = new CopyTask(
                session.getSessionId(),
                session.getSourcePosition(),
                chunkRadius,
                sourceWorld.dimension(),
                dimIndex
        );
        
        copyTasks.put(dimIndex, task);
        
        InstantWorldMirror.LOGGER.info("Queued world copy for session {} to dimension {} - {} chunks total",
                session.getSessionId(), dimIndex, task.getTotalChunks());
    }

    /**
     * Process all copy queues - call from server tick
     */
    public static void processCopyQueues(MinecraftServer server) {
        int chunksPerTick = MirrorConfig.COPY_CHUNKS_PER_TICK.get();
        
        for (Map.Entry<Integer, CopyTask> entry : copyTasks.entrySet()) {
            int dimIndex = entry.getKey();
            CopyTask task = entry.getValue();
            
            if (task.isCompleted()) continue;
            
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
                }
            }
            
            // Check completion
            if (task.isCompleted()) {
                copyTasks.remove(dimIndex);
                
                // Notify session that copy is complete
                MirrorWorldManager.getSession(task.sessionId).ifPresent(session -> {
                    session.markCopyComplete();
                });
                
                InstantWorldMirror.LOGGER.info("World copy completed for session {} in dimension {} - {} blocks copied",
                        task.sessionId, dimIndex, task.getTotalBlocksCopied());
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
            LevelChunk sourceChunk = sourceWorld.getChunk(chunkX, chunkZ);

            int minY = mirrorWorld.getMinBuildHeight();
            int maxY = mirrorWorld.getMaxBuildHeight();

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = chunkX * 16 + localX;
                    int worldZ = chunkZ * 16 + localZ;

                    for (int y = minY; y < maxY; y++) {
                        BlockPos sourcePos = new BlockPos(worldX, y, worldZ);
                        BlockPos targetPos = new BlockPos(worldX, y, worldZ);

                        try {
                            BlockState state = sourceWorld.getBlockState(sourcePos);
                            if (!state.isAir() && !isPortalBlock(state)) {
                                mirrorWorld.setBlock(targetPos, state, 2);
                                copyBlockEntity(sourceWorld, mirrorWorld, sourcePos, targetPos);
                                blocksCopied++;
                            }
                        } catch (Exception e) {
                            // Ignore single block errors
                        }
                    }
                }
            }
            
            // Copy entities in this chunk if enabled
            if (MirrorConfig.COPY_ENTITIES.get()) {
                copyEntitiesInChunk(sourceWorld, mirrorWorld, chunkX, chunkZ);
            }
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to copy chunk ({}, {}): {}", chunkX, chunkZ, e.getMessage());
        }
        
        return blocksCopied;
    }

    /**
     * Copy entities in a chunk (mobs, animals, etc.)
     */
    private static void copyEntitiesInChunk(ServerLevel sourceWorld, ServerLevel mirrorWorld, 
                                             int chunkX, int chunkZ) {
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
            InstantWorldMirror.LOGGER.debug("Error copying entities in chunk ({}, {}): {}", 
                    chunkX, chunkZ, e.getMessage());
        }
    }

    private static void copyBlockEntity(ServerLevel sourceWorld, ServerLevel mirrorWorld,
                                         BlockPos sourcePos, BlockPos targetPos) {
        BlockEntity sourceBE = sourceWorld.getBlockEntity(sourcePos);
        if (sourceBE != null) {
            BlockEntity targetBE = mirrorWorld.getBlockEntity(targetPos);
            if (targetBE != null) {
                try {
                    targetBE.loadWithComponents(
                            sourceBE.saveWithoutMetadata(sourceWorld.registryAccess()),
                            sourceWorld.registryAccess()
                    );
                } catch (Exception e) {
                    // Ignore block entity copy errors
                }
            }
        }
    }

    // ==================== World Cleanup (Asynchronous) ====================

    /**
     * Queue cleanup for a specific dimension
     */
    public static void cleanupMirrorWorld(ServerLevel mirrorWorld, BlockPos centerPos, int dimensionIndex) {
        int chunkRadius = MirrorConfig.COPY_CHUNK_RADIUS.get();
        
        CleanupTask task = new CleanupTask(centerPos, chunkRadius, dimensionIndex);
        cleanupTasks.put(dimensionIndex, task);
        
        InstantWorldMirror.LOGGER.info("Queued cleanup for dimension {} around ({}, {}) - {} chunks",
                dimensionIndex, centerPos.getX(), centerPos.getZ(), task.getTotalChunks());
    }

    /**
     * Process all cleanup queues - call from server tick
     */
    public static void processCleanupQueues(MinecraftServer server) {
        int chunksPerTick = MirrorConfig.CLEANUP_CHUNKS_PER_TICK.get();
        
        for (Map.Entry<Integer, CleanupTask> entry : cleanupTasks.entrySet()) {
            int dimIndex = entry.getKey();
            CleanupTask task = entry.getValue();
            
            if (task.isCompleted()) continue;
            
            ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, dimIndex);
            if (mirrorWorld == null) continue;
            
            // Process chunks per tick (configurable)
            for (int i = 0; i < chunksPerTick && !task.isCompleted(); i++) {
                int[] chunkCoords = task.getNextChunk();
                if (chunkCoords != null) {
                    int blocksCleared = clearChunk(mirrorWorld, chunkCoords[0], chunkCoords[1]);
                    task.addBlocksCleared(blocksCleared);
                }
            }
            
            // Log progress every 10 chunks
            if (task.getCleanedChunks() % 10 == 0) {
                InstantWorldMirror.LOGGER.debug("Cleanup progress for dim {}: {}/{} chunks",
                        dimIndex, task.getCleanedChunks(), task.getTotalChunks());
            }
            
            // Check completion
            if (task.isCompleted()) {
                cleanupTasks.remove(dimIndex);
                
                // Mark dimension as available again
                DimensionPool.markDimensionAvailable(dimIndex);
                
                InstantWorldMirror.LOGGER.info("Cleanup completed for dimension {}, now available for new sessions",
                        dimIndex);
            }
        }
    }

    private static int clearChunk(ServerLevel mirrorWorld, int chunkX, int chunkZ) {
        int blocksCleared = 0;

        try {
            int minY = mirrorWorld.getMinBuildHeight();
            int maxY = mirrorWorld.getMaxBuildHeight();

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = chunkX * 16 + localX;
                    int worldZ = chunkZ * 16 + localZ;

                    for (int y = minY; y < maxY; y++) {
                        BlockPos pos = new BlockPos(worldX, y, worldZ);

                        try {
                            BlockState currentState = mirrorWorld.getBlockState(pos);
                            if (!currentState.isAir()) {
                                BlockEntity be = mirrorWorld.getBlockEntity(pos);
                                if (be != null) {
                                    mirrorWorld.removeBlockEntity(pos);
                                }
                                mirrorWorld.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                                blocksCleared++;
                            }
                        } catch (Exception e) {
                            // Ignore single block errors
                        }
                    }
                }
            }
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to clear chunk ({}, {}): {}", chunkX, chunkZ, e.getMessage());
        }

        return blocksCleared;
    }

    // ==================== Query Methods ====================

    public static boolean hasPendingCleanup(int dimIndex) {
        return cleanupTasks.containsKey(dimIndex);
    }

    public static boolean hasPendingCopy(int dimIndex) {
        return copyTasks.containsKey(dimIndex);
    }

    /**
     * Clear all tasks (for server shutdown)
     */
    public static void clearAllTasks() {
        copyTasks.clear();
        cleanupTasks.clear();
    }
}
