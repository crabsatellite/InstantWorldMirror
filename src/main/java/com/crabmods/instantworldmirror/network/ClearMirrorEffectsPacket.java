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
 * Packet sent from server to client to clear mirror dimension effects
 * Sent when a player leaves a mirror world or session ends
 */
public record ClearMirrorEffectsPacket(int mirrorDimIndex) implements CustomPacketPayload {
    
    public static final Type<ClearMirrorEffectsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "clear_mirror_effects")
    );
    
    public static final StreamCodec<FriendlyByteBuf, ClearMirrorEffectsPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ClearMirrorEffectsPacket::mirrorDimIndex,
            ClearMirrorEffectsPacket::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Handle the packet on the client side
     */
    public static void handle(ClearMirrorEffectsPacket packet, IPayloadContext context) {
        // Execute on client thread
        context.enqueueWork(() -> {
            MirrorDimensionEffectsManager.clearSourceEffects(packet.mirrorDimIndex);
            InstantWorldMirror.LOGGER.debug("Cleared mirror effects for dim {}", packet.mirrorDimIndex);
        });
    }
}
