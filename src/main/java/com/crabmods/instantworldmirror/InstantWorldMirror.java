package com.crabmods.instantworldmirror;

import com.crabmods.instantworldmirror.entity.ModEntities;
import com.crabmods.instantworldmirror.network.ModNetworking;
import com.crabmods.instantworldmirror.registry.ModBlocks;
import com.crabmods.instantworldmirror.registry.ModChunkGenerators;
import com.crabmods.instantworldmirror.registry.ModCreativeTabs;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * InstantWorldMirror - World Mirrors
 * A Minecraft mod that allows players to enter a mirror copy of the overworld
 */
@Mod(InstantWorldMirror.MODID)
public class InstantWorldMirror {
    public static final String MODID = "instantworldmirror";
    public static final Logger LOGGER = LogUtils.getLogger();

    public InstantWorldMirror() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        // Register common setup event
        modEventBus.addListener(this::commonSetup);

        // Register items
        ModItems.ITEMS.register(modEventBus);

        // Register blocks and block entities
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.BLOCK_ENTITIES.register(modEventBus);

        // Register creative tabs
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        // Register chunk generators
        ModChunkGenerators.CHUNK_GENERATORS.register(modEventBus);

        // Register entities
        ModEntities.ENTITY_TYPES.register(modEventBus);

        // Register server events
        MinecraftForge.EVENT_BUS.register(this);

        // Register configuration
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MirrorConfig.SPEC);
        
        // Only register client config on client side
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, com.crabmods.instantworldmirror.client.ClientConfig.SPEC);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("InstantWorldMirror - Common Setup");
        
        // Register network packets
        ModNetworking.register();
        
        // Initialize dimension pool size from config
        event.enqueueWork(() -> {
            ModDimensions.updatePoolSizeFromConfig();
            DimensionPool.initialize();
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("InstantWorldMirror - Server Starting");
        
        // Re-initialize dimension pool on server start (in case config changed)
        ModDimensions.updatePoolSizeFromConfig();
        // Use server-aware initialization to restore cleanup states from persistent storage
        DimensionPool.initializeWithServer(event.getServer());
    }
}
