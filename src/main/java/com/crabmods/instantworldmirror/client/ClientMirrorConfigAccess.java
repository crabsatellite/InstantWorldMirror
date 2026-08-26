package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.network.ModNetworking;
import com.crabmods.instantworldmirror.network.OpenMirrorConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ClientMirrorConfigAccess {
    private ClientMirrorConfigAccess() {
    }

    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ClientMirrorConfigAccess::createConfigScreen)
        );
    }

    private static Screen createConfigScreen(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return new MirrorConfigScreen(MirrorConfig.configuredMirrorConfigState(), parent, false);
        }

        ModNetworking.sendToServer(new OpenMirrorConfigPacket());
        return parent;
    }
}
