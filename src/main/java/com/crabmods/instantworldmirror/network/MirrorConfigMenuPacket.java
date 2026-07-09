package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.MirrorAccess;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorKindSettings;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MirrorConfigMenuPacket(MirrorConfigState state) {
    static void encodeConfigState(FriendlyByteBuf buf, MirrorConfigState state) {
        encodeKindSettings(buf, state.worldReflectionMirror());
        encodeKindSettings(buf, state.heavenMirror());
        encodeKindSettings(buf, state.firstDreamMirror());
    }

    static MirrorConfigState decodeConfigState(FriendlyByteBuf buf) {
        return new MirrorConfigState(
                decodeKindSettings(buf, MirrorKind.DIMENSION),
                decodeKindSettings(buf, MirrorKind.HEAVEN),
                decodeKindSettings(buf, MirrorKind.FIRST_DREAM)
        );
    }

    private static void encodeKindSettings(FriendlyByteBuf buf, MirrorKindSettings settings) {
        buf.writeUtf(settings.access().name());
        buf.writeBoolean(settings.mobSpawning());
        buf.writeBoolean(settings.itemTransfer());
        buf.writeVarInt(settings.copyChunkRadius());
    }

    private static MirrorKindSettings decodeKindSettings(FriendlyByteBuf buf, MirrorKind kind) {
        return MirrorKindSettings.defaults(kind)
                .withAccess(MirrorAccess.parseFlexible(buf.readUtf(), MirrorAccess.ALL))
                .withMobSpawning(buf.readBoolean())
                .withItemTransfer(buf.readBoolean())
                .withCopyChunkRadius(buf.readVarInt());
    }

    public static void encode(MirrorConfigMenuPacket packet, FriendlyByteBuf buf) {
        encodeConfigState(buf, packet.state);
    }

    public static MirrorConfigMenuPacket decode(FriendlyByteBuf buf) {
        return new MirrorConfigMenuPacket(decodeConfigState(buf));
    }

    public static void handle(MirrorConfigMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> MirrorConfigScreen.open(packet.state)
        ));
        context.setPacketHandled(true);
    }
}
