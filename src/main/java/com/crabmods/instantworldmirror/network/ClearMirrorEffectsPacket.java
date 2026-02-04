package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.MirrorDimensionEffectsManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet sent from server to client to clear mirror dimension effects
 * Sent when a player leaves a mirror world or session ends
 */
public class ClearMirrorEffectsPacket {
    
    private final int mirrorDimIndex;
    
    public ClearMirrorEffectsPacket(int mirrorDimIndex) {
        this.mirrorDimIndex = mirrorDimIndex;
    }
    
    public static void encode(ClearMirrorEffectsPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mirrorDimIndex);
    }
    
    public static ClearMirrorEffectsPacket decode(FriendlyByteBuf buf) {
        int mirrorDimIndex = buf.readVarInt();
        return new ClearMirrorEffectsPacket(mirrorDimIndex);
    }
    
    public static void handle(ClearMirrorEffectsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Handle on client side
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                MirrorDimensionEffectsManager.clearSourceEffects(packet.mirrorDimIndex);
                InstantWorldMirror.LOGGER.debug("Cleared mirror effects for dim {}", packet.mirrorDimIndex);
            });
        });
        context.setPacketHandled(true);
    }
    
    public int mirrorDimIndex() {
        return mirrorDimIndex;
    }
}
