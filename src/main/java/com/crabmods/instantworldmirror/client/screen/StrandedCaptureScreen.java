package com.crabmods.instantworldmirror.client.screen;

import com.crabmods.instantworldmirror.network.CreateStrandedSnapshotPacket;
import com.crabmods.instantworldmirror.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

public class StrandedCaptureScreen extends Screen {
    private final BlockPos targetPos;
    @Nullable
    private final Screen parent;
    private EditBox nameBox;

    private StrandedCaptureScreen(BlockPos targetPos, @Nullable Screen parent) {
        super(Component.translatable("message.instantworldmirror.stranded.capture.title"));
        this.targetPos = targetPos.immutable();
        this.parent = parent;
    }

    public static void open(BlockPos targetPos) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new StrandedCaptureScreen(targetPos, minecraft.screen));
    }

    @Override
    protected void init() {
        int width = Math.min(300, this.width - 32);
        int left = (this.width - width) / 2;
        int top = this.height / 2 - 50;
        nameBox = new EditBox(this.font, left, top, width, 20,
                Component.translatable("message.instantworldmirror.stranded.capture.name"));
        nameBox.setMaxLength(48);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int buttonWidth = (width - 8) / 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.stranded.capture.save"),
                        button -> save())
                .bounds(left, top + 30, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("message.instantworldmirror.stranded.cancel"),
                        button -> onClose())
                .bounds(left + buttonWidth + 8, top + 30, buttonWidth, 20)
                .build());
    }

    private void save() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        ModNetworking.sendToServer(new CreateStrandedSnapshotPacket(targetPos, name));
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 76, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("message.instantworldmirror.stranded.capture.help"),
                this.width / 2, this.height / 2 - 63, 0xB8B8B8);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
