package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Persistent storage for cleanup tasks
 * Ensures cleanup tasks survive server restarts
 * 
 * Uses chunk-based cleanup for better performance:
 * - Each task tracks which chunk to clean next
 * - One chunk is cleaned per tick to prevent lag spikes
 */
public class CleanupQueueData extends SavedData {

    private static final String DATA_NAME = InstantWorldMirror.MODID + "_cleanup_queue";
    
    // Queue of pending cleanup tasks
    private final Queue<CleanupTask> pendingTasks = new ConcurrentLinkedQueue<>();

    /**
     * Cleanup task with progress tracking
     * Cleans one chunk at a time to prevent lag
     */
    public static class CleanupTask {
        private final BlockPos centerPos;
        private final int chunkRadius;
        
        // Progress tracking
        private int currentChunkX;
        private int currentChunkZ;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private boolean started = false;
        private boolean completed = false;

        public CleanupTask(BlockPos centerPos, int chunkRadius) {
            this.centerPos = centerPos;
            this.chunkRadius = chunkRadius;
            
            int centerChunkX = centerPos.getX() >> 4;
            int centerChunkZ = centerPos.getZ() >> 4;
            
            this.minChunkX = centerChunkX - chunkRadius;
            this.maxChunkX = centerChunkX + chunkRadius;
            this.minChunkZ = centerChunkZ - chunkRadius;
            this.maxChunkZ = centerChunkZ + chunkRadius;
            
            this.currentChunkX = minChunkX;
            this.currentChunkZ = minChunkZ;
        }

        public BlockPos centerPos() { return centerPos; }
        public int chunkRadius() { return chunkRadius; }
        public boolean isCompleted() { return completed; }
        
        /**
         * Get total number of chunks to clean
         */
        public int getTotalChunks() {
            int width = maxChunkX - minChunkX + 1;
            int height = maxChunkZ - minChunkZ + 1;
            return width * height;
        }
        
        /**
         * Get number of chunks already cleaned
         */
        public int getCleanedChunks() {
            if (!started) return 0;
            if (completed) return getTotalChunks();
            
            int width = maxChunkX - minChunkX + 1;
            int rowsCleaned = currentChunkZ - minChunkZ;
            int colsCleaned = currentChunkX - minChunkX;
            return rowsCleaned * width + colsCleaned;
        }
        
        /**
         * Get next chunk coordinates to clean
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
            tag.putInt("x", centerPos.getX());
            tag.putInt("y", centerPos.getY());
            tag.putInt("z", centerPos.getZ());
            tag.putInt("radius", chunkRadius);
            tag.putInt("currentX", currentChunkX);
            tag.putInt("currentZ", currentChunkZ);
            tag.putBoolean("started", started);
            tag.putBoolean("completed", completed);
            return tag;
        }

        public static CleanupTask load(CompoundTag tag) {
            BlockPos pos = new BlockPos(
                    tag.getInt("x"),
                    tag.getInt("y"),
                    tag.getInt("z")
            );
            int radius = tag.getInt("radius");
            CleanupTask task = new CleanupTask(pos, radius);
            
            // Restore progress
            task.currentChunkX = tag.getInt("currentX");
            task.currentChunkZ = tag.getInt("currentZ");
            task.started = tag.getBoolean("started");
            task.completed = tag.getBoolean("completed");
            
            return task;
        }
    }

    public CleanupQueueData() {
    }

    /**
     * Load from NBT
     */
    public static CleanupQueueData load(CompoundTag tag) {
        CleanupQueueData data = new CleanupQueueData();
        
        if (tag.contains("tasks")) {
            ListTag taskList = tag.getList("tasks", 10); // 10 = CompoundTag type
            for (int i = 0; i < taskList.size(); i++) {
                CompoundTag taskTag = taskList.getCompound(i);
                data.pendingTasks.add(CleanupTask.load(taskTag));
            }
            InstantWorldMirror.LOGGER.info("Loaded {} pending cleanup tasks from saved data", 
                    data.pendingTasks.size());
        }
        
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag taskList = new ListTag();
        for (CleanupTask task : pendingTasks) {
            taskList.add(task.save());
        }
        tag.put("tasks", taskList);
        
        if (!pendingTasks.isEmpty()) {
            InstantWorldMirror.LOGGER.info("Saved {} pending cleanup tasks to world data", 
                    pendingTasks.size());
        }
        
        return tag;
    }

    /**
     * Add a cleanup task
     */
    public void addTask(CleanupTask task) {
        pendingTasks.add(task);
        setDirty(); // Mark for saving
    }

    /**
     * Get the current task (without removing)
     * Tasks are only removed when completed
     */
    public CleanupTask getCurrentTask() {
        return pendingTasks.peek();
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
     * Peek at the next task without removing
     */
    public CleanupTask peekTask() {
        return pendingTasks.peek();
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
     * Clear all tasks
     */
    public void clearTasks() {
        pendingTasks.clear();
        setDirty();
    }

    /**
     * Get or create instance from server level
     */
    public static CleanupQueueData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CleanupQueueData::load,
                CleanupQueueData::new,
                DATA_NAME
        );
    }
}
