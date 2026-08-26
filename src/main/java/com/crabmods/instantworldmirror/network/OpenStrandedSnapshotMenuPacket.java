package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenStrandedSnapshotMenuPacket() implements CustomPacketPayload {
    public static final Type<OpenStrandedSnapshotMenuPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "open_stranded_snapshot_menu"));
    public static final StreamCodec<FriendlyByteBuf, OpenStrandedSnapshotMenuPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> { }, buf -> new OpenStrandedSnapshotMenuPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenStrandedSnapshotMenuPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && DimensionMirrorItem.getMirrorKind(DimensionMirrorItem.findMirrorStack(player))
                    == MirrorKind.STRANDED) {
                PacketDistributor.sendToPlayer(player, StrandedSnapshotMenuPacket.create(
                        player, player.blockPosition().below()));
            }
        });
    }
}
