package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.ClientCooldownTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent from server to client to sync the dimension mirror cooldown
 * Sends the cooldown end timestamp (in milliseconds since epoch)
 * and the total cooldown duration for progress bar calculation
 * The client uses this to display the remaining cooldown time
 */
public record SyncCooldownPacket(long cooldownEndTimestamp, long totalCooldownMillis) implements CustomPacketPayload {
    
    public static final Type<SyncCooldownPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "sync_cooldown")
    );
    
    public static final StreamCodec<FriendlyByteBuf, SyncCooldownPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG,
            SyncCooldownPacket::cooldownEndTimestamp,
            ByteBufCodecs.VAR_LONG,
            SyncCooldownPacket::totalCooldownMillis,
            SyncCooldownPacket::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Handle the packet on the client side
     */
    public static void handle(SyncCooldownPacket packet, IPayloadContext context) {
        // Execute on client thread
        context.enqueueWork(() -> {
            ClientCooldownTracker.setCooldown(packet.cooldownEndTimestamp, packet.totalCooldownMillis);
            InstantWorldMirror.LOGGER.debug("Received cooldown sync: end timestamp = {}, total = {} ms", 
                    packet.cooldownEndTimestamp, packet.totalCooldownMillis);
        });
    }
}
