package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.model.MirrorPortalModel;
import com.crabmods.instantworldmirror.client.renderer.MirrorPortalRenderer;
import com.crabmods.instantworldmirror.entity.ModEntities;
import com.crabmods.instantworldmirror.registry.ModItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client-side MOD bus event handling.
 * Handles renderer registration, HUD overlays, and item decorators.
 */
@EventBusSubscriber(modid = InstantWorldMirror.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    /**
     * Register entity renderers
     */
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MIRROR_PORTAL.get(), MirrorPortalRenderer::new);
        InstantWorldMirror.LOGGER.info("Mirror Portal renderer registered");
    }
    
    /**
     * Register model layers for entity models
     */
    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(MirrorPortalModel.LAYER_LOCATION, MirrorPortalModel::createBodyLayer);
        InstantWorldMirror.LOGGER.info("Mirror Portal model layer registered");
    }
    
    /**
     * Register HUD overlays
     */
    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        // Register cooldown HUD above the hotbar
        event.registerAbove(
                VanillaGuiLayers.HOTBAR, 
                CooldownHudOverlay.OVERLAY_ID, 
                new CooldownHudOverlay()
        );
        InstantWorldMirror.LOGGER.info("Cooldown HUD overlay registered");
    }
    
    /**
     * Register item decorators (cooldown bar on items)
     */
    @SubscribeEvent
    public static void registerItemDecorations(RegisterItemDecorationsEvent event) {
        // Register cooldown bar decorator for Dimension Mirror
        event.register(ModItems.DIMENSION_MIRROR.get(), new CooldownItemDecorator());
        InstantWorldMirror.LOGGER.info("Cooldown item decorator registered");
    }
}

/**
 * Client-side event handling (GAME bus) for network events
 */
@EventBusSubscriber(modid = InstantWorldMirror.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
class ClientGameEvents {
    
    /**
     * Clear cached dimension effects and cooldown tracker when disconnecting from server
     */
    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        MirrorDimensionEffectsManager.clearAll();
        ClientCooldownTracker.clear();
        InstantWorldMirror.LOGGER.debug("Client disconnected, cleared mirror dimension effects cache and cooldown tracker");
    }
}
