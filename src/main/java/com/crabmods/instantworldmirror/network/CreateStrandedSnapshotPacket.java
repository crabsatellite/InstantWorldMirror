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

public record CreateStrandedSnapshotPacket(BlockPos targetPos, String name) implements CustomPacketPayload {
    public static final Type<CreateStrandedSnapshotPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "create_stranded_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, CreateStrandedSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.targetPos);
                buf.writeUtf(packet.name, 48);
            },
            buf -> new CreateStrandedSnapshotPacket(buf.readBlockPos(), buf.readUtf(48)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CreateStrandedSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StrandedSnapshotManager.requestCapture(player, packet.targetPos, packet.name);
            }
        });
    }
}

