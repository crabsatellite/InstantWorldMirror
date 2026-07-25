package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.MirrorConfigState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SaveMirrorConfigPacket(MirrorConfigState state) {
    public static void encode(SaveMirrorConfigPacket packet, FriendlyByteBuf buf) {
        MirrorConfigMenuPacket.encodeConfigState(buf, packet.state);
    }

    public static SaveMirrorConfigPacket decode(FriendlyByteBuf buf) {
        return new SaveMirrorConfigPacket(MirrorConfigMenuPacket.decodeConfigState(buf));
    }

    public static void handle(SaveMirrorConfigPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!MirrorConfig.canManageConfig(player)) {
                player.displayClientMessage(Component.translatable("message.instantworldmirror.config.no_permission"), false);
                ModNetworking.sendToPlayer(new MirrorConfigSaveResultPacket(false), player);
                return;
            }

            boolean success = false;
            try {
                MirrorConfig.saveMirrorConfigState(packet.state);
                success = true;
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.config.saved_restart_required"), false);
            } catch (RuntimeException exception) {
                InstantWorldMirror.LOGGER.error("Failed to save mirror configuration for {}",
                        player.getName().getString(), exception);
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.config.save_failed"), false);
            }
            ModNetworking.sendToPlayer(new MirrorConfigSaveResultPacket(success), player);
        });
        context.setPacketHandled(true);
    }
}
