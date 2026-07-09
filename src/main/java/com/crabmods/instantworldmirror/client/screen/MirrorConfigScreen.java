package com.crabmods.instantworldmirror.client.screen;

import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorKindSettings;
import com.crabmods.instantworldmirror.network.SaveMirrorConfigPacket;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;

public class MirrorConfigScreen extends Screen {
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF8A7042;
    private static final int TEXT_COLOR = 0xFFEFE6D0;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B0A0;
    private static final int ROW_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int ACCESS_WIDTH = 92;
    private static final int TOGGLE_WIDTH = 58;
    private static final int RADIUS_WIDTH = 44;

    private MirrorConfigState state;
    @Nullable
    private final Screen parent;
    private final boolean serverBacked;
    private final EnumMap<MirrorKind, EditBox> radiusBoxes = new EnumMap<>(MirrorKind.class);
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int accessX;
    private int mobX;
    private int itemX;
    private int radiusX;
    private Component restartHint = Component.empty();
    private List<FormattedCharSequence> helpLines = List.of();

    public MirrorConfigScreen(MirrorConfigState state) {
        this(state, null, true);
    }

    public MirrorConfigScreen(MirrorConfigState state, @Nullable Screen parent, boolean serverBacked) {
        super(Component.translatable("message.instantworldmirror.config.gui.title"));
        this.state = state;
        this.parent = parent;
        this.serverBacked = serverBacked;
    }

    public static void open(MirrorConfigState state) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new MirrorConfigScreen(state, minecraft.screen, true));
    }

    public Component restartHintText() {
        return restartHint;
    }

    public MirrorConfigState currentStateForTesting() {
        return state;
    }

    @Override
    protected void init() {
        radiusBoxes.clear();
        this.panelWidth = Math.min(620, Math.max(360, this.width - 24));
        this.restartHint = Component.translatable("message.instantworldmirror.config.gui.restart_hint");
        this.helpLines = this.font.split(restartHint, panelWidth - 40);
        int rowCount = MirrorKind.values().length;
        this.panelHeight = Math.min(this.height - 24, 112 + helpLines.size() * 12 + rowCount * ROW_HEIGHT + 34);
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelTop = (this.height - panelHeight) / 2;
        this.radiusX = panelLeft + panelWidth - 22 - RADIUS_WIDTH;
        this.itemX = radiusX - GAP - TOGGLE_WIDTH;
        this.mobX = itemX - GAP - TOGGLE_WIDTH;
        this.accessX = mobX - GAP - ACCESS_WIDTH;

        addRenderableWidget(Button.builder(Component.literal("\u00D7"), button -> this.onClose())
                .bounds(panelLeft + panelWidth - 24, panelTop + 8, 16, 16)
                .build());

        int y = firstRowY();
        addSettingsRow(MirrorKind.DIMENSION, y);
        addSettingsRow(MirrorKind.HEAVEN, y + ROW_HEIGHT);
        addSettingsRow(MirrorKind.FIRST_DREAM, y + ROW_HEIGHT * 2);

        int footerY = panelTop + panelHeight - 30;
        int buttonWidth = (panelWidth - 56) / 2;
        addRenderableWidget(Button.builder(Component.translatable("message.instantworldmirror.config.button.save"), button -> save())
                .bounds(panelLeft + 24, footerY, buttonWidth, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("message.instantworldmirror.config.button.cancel"), button -> this.onClose())
                .bounds(panelLeft + 32 + buttonWidth, footerY, buttonWidth, BUTTON_HEIGHT)
                .build());
    }

    private int firstRowY() {
        return panelTop + 76 + helpLines.size() * 12;
    }

    private void addSettingsRow(MirrorKind kind, int y) {
        addRenderableWidget(Button.builder(accessLabel(kind), button -> {
                    state = state.cycleAccess(kind);
                    button.setMessage(accessLabel(kind));
                })
                .bounds(accessX, y, ACCESS_WIDTH, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(toggleLabel(state.get(kind).mobSpawning()), button -> {
                    state = state.toggleMobSpawning(kind);
                    button.setMessage(toggleLabel(state.get(kind).mobSpawning()));
                })
                .bounds(mobX, y, TOGGLE_WIDTH, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(itemTransferLabel(state.get(kind).itemTransfer()), button -> {
                    state = state.toggleItemTransfer(kind);
                    button.setMessage(itemTransferLabel(state.get(kind).itemTransfer()));
                })
                .bounds(itemX, y, TOGGLE_WIDTH, BUTTON_HEIGHT)
                .build());

        EditBox radiusBox = new EditBox(this.font, radiusX, y, RADIUS_WIDTH, BUTTON_HEIGHT,
                Component.translatable("message.instantworldmirror.config.header.copy_radius"));
        radiusBox.setMaxLength(2);
        radiusBox.setValue(Integer.toString(state.get(kind).copyChunkRadius()));
        radiusBox.setResponder(value -> updateRadius(kind, value));
        radiusBoxes.put(kind, radiusBox);
        addRenderableWidget(radiusBox);
    }

    private Component accessLabel(MirrorKind kind) {
        return Component.translatable(state.get(kind).access().translationKey());
    }

    private Component toggleLabel(boolean enabled) {
        return Component.translatable(enabled
                ? "message.instantworldmirror.config.toggle.on"
                : "message.instantworldmirror.config.toggle.off");
    }

    private Component itemTransferLabel(boolean enabled) {
        return Component.translatable(enabled
                ? "message.instantworldmirror.config.item_transfer.allow"
                : "message.instantworldmirror.config.item_transfer.block");
    }

    private void updateRadius(MirrorKind kind, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            state = state.withCopyChunkRadius(kind, parsed);
        } catch (NumberFormatException ignored) {
            EditBox box = radiusBoxes.get(kind);
            if (box != null) {
                box.setValue(Integer.toString(state.get(kind).copyChunkRadius()));
            }
        }
    }

    private void commitRadiusBoxes() {
        for (MirrorKind kind : MirrorKind.values()) {
            EditBox box = radiusBoxes.get(kind);
            if (box == null) {
                continue;
            }
            int value = state.get(kind).copyChunkRadius();
            String text = box.getValue();
            if (text != null && !text.isBlank()) {
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    value = state.get(kind).copyChunkRadius();
                }
            }
            value = MirrorKindSettings.clampCopyChunkRadius(value);
            state = state.withCopyChunkRadius(kind, value);
            box.setValue(Integer.toString(value));
        }
    }

    private void save() {
        commitRadiusBoxes();
        if (serverBacked) {
            PacketDistributor.sendToServer(new SaveMirrorConfigPacket(state));
        } else {
            com.crabmods.instantworldmirror.MirrorConfig.saveMirrorConfigState(state);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible behind this small admin panel.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);
        guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_COLOR);
        guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, BORDER_COLOR);
        guiGraphics.fill(panelLeft, panelTop + panelHeight - 1, panelLeft + panelWidth, panelTop + panelHeight, BORDER_COLOR);
        guiGraphics.fill(panelLeft, panelTop, panelLeft + 1, panelTop + panelHeight, BORDER_COLOR);
        guiGraphics.fill(panelLeft + panelWidth - 1, panelTop, panelLeft + panelWidth, panelTop + panelHeight, BORDER_COLOR);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, panelTop + 14, TEXT_COLOR);
        int y = panelTop + 34;
        for (FormattedCharSequence line : helpLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, MUTED_TEXT_COLOR);
            y += 12;
        }

        int headerY = firstRowY() - 16;
        guiGraphics.drawString(this.font, Component.translatable("message.instantworldmirror.config.header.mirror"),
                panelLeft + 24, headerY, MUTED_TEXT_COLOR, false);
        guiGraphics.drawString(this.font, Component.translatable("message.instantworldmirror.config.header.access"),
                accessX, headerY, MUTED_TEXT_COLOR, false);
        guiGraphics.drawString(this.font, Component.translatable("message.instantworldmirror.config.header.mob_spawning"),
                mobX, headerY, MUTED_TEXT_COLOR, false);
        guiGraphics.drawString(this.font, Component.translatable("message.instantworldmirror.config.header.item_transfer"),
                itemX, headerY, MUTED_TEXT_COLOR, false);
        guiGraphics.drawString(this.font, Component.translatable("message.instantworldmirror.config.header.copy_radius"),
                radiusX, headerY, MUTED_TEXT_COLOR, false);

        int rowY = firstRowY();
        drawRowLabel(guiGraphics, MirrorKind.DIMENSION, rowY);
        drawRowLabel(guiGraphics, MirrorKind.HEAVEN, rowY + ROW_HEIGHT);
        drawRowLabel(guiGraphics, MirrorKind.FIRST_DREAM, rowY + ROW_HEIGHT * 2);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawRowLabel(GuiGraphics guiGraphics, MirrorKind kind, int y) {
        String label = this.font.plainSubstrByWidth(
                Component.translatable(kind.translationKey()).getString(),
                Math.max(40, accessX - panelLeft - 36));
        guiGraphics.drawString(this.font, label, panelLeft + 24, y + 6, TEXT_COLOR, false);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
