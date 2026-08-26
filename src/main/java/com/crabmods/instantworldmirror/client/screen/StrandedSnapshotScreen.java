package com.crabmods.instantworldmirror.client.screen;

import com.crabmods.instantworldmirror.network.OpenStrandedSnapshotPacket;
import com.crabmods.instantworldmirror.network.BackupStrandedSnapshotPacket;
import com.crabmods.instantworldmirror.network.DeleteStrandedSnapshotPacket;
import com.crabmods.instantworldmirror.network.OpenPersistentMirrorLibraryPacket;
import com.crabmods.instantworldmirror.network.StrandedSnapshotMenuPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

public class StrandedSnapshotScreen extends Screen {
    private static final int PAGE_SIZE = 6;

    private final BlockPos targetPos;
    private final List<StrandedSnapshotMenuPacket.Entry> entries;
    @Nullable
    private final Screen parent;
    private int page;
    @Nullable
    private java.util.UUID pendingDeleteId;

    private StrandedSnapshotScreen(BlockPos targetPos, List<StrandedSnapshotMenuPacket.Entry> entries,
                                   @Nullable Screen parent) {
        super(Component.translatable("message.instantworldmirror.stranded.open.title"));
        this.targetPos = targetPos.immutable();
        this.entries = List.copyOf(entries);
        this.parent = parent;
    }

    public static void open(BlockPos targetPos, List<StrandedSnapshotMenuPacket.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.screen instanceof StrandedSnapshotScreen current
                ? current.parent
                : minecraft.screen;
        minecraft.setScreen(new StrandedSnapshotScreen(targetPos, entries, parent));
    }

    @Override
    protected void init() {
        clearWidgets();
        int width = Math.min(360, this.width - 32);
        int left = (this.width - width) / 2;
        int top = Math.max(42, this.height / 2 - 88);
        addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.library.persistent"),
                        button -> PacketDistributor.sendToServer(new OpenPersistentMirrorLibraryPacket()))
                .bounds(left, top, width, 20)
                .build());
        top += 24;
        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            StrandedSnapshotMenuPacket.Entry entry = entries.get(index);
            Component label = Component.translatable(
                    "message.instantworldmirror.stranded.open.entry", entry.name(), entry.radius());
            if (!entry.available()) {
                label = label.copy().append(Component.translatable(
                        "message.instantworldmirror.stranded.open.unavailable"));
            }
            Button openButton = addRenderableWidget(Button.builder(label, button -> open(entry))
                    .bounds(left, top + (index - start) * 24, width - 58, 20)
                    .build());
            openButton.active = entry.available();
            Button backupButton = addRenderableWidget(Button.builder(
                            Component.translatable("message.instantworldmirror.stranded.backup"),
                            button -> backup(entry))
                    .bounds(left + width - 52, top + (index - start) * 24, 24, 20)
                    .build());
            backupButton.active = entry.backupAvailable();
            backupButton.setTooltip(Tooltip.create(Component.translatable(
                    "message.instantworldmirror.stranded.backup.tooltip")));
            boolean confirmingDelete = entry.id().equals(pendingDeleteId);
            Button deleteButton = addRenderableWidget(Button.builder(
                            Component.translatable(confirmingDelete
                                    ? "message.instantworldmirror.stranded.delete.confirm"
                                    : "message.instantworldmirror.stranded.delete"),
                            button -> delete(entry))
                    .bounds(left + width - 24, top + (index - start) * 24, 24, 20)
                    .build());
            deleteButton.setTooltip(Tooltip.create(Component.translatable(confirmingDelete
                    ? "message.instantworldmirror.stranded.delete.confirm.tooltip"
                    : "message.instantworldmirror.stranded.delete.tooltip")));
        }

        int navY = top + PAGE_SIZE * 24 + 4;
        Button previous = addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.stranded.previous"),
                        button -> changePage(-1))
                .bounds(left, navY, 86, 20)
                .build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.stranded.next"),
                        button -> changePage(1))
                .bounds(left + width - 86, navY, 86, 20)
                .build());
        next.active = end < entries.size();
        addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.stranded.cancel"),
                        button -> onClose())
                .bounds(left + width / 2 - 43, navY, 86, 20)
                .build());
    }

    private void changePage(int delta) {
        page += delta;
        rebuildWidgets();
    }

    private void open(StrandedSnapshotMenuPacket.Entry entry) {
        PacketDistributor.sendToServer(new OpenStrandedSnapshotPacket(targetPos, entry.id()));
        Minecraft.getInstance().setScreen(parent);
    }

    private void delete(StrandedSnapshotMenuPacket.Entry entry) {
        if (!entry.id().equals(pendingDeleteId)) {
            pendingDeleteId = entry.id();
            rebuildWidgets();
            return;
        }
        pendingDeleteId = null;
        PacketDistributor.sendToServer(new DeleteStrandedSnapshotPacket(targetPos, entry.id()));
    }

    private void backup(StrandedSnapshotMenuPacket.Entry entry) {
        pendingDeleteId = null;
        PacketDistributor.sendToServer(new BackupStrandedSnapshotPacket(targetPos, entry.id()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("message.instantworldmirror.stranded.open.empty"),
                    this.width / 2, this.height / 2 - 8, 0xB8B8B8);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
