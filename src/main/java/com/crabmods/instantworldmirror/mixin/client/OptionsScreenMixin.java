package com.crabmods.instantworldmirror.mixin.client;

import com.crabmods.instantworldmirror.client.ClientMirrorConfigAccess;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void instantworldmirror$addMirrorOptionsSection(
            CallbackInfo ci,
            GridLayout gridLayout,
            GridLayout.RowHelper rowHelper
    ) {
        rowHelper.addChild(
                Button.builder(
                                Component.translatable("message.instantworldmirror.options.button"),
                                button -> ClientMirrorConfigAccess.openFromOptions((OptionsScreen) (Object) this)
                        )
                        .width(200)
                        .build(),
                2,
                rowHelper.newCellSettings().paddingTop(6).alignHorizontallyCenter()
        );
    }
}
