package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.network.OpenMirrorConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientMirrorConfigAccess {
    private ClientMirrorConfigAccess() {
    }

    public static void registerConfigScreen(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> createConfigScreen(parent));
    }

    private static Screen createConfigScreen(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return new MirrorConfigScreen(MirrorConfig.configuredMirrorConfigState(), parent, false);
        }

        PacketDistributor.sendToServer(new OpenMirrorConfigPacket());
        return parent;
    }
}
