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
    
    public static final String PROTOCOL_VERSION = "1";
    
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
        
        // Register server-bound packets
        CHANNEL.registerMessage(
                nextId(),
                TeleportToSpawnPacket.class,
                TeleportToSpawnPacket::encode,
                TeleportToSpawnPacket::decode,
                TeleportToSpawnPacket::handle,
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
