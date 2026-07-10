package com.crabmods.instantworldmirror.mixin.client;

import com.crabmods.instantworldmirror.client.ClientMirrorConfigAccess;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.OptionsScreen;
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
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            ),
            index = 0
    )
    private LayoutElement instantworldmirror$addMirrorOptionsSection(LayoutElement optionsGrid) {
        LinearLayout section = LinearLayout.vertical().spacing(6);
        section.addChild(optionsGrid);
        section.addChild(
                Button.builder(
                                Component.translatable("message.instantworldmirror.options.button"),
                                button -> ClientMirrorConfigAccess.openFromOptions((OptionsScreen) (Object) this)
                        )
                        .width(200)
                        .build(),
                settings -> settings.alignHorizontallyCenter()
        );
        return section;
    }
}
