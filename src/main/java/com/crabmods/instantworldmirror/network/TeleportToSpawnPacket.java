package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent from client to server to request returning to the overworld.
 * This is used when a player in the mirror world holds right-click with the mirror
 * to quickly return to their original position in the overworld.
 */
public record TeleportToSpawnPacket() implements CustomPacketPayload {
    
    public static final Type<TeleportToSpawnPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "teleport_to_spawn")
    );
    
    public static final StreamCodec<FriendlyByteBuf, TeleportToSpawnPacket> STREAM_CODEC = StreamCodec.unit(
            new TeleportToSpawnPacket()
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Handle the packet on the server side
     */
    public static void handle(TeleportToSpawnPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
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
                
                // Return to overworld (original position)
                MirrorWorldManager.returnToOverworld(serverPlayer);
            }
        });
    }
}
