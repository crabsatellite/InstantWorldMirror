package com.crabmods.instantworldmirror.client.screen;

import com.crabmods.instantworldmirror.network.PersistentMirrorMenuPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public class PersistentMirrorMenuScreen extends Screen {
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF8A7042;
    private static final int TEXT_COLOR = 0xFFEFE6D0;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B0A0;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_HEIGHT = 24;

    private final PersistentMirrorMenuPacket menu;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private List<FormattedCharSequence> contextLines = List.of();
    private List<FormattedCharSequence> statusLines = List.of();

    public PersistentMirrorMenuScreen(PersistentMirrorMenuPacket menu) {
        super(Component.translatable("message.instantworldmirror.persistent.gui.title"));
        this.menu = menu;
    }

    public static void open(PersistentMirrorMenuPacket menu) {
        Minecraft.getInstance().setScreen(new PersistentMirrorMenuScreen(menu));
    }

    @Override
    protected void init() {
        this.panelWidth = Math.min(380, Math.max(260, this.width - 32));
        this.contextLines = this.font.split(contextComponent(), panelWidth - 32);
        this.statusLines = statusComponent().map(component -> this.font.split(component, panelWidth - 32)).orElse(List.of());

        int buttonRows = countButtonRows();
        int textHeight = (contextLines.size() + statusLines.size()) * 12;
        this.panelHeight = Math.min(this.height - 24, 58 + textHeight + buttonRows * ROW_HEIGHT + 18);
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelTop = (this.height - panelHeight) / 2;

        addRenderableWidget(Button.builder(Component.literal("\u00D7"), button -> this.onClose())
                .bounds(panelLeft + panelWidth - 24, panelTop + 8, 16, 16)
                .build());

        int y = panelTop + 44 + textHeight + 8;
        if (PersistentMirrorMenuPacket.MODE_TEMPORARY.equals(menu.mode())) {
            if (menu.showSaveButton()) {
                addWideButton(Component.translatable("message.instantworldmirror.persistent.button.save"),
                        y, () -> runCommand("iwm persistent save"));
                y += ROW_HEIGHT;
            }
            if (menu.showReturnButton()) {
                addWideButton(Component.translatable("message.instantworldmirror.persistent.button.return"),
                        y, () -> runCommand("iwm return"));
            }
        } else if (PersistentMirrorMenuPacket.MODE_INSIDE.equals(menu.mode())) {
            if (menu.showLeaveButton()) {
                addWideButton(Component.translatable("message.instantworldmirror.persistent.button.leave"),
                        y, () -> runCommand("iwm persistent leave"));
                y += ROW_HEIGHT;
            }
            addManageButtons(y, menu.currentSelector(), menu.showRenameButton(), menu.showDeleteButton());
        } else {
            for (PersistentMirrorMenuPacket.Entry entry : menu.entries()) {
                addEntryRow(y, entry);
                y += ROW_HEIGHT;
            }
        }
    }

    private void addWideButton(Component label, int y, Runnable action) {
        addRenderableWidget(Button.builder(label, button -> action.run())
                .bounds(panelLeft + 24, y, panelWidth - 48, BUTTON_HEIGHT)
                .build());
    }

    private void addEntryRow(int y, PersistentMirrorMenuPacket.Entry entry) {
        int left = panelLeft + 24;
        int manageWidth = entry.canManage() ? 94 : 0;
        int enterWidth = panelWidth - 48 - manageWidth;
        Component label = entry.ready()
                ? Component.literal(entry.name())
                : Component.literal(entry.name()).append(Component.translatable("message.instantworldmirror.persistent.status.copying"));
        Button enter = Button.builder(label, button -> runCommand("iwm persistent enter " + entry.selector()))
                .bounds(left, y, enterWidth, BUTTON_HEIGHT)
                .build();
        enter.active = entry.ready();
        addRenderableWidget(enter);
        if (entry.canManage()) {
            int x = left + enterWidth + 4;
            addRenderableWidget(Button.builder(Component.translatable("message.instantworldmirror.persistent.button.rename"),
                    button -> suggestCommand("iwm persistent rename " + entry.selector() + " "))
                    .bounds(x, y, 44, BUTTON_HEIGHT)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("message.instantworldmirror.persistent.button.delete"),
                    button -> runCommand("iwm persistent delete " + entry.selector()))
                    .bounds(x + 48, y, 42, BUTTON_HEIGHT)
                    .build());
        }
    }

    private void addManageButtons(int y, String selector, boolean showRename, boolean showDelete) {
        List<Button> buttons = new ArrayList<>();
        if (showRename) {
            buttons.add(Button.builder(Component.translatable("message.instantworldmirror.persistent.button.rename"),
                    button -> suggestCommand("iwm persistent rename " + selector + " "))
                    .bounds(0, y, 0, BUTTON_HEIGHT)
                    .build());
        }
        if (showDelete) {
            buttons.add(Button.builder(Component.translatable("message.instantworldmirror.persistent.button.delete"),
                    button -> runCommand("iwm persistent delete " + selector))
                    .bounds(0, y, 0, BUTTON_HEIGHT)
                    .build());
        }
        if (buttons.isEmpty()) {
            return;
        }
        int gap = 8;
        int buttonWidth = (panelWidth - 48 - gap * (buttons.size() - 1)) / buttons.size();
        int x = panelLeft + 24;
        for (Button button : buttons) {
            button.setX(x);
            button.setY(y);
            button.setWidth(buttonWidth);
            addRenderableWidget(button);
            x += buttonWidth + gap;
        }
    }

    private int countButtonRows() {
        if (PersistentMirrorMenuPacket.MODE_TEMPORARY.equals(menu.mode())) {
            int rows = 0;
            if (menu.showSaveButton()) rows++;
            if (menu.showReturnButton()) rows++;
            return Math.max(1, rows);
        }
        if (PersistentMirrorMenuPacket.MODE_INSIDE.equals(menu.mode())) {
            int rows = menu.showLeaveButton() ? 1 : 0;
            if (menu.showRenameButton() || menu.showDeleteButton()) rows++;
            return Math.max(1, rows);
        }
        return Math.max(1, menu.entries().size());
    }

    private Component contextComponent() {
        if (PersistentMirrorMenuPacket.MODE_TEMPORARY.equals(menu.mode())) {
            return Component.translatable("message.instantworldmirror.persistent.temporary.current",
                    Component.translatable(menu.kindTranslationKey()));
        }
        if (PersistentMirrorMenuPacket.MODE_INSIDE.equals(menu.mode())) {
            return Component.translatable("message.instantworldmirror.persistent.inside.current", menu.currentName());
        }
        return Component.translatable("message.instantworldmirror.persistent.list.header",
                Component.translatable(menu.kindTranslationKey()));
    }

    private java.util.Optional<Component> statusComponent() {
        if (!menu.statusTranslationKey().isBlank()) {
            return java.util.Optional.of(Component.translatable(menu.statusTranslationKey()).withStyle(ChatFormatting.YELLOW));
        }
        if (PersistentMirrorMenuPacket.MODE_LIST.equals(menu.mode()) && menu.entries().isEmpty()) {
            return java.util.Optional.of(Component.translatable("message.instantworldmirror.persistent.no_accessible")
                    .append(Component.literal(" "))
                    .append(Component.translatable("message.instantworldmirror.persistent.create_hint"))
                    .withStyle(ChatFormatting.YELLOW));
        }
        return java.util.Optional.empty();
    }

    private void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.setScreen(null);
            minecraft.player.connection.sendCommand(command);
        }
    }

    private void suggestCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ChatScreen("/" + command));
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Super.render calls this before rendering buttons; keep the world visible and avoid the 1.21 blur/menu overlay.
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
        int y = panelTop + 32;
        for (FormattedCharSequence line : contextLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, MUTED_TEXT_COLOR);
            y += 12;
        }
        for (FormattedCharSequence line : statusLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, MUTED_TEXT_COLOR);
            y += 12;
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
