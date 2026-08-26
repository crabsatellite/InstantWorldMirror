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
    
    public static final String PROTOCOL_VERSION = "4";
    
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

        registrar.playToClient(
                StrandedCapturePromptPacket.TYPE,
                StrandedCapturePromptPacket.STREAM_CODEC,
                StrandedCapturePromptPacket::handle
        );

        registrar.playToClient(
                StrandedSnapshotMenuPacket.TYPE,
                StrandedSnapshotMenuPacket.STREAM_CODEC,
                StrandedSnapshotMenuPacket::handle
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

        registrar.playToServer(
                CreateStrandedSnapshotPacket.TYPE,
                CreateStrandedSnapshotPacket.STREAM_CODEC,
                CreateStrandedSnapshotPacket::handle
        );

        registrar.playToServer(
                OpenStrandedSnapshotPacket.TYPE,
                OpenStrandedSnapshotPacket.STREAM_CODEC,
                OpenStrandedSnapshotPacket::handle
        );

        registrar.playToServer(
                DeleteStrandedSnapshotPacket.TYPE,
                DeleteStrandedSnapshotPacket.STREAM_CODEC,
                DeleteStrandedSnapshotPacket::handle
        );

        registrar.playToServer(
                BackupStrandedSnapshotPacket.TYPE,
                BackupStrandedSnapshotPacket.STREAM_CODEC,
                BackupStrandedSnapshotPacket::handle
        );

        registrar.playToServer(
                BackupPersistentMirrorPacket.TYPE,
                BackupPersistentMirrorPacket.STREAM_CODEC,
                BackupPersistentMirrorPacket::handle
        );

        registrar.playToServer(
                OpenStrandedSnapshotMenuPacket.TYPE,
                OpenStrandedSnapshotMenuPacket.STREAM_CODEC,
                OpenStrandedSnapshotMenuPacket::handle
        );

        registrar.playToServer(
                OpenPersistentMirrorLibraryPacket.TYPE,
                OpenPersistentMirrorLibraryPacket.STREAM_CODEC,
                OpenPersistentMirrorLibraryPacket::handle
        );
        
        InstantWorldMirror.LOGGER.info("Registered network packets");
    }
}
