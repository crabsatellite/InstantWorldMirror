package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BackupStrandedSnapshotPacket(BlockPos targetPos, UUID snapshotId) {
    public static void encode(BackupStrandedSnapshotPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.targetPos);
        buf.writeUUID(packet.snapshotId);
    }

    public static BackupStrandedSnapshotPacket decode(FriendlyByteBuf buf) {
        return new BackupStrandedSnapshotPacket(buf.readBlockPos(), buf.readUUID());
    }

    public static void handle(BackupStrandedSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                StrandedSnapshotManager.requestBackup(player, packet.snapshotId);
                ModNetworking.sendToPlayer(
                        StrandedSnapshotMenuPacket.create(player, packet.targetPos), player);
            }
        });
        context.setPacketHandled(true);
    }
}
