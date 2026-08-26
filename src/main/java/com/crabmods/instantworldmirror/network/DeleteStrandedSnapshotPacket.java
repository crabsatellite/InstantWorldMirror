package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record DeleteStrandedSnapshotPacket(BlockPos targetPos, UUID snapshotId) implements CustomPacketPayload {
    public static final Type<DeleteStrandedSnapshotPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "delete_stranded_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, DeleteStrandedSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.targetPos);
                buf.writeUUID(packet.snapshotId);
            },
            buf -> new DeleteStrandedSnapshotPacket(buf.readBlockPos(), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeleteStrandedSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StrandedSnapshotManager.requestDelete(player, packet.snapshotId);
                PacketDistributor.sendToPlayer(
                        player, StrandedSnapshotMenuPacket.create(player, packet.targetPos));
            }
        });
    }
}
