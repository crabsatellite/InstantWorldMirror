package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

public record OpenStrandedSnapshotPacket(BlockPos targetPos, UUID snapshotId) implements CustomPacketPayload {
    public static final Type<OpenStrandedSnapshotPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "open_stranded_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, OpenStrandedSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.targetPos);
                buf.writeUUID(packet.snapshotId);
            },
            buf -> new OpenStrandedSnapshotPacket(buf.readBlockPos(), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenStrandedSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                boolean opened;
                try {
                    opened = StrandedSnapshotManager.requestOpen(
                            player, packet.targetPos, packet.snapshotId);
                } catch (RuntimeException exception) {
                    InstantWorldMirror.LOGGER.error(
                            "Failed to open Stranded Mirror snapshot {}", packet.snapshotId, exception);
                    opened = false;
                }
                PacketDistributor.sendToPlayer(
                        player, new OpenStrandedSnapshotResultPacket(packet.snapshotId, opened));
            }
        });
    }
}
