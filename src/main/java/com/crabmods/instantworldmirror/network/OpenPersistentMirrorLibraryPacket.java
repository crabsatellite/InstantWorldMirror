package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenPersistentMirrorLibraryPacket() implements CustomPacketPayload {
    public static final Type<OpenPersistentMirrorLibraryPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "open_persistent_mirror_library"));
    public static final StreamCodec<FriendlyByteBuf, OpenPersistentMirrorLibraryPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> { }, buf -> new OpenPersistentMirrorLibraryPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenPersistentMirrorLibraryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && DimensionMirrorItem.getMirrorKind(DimensionMirrorItem.findMirrorStack(player))
                    == MirrorKind.STRANDED) {
                PersistentMirrorManager.openStrandedLongTermMenu(player);
            }
        });
    }
}
