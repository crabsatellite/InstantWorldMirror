package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.client.screen.StrandedSnapshotScreen;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record StrandedSnapshotMenuPacket(BlockPos targetPos, List<Entry> entries) {
    public record Entry(UUID id, String name, int radius, long createdAt, boolean available) {
    }

    public static StrandedSnapshotMenuPacket create(ServerPlayer player, BlockPos targetPos) {
        List<Entry> entries = StrandedSnapshotManager.listSnapshotMenuEntries(player).stream()
                .map(entry -> new Entry(
                        entry.summary().id(), entry.summary().name(), entry.summary().radius(),
                        entry.summary().createdAt(), entry.available()))
                .toList();
        return new StrandedSnapshotMenuPacket(targetPos, entries);
    }

    public static void encode(StrandedSnapshotMenuPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.targetPos);
        buf.writeVarInt(packet.entries.size());
        for (Entry entry : packet.entries) {
            buf.writeUUID(entry.id);
            buf.writeUtf(entry.name, 48);
            buf.writeVarInt(entry.radius);
            buf.writeLong(entry.createdAt);
            buf.writeBoolean(entry.available);
        }
    }

    public static StrandedSnapshotMenuPacket decode(FriendlyByteBuf buf) {
        BlockPos targetPos = buf.readBlockPos();
        int size = Math.min(buf.readVarInt(), 256);
        List<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(
                    buf.readUUID(), buf.readUtf(48), buf.readVarInt(), buf.readLong(), buf.readBoolean()));
        }
        return new StrandedSnapshotMenuPacket(targetPos, entries);
    }

    public static void handle(StrandedSnapshotMenuPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> StrandedSnapshotScreen.open(packet.targetPos, packet.entries)));
        context.setPacketHandled(true);
    }
}
