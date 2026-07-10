package com.crabmods.instantworldmirror.mixin.client;

import com.crabmods.instantworldmirror.client.ClientMirrorConfigAccess;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            ),
            index = 0
    )
    private LayoutElement instantworldmirror$addMirrorOptionsSection(LayoutElement doneButton) {
        LinearLayout section = new LinearLayout(200, 0, LinearLayout.Orientation.VERTICAL);
        section.addChild(
                Button.builder(
                                Component.translatable("message.instantworldmirror.options.button"),
                                button -> ClientMirrorConfigAccess.openFromOptions((OptionsScreen) (Object) this)
                        )
                        .width(200)
                        .build()
        );
        section.addChild(doneButton, section.newChildLayoutSettings().paddingTop(6));
        return section;
    }
}
