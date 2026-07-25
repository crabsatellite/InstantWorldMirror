package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;

import java.util.List;

/**
 * Connected-client config gate used by scripts/runclient_config_network_gate.ps1.
 */
public final class MirrorConfigNetworkGate {
    private static final String ENVIRONMENT = "IWM_CLIENT_CONFIG_NETWORK_GATE";
    private static final String EXPECTED_ENVIRONMENT = "IWM_CLIENT_CONFIG_NETWORK_EXPECT_HEAVEN_TRANSFER";
    private static final int MAX_TICKS = 2400;

    private static Stage stage = Stage.WAIT_FOR_WORLD;
    private static int ticks;
    private static MirrorConfigState expectedState;

    private MirrorConfigNetworkGate() {
    }

    public static void runIfEnabled() {
        String mode = System.getenv(ENVIRONMENT);
        if (mode == null || mode.isBlank()) {
            return;
        }
        if (++ticks > MAX_TICKS) {
            fail("Timed out in stage " + stage, null);
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (!hasConnectedIntegratedServer(minecraft)) {
                return;
            }

            if ("verify".equalsIgnoreCase(mode)) {
                verifyRestartState();
                return;
            }

            switch (stage) {
                case WAIT_FOR_WORLD -> {
                    minecraft.setScreen(new OptionsScreen(null, minecraft.options));
                    stage = Stage.OPEN_CONFIG;
                }
                case OPEN_CONFIG -> {
                    if (!(minecraft.screen instanceof OptionsScreen optionsScreen)) {
                        return;
                    }
                    MirrorConfigUiGate.findButton(
                            optionsScreen, "message.instantworldmirror.options.button").onPress();
                    stage = Stage.EDIT_AND_SAVE;
                }
                case EDIT_AND_SAVE -> {
                    if (!(minecraft.screen instanceof MirrorConfigScreen configScreen)) {
                        return;
                    }
                    List<Button> settingsButtons = MirrorConfigUiGate.findSettingsButtons(configScreen);
                    if (settingsButtons.size() != MirrorKind.values().length * 3) {
                        throw new IllegalStateException("Unexpected mirror settings button count: "
                                + settingsButtons.size());
                    }
                    settingsButtons.get(MirrorKind.HEAVEN.ordinal() * 3 + 2).onPress();
                    expectedState = configScreen.currentStateForTesting();

                    MirrorConfigUiGate.findButton(
                            configScreen, "message.instantworldmirror.config.button.save").onPress();
                    if (minecraft.screen != configScreen || !configScreen.savingForTesting()) {
                        throw new IllegalStateException(
                                "Connected config screen closed before the server acknowledged the save");
                    }
                    stage = Stage.WAIT_FOR_ACK;
                }
                case WAIT_FOR_ACK -> {
                    if (minecraft.screen instanceof MirrorConfigScreen) {
                        return;
                    }
                    if (!MirrorConfig.configuredMirrorConfigState().equals(expectedState)) {
                        throw new IllegalStateException("Server acknowledgement arrived before the saved state was visible");
                    }
                    InstantWorldMirror.LOGGER.info(
                            "IWM_CLIENT_CONFIG_NETWORK_SAVE_OK heavenItemTransfer={}",
                            expectedState.get(MirrorKind.HEAVEN).itemTransfer());
                    System.exit(0);
                }
            }
        } catch (Throwable throwable) {
            fail("Connected config gate failed in stage " + stage, throwable);
        }
    }

    private static boolean hasConnectedIntegratedServer(Minecraft minecraft) {
        return minecraft.level != null
                && minecraft.player != null
                && minecraft.getConnection() != null
                && minecraft.getSingleplayerServer() != null;
    }

    private static void verifyRestartState() {
        String rawExpected = System.getenv(EXPECTED_ENVIRONMENT);
        if (!"true".equalsIgnoreCase(rawExpected) && !"false".equalsIgnoreCase(rawExpected)) {
            throw new IllegalStateException("Missing " + EXPECTED_ENVIRONMENT);
        }
        boolean expected = Boolean.parseBoolean(rawExpected);
        boolean configured = MirrorConfig.configuredMirrorConfigState()
                .get(MirrorKind.HEAVEN).itemTransfer();
        boolean active = MirrorConfig.activeMirrorConfigState()
                .get(MirrorKind.HEAVEN).itemTransfer();
        if (configured != expected || active != expected) {
            throw new IllegalStateException("Restarted config mismatch: expected=" + expected
                    + ", configured=" + configured + ", active=" + active);
        }
        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_CONFIG_NETWORK_RESTART_OK heavenItemTransfer={}", expected);
        System.exit(0);
    }

    private static void fail(String message, Throwable throwable) {
        if (throwable == null) {
            InstantWorldMirror.LOGGER.error("IWM_CLIENT_CONFIG_NETWORK_GATE_FAILED {}", message);
        } else {
            InstantWorldMirror.LOGGER.error("IWM_CLIENT_CONFIG_NETWORK_GATE_FAILED " + message, throwable);
        }
        System.exit(1);
    }

    private enum Stage {
        WAIT_FOR_WORLD,
        OPEN_CONFIG,
        EDIT_AND_SAVE,
        WAIT_FOR_ACK
    }
}
