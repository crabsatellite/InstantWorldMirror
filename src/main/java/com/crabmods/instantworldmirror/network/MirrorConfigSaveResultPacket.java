package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MirrorConfigSaveResultPacket(boolean success) implements CustomPacketPayload {
    public static final Type<MirrorConfigSaveResultPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "mirror_config_save_result")
    );

    public static final StreamCodec<FriendlyByteBuf, MirrorConfigSaveResultPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.success),
            buf -> new MirrorConfigSaveResultPacket(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MirrorConfigSaveResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MirrorConfigScreen.handleSaveResult(packet.success));
    }
}
