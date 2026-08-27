package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record OpenStrandedSnapshotPacket(BlockPos targetPos, UUID snapshotId) {
    public static void encode(OpenStrandedSnapshotPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.targetPos);
        buf.writeUUID(packet.snapshotId);
    }

    public static OpenStrandedSnapshotPacket decode(FriendlyByteBuf buf) {
        return new OpenStrandedSnapshotPacket(buf.readBlockPos(), buf.readUUID());
    }

    public static void handle(OpenStrandedSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                boolean opened;
                try {
                    opened = StrandedSnapshotManager.requestOpen(
                            player, packet.targetPos, packet.snapshotId);
                } catch (RuntimeException exception) {
                    InstantWorldMirror.LOGGER.error(
                            "Failed to open Stranded Mirror snapshot {}", packet.snapshotId, exception);
                    opened = false;
                }
                ModNetworking.sendToPlayer(
                        new OpenStrandedSnapshotResultPacket(packet.snapshotId, opened), player);
            }
        });
        context.setPacketHandled(true);
    }
}
