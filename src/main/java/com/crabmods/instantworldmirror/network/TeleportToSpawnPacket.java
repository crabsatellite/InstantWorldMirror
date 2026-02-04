package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet sent from client to server to request teleportation to mirror world spawn.
 * This is used when a player in the mirror world wants to return to the spawn point
 * (where they originally entered the mirror world) without leaving the mirror world.
 */
public class TeleportToSpawnPacket {
    
    public TeleportToSpawnPacket() {
    }
    
    public static void encode(TeleportToSpawnPacket packet, FriendlyByteBuf buf) {
        // No data to encode - this is a simple trigger packet
    }
    
    public static TeleportToSpawnPacket decode(FriendlyByteBuf buf) {
        return new TeleportToSpawnPacket();
    }
    
    public static void handle(TeleportToSpawnPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer serverPlayer = context.getSender();
            if (serverPlayer != null) {
                // Creative mode: clear any existing cooldown and sync to client
                if (serverPlayer.isCreative()) {
                    DimensionMirrorItem.clearCooldown(serverPlayer.getUUID());
                    DimensionMirrorItem.syncCooldownToClient(serverPlayer);
                } else {
                    // Check cooldown first
                    long remainingMillis = DimensionMirrorItem.getRemainingCooldownMillis(serverPlayer.getUUID());
                    if (remainingMillis > 0) {
                        int seconds = (int) (remainingMillis / 1000);
                        serverPlayer.displayClientMessage(
                                Component.translatable("message.instantworldmirror.cooldown", seconds),
                                true
                        );
                        return;
                    }
                }
                
                MirrorWorldManager.teleportToMirrorSpawn(serverPlayer);
            }
        });
        context.setPacketHandled(true);
    }
}
