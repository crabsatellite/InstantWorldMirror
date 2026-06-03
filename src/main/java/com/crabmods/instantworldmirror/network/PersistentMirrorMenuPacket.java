package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.client.screen.PersistentMirrorMenuScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record PersistentMirrorMenuPacket(
        String mode,
        String kindTranslationKey,
        String currentName,
        String statusTranslationKey,
        String currentSelector,
        List<Entry> entries,
        boolean showSaveButton,
        boolean showReturnButton,
        boolean showLeaveButton,
        boolean showRenameButton,
        boolean showDeleteButton
) {
    public static final String MODE_TEMPORARY = "temporary";
    public static final String MODE_INSIDE = "inside";
    public static final String MODE_LIST = "list";

    public static PersistentMirrorMenuPacket temporary(String kindTranslationKey, String statusTranslationKey,
                                                       boolean showSaveButton, boolean showReturnButton) {
        return new PersistentMirrorMenuPacket(
                MODE_TEMPORARY,
                kindTranslationKey,
                "",
                statusTranslationKey,
                "",
                List.of(),
                showSaveButton,
                showReturnButton,
                false,
                false,
                false
        );
    }

    public static PersistentMirrorMenuPacket inside(String currentName, String currentSelector,
                                                    boolean showRenameButton, boolean showDeleteButton) {
        return new PersistentMirrorMenuPacket(
                MODE_INSIDE,
                "",
                currentName,
                "",
                currentSelector,
                List.of(),
                false,
                false,
                true,
                showRenameButton,
                showDeleteButton
        );
    }

    public static PersistentMirrorMenuPacket list(String kindTranslationKey, List<Entry> entries) {
        return new PersistentMirrorMenuPacket(
                MODE_LIST,
                kindTranslationKey,
                "",
                "",
                "",
                entries,
                false,
                false,
                false,
                false,
                false
        );
    }

    public static void encode(PersistentMirrorMenuPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.mode);
        buf.writeUtf(packet.kindTranslationKey);
        buf.writeUtf(packet.currentName);
        buf.writeUtf(packet.statusTranslationKey);
        buf.writeUtf(packet.currentSelector);
        buf.writeVarInt(packet.entries.size());
        for (Entry entry : packet.entries) {
            entry.encode(buf);
        }
        buf.writeBoolean(packet.showSaveButton);
        buf.writeBoolean(packet.showReturnButton);
        buf.writeBoolean(packet.showLeaveButton);
        buf.writeBoolean(packet.showRenameButton);
        buf.writeBoolean(packet.showDeleteButton);
    }

    public static PersistentMirrorMenuPacket decode(FriendlyByteBuf buf) {
        String mode = buf.readUtf();
        String kindTranslationKey = buf.readUtf();
        String currentName = buf.readUtf();
        String statusTranslationKey = buf.readUtf();
        String currentSelector = buf.readUtf();
        int entryCount = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            entries.add(Entry.decode(buf));
        }
        return new PersistentMirrorMenuPacket(
                mode,
                kindTranslationKey,
                currentName,
                statusTranslationKey,
                currentSelector,
                entries,
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }

    public static void handle(PersistentMirrorMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> PersistentMirrorMenuScreen.open(packet)
        ));
        context.setPacketHandled(true);
    }

    public record Entry(String name, String selector, boolean ready, boolean canManage) {
        private void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name);
            buf.writeUtf(selector);
            buf.writeBoolean(ready);
            buf.writeBoolean(canManage);
        }

        private static Entry decode(FriendlyByteBuf buf) {
            return new Entry(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean());
        }
    }
}
