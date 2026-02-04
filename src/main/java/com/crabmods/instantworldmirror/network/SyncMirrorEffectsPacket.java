package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.MirrorDimensionEffectsManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet sent from server to client to sync mirror dimension effects
 * Tells the client which visual effects a mirror dimension should use
 */
public class SyncMirrorEffectsPacket {
    
    private final int mirrorDimIndex;
    private final String sourceEffects;
    
    public SyncMirrorEffectsPacket(int mirrorDimIndex, String sourceEffects) {
        this.mirrorDimIndex = mirrorDimIndex;
        this.sourceEffects = sourceEffects;
    }
    
    public static void encode(SyncMirrorEffectsPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mirrorDimIndex);
        buf.writeUtf(packet.sourceEffects);
    }
    
    public static SyncMirrorEffectsPacket decode(FriendlyByteBuf buf) {
        int mirrorDimIndex = buf.readVarInt();
        String sourceEffects = buf.readUtf();
        return new SyncMirrorEffectsPacket(mirrorDimIndex, sourceEffects);
    }
    
    public static void handle(SyncMirrorEffectsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Handle on client side
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ResourceLocation effectsLoc = new ResourceLocation(packet.sourceEffects);
                MirrorDimensionEffectsManager.setSourceEffects(packet.mirrorDimIndex, effectsLoc);
                InstantWorldMirror.LOGGER.debug("Received mirror effects sync: dim {} -> {}", 
                        packet.mirrorDimIndex, packet.sourceEffects);
            });
        });
        context.setPacketHandled(true);
    }
    
    public int mirrorDimIndex() {
        return mirrorDimIndex;
    }
    
    public String sourceEffects() {
        return sourceEffects;
    }
}
