package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.client.screen.StrandedSnapshotScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record OpenStrandedSnapshotResultPacket(UUID snapshotId, boolean opened) {
    public static void encode(OpenStrandedSnapshotResultPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.snapshotId);
        buf.writeBoolean(packet.opened);
    }

    public static OpenStrandedSnapshotResultPacket decode(FriendlyByteBuf buf) {
        return new OpenStrandedSnapshotResultPacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(OpenStrandedSnapshotResultPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> StrandedSnapshotScreen.handleOpenResult(
                        packet.snapshotId, packet.opened)));
        context.setPacketHandled(true);
    }
}
