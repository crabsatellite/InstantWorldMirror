package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenStrandedSnapshotMenuPacket() {
    public static void encode(OpenStrandedSnapshotMenuPacket packet, FriendlyByteBuf buf) {
    }

    public static OpenStrandedSnapshotMenuPacket decode(FriendlyByteBuf buf) {
        return new OpenStrandedSnapshotMenuPacket();
    }

    public static void handle(OpenStrandedSnapshotMenuPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null
                    && DimensionMirrorItem.getMirrorKind(DimensionMirrorItem.findMirrorStack(player))
                    == MirrorKind.STRANDED) {
                ModNetworking.sendToPlayer(StrandedSnapshotMenuPacket.create(
                        player, player.blockPosition().below()), player);
            }
        });
        context.setPacketHandled(true);
    }
}
