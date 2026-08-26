package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.screen.StrandedSnapshotScreen;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record StrandedSnapshotMenuPacket(BlockPos targetPos, List<Entry> entries) implements CustomPacketPayload {
    public record Entry(UUID id, String name, int radius, long createdAt,
                        boolean available, boolean backupAvailable) {
    }

    public static final Type<StrandedSnapshotMenuPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "stranded_snapshot_menu"));
    public static final StreamCodec<FriendlyByteBuf, StrandedSnapshotMenuPacket> STREAM_CODEC = StreamCodec.of(
            StrandedSnapshotMenuPacket::encode,
            StrandedSnapshotMenuPacket::decode);

    public static StrandedSnapshotMenuPacket create(ServerPlayer player, BlockPos targetPos) {
        List<Entry> entries = StrandedSnapshotManager.listSnapshotMenuEntries(player).stream()
                .map(entry -> new Entry(
                        entry.summary().id(), entry.summary().name(), entry.summary().radius(),
                        entry.summary().createdAt(), entry.available(), entry.backupAvailable()))
                .toList();
        return new StrandedSnapshotMenuPacket(targetPos, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, StrandedSnapshotMenuPacket packet) {
        buf.writeBlockPos(packet.targetPos);
        buf.writeVarInt(packet.entries.size());
        for (Entry entry : packet.entries) {
            buf.writeUUID(entry.id);
            buf.writeUtf(entry.name, 48);
            buf.writeVarInt(entry.radius);
            buf.writeLong(entry.createdAt);
            buf.writeBoolean(entry.available);
            buf.writeBoolean(entry.backupAvailable);
        }
    }

    private static StrandedSnapshotMenuPacket decode(FriendlyByteBuf buf) {
        BlockPos targetPos = buf.readBlockPos();
        int size = Math.min(buf.readVarInt(), 256);
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                    buf.readUUID(), buf.readUtf(48), buf.readVarInt(), buf.readLong(),
                    buf.readBoolean(), buf.readBoolean()));
        }
        return new StrandedSnapshotMenuPacket(targetPos, entries);
    }

    public static void handle(StrandedSnapshotMenuPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> StrandedSnapshotScreen.open(packet.targetPos, packet.entries));
    }
}
