package com.crabmods.instantworldmirror.block;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
 */
public class PortalLightBlockEntity extends BlockEntity {
    
    private UUID portalEntityId = null;
    private int checkCounter = 0;
    private static final int CHECK_INTERVAL = 10; // Check every 10 ticks (0.5 seconds)
    
    public PortalLightBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PORTAL_LIGHT_BLOCK_ENTITY.get(), pos, state);
    }
    
    /**
     * Set the portal entity this light block is bound to
     */
    public void setPortalEntityId(UUID portalId) {
        this.portalEntityId = portalId;
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
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PortalLightBlockEntity blockEntity) {
        blockEntity.checkCounter++;
        
        // Only check periodically to reduce performance impact
        if (blockEntity.checkCounter < CHECK_INTERVAL) {
            return;
        }
        blockEntity.checkCounter = 0;
        
        // If no portal is bound, remove self immediately
        if (blockEntity.portalEntityId == null) {
            removeSelf(level, pos);
            return;
        }
        
        // Check if the portal entity still exists
        if (level instanceof ServerLevel serverLevel) {
            Entity portalEntity = serverLevel.getEntity(blockEntity.portalEntityId);
            if (portalEntity == null || !portalEntity.isAlive()) {
                // Portal entity no longer exists, remove this light block
                InstantWorldMirror.LOGGER.debug("Portal entity {} no longer exists, removing light block at {}", 
                        blockEntity.portalEntityId, pos);
                removeSelf(level, pos);
            }
        }
    }
    
    /**
     * Remove this block from the world
     */
    private static void removeSelf(Level level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(pos);
        }
    }
    
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (portalEntityId != null) {
            tag.putUUID("PortalEntityId", portalEntityId);
        }
    }
    
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("PortalEntityId")) {
            portalEntityId = tag.getUUID("PortalEntityId");
        }
    }
}
