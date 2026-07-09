package com.crabmods.instantworldmirror;

import com.crabmods.instantworldmirror.entity.ModEntities;
import com.crabmods.instantworldmirror.registry.ModBlocks;
import com.crabmods.instantworldmirror.registry.ModChunkGenerators;
import com.crabmods.instantworldmirror.registry.ModCreativeTabs;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * InstantWorldMirror - World Mirrors
 * A Minecraft mod that allows players to enter a mirror copy of the overworld
 */
@Mod(InstantWorldMirror.MODID)
public class InstantWorldMirror {
    public static final String MODID = "instantworldmirror";
    public static final Logger LOGGER = LogUtils.getLogger();

    public InstantWorldMirror(IEventBus modEventBus, ModContainer modContainer) {
        MirrorConfigMigration.migrateCommonConfig();

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
        NeoForge.EVENT_BUS.register(this);

        // Register configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, MirrorConfig.SPEC);
        
        // Only register client config on client side
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, com.crabmods.instantworldmirror.client.ClientConfig.SPEC);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("InstantWorldMirror - Common Setup");
        
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
        MirrorConfig.refreshServerConfigSnapshot();
        ModDimensions.updatePoolSizeFromConfig();
        // Use server-aware initialization to restore cleanup states from persistent storage
        DimensionPool.initializeWithServer(event.getServer());
        PersistentMirrorManager.recoverUnreadyPersistentMirrors(event.getServer());
    }
}
