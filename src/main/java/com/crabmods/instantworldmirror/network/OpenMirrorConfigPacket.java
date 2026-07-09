package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenMirrorConfigPacket() implements CustomPacketPayload {
    public static final Type<OpenMirrorConfigPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "open_mirror_config")
    );

    public static final StreamCodec<FriendlyByteBuf, OpenMirrorConfigPacket> STREAM_CODEC = StreamCodec.of(
            OpenMirrorConfigPacket::encode,
            OpenMirrorConfigPacket::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, OpenMirrorConfigPacket packet) {
    }

    private static OpenMirrorConfigPacket decode(FriendlyByteBuf buf) {
        return new OpenMirrorConfigPacket();
    }

    public static void handle(OpenMirrorConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!MirrorConfig.canManageConfig(player)) {
                player.displayClientMessage(Component.translatable("message.instantworldmirror.config.no_permission"), false);
                return;
            }

            PacketDistributor.sendToPlayer(player, new MirrorConfigMenuPacket(MirrorConfig.configuredMirrorConfigState()));
        });
    }
}
