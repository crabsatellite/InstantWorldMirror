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
    
    public static final String PROTOCOL_VERSION = "2";
    
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

        registrar.playToClient(
                PersistentMirrorMenuPacket.TYPE,
                PersistentMirrorMenuPacket.STREAM_CODEC,
                PersistentMirrorMenuPacket::handle
        );

        registrar.playToClient(
                MirrorConfigMenuPacket.TYPE,
                MirrorConfigMenuPacket.STREAM_CODEC,
                MirrorConfigMenuPacket::handle
        );

        registrar.playToClient(
                MirrorConfigSaveResultPacket.TYPE,
                MirrorConfigSaveResultPacket.STREAM_CODEC,
                MirrorConfigSaveResultPacket::handle
        );
        
        // Register server-bound packets
        registrar.playToServer(
                TeleportToSpawnPacket.TYPE,
                TeleportToSpawnPacket.STREAM_CODEC,
                TeleportToSpawnPacket::handle
        );

        registrar.playToServer(
                SaveMirrorConfigPacket.TYPE,
                SaveMirrorConfigPacket.STREAM_CODEC,
                SaveMirrorConfigPacket::handle
        );

        registrar.playToServer(
                OpenMirrorConfigPacket.TYPE,
                OpenMirrorConfigPacket.STREAM_CODEC,
                OpenMirrorConfigPacket::handle
        );
        
        InstantWorldMirror.LOGGER.info("Registered network packets");
    }
}
