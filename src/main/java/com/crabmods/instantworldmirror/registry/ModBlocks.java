package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.block.PortalLightBlock;
import com.crabmods.instantworldmirror.block.PortalLightBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block and BlockEntity Registry Class
 */
public class ModBlocks {
    // Block registry
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, InstantWorldMirror.MODID);
    
    // Block Entity registry
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = 
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, InstantWorldMirror.MODID);
    
    // Portal Light Block - invisible light source that tracks a portal entity
    public static final RegistryObject<PortalLightBlock> PORTAL_LIGHT_BLOCK = BLOCKS.register(
            "portal_light",
            () -> new PortalLightBlock(BlockBehaviour.Properties.of()
                    .noCollission()
                    .noOcclusion()
                    .replaceable()
                    .noLootTable()
                    .lightLevel(state -> 15) // Max light level
            )
    );
    
    // Portal Light Block Entity
    public static final RegistryObject<BlockEntityType<PortalLightBlockEntity>> PORTAL_LIGHT_BLOCK_ENTITY = 
            BLOCK_ENTITIES.register("portal_light",
                    () -> BlockEntityType.Builder.of(
                            PortalLightBlockEntity::new,
                            PORTAL_LIGHT_BLOCK.get()
                    ).build(null)
            );
}
