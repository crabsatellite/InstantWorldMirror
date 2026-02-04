package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.ClientCooldownTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet sent from server to client to sync the dimension mirror cooldown
 * Sends the cooldown end timestamp (in milliseconds since epoch)
 * and the total cooldown duration for progress bar calculation
 * The client uses this to display the remaining cooldown time
 */
public class SyncCooldownPacket {
    
    private final long cooldownEndTimestamp;
    private final long totalCooldownMillis;
    
    public SyncCooldownPacket(long cooldownEndTimestamp, long totalCooldownMillis) {
        this.cooldownEndTimestamp = cooldownEndTimestamp;
        this.totalCooldownMillis = totalCooldownMillis;
    }
    
    public static void encode(SyncCooldownPacket packet, FriendlyByteBuf buf) {
        buf.writeVarLong(packet.cooldownEndTimestamp);
        buf.writeVarLong(packet.totalCooldownMillis);
    }
    
    public static SyncCooldownPacket decode(FriendlyByteBuf buf) {
        long cooldownEndTimestamp = buf.readVarLong();
        long totalCooldownMillis = buf.readVarLong();
        return new SyncCooldownPacket(cooldownEndTimestamp, totalCooldownMillis);
    }
    
    public static void handle(SyncCooldownPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Handle on client side
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientCooldownTracker.setCooldown(packet.cooldownEndTimestamp, packet.totalCooldownMillis);
                InstantWorldMirror.LOGGER.debug("Received cooldown sync: end timestamp = {}, total = {} ms", 
                        packet.cooldownEndTimestamp, packet.totalCooldownMillis);
            });
        });
        context.setPacketHandled(true);
    }
    
    public long cooldownEndTimestamp() {
        return cooldownEndTimestamp;
    }
    
    public long totalCooldownMillis() {
        return totalCooldownMillis;
    }
}
