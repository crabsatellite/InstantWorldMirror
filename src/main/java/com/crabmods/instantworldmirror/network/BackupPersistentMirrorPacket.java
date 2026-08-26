package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BackupPersistentMirrorPacket(String selector) implements CustomPacketPayload {
    public static final Type<BackupPersistentMirrorPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "backup_persistent_mirror"));
    public static final StreamCodec<FriendlyByteBuf, BackupPersistentMirrorPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.selector, 64),
            buf -> new BackupPersistentMirrorPacket(buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BackupPersistentMirrorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PersistentMirrorManager.resolveRecordSelector(player, packet.selector).ifPresent(record -> {
                    PersistentMirrorManager.backupRecord(player, record.id());
                    PersistentMirrorManager.openMirrorMenu(player, record.kind());
                });
            }
        });
    }
}
