package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.screen.StrandedCaptureScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StrandedCapturePromptPacket(BlockPos targetPos) implements CustomPacketPayload {
    public static final Type<StrandedCapturePromptPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "stranded_capture_prompt"));
    public static final StreamCodec<FriendlyByteBuf, StrandedCapturePromptPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBlockPos(packet.targetPos),
            buf -> new StrandedCapturePromptPacket(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StrandedCapturePromptPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> StrandedCaptureScreen.open(packet.targetPos));
    }
}

