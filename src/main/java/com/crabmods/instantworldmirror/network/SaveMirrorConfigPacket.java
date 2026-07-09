package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveMirrorConfigPacket(MirrorConfigState state) implements CustomPacketPayload {
    public static final Type<SaveMirrorConfigPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "save_mirror_config")
    );

    public static final StreamCodec<FriendlyByteBuf, SaveMirrorConfigPacket> STREAM_CODEC = StreamCodec.of(
            SaveMirrorConfigPacket::encode,
            SaveMirrorConfigPacket::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, SaveMirrorConfigPacket packet) {
        MirrorConfigMenuPacket.encodeConfigState(buf, packet.state);
    }

    private static SaveMirrorConfigPacket decode(FriendlyByteBuf buf) {
        return new SaveMirrorConfigPacket(MirrorConfigMenuPacket.decodeConfigState(buf));
    }

    public static void handle(SaveMirrorConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!MirrorConfig.canManageConfig(player)) {
                player.displayClientMessage(Component.translatable("message.instantworldmirror.config.no_permission"), false);
                return;
            }

            MirrorConfig.saveMirrorConfigState(packet.state);
            player.displayClientMessage(Component.translatable("message.instantworldmirror.config.saved_restart_required"), false);
        });
    }
}
