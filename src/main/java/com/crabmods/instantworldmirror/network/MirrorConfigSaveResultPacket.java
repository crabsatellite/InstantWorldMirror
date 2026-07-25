package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MirrorConfigSaveResultPacket(boolean success) {
    public static void encode(MirrorConfigSaveResultPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
    }

    public static MirrorConfigSaveResultPacket decode(FriendlyByteBuf buf) {
        return new MirrorConfigSaveResultPacket(buf.readBoolean());
    }

    public static void handle(MirrorConfigSaveResultPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> MirrorConfigScreen.handleSaveResult(packet.success)
        ));
        context.setPacketHandled(true);
    }
}
