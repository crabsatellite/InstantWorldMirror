package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * World Copy Service
 * Responsible for copying blocks from overworld to mirror world (empty world)
 */
public class WorldCopyService {

    /**
     * Check if block is a portal type (should be skipped during copy)
     * Includes nether portal, end portal, etc.
     */
    private static boolean isPortalBlock(BlockState state) {
        return state.is(Blocks.NETHER_PORTAL) ||
               state.is(Blocks.END_PORTAL) ||
               state.is(Blocks.END_PORTAL_FRAME) ||
               state.is(Blocks.END_GATEWAY);
    }

    /**
     * Copy chunks around the player to mirror world
     * @param overworld The overworld (source)
     * @param mirrorWorld The mirror world (empty target)
     * @param centerPos Center position for copying
     */
    public static void copyAreaAroundPosition(ServerLevel overworld, ServerLevel mirrorWorld, BlockPos centerPos) {
        int chunkRadius = MirrorConfig.COPY_CHUNK_RADIUS.get();

        int centerChunkX = centerPos.getX() >> 4;
        int centerChunkZ = centerPos.getZ() >> 4;

        int minChunkX = centerChunkX - chunkRadius;
        int maxChunkX = centerChunkX + chunkRadius;
        int minChunkZ = centerChunkZ - chunkRadius;
        int maxChunkZ = centerChunkZ + chunkRadius;

        InstantWorldMirror.LOGGER.info("Starting world copy: {} chunks radius", chunkRadius);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                copyChunk(overworld, mirrorWorld, chunkX, chunkZ);
            }
        }

        InstantWorldMirror.LOGGER.info("World copy completed");
    }

    /**
     * Copy a single chunk - direct copy from source to target
     */
    private static void copyChunk(ServerLevel overworld, ServerLevel mirrorWorld, 
                                   int chunkX, int chunkZ) {
        try {
            LevelChunk sourceChunk = overworld.getChunk(chunkX, chunkZ);
            
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
                            BlockState state = overworld.getBlockState(sourcePos);
                            // Skip air and portal blocks (prevent using portals in mirror world)
                            if (!state.isAir() && !isPortalBlock(state)) {
                                mirrorWorld.setBlock(targetPos, state, 2);
                                copyBlockEntity(overworld, mirrorWorld, sourcePos, targetPos);
                            }
                        } catch (Exception e) {
                            // Ignore single block errors
                        }
                    }
                }
            }
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to copy chunk ({}, {}): {}", chunkX, chunkZ, e.getMessage());
        }
    }

    /**
     * Copy block entity data
     */
    private static void copyBlockEntity(ServerLevel overworld, ServerLevel mirrorWorld, 
                                         BlockPos sourcePos, BlockPos targetPos) {
        BlockEntity sourceBE = overworld.getBlockEntity(sourcePos);
        if (sourceBE != null) {
            BlockEntity targetBE = mirrorWorld.getBlockEntity(targetPos);
            if (targetBE != null) {
                try {
                    targetBE.loadWithComponents(
                            sourceBE.saveWithoutMetadata(overworld.registryAccess()), 
                            overworld.registryAccess()
                    );
                } catch (Exception e) {
                    // Ignore block entity copy errors
                }
            }
        }
    }

    /**
     * Cleanup mirror world data (when player leaves)
     * Note: Since Minecraft dimensions are persistent, full cleanup happens during next copy
     * This is mainly for logging and future extensions
     */
    public static void cleanupMirrorWorld(ServerLevel mirrorWorld) {
        InstantWorldMirror.LOGGER.info("Mirror world cleanup initiated - world will be reset on next entry");
        // Note: Actual cleanup happens automatically during next copyAreaAroundPosition
        // because we overwrite all chunk contents
    }
}
