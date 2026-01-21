package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.MirrorDimensionEffectsManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent from server to client to sync mirror dimension effects
 * Tells the client which visual effects a mirror dimension should use
 */
public record SyncMirrorEffectsPacket(int mirrorDimIndex, String sourceEffects) implements CustomPacketPayload {
    
    public static final Type<SyncMirrorEffectsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "sync_mirror_effects")
    );
    
    public static final StreamCodec<FriendlyByteBuf, SyncMirrorEffectsPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            SyncMirrorEffectsPacket::mirrorDimIndex,
            ByteBufCodecs.STRING_UTF8,
            SyncMirrorEffectsPacket::sourceEffects,
            SyncMirrorEffectsPacket::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Handle the packet on the client side
     */
    public static void handle(SyncMirrorEffectsPacket packet, IPayloadContext context) {
        // Execute on client thread
        context.enqueueWork(() -> {
            ResourceLocation effectsLoc = ResourceLocation.parse(packet.sourceEffects);
            MirrorDimensionEffectsManager.setSourceEffects(packet.mirrorDimIndex, effectsLoc);
            InstantWorldMirror.LOGGER.debug("Received mirror effects sync: dim {} -> {}", 
                    packet.mirrorDimIndex, packet.sourceEffects);
        });
    }
}
