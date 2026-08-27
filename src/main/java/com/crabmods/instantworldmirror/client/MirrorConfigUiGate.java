package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorAccess;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.screen.MirrorConfigScreen;
import com.crabmods.instantworldmirror.client.screen.StrandedCaptureScreen;
import com.crabmods.instantworldmirror.client.screen.StrandedSnapshotScreen;
import com.crabmods.instantworldmirror.client.screen.PersistentMirrorMenuScreen;
import com.crabmods.instantworldmirror.client.renderer.MirrorItemRenderer;
import com.crabmods.instantworldmirror.network.StrandedSnapshotMenuPacket;
import com.crabmods.instantworldmirror.network.PersistentMirrorMenuPacket;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.gui.ModListScreen;
import net.neoforged.neoforge.client.gui.widget.ModListWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Strict client UI gate used only by the automated runClient smoke script.
 */
public final class MirrorConfigUiGate {
    private static final String PROPERTY = "instantworldmirror.clientUiGate";
    private static final String ENVIRONMENT = "IWM_CLIENT_UI_GATE";
    private static final String LANGUAGE_ENVIRONMENT = "IWM_CLIENT_UI_GATE_LANGUAGE";
    private static final int SETTINGS_PER_MIRROR = 4;
    private static final int BUTTONS_PER_MIRROR = 3;
    private static final Map<String, String> LEGACY_OPTIONS_LABELS = Map.of(
            "en_us", "World Mirror Settings...",
            "zh_cn", "世界之镜设置..."
    );
    private static final Map<String, String> ENGLISH_LABELS = Map.ofEntries(
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
            Map.entry("message.instantworldmirror.stranded.capture.title", "Capture world slice"),
            Map.entry("message.instantworldmirror.stranded.capture.name", "Snapshot name"),
            Map.entry("message.instantworldmirror.stranded.capture.help",
                    "Captures the configured chunk radius around this mirror."),
            Map.entry("message.instantworldmirror.stranded.capture.save", "Capture"),
            Map.entry("message.instantworldmirror.stranded.open.title", "Open world slice"),
            Map.entry("message.instantworldmirror.stranded.open.unavailable", " (unavailable)"),
            Map.entry("message.instantworldmirror.stranded.open.opening", " (opening...)"),
            Map.entry("message.instantworldmirror.stranded.upgrade_failed",
                    "This world slice could not be upgraded safely. It may use missing or incompatible mod content."),
            Map.entry("message.instantworldmirror.stranded.delete.tooltip", "Delete this snapshot"),
            Map.entry("message.instantworldmirror.stranded.delete.confirm.tooltip", "Click again to confirm deletion"),
            Map.entry("message.instantworldmirror.stranded.backup.tooltip", "Back up this world slice"),
            Map.entry("message.instantworldmirror.persistent.button.backup", "Backup"),
            Map.entry("message.instantworldmirror.library.persistent", "Persistent mirror worlds"),
            Map.entry("message.instantworldmirror.library.snapshots", "Cross-save world slices"),
            Map.entry("message.instantworldmirror.stranded.tooltip.capture", "Right-click a block: save a named world slice"),
            Map.entry("message.instantworldmirror.stranded.tooltip.library", "Use in air or Shift-right-click: open long-term mirrors"),
            Map.entry("message.instantworldmirror.stranded.previous", "Previous"),
            Map.entry("message.instantworldmirror.stranded.next", "Next"),
            Map.entry("message.instantworldmirror.stranded.cancel", "Cancel"),
            Map.entry("item.instantworldmirror.dimension_mirror", "World Reflection Mirror"),
            Map.entry("item.instantworldmirror.heaven_mirror", "Heaven Mirror"),
            Map.entry("item.instantworldmirror.first_dream_mirror", "First Dream Mirror"),
            Map.entry("item.instantworldmirror.stranded_mirror", "Stranded Mirror")
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
        assertLegacyOptionsButtonAbsent(minecraft.screen, activeLanguage);

        Button configEntryButton = openFromModList(minecraft, parent);

        assertTrue(minecraft.screen instanceof MirrorConfigScreen, "Mod config button did not open MirrorConfigScreen");
        Screen configScreen = minecraft.screen;
        configScreen.resize(minecraft, 480, 240);
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

        assertTrue(minecraft.screen instanceof ModListScreen, "Mirror config save did not return to the mod list");
        assertConfiguredState(expectedState, "UI save");
        assertTrue(MirrorConfig.activeMirrorConfigState().equals(baseState),
                "UI save must not change active mirror behavior before the restart snapshot is refreshed");
        assertClientBehaviorMatchesState(baseState, "after save before restart snapshot");

        MirrorConfig.refreshServerConfigSnapshot();
        assertClientBehaviorMatchesState(expectedState, "after restart snapshot");
        int strandedUiChecks = assertStrandedMirrorScreens(minecraft);

        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_METRICS language={} mirrors={} settings={} uiActions={} restartChecks={} strandedUiChecks={}",
                activeLanguage,
                MirrorKind.values().length,
                MirrorKind.values().length * SETTINGS_PER_MIRROR + 1,
                uiActions,
                (MirrorKind.values().length * SETTINGS_PER_MIRROR + 1) * 2,
                strandedUiChecks
        );
        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_BEHAVIOR language={} before={} after={}",
                activeLanguage,
                baseState,
                expectedState
        );
        InstantWorldMirror.LOGGER.info(
                "IWM_CLIENT_UI_GATE_LANGUAGE language={} entry=mod_list configButton=\"{}\" saveButton=\"{}\"",
                activeLanguage,
                configEntryButton.getMessage().getString(),
                localized("message.instantworldmirror.config.button.save", activeLanguage)
        );
        return activeLanguage;
    }

    private static int assertStrandedMirrorScreens(Minecraft minecraft) {
        ResourceLocation modelResource = ResourceLocation.tryParse(
                InstantWorldMirror.MODID + ":models/item/stranded_mirror.json");
        ResourceLocation textureResource = ResourceLocation.tryParse(
                InstantWorldMirror.MODID + ":textures/item/stranded_mirror.png");
        assertTrue(modelResource != null && minecraft.getResourceManager().getResource(modelResource).isPresent(),
                "Stranded Mirror item model resource was not loaded by the actual client");
        assertTrue(textureResource != null && minecraft.getResourceManager().getResource(textureResource).isPresent(),
                "Stranded Mirror texture resource was not loaded by the actual client");
        assertTrue(!new net.minecraft.world.item.ItemStack(ModItems.STRANDED_MIRROR.get()).isEmpty()
                        && MirrorItemRenderer.getInstance() != null,
                "Stranded Mirror item and custom renderer must initialize on the actual client");
        Screen parent = minecraft.screen;
        StrandedCaptureScreen.open(BlockPos.ZERO);
        assertTrue(minecraft.screen instanceof StrandedCaptureScreen,
                "Stranded Mirror capture screen did not open");
        assertTrue(localized("message.instantworldmirror.stranded.capture.title", minecraft.options.languageCode)
                        .equals(minecraft.screen.getTitle().getString()),
                "Stranded Mirror capture title was not localized");
        assertTrue(findEditBoxes(minecraft.screen).size() == 1,
                "Stranded Mirror capture screen must expose one snapshot-name input");
        findButton(minecraft.screen, "message.instantworldmirror.stranded.capture.save");
        findButton(minecraft.screen, "message.instantworldmirror.stranded.cancel").onPress();
        assertTrue(minecraft.screen == parent,
                "Stranded Mirror capture cancel button must return to the previous screen");

        List<StrandedSnapshotMenuPacket.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            entries.add(new StrandedSnapshotMenuPacket.Entry(
                    UUID.randomUUID(), "Snapshot " + index, index + 1, index, index != 6, true));
        }
        StrandedSnapshotScreen.open(BlockPos.ZERO, entries);
        assertTrue(minecraft.screen instanceof StrandedSnapshotScreen,
                "Stranded Mirror snapshot selection screen did not open");
        findButton(minecraft.screen, "message.instantworldmirror.library.persistent");
        assertTrue(localized("message.instantworldmirror.stranded.open.title", minecraft.options.languageCode)
                        .equals(minecraft.screen.getTitle().getString()),
                "Stranded Mirror snapshot selection title was not localized");
        findButton(minecraft.screen, "message.instantworldmirror.stranded.next").onPress();
        String unavailableLabel = localized(
                "message.instantworldmirror.stranded.open.unavailable", minecraft.options.languageCode);
        Button unavailableEntry = minecraft.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().contains(unavailableLabel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Stranded Mirror menu did not render an unavailable cross-version entry"));
        assertTrue(!unavailableEntry.active,
                "Unavailable Stranded Mirror entries must not be openable");
        Button unavailableBackup = findButton(minecraft.screen, "message.instantworldmirror.stranded.backup");
        assertTrue(unavailableBackup.active && unavailableBackup.getTooltip() != null,
                "Complete cross-version world slices must remain backupable when they cannot be opened");
        findButton(minecraft.screen, "message.instantworldmirror.stranded.previous").onPress();
        Button backupButton = findButton(minecraft.screen, "message.instantworldmirror.stranded.backup");
        assertTrue(backupButton.active && backupButton.getTooltip() != null,
                "Available world slices must expose the localized backup control");
        Button firstOpenButton = minecraft.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().contains("Snapshot 0"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Stranded Mirror menu did not render its first open control"));
        firstOpenButton.onPress();
        assertTrue(minecraft.screen instanceof StrandedSnapshotScreen
                        && !findButton(minecraft.screen,
                        "message.instantworldmirror.stranded.backup").active
                        && minecraft.screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .anyMatch(button -> button.getMessage().getString().contains(localized(
                                "message.instantworldmirror.stranded.open.opening",
                                minecraft.options.languageCode))),
                "Selecting a world slice must wait for the server and lock conflicting controls");
        StrandedSnapshotScreen.handleOpenResult(entries.get(0).id(), false);
        assertTrue(minecraft.screen instanceof StrandedSnapshotScreen
                        && findButton(minecraft.screen,
                        "message.instantworldmirror.stranded.backup").active,
                "A rejected world-slice open must preserve and restore the selection screen");
        minecraft.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().contains("Snapshot 0"))
                .findFirst()
                .orElseThrow()
                .onPress();
        StrandedSnapshotScreen.handleOpenResult(entries.get(0).id(), true);
        assertTrue(!(minecraft.screen instanceof StrandedSnapshotScreen)
                        && minecraft.screen != parent,
                "A successful world-slice open must close the complete long-term menu stack");
        minecraft.setScreen(parent);
        StrandedSnapshotScreen.open(BlockPos.ZERO, entries);
        Button deleteButton = findButton(minecraft.screen, "message.instantworldmirror.stranded.delete");
        assertTrue(deleteButton.getTooltip() != null,
                "Stranded Mirror delete control must explain its action on hover");
        deleteButton.onPress();
        Button confirmDeleteButton = findButton(
                minecraft.screen, "message.instantworldmirror.stranded.delete.confirm");
        assertTrue(confirmDeleteButton.getTooltip() != null,
                "Stranded Mirror delete confirmation must explain the second click");
        findButton(minecraft.screen, "message.instantworldmirror.stranded.cancel").onPress();
        assertTrue(minecraft.screen == parent,
                "Stranded Mirror selection cancel button must return to the previous screen");

        minecraft.setScreen(new PersistentMirrorMenuScreen(PersistentMirrorMenuPacket.list(
                MirrorKind.STRANDED.translationKey(), List.of(
                        new PersistentMirrorMenuPacket.Entry("Persistent", "slot_1", true, true)))));
        assertTrue(minecraft.screen instanceof PersistentMirrorMenuScreen,
                "Stranded Mirror long-term menu did not open the persistent section");
        findButton(minecraft.screen, "message.instantworldmirror.library.snapshots");
        findButton(minecraft.screen, "message.instantworldmirror.persistent.button.backup");
        minecraft.setScreen(new PersistentMirrorMenuScreen(PersistentMirrorMenuPacket.inside(
                "Persistent", "slot_1", true, true)));
        findButton(minecraft.screen, "message.instantworldmirror.persistent.button.backup");
        minecraft.setScreen(parent);
        return 22;
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
            case STRANDED -> MirrorAccess.NONE;
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
        return findButtonByLabel(screen, expected);
    }

    private static Button findTranslatedButton(Screen screen, String translationKey) {
        return findButtonByLabel(screen, Component.translatable(translationKey).getString());
    }

    private static Button findButtonByLabel(Screen screen, String expected) {
        List<? extends GuiEventListener> children = screen.children();
        for (GuiEventListener child : children) {
            if (child instanceof Button button && expected.equals(button.getMessage().getString())) {
                return button;
            }
        }
        throw new IllegalStateException("Button not found on " + screen.getClass().getSimpleName() + ": " + expected);
    }

    static Button openFromModList(Minecraft minecraft, Screen parent) {
        ModListScreen modListScreen = new ModListScreen(parent);
        minecraft.setScreen(modListScreen);
        assertTrue(minecraft.screen == modListScreen, "Mod list screen did not open");

        ModListWidget modList = null;
        for (GuiEventListener child : modListScreen.children()) {
            if (child instanceof ModListWidget widget) {
                modList = widget;
                break;
            }
        }
        assertTrue(modList != null, "Mod list widget was not present");

        ModListWidget.ModEntry mirrorEntry = modList.children().stream()
                .filter(entry -> InstantWorldMirror.MODID.equals(entry.getInfo().getModId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Instant World Mirror was not listed in the mod menu"));
        modListScreen.setSelected(mirrorEntry);
        modList.setSelected(mirrorEntry);

        Button configButton = findTranslatedButton(modListScreen, "fml.menu.mods.config");
        assertTrue(configButton.active, "Mod config button was disabled for Instant World Mirror");
        configButton.onPress();
        return configButton;
    }

    private static void assertLegacyOptionsButtonAbsent(Screen optionsScreen, String activeLanguage) {
        String legacyLabel = LEGACY_OPTIONS_LABELS.get(activeLanguage);
        if (legacyLabel == null) {
            return;
        }
        for (GuiEventListener child : optionsScreen.children()) {
            if (child instanceof Button button && legacyLabel.equals(button.getMessage().getString())) {
                throw new IllegalStateException("Legacy World Mirror button is still present on the Options screen");
            }
        }
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
