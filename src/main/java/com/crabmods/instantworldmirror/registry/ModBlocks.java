package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.block.PortalLightBlock;
import com.crabmods.instantworldmirror.block.PortalLightBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block and BlockEntity Registry Class
 */
public class ModBlocks {
    // Block registry
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(InstantWorldMirror.MODID);
    
    // Block Entity registry
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = 
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, InstantWorldMirror.MODID);
    
    // Portal Light Block - invisible light source that tracks a portal entity
    public static final DeferredBlock<PortalLightBlock> PORTAL_LIGHT_BLOCK = BLOCKS.register(
            "portal_light",
            () -> new PortalLightBlock(BlockBehaviour.Properties.of()
                    .noCollission()
                    .noOcclusion()
                    .air()
                    .lightLevel(state -> 15) // Max light level
                    .noLootTable()
            )
    );
    
    // Portal Light Block Entity
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PortalLightBlockEntity>> PORTAL_LIGHT_BLOCK_ENTITY = 
            BLOCK_ENTITIES.register("portal_light",
                    () -> BlockEntityType.Builder.of(
                            PortalLightBlockEntity::new,
                            PORTAL_LIGHT_BLOCK.get()
                    ).build(null)
            );
}
