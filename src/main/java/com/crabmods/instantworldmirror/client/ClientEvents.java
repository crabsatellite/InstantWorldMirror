package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.renderer.MirrorPortalRenderer;
import com.crabmods.instantworldmirror.entity.ModEntities;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

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
        // Register cooldown bar decorators for mirror items
        event.register(ModItems.DIMENSION_MIRROR.get(), new CooldownItemDecorator());
        event.register(ModItems.HEAVEN_MIRROR.get(), new CooldownItemDecorator());
        event.register(ModItems.FIRST_DREAM_MIRROR.get(), new CooldownItemDecorator());
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

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof DimensionMirrorItem)) {
            return;
        }
        long remainingMillis = ClientCooldownTracker.getRemainingCooldownMillis();
        if (remainingMillis <= 0) {
            return;
        }
        event.getToolTip().add(Component.translatable("message.instantworldmirror.mirror_use_cooldown",
                (int) Math.ceil(remainingMillis / 1000.0)));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BlockEntityCompatibilityGate.runIfEnabled();
        MirrorConfigUiGate.runIfEnabled();
        MirrorConfigNetworkGate.runIfEnabled();
    }
}
