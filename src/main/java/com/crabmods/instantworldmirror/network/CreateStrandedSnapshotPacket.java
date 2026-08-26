package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CreateStrandedSnapshotPacket(BlockPos targetPos, String name) {
    public static void encode(CreateStrandedSnapshotPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.targetPos);
        buf.writeUtf(packet.name, 48);
    }

    public static CreateStrandedSnapshotPacket decode(FriendlyByteBuf buf) {
        return new CreateStrandedSnapshotPacket(buf.readBlockPos(), buf.readUtf(48));
    }

    public static void handle(CreateStrandedSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                StrandedSnapshotManager.requestCapture(player, packet.targetPos, packet.name);
            }
        });
        context.setPacketHandled(true);
    }
}
