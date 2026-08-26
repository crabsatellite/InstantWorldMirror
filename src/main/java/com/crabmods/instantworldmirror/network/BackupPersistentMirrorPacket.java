package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record BackupPersistentMirrorPacket(String selector) {
    public static void encode(BackupPersistentMirrorPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.selector, 64);
    }

    public static BackupPersistentMirrorPacket decode(FriendlyByteBuf buf) {
        return new BackupPersistentMirrorPacket(buf.readUtf(64));
    }

    public static void handle(BackupPersistentMirrorPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                PersistentMirrorManager.resolveRecordSelector(player, packet.selector).ifPresent(record -> {
                    PersistentMirrorManager.backupRecord(player, record.id());
                    PersistentMirrorManager.openMirrorMenu(player, record.kind());
                });
            }
        });
        context.setPacketHandled(true);
    }
}
