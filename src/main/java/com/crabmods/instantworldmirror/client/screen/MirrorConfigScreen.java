package com.crabmods.instantworldmirror.client.screen;

import com.crabmods.instantworldmirror.InstantWorldMirror;
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
    private static final int COOLDOWN_WIDTH = 72;

    private MirrorConfigState state;
    @Nullable
    private final Screen parent;
    private final boolean serverBacked;
    private final EnumMap<MirrorKind, EditBox> radiusBoxes = new EnumMap<>(MirrorKind.class);
    @Nullable
    private EditBox cooldownBox;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int accessX;
    private int mobX;
    private int itemX;
    private int radiusX;
    private int rowStep = ROW_HEIGHT;
    private int firstRowOffset = 76;
    private Component restartHint = Component.empty();
    private List<FormattedCharSequence> helpLines = List.of();
    @Nullable
    private Button saveButton;
    private boolean saving;

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
        commitNumericInputs();
        return state;
    }

    @Override
    protected void init() {
        radiusBoxes.clear();
        this.panelWidth = Math.min(620, Math.max(360, this.width - 24));
        this.restartHint = Component.translatable("message.instantworldmirror.config.gui.restart_hint");
        this.helpLines = this.font.split(restartHint, panelWidth - 40);
        int rowCount = MirrorKind.values().length;
        int desiredPanelHeight = 112 + helpLines.size() * 12 + (rowCount + 1) * ROW_HEIGHT + 34;
        this.panelHeight = Math.min(this.height - 24, desiredPanelHeight);
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelTop = (this.height - panelHeight) / 2;
        this.firstRowOffset = panelHeight < desiredPanelHeight ? 60 : 76;
        int footerY = panelTop + panelHeight - 30;
        int availableRowHeight = footerY - (panelTop + firstRowOffset + helpLines.size() * 12) - BUTTON_HEIGHT - 4;
        this.rowStep = Math.max(BUTTON_HEIGHT + 1,
                Math.min(ROW_HEIGHT, availableRowHeight / Math.max(1, rowCount)));
        this.radiusX = panelLeft + panelWidth - 22 - RADIUS_WIDTH;
        this.itemX = radiusX - GAP - TOGGLE_WIDTH;
        this.mobX = itemX - GAP - TOGGLE_WIDTH;
        this.accessX = mobX - GAP - ACCESS_WIDTH;

        addRenderableWidget(Button.builder(Component.literal("\u00D7"), button -> this.onClose())
                .bounds(panelLeft + panelWidth - 24, panelTop + 8, 16, 16)
                .build());

        int y = firstRowY();
        MirrorKind[] kinds = MirrorKind.values();
        for (int index = 0; index < kinds.length; index++) {
            addSettingsRow(kinds[index], y + rowStep * index);
        }
        addCooldownRow(y + rowStep * kinds.length);

        int buttonWidth = (panelWidth - 56) / 2;
        this.saveButton = addRenderableWidget(
                Button.builder(Component.translatable("message.instantworldmirror.config.button.save"), button -> save())
                        .bounds(panelLeft + 24, footerY, buttonWidth, BUTTON_HEIGHT)
                        .build());
        addRenderableWidget(Button.builder(Component.translatable("message.instantworldmirror.config.button.cancel"), button -> this.onClose())
                .bounds(panelLeft + 32 + buttonWidth, footerY, buttonWidth, BUTTON_HEIGHT)
                .build());
    }

    private int firstRowY() {
        return panelTop + firstRowOffset + helpLines.size() * 12;
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

    private void addCooldownRow(int y) {
        int x = panelLeft + panelWidth - 24 - COOLDOWN_WIDTH;
        cooldownBox = new EditBox(
                this.font,
                x,
                y,
                COOLDOWN_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("message.instantworldmirror.config.global.mirror_cooldown")
        );
        cooldownBox.setMaxLength(4);
        cooldownBox.setValue(Integer.toString(state.mirrorCooldownSeconds()));
        cooldownBox.setResponder(this::updateCooldown);
        addRenderableWidget(cooldownBox);
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

    private void updateCooldown(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            state = state.withMirrorCooldownSeconds(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            cooldownBox.setValue(Integer.toString(state.mirrorCooldownSeconds()));
        }
    }

    private void commitNumericInputs() {
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
        if (cooldownBox != null) {
            int value = state.mirrorCooldownSeconds();
            try {
                value = Integer.parseInt(cooldownBox.getValue());
            } catch (NumberFormatException ignored) {
                // Keep the last valid value.
            }
            value = MirrorConfigState.clampMirrorCooldownSeconds(value);
            state = state.withMirrorCooldownSeconds(value);
            cooldownBox.setValue(Integer.toString(value));
        }
    }

    private void save() {
        if (saving) {
            return;
        }
        commitNumericInputs();
        if (serverBacked) {
            setSaving(true);
            PacketDistributor.sendToServer(new SaveMirrorConfigPacket(state));
        } else {
            try {
                com.crabmods.instantworldmirror.MirrorConfig.saveMirrorConfigState(state);
            } catch (RuntimeException exception) {
                InstantWorldMirror.LOGGER.error("Failed to save local mirror configuration", exception);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.translatable("message.instantworldmirror.config.save_failed"), false);
                }
                return;
            }
            Minecraft.getInstance().setScreen(parent);
        }
    }

    public static void handleSaveResult(boolean success) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MirrorConfigScreen screen) {
            screen.finishServerSave(success);
        }
    }

    private void finishServerSave(boolean success) {
        if (!saving) {
            return;
        }
        setSaving(false);
        if (success) {
            Minecraft.getInstance().setScreen(parent);
        }
    }

    private void setSaving(boolean saving) {
        this.saving = saving;
        if (saveButton != null) {
            saveButton.active = !saving;
            saveButton.setMessage(Component.translatable(saving
                    ? "message.instantworldmirror.config.button.saving"
                    : "message.instantworldmirror.config.button.save"));
        }
    }

    public boolean savingForTesting() {
        return saving;
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
        MirrorKind[] kinds = MirrorKind.values();
        for (int index = 0; index < kinds.length; index++) {
            drawRowLabel(guiGraphics, kinds[index], rowY + rowStep * index);
        }
        guiGraphics.drawString(
                this.font,
                Component.translatable("message.instantworldmirror.config.global.mirror_cooldown"),
                panelLeft + 24,
                rowY + rowStep * kinds.length + 6,
                TEXT_COLOR,
                false
        );

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
        return !saving;
    }

    @Override
    public void onClose() {
        if (!saving) {
            Minecraft.getInstance().setScreen(parent);
        }
    }
}
