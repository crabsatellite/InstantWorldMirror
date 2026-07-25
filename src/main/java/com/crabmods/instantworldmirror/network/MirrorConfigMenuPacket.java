package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorAccess;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorKindSettings;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MirrorConfigMenuPacket(MirrorConfigState state) implements CustomPacketPayload {
    public static final Type<MirrorConfigMenuPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "mirror_config_menu")
    );

    public static final StreamCodec<FriendlyByteBuf, MirrorConfigMenuPacket> STREAM_CODEC = StreamCodec.of(
            MirrorConfigMenuPacket::encode,
            MirrorConfigMenuPacket::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void encodeConfigState(FriendlyByteBuf buf, MirrorConfigState state) {
        encodeKindSettings(buf, state.get(MirrorKind.DIMENSION));
        encodeKindSettings(buf, state.get(MirrorKind.HEAVEN));
        encodeKindSettings(buf, state.get(MirrorKind.FIRST_DREAM));
        buf.writeVarInt(state.mirrorCooldownSeconds());
    }

    static MirrorConfigState decodeConfigState(FriendlyByteBuf buf) {
        return new MirrorConfigState(
                decodeKindSettings(buf, MirrorKind.DIMENSION),
                decodeKindSettings(buf, MirrorKind.HEAVEN),
                decodeKindSettings(buf, MirrorKind.FIRST_DREAM),
                MirrorConfigState.clampMirrorCooldownSeconds(buf.readVarInt())
        );
    }

    private static void encodeKindSettings(FriendlyByteBuf buf, MirrorKindSettings settings) {
        buf.writeUtf(settings.access().name());
        buf.writeBoolean(settings.mobSpawning());
        buf.writeBoolean(settings.itemTransfer());
        buf.writeVarInt(settings.copyChunkRadius());
    }

    private static MirrorKindSettings decodeKindSettings(FriendlyByteBuf buf, MirrorKind kind) {
        MirrorKindSettings defaults = MirrorKindSettings.defaults(kind);
        return new MirrorKindSettings(
                MirrorAccess.parseFlexible(buf.readUtf(), defaults.access()),
                buf.readBoolean(),
                buf.readBoolean(),
                MirrorKindSettings.clampCopyChunkRadius(buf.readVarInt())
        );
    }

    private static void encode(FriendlyByteBuf buf, MirrorConfigMenuPacket packet) {
        encodeConfigState(buf, packet.state);
    }

    private static MirrorConfigMenuPacket decode(FriendlyByteBuf buf) {
        return new MirrorConfigMenuPacket(decodeConfigState(buf));
    }

    public static void handle(MirrorConfigMenuPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MirrorConfigScreen.open(packet.state));
    }
}
