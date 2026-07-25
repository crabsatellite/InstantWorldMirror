package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorAccess;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strict client UI gate used only by the automated runClient smoke script.
 */
public final class MirrorConfigUiGate {
    private static final String PROPERTY = "instantworldmirror.clientUiGate";
    private static final String ENVIRONMENT = "IWM_CLIENT_UI_GATE";
    private static final String LANGUAGE_ENVIRONMENT = "IWM_CLIENT_UI_GATE_LANGUAGE";
    private static final int SETTINGS_PER_MIRROR = 4;
    private static final int BUTTONS_PER_MIRROR = 3;
    private static final Map<String, String> ENGLISH_LABELS = Map.ofEntries(
            Map.entry("message.instantworldmirror.options.button", "World Mirror Settings..."),
            Map.entry("message.instantworldmirror.config.gui.title", "Mirror Settings"),
            Map.entry("message.instantworldmirror.config.gui.restart_hint",
                    "Changes are saved to the server config and take effect after the server restarts."),
            Map.entry("message.instantworldmirror.config.access.none", "No players"),
            Map.entry("message.instantworldmirror.config.access.admin", "Admins only"),
            Map.entry("message.instantworldmirror.config.access.all", "All players"),
            Map.entry("message.instantworldmirror.config.header.mirror", "Mirror"),
            Map.entry("message.instantworldmirror.config.header.access", "Permission"),
            Map.entry("message.instantworldmirror.config.header.mob_spawning", "Spawning"),
            Map.entry("message.instantworldmirror.config.header.item_transfer", "Items"),
            Map.entry("message.instantworldmirror.config.header.copy_radius", "Radius"),
            Map.entry("message.instantworldmirror.config.global.mirror_cooldown", "Base cooldown (seconds)"),
            Map.entry("message.instantworldmirror.config.toggle.on", "On"),
            Map.entry("message.instantworldmirror.config.toggle.off", "Off"),
            Map.entry("message.instantworldmirror.config.item_transfer.allow", "Allow"),
            Map.entry("message.instantworldmirror.config.item_transfer.block", "Block"),
            Map.entry("message.instantworldmirror.config.button.save", "Save"),
            Map.entry("message.instantworldmirror.config.button.saving", "Saving..."),
            Map.entry("message.instantworldmirror.config.button.cancel", "Cancel"),
            Map.entry("item.instantworldmirror.dimension_mirror", "World Reflection Mirror"),
            Map.entry("item.instantworldmirror.heaven_mirror", "Heaven Mirror"),
            Map.entry("item.instantworldmirror.first_dream_mirror", "First Dream Mirror")
    );
    private static boolean running;
    private static MirrorConfigState previousConfigured;
    private static MirrorConfigState previousActive;

    private MirrorConfigUiGate() {
    }

    public static void runIfEnabled() {
        if (!enabled() || running) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.options == null) {
            return;
        }

        running = true;
        int exitCode = 0;
        boolean passed = false;
        String passedLanguage = "";
        try {
            passedLanguage = runGate(minecraft);
            passed = true;
        } catch (Throwable throwable) {
            InstantWorldMirror.LOGGER.error("IWM_CLIENT_UI_GATE_FAILED", throwable);
            exitCode = 1;
        } finally {
            try {
                restorePreviousConfig();
            } catch (Throwable throwable) {
                InstantWorldMirror.LOGGER.error("IWM_CLIENT_UI_GATE_RESTORE_FAILED", throwable);
                exitCode = 1;
            }
        }
        if (passed && exitCode == 0) {
            InstantWorldMirror.LOGGER.info("IWM_CLIENT_UI_GATE_OK language={}", passedLanguage);
        }
        System.exit(exitCode);
    }

    private static boolean enabled() {
        return Boolean.getBoolean(PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT));
    }

    private static String runGate(Minecraft minecraft) {
        String activeLanguage = minecraft.options.languageCode;
        assertExpectedLanguage(activeLanguage);
        assertUiTranslationCoverage(activeLanguage);

        previousConfigured = MirrorConfig.configuredMirrorConfigState();
        previousActive = MirrorConfig.activeMirrorConfigState();

        MirrorConfigState baseState = strictGateBaseState();
        MirrorConfigState expectedState = baseState;
        MirrorConfig.setConfiguredMirrorConfigStateForTesting(baseState);
        MirrorConfig.setActiveMirrorConfigStateForTesting(baseState);

        assertClientBehaviorMatchesState(baseState, "before UI edits");

        Screen parent = minecraft.screen;
        minecraft.setScreen(new OptionsScreen(parent, minecraft.options));
        assertTrue(minecraft.screen instanceof OptionsScreen, "Options screen did not open");
        Screen optionsScreen = minecraft.screen;

        Button optionsButton = findButton(optionsScreen, "message.instantworldmirror.options.button");
        optionsButton.onPress();

        assertTrue(minecraft.screen instanceof MirrorConfigScreen, "Options button did not open MirrorConfigScreen");
        Screen configScreen = minecraft.screen;
        MirrorConfigScreen mirrorConfigScreen = (MirrorConfigScreen) configScreen;
        assertTrue(localized("message.instantworldmirror.config.gui.title", activeLanguage).equals(configScreen.getTitle().getString()),
                "Mirror config screen title did not use the active language");
        assertTrue(localized("message.instantworldmirror.config.gui.restart_hint", activeLanguage)
                        .equals(mirrorConfigScreen.restartHintText().getString()),
                "Mirror config screen did not expose the restart-required hint in the active language");

        List<Button> settingsButtons = findSettingsButtons(configScreen);
        List<EditBox> numericInputs = findEditBoxes(configScreen);
        assertTrue(settingsButtons.size() == MirrorKind.values().length * BUTTONS_PER_MIRROR,
                "Mirror config screen must expose three setting buttons for every mirror row");
        assertTrue(numericInputs.size() == MirrorKind.values().length + 1,
                "Mirror config screen must expose every radius input and the global cooldown input");

        int uiActions = 0;
        int row = 0;
        for (MirrorKind kind : MirrorKind.values()) {
            Button accessButton = settingsButtons.get(row * BUTTONS_PER_MIRROR);
            Button mobButton = settingsButtons.get(row * BUTTONS_PER_MIRROR + 1);
            Button itemButton = settingsButtons.get(row * BUTTONS_PER_MIRROR + 2);

            assertButtonLabel(accessButton, "message.instantworldmirror.config.access.all");
            assertButtonLabel(mobButton, "message.instantworldmirror.config.toggle.off");
            assertButtonLabel(itemButton, "message.instantworldmirror.config.item_transfer.block");

            MirrorAccess targetAccess = targetAccessForUiGate(kind);
            uiActions += clickAccessToTarget(accessButton, targetAccess);
            expectedState = expectedState.withAccess(kind, targetAccess);

            mobButton.onPress();
            uiActions++;
            assertButtonLabel(mobButton, "message.instantworldmirror.config.toggle.on");
            expectedState = expectedState.withMobSpawning(kind, true);

            itemButton.onPress();
            uiActions++;
            assertButtonLabel(itemButton, "message.instantworldmirror.config.item_transfer.allow");
            expectedState = expectedState.withItemTransfer(kind, true);

            int targetRadius = 7 + row;
            numericInputs.get(row).setValue(Integer.toString(targetRadius));
            uiActions++;
            expectedState = expectedState.withCopyChunkRadius(kind, targetRadius);

            row++;
        }
        int targetCooldownSeconds = 420;
        numericInputs.get(MirrorKind.values().length).setValue(Integer.toString(targetCooldownSeconds));
        uiActions++;
        expectedState = expectedState.withMirrorCooldownSeconds(targetCooldownSeconds);

        assertTrue(mirrorConfigScreen.currentStateForTesting().equals(expectedState),
                "Mirror config screen state must match every UI-edited setting before save");

        Button saveButton = findButton(configScreen, "message.instantworldmirror.config.button.save");
        saveButton.onPress();

        assertTrue(minecraft.screen instanceof OptionsScreen, "Mirror config save did not return to Options screen");
        assertConfiguredState(expectedState, "UI save");
        assertTrue(MirrorConfig.activeMirrorConfigState().equals(baseState),
                "UI save must not change active mirror behavior before the restart snapshot is refreshed");
        assertClientBehaviorMatchesState(baseState, "after save before restart snapshot");

        MirrorConfig.refreshServerConfigSnapshot();
        assertClientBehaviorMatchesState(expectedState, "after restart snapshot");

        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_METRICS language={} mirrors={} settings={} uiActions={} restartChecks={}",
                activeLanguage,
                MirrorKind.values().length,
                MirrorKind.values().length * SETTINGS_PER_MIRROR + 1,
                uiActions,
                (MirrorKind.values().length * SETTINGS_PER_MIRROR + 1) * 2
        );
        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_BEHAVIOR language={} before={} after={}",
                activeLanguage,
                baseState,
                expectedState
        );
        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_LANGUAGE language={} optionsButton=\"{}\" saveButton=\"{}\"",
                activeLanguage,
                localized("message.instantworldmirror.options.button", activeLanguage),
                localized("message.instantworldmirror.config.button.save", activeLanguage)
        );
        return activeLanguage;
    }

    private static MirrorConfigState strictGateBaseState() {
        MirrorConfigState state = MirrorConfigState.defaults();
        for (MirrorKind kind : MirrorKind.values()) {
            state = state
                    .withAccess(kind, MirrorAccess.ALL)
                    .withMobSpawning(kind, false)
                    .withItemTransfer(kind, false)
                    .withCopyChunkRadius(kind, 10);
        }
        return state;
    }

    private static MirrorAccess targetAccessForUiGate(MirrorKind kind) {
        return switch (kind) {
            case DIMENSION -> MirrorAccess.NONE;
            case HEAVEN -> MirrorAccess.ADMIN;
            case FIRST_DREAM -> MirrorAccess.ALL;
        };
    }

    private static int clickAccessToTarget(Button button, MirrorAccess targetAccess) {
        int clicks = 0;
        button.onPress();
        clicks++;
        assertButtonLabel(button, "message.instantworldmirror.config.access.admin");
        if (targetAccess == MirrorAccess.ADMIN) {
            return clicks;
        }

        button.onPress();
        clicks++;
        assertButtonLabel(button, "message.instantworldmirror.config.access.none");
        if (targetAccess == MirrorAccess.NONE) {
            return clicks;
        }

        button.onPress();
        clicks++;
        assertButtonLabel(button, "message.instantworldmirror.config.access.all");
        return clicks;
    }

    private static void assertConfiguredState(MirrorConfigState expected, String phase) {
        for (MirrorKind kind : MirrorKind.values()) {
            assertTrue(MirrorConfig.configuredMirrorSettings(kind).equals(expected.get(kind)),
                    phase + " did not write configured settings for " + kind.id());
        }
        assertTrue(MirrorConfig.configuredMirrorConfigState().mirrorCooldownSeconds()
                        == expected.mirrorCooldownSeconds(),
                phase + " did not write the configured base cooldown");
    }

    private static void assertClientBehaviorMatchesState(MirrorConfigState expected, String phase) {
        for (MirrorKind kind : MirrorKind.values()) {
            assertTrue(MirrorConfig.isMirrorKindEnabled(kind) == (expected.get(kind).access() != MirrorAccess.NONE),
                    phase + " access behavior mismatch for " + kind.id());
            assertTrue(MirrorConfig.isMobSpawningEnabled(kind) == expected.get(kind).mobSpawning(),
                    phase + " mob-spawning behavior mismatch for " + kind.id());
            assertTrue(MirrorConfig.isItemTransferEnabled(kind) == expected.get(kind).itemTransfer(),
                    phase + " item-transfer behavior mismatch for " + kind.id());
            assertTrue(MirrorConfig.copyChunkRadius(kind) == expected.get(kind).copyChunkRadius(),
                    phase + " copy-radius behavior mismatch for " + kind.id());
        }
        assertTrue(MirrorConfig.getMirrorCooldownSeconds() == expected.mirrorCooldownSeconds(),
                phase + " base-cooldown behavior mismatch");
    }

    private static void assertExpectedLanguage(String activeLanguage) {
        String expectedLanguage = System.getenv(LANGUAGE_ENVIRONMENT);
        if (expectedLanguage == null || expectedLanguage.isBlank()) {
            return;
        }
        assertTrue(expectedLanguage.equals(activeLanguage),
                "Client language was '" + activeLanguage + "' but UI gate expected '" + expectedLanguage + "'");
    }

    private static void assertUiTranslationCoverage(String activeLanguage) {
        ENGLISH_LABELS.keySet().forEach(key -> localized(key, activeLanguage));
    }

    static Button findButton(Screen screen, String translationKey) {
        String expected = localized(translationKey, Minecraft.getInstance().options.languageCode);
        List<? extends GuiEventListener> children = screen.children();
        for (GuiEventListener child : children) {
            if (child instanceof Button button && expected.equals(button.getMessage().getString())) {
                return button;
            }
        }
        throw new IllegalStateException("Button not found on " + screen.getClass().getSimpleName() + ": " + expected);
    }

    static List<EditBox> findEditBoxes(Screen screen) {
        List<EditBox> editBoxes = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox editBox) {
                editBoxes.add(editBox);
            }
        }
        return editBoxes;
    }

    static List<Button> findSettingsButtons(Screen screen) {
        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && isSettingsButton(button)) {
                buttons.add(button);
            }
        }
        return buttons;
    }

    private static boolean isSettingsButton(Button button) {
        String label = button.getMessage().getString();
        return labelMatches(label,
                "message.instantworldmirror.config.access.all",
                "message.instantworldmirror.config.access.admin",
                "message.instantworldmirror.config.access.none",
                "message.instantworldmirror.config.toggle.on",
                "message.instantworldmirror.config.toggle.off",
                "message.instantworldmirror.config.item_transfer.allow",
                "message.instantworldmirror.config.item_transfer.block");
    }

    private static boolean labelMatches(String label, String... translationKeys) {
        String language = Minecraft.getInstance().options.languageCode;
        for (String translationKey : translationKeys) {
            if (localized(translationKey, language).equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static void assertButtonLabel(Button button, String translationKey) {
        String expected = localized(translationKey, Minecraft.getInstance().options.languageCode);
        String actual = button.getMessage().getString();
        assertTrue(expected.equals(actual), "Expected button label '" + expected + "' but got '" + actual + "'");
    }

    private static String localized(String translationKey, String activeLanguage) {
        String value = Component.translatable(translationKey).getString();
        assertTrue(!translationKey.equals(value), "Missing translation for " + translationKey + " in " + activeLanguage);
        String englishValue = ENGLISH_LABELS.get(translationKey);
        if (englishValue != null && !"en_us".equals(activeLanguage)) {
            assertTrue(!englishValue.equals(value),
                    "Translation for " + translationKey + " fell back to English in " + activeLanguage);
        }
        return value;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void restorePreviousConfig() {
        if (previousConfigured != null) {
            MirrorConfig.saveMirrorConfigState(previousConfigured);
        }
        if (previousActive != null) {
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActive);
        }
    }
}
