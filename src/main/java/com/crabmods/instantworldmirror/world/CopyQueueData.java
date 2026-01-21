package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Persistent storage for world copy tasks
 * Copies are spread across ticks to prevent server lag
 */
public class CopyQueueData extends SavedData {

    private static final String DATA_NAME = InstantWorldMirror.MODID + "_copy_queue";
    
    // Queue of pending copy tasks
    private final Queue<CopyTask> pendingTasks = new ConcurrentLinkedQueue<>();

    /**
     * Copy task with progress tracking
     * Copies one chunk at a time to prevent lag
     */
    public static class CopyTask {
        private final UUID sessionId;
        private final BlockPos centerPos;
        private final int chunkRadius;
        private final String sourceDimension;
        
        // Progress tracking
        private int currentChunkX;
        private int currentChunkZ;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private boolean started = false;
        private boolean completed = false;
        private int totalBlocksCopied = 0;

        public CopyTask(UUID sessionId, BlockPos centerPos, int chunkRadius, ResourceKey<Level> sourceDimension) {
            this.sessionId = sessionId;
            this.centerPos = centerPos;
            this.chunkRadius = chunkRadius;
            this.sourceDimension = sourceDimension.location().toString();
            
            int centerChunkX = centerPos.getX() >> 4;
            int centerChunkZ = centerPos.getZ() >> 4;
            
            this.minChunkX = centerChunkX - chunkRadius;
            this.maxChunkX = centerChunkX + chunkRadius;
            this.minChunkZ = centerChunkZ - chunkRadius;
            this.maxChunkZ = centerChunkZ + chunkRadius;
            
            this.currentChunkX = minChunkX;
            this.currentChunkZ = minChunkZ;
        }

        public UUID sessionId() { return sessionId; }
        public BlockPos centerPos() { return centerPos; }
        public int chunkRadius() { return chunkRadius; }
        public String sourceDimension() { return sourceDimension; }
        public boolean isCompleted() { return completed; }
        public int getTotalBlocksCopied() { return totalBlocksCopied; }
        
        public void addBlocksCopied(int count) {
            totalBlocksCopied += count;
        }
        
        /**
         * Get total number of chunks to copy
         */
        public int getTotalChunks() {
            int width = maxChunkX - minChunkX + 1;
            int height = maxChunkZ - minChunkZ + 1;
            return width * height;
        }
        
        /**
         * Get number of chunks already copied
         */
        public int getCopiedChunks() {
            if (!started) return 0;
            if (completed) return getTotalChunks();
            
            int width = maxChunkX - minChunkX + 1;
            int rowsCopied = currentChunkZ - minChunkZ;
            int colsCopied = currentChunkX - minChunkX;
            return rowsCopied * width + colsCopied;
        }
        
        /**
         * Get copy progress percentage
         */
        public int getProgressPercent() {
            int total = getTotalChunks();
            if (total == 0) return 100;
            return (getCopiedChunks() * 100) / total;
        }
        
        /**
         * Get next chunk coordinates to copy
         * @return int[2] with {chunkX, chunkZ}, or null if completed
         */
        public int[] getNextChunk() {
            if (completed) return null;
            
            started = true;
            int[] result = new int[]{currentChunkX, currentChunkZ};
            
            // Move to next chunk
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

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("sessionId", sessionId);
            tag.putInt("x", centerPos.getX());
            tag.putInt("y", centerPos.getY());
            tag.putInt("z", centerPos.getZ());
            tag.putInt("radius", chunkRadius);
            tag.putString("sourceDim", sourceDimension);
            tag.putInt("currentX", currentChunkX);
            tag.putInt("currentZ", currentChunkZ);
            tag.putBoolean("started", started);
            tag.putBoolean("completed", completed);
            tag.putInt("blocksCopied", totalBlocksCopied);
            return tag;
        }

        public static CopyTask load(CompoundTag tag) {
            UUID sessionId = tag.getUUID("sessionId");
            BlockPos pos = new BlockPos(
                    tag.getInt("x"),
                    tag.getInt("y"),
                    tag.getInt("z")
            );
            int radius = tag.getInt("radius");
            String dimStr = tag.getString("sourceDim");
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimStr));
            
            CopyTask task = new CopyTask(sessionId, pos, radius, dim);
            
            // Restore progress
            task.currentChunkX = tag.getInt("currentX");
            task.currentChunkZ = tag.getInt("currentZ");
            task.started = tag.getBoolean("started");
            task.completed = tag.getBoolean("completed");
            task.totalBlocksCopied = tag.getInt("blocksCopied");
            
            return task;
        }
    }

    public CopyQueueData() {
    }

    /**
     * Load from NBT
     */
    public static CopyQueueData load(CompoundTag tag, HolderLookup.Provider provider) {
        CopyQueueData data = new CopyQueueData();
        
        if (tag.contains("tasks")) {
            ListTag taskList = tag.getList("tasks", 10); // 10 = CompoundTag type
            for (int i = 0; i < taskList.size(); i++) {
                CompoundTag taskTag = taskList.getCompound(i);
                data.pendingTasks.add(CopyTask.load(taskTag));
            }
            InstantWorldMirror.LOGGER.info("Loaded {} pending copy tasks from saved data", 
                    data.pendingTasks.size());
        }
        
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag taskList = new ListTag();
        for (CopyTask task : pendingTasks) {
            taskList.add(task.save());
        }
        tag.put("tasks", taskList);
        
        if (!pendingTasks.isEmpty()) {
            InstantWorldMirror.LOGGER.info("Saved {} pending copy tasks to world data", 
                    pendingTasks.size());
        }
        
        return tag;
    }

    /**
     * Add a copy task
     */
    public void addTask(CopyTask task) {
        pendingTasks.add(task);
        setDirty();
    }

    /**
     * Get the current task (without removing)
     */
    public CopyTask getCurrentTask() {
        return pendingTasks.peek();
    }

    /**
     * Get task by session ID
     */
    public CopyTask getTaskBySession(UUID sessionId) {
        for (CopyTask task : pendingTasks) {
            if (task.sessionId().equals(sessionId)) {
                return task;
            }
        }
        return null;
    }

    /**
     * Remove the current task (should only be called when task is complete)
     */
    public void completeCurrentTask() {
        pendingTasks.poll();
        setDirty();
    }

    /**
     * Mark that progress was made (triggers save)
     */
    public void markProgress() {
        setDirty();
    }

    /**
     * Check if there are pending tasks
     */
    public boolean hasTasks() {
        return !pendingTasks.isEmpty();
    }

    /**
     * Get number of pending tasks
     */
    public int getTaskCount() {
        return pendingTasks.size();
    }

    /**
     * Get or create instance from server level
     */
    public static CopyQueueData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        CopyQueueData::new,
                        CopyQueueData::load
                ),
                DATA_NAME
        );
    }
}
