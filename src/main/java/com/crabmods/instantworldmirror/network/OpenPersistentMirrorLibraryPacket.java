package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenPersistentMirrorLibraryPacket() {
    public static void encode(OpenPersistentMirrorLibraryPacket packet, FriendlyByteBuf buf) {
    }

    public static OpenPersistentMirrorLibraryPacket decode(FriendlyByteBuf buf) {
        return new OpenPersistentMirrorLibraryPacket();
    }

    public static void handle(OpenPersistentMirrorLibraryPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null
                    && DimensionMirrorItem.getMirrorKind(DimensionMirrorItem.findMirrorStack(player))
                    == MirrorKind.STRANDED) {
                PersistentMirrorManager.openStrandedLongTermMenu(player);
            }
        });
        context.setPacketHandled(true);
    }
}
