package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Handles registration of network packets
 */
@EventBusSubscriber(modid = InstantWorldMirror.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {
    
    public static final String PROTOCOL_VERSION = "1";
    
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(InstantWorldMirror.MODID)
                .versioned(PROTOCOL_VERSION);
        
        // Register client-bound packets
        registrar.playToClient(
                SyncMirrorEffectsPacket.TYPE,
                SyncMirrorEffectsPacket.STREAM_CODEC,
                SyncMirrorEffectsPacket::handle
        );
        
        registrar.playToClient(
                ClearMirrorEffectsPacket.TYPE,
                ClearMirrorEffectsPacket.STREAM_CODEC,
                ClearMirrorEffectsPacket::handle
        );
        
        registrar.playToClient(
                SyncCooldownPacket.TYPE,
                SyncCooldownPacket.STREAM_CODEC,
                SyncCooldownPacket::handle
        );
        
        InstantWorldMirror.LOGGER.info("Registered network packets");
    }
}
