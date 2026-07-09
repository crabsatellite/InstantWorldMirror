package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.network.OpenMirrorConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientMirrorConfigAccess {
    private ClientMirrorConfigAccess() {
    }

    public static void openFromOptions(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            minecraft.setScreen(new MirrorConfigScreen(MirrorConfig.configuredMirrorConfigState(), parent, false));
            return;
        }

        PacketDistributor.sendToServer(new OpenMirrorConfigPacket());
    }
}
