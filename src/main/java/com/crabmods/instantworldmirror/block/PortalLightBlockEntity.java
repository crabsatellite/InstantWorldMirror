package com.crabmods.instantworldmirror.block;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * BlockEntity for PortalLightBlock.
 * Tracks an associated MirrorPortalEntity and removes itself when the portal no longer exists.
 * 
 * Edge case handling:
 * - If portalEntityId is null after initialization, removes self after a grace period
 * - If portal entity cannot be found for extended time, removes self
 */
public class PortalLightBlockEntity extends BlockEntity {
    
    private UUID portalEntityId = null;
    private int checkCounter = 0;
    private static final int CHECK_INTERVAL = 10; // Check every 10 ticks (0.5 seconds)
    
    // Orphan detection - tracks how long the block has been without a valid portal
    private int orphanTickCounter = 0;
    private static final int ORPHAN_GRACE_PERIOD = 100; // 5 seconds grace period for initial setup
    private static final int MAX_ORPHAN_TICKS = 600; // 30 seconds max without valid portal before auto-removal
    
    public PortalLightBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PORTAL_LIGHT_BLOCK_ENTITY.get(), pos, state);
    }
    
    /**
     * Set the portal entity this light block is bound to
     */
    public void setPortalEntityId(UUID portalId) {
        this.portalEntityId = portalId;
        this.orphanTickCounter = 0; // Reset orphan counter when portal is set
        setChanged();
    }
    
    /**
     * Get the bound portal entity ID
     */
    public UUID getPortalEntityId() {
        return portalEntityId;
    }
    
    /**
     * Server tick - check if the bound portal entity still exists
     * Includes safety mechanisms for edge case cleanup:
     * 1. Periodic portal existence check
     * 2. Orphan detection with grace period
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PortalLightBlockEntity blockEntity) {
        blockEntity.checkCounter++;
        
        // Only check periodically to reduce performance impact
        if (blockEntity.checkCounter < CHECK_INTERVAL) {
            return;
        }
        blockEntity.checkCounter = 0;
        
        // If no portal is bound
        if (blockEntity.portalEntityId == null) {
            blockEntity.orphanTickCounter += CHECK_INTERVAL;
            
            // Grace period for initial setup (5 seconds)
            if (blockEntity.orphanTickCounter < ORPHAN_GRACE_PERIOD) {
                // Still in grace period, wait for portal to be set
                return;
            }
            
            // Past grace period with no portal bound - definitely orphan
            InstantWorldMirror.LOGGER.debug("Portal light block at {} has no bound portal after grace period, removing", pos);
            removeSelf(level, pos);
            return;
        }
        
        // Check if the portal entity still exists
        if (level instanceof ServerLevel serverLevel) {
            Entity portalEntity = serverLevel.getEntity(blockEntity.portalEntityId);
            if (portalEntity == null || !portalEntity.isAlive()) {
                // Portal entity not found - could be temporary (chunk unloaded) or permanent (entity removed)
                blockEntity.orphanTickCounter += CHECK_INTERVAL;
                
                // Allow some tolerance for temporary absence (e.g., chunk unloaded)
                if (blockEntity.orphanTickCounter >= MAX_ORPHAN_TICKS) {
                    // Extended absence - portal is gone
                    InstantWorldMirror.LOGGER.debug("Portal entity {} no longer exists for {} ticks, removing light block at {}", 
                            blockEntity.portalEntityId, blockEntity.orphanTickCounter, pos);
                    removeSelf(level, pos);
                }
            } else {
                // Portal exists and is alive - reset orphan counter
                blockEntity.orphanTickCounter = 0;
            }
        }
    }
    
    /**
     * Remove this block from the world
     */
    private static void removeSelf(Level level, BlockPos pos) {
        // If waterlogged, restore water instead of air
        BlockState currentState = level.getBlockState(pos);
        boolean wasWaterlogged = currentState.hasProperty(PortalLightBlock.WATERLOGGED)
                && currentState.getValue(PortalLightBlock.WATERLOGGED);
        BlockState replacement = wasWaterlogged
                ? Blocks.WATER.defaultBlockState()
                : Blocks.AIR.defaultBlockState();
        level.setBlockAndUpdate(pos, replacement);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(pos);
        }
    }
    
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (portalEntityId != null) {
            tag.putUUID("PortalEntityId", portalEntityId);
        }
        tag.putInt("OrphanTickCounter", orphanTickCounter);
    }
    
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("PortalEntityId")) {
            portalEntityId = tag.getUUID("PortalEntityId");
        }
        if (tag.contains("OrphanTickCounter")) {
            orphanTickCounter = tag.getInt("OrphanTickCounter");
        }
    }
}
