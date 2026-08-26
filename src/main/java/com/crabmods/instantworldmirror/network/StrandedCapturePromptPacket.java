package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.client.screen.StrandedCaptureScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record StrandedCapturePromptPacket(BlockPos targetPos) {
    public static void encode(StrandedCapturePromptPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.targetPos);
    }

    public static StrandedCapturePromptPacket decode(FriendlyByteBuf buf) {
        return new StrandedCapturePromptPacket(buf.readBlockPos());
    }

    public static void handle(StrandedCapturePromptPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> StrandedCaptureScreen.open(packet.targetPos)));
        context.setPacketHandled(true);
    }
}
