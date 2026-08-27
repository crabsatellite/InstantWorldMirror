package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Handles registration of network packets using Forge SimpleChannel
 */
public class ModNetworking {
    
    public static final String PROTOCOL_VERSION = "5";
    
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(InstantWorldMirror.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    
    private static int packetId = 0;
    
    private static int nextId() {
        return packetId++;
    }
    
    public static void register() {
        // Register client-bound packets
        CHANNEL.registerMessage(
                nextId(),
                SyncMirrorEffectsPacket.class,
                SyncMirrorEffectsPacket::encode,
                SyncMirrorEffectsPacket::decode,
                SyncMirrorEffectsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        
        CHANNEL.registerMessage(
                nextId(),
                ClearMirrorEffectsPacket.class,
                ClearMirrorEffectsPacket::encode,
                ClearMirrorEffectsPacket::decode,
                ClearMirrorEffectsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        
        CHANNEL.registerMessage(
                nextId(),
                SyncCooldownPacket.class,
                SyncCooldownPacket::encode,
                SyncCooldownPacket::decode,
                SyncCooldownPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                PersistentMirrorMenuPacket.class,
                PersistentMirrorMenuPacket::encode,
                PersistentMirrorMenuPacket::decode,
                PersistentMirrorMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                MirrorConfigMenuPacket.class,
                MirrorConfigMenuPacket::encode,
                MirrorConfigMenuPacket::decode,
                MirrorConfigMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                MirrorConfigSaveResultPacket.class,
                MirrorConfigSaveResultPacket::encode,
                MirrorConfigSaveResultPacket::decode,
                MirrorConfigSaveResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                StrandedCapturePromptPacket.class,
                StrandedCapturePromptPacket::encode,
                StrandedCapturePromptPacket::decode,
                StrandedCapturePromptPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                StrandedSnapshotMenuPacket.class,
                StrandedSnapshotMenuPacket::encode,
                StrandedSnapshotMenuPacket::decode,
                StrandedSnapshotMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                OpenStrandedSnapshotResultPacket.class,
                OpenStrandedSnapshotResultPacket::encode,
                OpenStrandedSnapshotResultPacket::decode,
                OpenStrandedSnapshotResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        
        // Register server-bound packets
        CHANNEL.registerMessage(
                nextId(),
                TeleportToSpawnPacket.class,
                TeleportToSpawnPacket::encode,
                TeleportToSpawnPacket::decode,
                TeleportToSpawnPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                SaveMirrorConfigPacket.class,
                SaveMirrorConfigPacket::encode,
                SaveMirrorConfigPacket::decode,
                SaveMirrorConfigPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                OpenMirrorConfigPacket.class,
                OpenMirrorConfigPacket::encode,
                OpenMirrorConfigPacket::decode,
                OpenMirrorConfigPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                CreateStrandedSnapshotPacket.class,
                CreateStrandedSnapshotPacket::encode,
                CreateStrandedSnapshotPacket::decode,
                CreateStrandedSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                OpenStrandedSnapshotPacket.class,
                OpenStrandedSnapshotPacket::encode,
                OpenStrandedSnapshotPacket::decode,
                OpenStrandedSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                DeleteStrandedSnapshotPacket.class,
                DeleteStrandedSnapshotPacket::encode,
                DeleteStrandedSnapshotPacket::decode,
                DeleteStrandedSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                BackupStrandedSnapshotPacket.class,
                BackupStrandedSnapshotPacket::encode,
                BackupStrandedSnapshotPacket::decode,
                BackupStrandedSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                BackupPersistentMirrorPacket.class,
                BackupPersistentMirrorPacket::encode,
                BackupPersistentMirrorPacket::decode,
                BackupPersistentMirrorPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                OpenStrandedSnapshotMenuPacket.class,
                OpenStrandedSnapshotMenuPacket::encode,
                OpenStrandedSnapshotMenuPacket::decode,
                OpenStrandedSnapshotMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                nextId(),
                OpenPersistentMirrorLibraryPacket.class,
                OpenPersistentMirrorLibraryPacket::encode,
                OpenPersistentMirrorLibraryPacket::decode,
                OpenPersistentMirrorLibraryPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        
        InstantWorldMirror.LOGGER.info("Registered network packets");
    }
    
    /**
     * Send a packet to a specific player
     */
    public static void sendToPlayer(Object packet, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    
    /**
     * Send a packet to the server (from client)
     */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
