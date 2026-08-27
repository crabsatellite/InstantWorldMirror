package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.screen.StrandedSnapshotScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record OpenStrandedSnapshotResultPacket(UUID snapshotId, boolean opened)
        implements CustomPacketPayload {
    public static final Type<OpenStrandedSnapshotResultPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    InstantWorldMirror.MODID, "open_stranded_snapshot_result"));
    public static final StreamCodec<FriendlyByteBuf, OpenStrandedSnapshotResultPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUUID(packet.snapshotId);
                        buf.writeBoolean(packet.opened);
                    },
                    buf -> new OpenStrandedSnapshotResultPacket(
                            buf.readUUID(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenStrandedSnapshotResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                StrandedSnapshotScreen.handleOpenResult(packet.snapshotId, packet.opened));
    }
}
