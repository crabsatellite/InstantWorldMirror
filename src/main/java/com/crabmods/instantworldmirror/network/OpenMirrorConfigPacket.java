package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenMirrorConfigPacket() {
    public static void encode(OpenMirrorConfigPacket packet, FriendlyByteBuf buf) {
    }

    public static OpenMirrorConfigPacket decode(FriendlyByteBuf buf) {
        return new OpenMirrorConfigPacket();
    }

    public static void handle(OpenMirrorConfigPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!MirrorConfig.canManageConfig(player)) {
                player.displayClientMessage(Component.translatable("message.instantworldmirror.config.no_permission"), false);
                return;
            }

            ModNetworking.sendToPlayer(new MirrorConfigMenuPacket(MirrorConfig.configuredMirrorConfigState()), player);
        });
        context.setPacketHandled(true);
    }
}
