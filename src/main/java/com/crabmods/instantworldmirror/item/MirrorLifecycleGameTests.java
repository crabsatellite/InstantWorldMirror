package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorAccess;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.MirrorConfigMigration;
import com.crabmods.instantworldmirror.MirrorConfigState;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.registry.ModEnchantments;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.MirrorSession;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.PersistentMirrorData;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import com.crabmods.instantworldmirror.world.PersistentMirrorRecord;
import com.crabmods.instantworldmirror.world.WorldCopyService;
import com.mojang.authlib.GameProfile;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(InstantWorldMirror.MODID)
@PrefixGameTestTemplate(false)
public final class MirrorLifecycleGameTests {
    private static final String TEMPLATE = "mirror_lifecycle_empty";

    private MirrorLifecycleGameTests() {
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void mirrorKindsAndPermanenceEnchanting(GameTestHelper helper) {
        ItemStack dimensionMirror = new ItemStack(ModItems.DIMENSION_MIRROR.get());
        ItemStack heavenMirror = new ItemStack(ModItems.HEAVEN_MIRROR.get());
        ItemStack firstDreamMirror = new ItemStack(ModItems.FIRST_DREAM_MIRROR.get());
        ItemStack nonMirror = new ItemStack(Items.DIAMOND);

        helper.assertTrue(DimensionMirrorItem.getMirrorKind(dimensionMirror) == MirrorKind.DIMENSION,
                "Dimensional mirror stack must resolve to the dimension mirror kind");
        helper.assertTrue(DimensionMirrorItem.getMirrorKind(heavenMirror) == MirrorKind.HEAVEN,
                "Heaven mirror stack must resolve to the heaven mirror kind");
        helper.assertTrue(DimensionMirrorItem.getMirrorKind(firstDreamMirror) == MirrorKind.FIRST_DREAM,
                "First dream mirror stack must resolve to the first dream mirror kind");
        helper.assertFalse(MirrorKind.FIRST_DREAM.isSandbox(),
                "First dream mirror must use default player state");
        helper.assertTrue(MirrorKind.FIRST_DREAM.usesPristineTerrain(),
                "First dream mirror must request pristine generated terrain");
        helper.assertFalse(MirrorKind.FIRST_DREAM.usesHeavenVisuals(),
                "First dream mirror must not reuse heaven mirror visuals");
        helper.assertTrue(MirrorConfig.isMirrorKindEnabled(MirrorKind.DIMENSION),
                "World Reflection Mirror must be enabled by default");
        helper.assertTrue(MirrorConfig.isMirrorKindEnabled(MirrorKind.HEAVEN),
                "Heaven Mirror must be enabled by default");
        helper.assertTrue(MirrorConfig.isMirrorKindEnabled(MirrorKind.FIRST_DREAM),
                "First Dream Mirror must be enabled by default");
        helper.assertFalse(DimensionMirrorItem.hasPermanence(helper.getLevel(), heavenMirror),
                "Fresh heaven mirror must not start permanent");

        MirrorPortalEntity firstDreamPortal = new MirrorPortalEntity(
                helper.getLevel(), 0, 0, 0, UUID.randomUUID(), true, false, null, MirrorKind.FIRST_DREAM);
        helper.assertTrue(firstDreamPortal.getMirrorKind() == MirrorKind.FIRST_DREAM,
                "First dream portals must sync the first dream mirror kind");
        helper.assertFalse(firstDreamPortal.isHeavenPortal(),
                "First dream portals must not render as heaven portals");

        DimensionMirrorItem dimensionMirrorItem = (DimensionMirrorItem) dimensionMirror.getItem();
        DimensionMirrorItem heavenMirrorItem = (DimensionMirrorItem) heavenMirror.getItem();
        DimensionMirrorItem firstDreamMirrorItem = (DimensionMirrorItem) firstDreamMirror.getItem();
        helper.assertTrue(dimensionMirrorItem.canApplyAtEnchantingTable(dimensionMirror, ModEnchantments.PERMANENCE.get()),
                "Permanence enchantment must be valid for the default mirror");
        helper.assertTrue(heavenMirrorItem.canApplyAtEnchantingTable(heavenMirror, ModEnchantments.PERMANENCE.get()),
                "Permanence enchantment must be valid for the heaven mirror");
        helper.assertTrue(firstDreamMirrorItem.canApplyAtEnchantingTable(firstDreamMirror, ModEnchantments.PERMANENCE.get()),
                "Permanence enchantment must be valid for the first dream mirror");
        helper.assertTrue(dimensionMirrorItem.canApplyAtEnchantingTable(dimensionMirror, Enchantments.BLOCK_EFFICIENCY),
                "Efficiency must remain valid for mirror cooldown reduction");
        helper.assertFalse(dimensionMirrorItem.canApplyAtEnchantingTable(dimensionMirror, ModEnchantments.RENEWAL.get()),
                "Renewal must not be valid for the default mirror");
        helper.assertFalse(heavenMirrorItem.canApplyAtEnchantingTable(heavenMirror, ModEnchantments.RENEWAL.get()),
                "Renewal must not be valid for the heaven mirror");
        helper.assertTrue(firstDreamMirrorItem.canApplyAtEnchantingTable(firstDreamMirror, ModEnchantments.RENEWAL.get()),
                "Renewal must be valid for first dream mirrors");

        ModEnchantments.applyPermanence(helper.getLevel(), nonMirror);
        helper.assertFalse(ModEnchantments.hasPermanence(helper.getLevel(), nonMirror),
                "Permanence helper must ignore non-mirror items");
        helper.assertTrue(nonMirror.getEnchantmentLevel(ModEnchantments.PERMANENCE.get()) == 0,
                "Permanence must not be applied to non-mirror items");

        ModEnchantments.applyPermanence(helper.getLevel(), heavenMirror);
        helper.assertTrue(DimensionMirrorItem.hasPermanence(helper.getLevel(), heavenMirror),
                "Permanence helper must mark the stack as permanent");
        helper.assertTrue(heavenMirror.getEnchantmentLevel(ModEnchantments.PERMANENCE.get()) == 1,
                "Permanence must be applied exactly once");
        helper.assertTrue(heavenMirror.hasFoil(),
                "Permanence enchanted mirrors must show enchantment glint");

        ModEnchantments.applyRenewal(helper.getLevel(), heavenMirror);
        helper.assertFalse(DimensionMirrorItem.hasGeneratedContentRefresh(helper.getLevel(), heavenMirror),
                "Renewal helper must ignore non-first-dream mirrors");
        helper.assertTrue(heavenMirror.getEnchantmentLevel(ModEnchantments.RENEWAL.get()) == 0,
                "Renewal must not be applied to the heaven mirror");

        ModEnchantments.applyRenewal(helper.getLevel(), firstDreamMirror);
        helper.assertTrue(DimensionMirrorItem.hasGeneratedContentRefresh(helper.getLevel(), firstDreamMirror),
                "Renewal helper must mark first dream mirrors as generated content refresh capable");
        helper.assertTrue(firstDreamMirror.getEnchantmentLevel(ModEnchantments.RENEWAL.get()) == 1,
                "Renewal must be applied exactly once");
        helper.assertTrue(firstDreamMirror.hasFoil(),
                "Renewal enchanted first dream mirrors must show enchantment glint");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void disabledMirrorKindBlocksTemporarySessionCreation(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        clearDimensionPoolTestState(player);
        MirrorConfigState previousActiveAccess = MirrorConfig.activeMirrorConfigState();

        try {
            MirrorConfig.setActiveMirrorConfigStateForTesting(
                    MirrorConfig.activeMirrorConfigState().withAccess(MirrorKind.FIRST_DREAM, MirrorAccess.NONE));

            helper.assertFalse(MirrorConfig.isMirrorKindEnabled(MirrorKind.FIRST_DREAM),
                    "First Dream Mirror config toggle must disable only that kind");
            helper.assertTrue(MirrorWorldManager.createSession(
                            player, BlockPos.ZERO, MirrorKind.FIRST_DREAM, false).isEmpty(),
                    "Disabled mirror kinds must not create temporary sessions");
            helper.assertFalse(MirrorWorldManager.hasActiveSession(player.getUUID()),
                    "A blocked mirror kind must not leave an active session behind");

            MirrorSession allowedSession = MirrorWorldManager.createSession(
                    player, BlockPos.ZERO.above(), MirrorKind.DIMENSION, false).orElseThrow();
            helper.assertTrue(allowedSession.getKind() == MirrorKind.DIMENSION,
                    "Disabling one mirror kind must not block other mirror kinds");
        } finally {
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveAccess);
            MirrorWorldManager.clearAllSessions(player.getServer());
            WorldCopyService.clearAllTasks();
            clearDimensionPoolTestState(player);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 320)
    public static void mirrorConfigStrictGateControlsRestartGatedGameBehavior(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        ServerPlayer admin = makeConnectedServerPlayer(helper);
        admin.getServer().getPlayerList().op(admin.getGameProfile());
        helper.assertTrue(MirrorConfig.canManageConfig(admin),
                "Connected GameTest admin player must have operator permissions before strict gate runs");

        MirrorConfigState previousConfigured = MirrorConfig.configuredMirrorConfigState();
        MirrorConfigState previousActive = MirrorConfig.activeMirrorConfigState();
        boolean previousSourceMobRule = helper.getLevel().getGameRules()
                .getRule(GameRules.RULE_DOMOBSPAWNING)
                .get();

        try {
            MirrorConfig.setRuntimeMobSpawning(null);

            MirrorConfigState accessBase = strictGateBaseConfigState(1);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(accessBase);
            MirrorConfig.setActiveMirrorConfigStateForTesting(accessBase);
            int accessChecks = assertAccessRestartGateWithMirrorUse(helper, player, admin, accessBase);

            MirrorConfigState optionBase = strictGateBaseConfigState(1);
            MirrorConfigState optionTarget = strictGateOptionTargetState(optionBase);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(optionBase);
            MirrorConfig.setActiveMirrorConfigStateForTesting(optionBase);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(optionTarget);

            int behaviorChecks = 0;
            int scenarioIndex = 0;
            for (MirrorKind kind : MirrorKind.values()) {
                assertItemTransferActiveBehavior(helper, kind, false, scenarioIndex++);
                behaviorChecks++;
                behaviorChecks += assertWorldCopyActiveBehavior(helper, kind, false, 1, scenarioIndex++);
            }

            MirrorConfig.refreshServerConfigSnapshot();

            for (MirrorKind kind : MirrorKind.values()) {
                int targetRadius = optionTarget.get(kind).copyChunkRadius();
                assertItemTransferActiveBehavior(helper, kind, true, scenarioIndex++);
                behaviorChecks++;
                behaviorChecks += assertWorldCopyActiveBehavior(helper, kind, true, targetRadius, scenarioIndex++);
            }

            InstantWorldMirror.LOGGER.info(
                    "IWM_GAME_CONFIG_STRICT_GATE_METRICS mirrors={} settings={} accessChecks={} behaviorChecks={} restartPhases={}",
                    MirrorKind.values().length,
                    MirrorKind.values().length * 4,
                    accessChecks,
                    behaviorChecks,
                    2
            );
        } finally {
            MirrorConfig.setRuntimeMobSpawning(null);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(previousConfigured);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActive);
            helper.getLevel().getGameRules()
                    .getRule(GameRules.RULE_DOMOBSPAWNING)
                    .set(previousSourceMobRule, helper.getLevel().getServer());
            admin.getServer().getPlayerList().deop(admin.getGameProfile());
            MirrorWorldManager.clearAllSessions(player.getServer());
            WorldCopyService.clearAllTasks();
            clearDimensionPoolTestState(player);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void mirrorConfigMigrationTranslatesLegacyAccessValues(GameTestHelper helper) {
        Path path = null;
        try {
            path = Files.createTempFile("instantworldmirror-legacy-access", ".toml");
            Files.writeString(path, String.join(System.lineSeparator(),
                    "enableWorldReflectionMirror = false",
                    "enableHeavenMirror = \"O\"",
                    "firstDreamMirrorAccess = \"X\"",
                    "enableFirstDreamMirror = true",
                    "enableMobSpawning = false",
                    "allowItemTransfer = true",
                    "copyChunkRadius = 12"));

            MirrorConfigMigration.migrateCommonConfig(path);
            try (CommentedFileConfig config = CommentedFileConfig.builder(path).build()) {
                config.load();
                helper.assertFalse(config.contains("enableWorldReflectionMirror"),
                        "Migration must remove legacy world reflection enable key");
                helper.assertFalse(config.contains("enableHeavenMirror"),
                        "Migration must remove legacy heaven enable key");
                helper.assertFalse(config.contains("enableFirstDreamMirror"),
                        "Migration must remove legacy first dream enable key");
                helper.assertTrue("NONE".equals(config.get("worldReflectionMirrorAccess")),
                        "Legacy false must migrate to NONE");
                helper.assertTrue("ALL".equals(config.get("heavenMirrorAccess")),
                        "Legacy O must migrate to ALL");
                helper.assertTrue("NONE".equals(config.get("firstDreamMirrorAccess")),
                        "Existing legacy X access must normalize to NONE and must not be overwritten");
                helper.assertFalse(Boolean.TRUE.equals(config.get("worldReflectionMirrorMobSpawning")),
                        "Legacy mob spawning must initialize World Reflection Mirror mob spawning");
                helper.assertFalse(Boolean.TRUE.equals(config.get("heavenMirrorMobSpawning")),
                        "Legacy mob spawning must initialize Heaven Mirror mob spawning");
                helper.assertFalse(Boolean.TRUE.equals(config.get("firstDreamMirrorMobSpawning")),
                        "Existing legacy mob spawning must preserve First Dream behavior on upgrade");
                helper.assertTrue(Boolean.TRUE.equals(config.get("worldReflectionMirrorItemTransfer")),
                        "Legacy item transfer must initialize World Reflection Mirror item transfer");
                helper.assertTrue(Boolean.TRUE.equals(config.get("heavenMirrorItemTransfer")),
                        "Legacy item transfer must initialize Heaven Mirror item transfer");
                helper.assertTrue(Boolean.TRUE.equals(config.get("firstDreamMirrorItemTransfer")),
                        "Legacy item transfer must initialize First Dream item transfer");
                helper.assertTrue(((Number) config.get("worldReflectionMirrorCopyChunkRadius")).intValue() == 12,
                        "Legacy copy radius must initialize World Reflection Mirror copy radius");
                helper.assertTrue(((Number) config.get("heavenMirrorCopyChunkRadius")).intValue() == 12,
                        "Legacy copy radius must initialize Heaven Mirror copy radius");
                helper.assertTrue(((Number) config.get("firstDreamMirrorCopyChunkRadius")).intValue() == 12,
                        "Legacy copy radius must initialize First Dream copy radius");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Legacy access migration test failed", e);
        } finally {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            }
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void renewalRefreshUsesItemCooldownAndCreativeBypass(GameTestHelper helper) {
        ItemStack firstDreamMirror = new ItemStack(ModItems.FIRST_DREAM_MIRROR.get());
        ItemStack heavenMirror = new ItemStack(ModItems.HEAVEN_MIRROR.get());
        ModEnchantments.applyRenewal(helper.getLevel(), firstDreamMirror);
        addEfficiency(firstDreamMirror, 2);

        try {
            MirrorConfig.setRuntimeMobSpawning(false);
            helper.assertTrue(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror),
                    "Renewal must be ready when the item cooldown is clear and generated content is disabled");

            DimensionMirrorItem.markGeneratedContentRefreshUsed(helper.getLevel(), heavenMirror);
            helper.assertTrue(DimensionMirrorItem.getGeneratedContentRefreshRemainingMillis(heavenMirror) == 0,
                    "Renewal cooldown must ignore non-first-dream mirrors");

            long efficientRenewalMillis =
                    DimensionMirrorItem.calculateGeneratedContentRefreshCooldownMillis(helper.getLevel(), firstDreamMirror);
            long expectedEfficientRenewalMillis =
                    Math.max(30_000L, (long) (DimensionMirrorItem.GENERATED_CONTENT_REFRESH_COOLDOWN_MILLIS * 0.6));
            helper.assertTrue(efficientRenewalMillis == expectedEfficientRenewalMillis,
                    "Renewal cooldown must use the same Efficiency reduction as mirror use cooldown");

            DimensionMirrorItem.markGeneratedContentRefreshUsed(helper.getLevel(), firstDreamMirror);
            helper.assertFalse(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror),
                    "Renewal must respect the efficiency-reduced item cooldown for non-creative use");
            long remainingMillis = DimensionMirrorItem.getGeneratedContentRefreshRemainingMillis(firstDreamMirror);
            helper.assertTrue(remainingMillis > 0
                            && remainingMillis <= efficientRenewalMillis,
                    "Renewal cooldown must be stored on the mirror item stack");
            helper.assertTrue(DimensionMirrorItem.getGeneratedContentRefreshCooldownDurationMillis(firstDreamMirror)
                            == efficientRenewalMillis,
                    "Renewal cooldown duration must be stored for the item cooldown bar");
            helper.assertTrue(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror, true),
                    "Creative use must bypass the Renewal item cooldown");

            MirrorConfig.setRuntimeMobSpawning(true);
            helper.assertFalse(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror, true),
                    "Renewal must not consume or override anything when the unified generation config is already enabled");
        } finally {
            MirrorConfig.setRuntimeMobSpawning(null);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void permanenceDoesNotResetEfficiencyCooldown(GameTestHelper helper) {
        Player player = helper.makeMockSurvivalPlayer();
        ItemStack efficientPermanentMirror = new ItemStack(ModItems.DIMENSION_MIRROR.get());
        addEfficiency(efficientPermanentMirror, 2);
        ModEnchantments.applyPermanence(helper.getLevel(), efficientPermanentMirror);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, efficientPermanentMirror);

        helper.assertTrue(DimensionMirrorItem.findMirrorStack(player) == efficientPermanentMirror,
                "Hold-to-return cooldown must find the enchanted mirror in the player's hand");

        int baseSeconds = MirrorConfig.getMirrorCooldownTicks() / 20;
        int efficientSeconds = Math.max(30, (int) (baseSeconds * 0.6));
        helper.assertTrue(
                DimensionMirrorItem.calculateCooldownSeconds(helper.getLevel(), DimensionMirrorItem.findMirrorStack(player)) == efficientSeconds,
                "Efficiency and Permanence together must keep the reduced cooldown");

        ItemStack permanentOnlyMirror = new ItemStack(ModItems.DIMENSION_MIRROR.get());
        ModEnchantments.applyPermanence(helper.getLevel(), permanentOnlyMirror);
        helper.assertTrue(DimensionMirrorItem.calculateCooldownSeconds(helper.getLevel(), permanentOnlyMirror) == baseSeconds,
                "Permanence alone must not change the base cooldown");

        DimensionMirrorItem.clearCooldown(player.getUUID());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void heavenSandboxKeepsPermanenceMirror(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        MirrorWorldManager.setItemTransferPermission(player.getUUID(), false);
        player.getInventory().items.set(3, new ItemStack(Items.DIAMOND));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, true, true);

        ItemStack hotbarMirror = player.getInventory().items.get(0);
        helper.assertTrue(hotbarMirror.getItem() == ModItems.HEAVEN_MIRROR.get(),
                "Heaven sandbox entry must leave a heaven mirror in hotbar slot 0");
        helper.assertTrue(DimensionMirrorItem.hasPermanence(helper.getLevel(), hotbarMirror),
                "Persistent heaven sandbox entry must preserve Permanence on the hotbar mirror");
        helper.assertTrue(player.getInventory().items.get(3).isEmpty(),
                "Sandbox entry must clear normal inventory items");
        helper.assertTrue(player.getEnderChestInventory().getItem(0).isEmpty(),
                "Sandbox entry must clear vanilla ender chest items");

        player.getInventory().items.set(3, new ItemStack(Items.DIRT));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND_BLOCK));
        MirrorWorldManager.restorePlayerForMirrorExit(player);
        helper.assertTrue(player.getInventory().items.get(3).is(Items.DIAMOND),
                "Heaven sandbox without item transfer must restore the saved vanilla inventory");
        helper.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD),
                "Heaven sandbox without item transfer must restore the saved ender chest");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void heavenSandboxItemTransferPermissionAllowsCreativeItems(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        MirrorWorldManager.setItemTransferPermission(player.getUUID(), true);
        player.getInventory().items.set(3, new ItemStack(Items.DIAMOND));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, true, false);

        player.getInventory().items.set(3, new ItemStack(Items.DIRT));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND_BLOCK));
        MirrorWorldManager.restorePlayerForMirrorExit(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "Heaven sandbox item transfer must still restore the original game mode");
        helper.assertTrue(player.getInventory().items.get(3).is(Items.DIRT),
                "Heaven sandbox item transfer permission must allow creative inventory items");
        helper.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.DIAMOND_BLOCK),
                "Heaven sandbox item transfer permission must allow creative ender chest items");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamUsesDefaultPlayerState(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().items.set(3, new ItemStack(Items.DIAMOND, 2));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.FIRST_DREAM, true);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "First dream entry must keep survival mode");
        helper.assertTrue(player.getInventory().items.get(0).isEmpty(),
                "First dream entry must not inject a sandbox mirror into hotbar slot 0");
        helper.assertTrue(player.getInventory().items.get(3).is(Items.DIAMOND),
                "First dream entry must keep vanilla inventory items in place");
        helper.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD),
                "First dream entry must keep vanilla ender chest items in place");

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        player.setGameMode(GameType.CREATIVE);
        player.getInventory().items.set(0, new ItemStack(Items.REDSTONE));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.FIRST_DREAM, false);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                "First dream entry must keep creative mode");
        helper.assertTrue(player.getInventory().items.get(0).is(Items.REDSTONE),
                "First dream creative entry must not clear the player's hotbar");

        player.getInventory().items.set(0, new ItemStack(Items.DIRT));
        MirrorWorldManager.restorePlayerForMirrorExit(player);
        helper.assertTrue(player.getInventory().items.get(0).is(Items.REDSTONE),
                "First dream without item transfer must restore the saved creative hotbar");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void nonSandboxExitRestoresCreativeModeAfterMirrorModeChange(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.CREATIVE);
        player.getInventory().items.set(0, new ItemStack(Items.REDSTONE));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.FIRST_DREAM, false);

        player.setGameMode(GameType.SURVIVAL);
        MirrorWorldManager.syncPlayerAbilitiesToGameMode(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "Mirror-local game mode change must switch the player to survival");
        helper.assertTrue(player.getAbilities().mayBuild,
                "Mirror-local survival mode must keep block breaking permission");
        helper.assertFalse(player.getAbilities().instabuild,
                "Mirror-local survival mode must clear creative instabuild");

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                "Leaving first dream mirror must restore the original creative mode");
        helper.assertTrue(player.getAbilities().mayBuild,
                "Restored creative mode must keep block breaking permission");
        helper.assertTrue(player.getAbilities().instabuild,
                "Restored creative mode must restore creative instabuild");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void nonSandboxInventoryRestoreIgnoresTemporaryMirrorMode(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().items.set(4, new ItemStack(Items.DIAMOND, 2));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.DIMENSION, false);

        player.setGameMode(GameType.CREATIVE);
        player.getInventory().items.set(4, new ItemStack(Items.DIRT));

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "Leaving default mirror must restore the original survival mode");
        helper.assertTrue(player.getInventory().items.get(4).is(Items.DIAMOND),
                "Default mirror must restore survival inventory even after a temporary creative change");
        helper.assertTrue(player.getInventory().items.get(4).getCount() == 2,
                "Default mirror must restore the saved stack count");
        helper.assertTrue(player.getAbilities().mayBuild,
                "Restored survival mode must keep block breaking permission");
        helper.assertFalse(player.getAbilities().instabuild,
                "Restored survival mode must clear creative instabuild");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void nonSandboxItemTransferPermissionAllowsMirrorItems(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        MirrorWorldManager.setItemTransferPermission(player.getUUID(), true);
        player.getInventory().items.set(4, new ItemStack(Items.DIAMOND, 2));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.DIMENSION, false);

        player.setGameMode(GameType.CREATIVE);
        player.getInventory().items.set(4, new ItemStack(Items.DIRT));

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "Default mirror item transfer must still restore the original game mode");
        helper.assertTrue(player.getInventory().items.get(4).is(Items.DIRT),
                "Default mirror item transfer permission must allow mirror-world items");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamItemTransferPermissionAllowsMirrorItems(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        MirrorWorldManager.setItemTransferPermission(player.getUUID(), true);
        player.getInventory().items.set(4, new ItemStack(Items.DIAMOND, 2));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.FIRST_DREAM, false);

        player.getInventory().items.set(4, new ItemStack(Items.DIRT));

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "First dream item transfer must still restore the original game mode");
        helper.assertTrue(player.getInventory().items.get(4).is(Items.DIRT),
                "First dream item transfer permission must allow mirror-world items");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void vanillaEnderChestFollowsItemTransferConfig(GameTestHelper helper) {
        assertEnderChestTransferScenario(helper, MirrorKind.DIMENSION, false);
        assertEnderChestTransferScenario(helper, MirrorKind.DIMENSION, true);
        assertEnderChestTransferScenario(helper, MirrorKind.HEAVEN, false);
        assertEnderChestTransferScenario(helper, MirrorKind.HEAVEN, true);
        assertEnderChestTransferScenario(helper, MirrorKind.FIRST_DREAM, false);
        assertEnderChestTransferScenario(helper, MirrorKind.FIRST_DREAM, true);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 160)
    public static void vanillaChestTransferFollowsItemTransferConfig(GameTestHelper helper) {
        assertVanillaChestTransferScenario(helper, MirrorKind.DIMENSION, false, 0);
        assertVanillaChestTransferScenario(helper, MirrorKind.DIMENSION, true, 1);
        assertVanillaChestTransferScenario(helper, MirrorKind.HEAVEN, false, 2);
        assertVanillaChestTransferScenario(helper, MirrorKind.HEAVEN, true, 3);
        assertVanillaChestTransferScenario(helper, MirrorKind.FIRST_DREAM, false, 4);
        assertVanillaChestTransferScenario(helper, MirrorKind.FIRST_DREAM, true, 5);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void itemTransferCommandsControlMirrorEntryExitInventory(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        boolean previousAllowItemTransfer = MirrorConfig.ALLOW_ITEM_TRANSFER.get();
        try {
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(false);
            player.setGameMode(GameType.SURVIVAL);
            runItemTransferCommand(player, true);

            assertMirrorItemTransferFlow(helper, player, MirrorKind.DIMENSION,
                    true, "minecraft:diamond_block", Items.DIAMOND_BLOCK);
            assertMirrorItemTransferFlow(helper, player, MirrorKind.FIRST_DREAM,
                    true, "minecraft:emerald_block", Items.EMERALD_BLOCK);

            runItemTransferCommand(player, false);
            assertMirrorItemTransferFlow(helper, player, MirrorKind.DIMENSION,
                    false, "minecraft:redstone_block", Items.REDSTONE_BLOCK);
        } finally {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(previousAllowItemTransfer);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 240)
    public static void itemTransferCommandControlsFullMirrorLifecycle(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        boolean previousAllowItemTransfer = MirrorConfig.ALLOW_ITEM_TRANSFER.get();
        MirrorConfigState previousActiveConfig = MirrorConfig.activeMirrorConfigState();
        int previousCopyChunksPerTick = MirrorConfig.COPY_CHUNKS_PER_TICK.get();
        try {
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(false);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withItemTransfer(MirrorKind.DIMENSION, false)
                    .withCopyChunkRadius(MirrorKind.DIMENSION, 1));
            MirrorConfig.COPY_CHUNKS_PER_TICK.set(10);
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            clearDimensionPoolTestState(player);
            player.setGameMode(GameType.SURVIVAL);

            runItemTransferCommand(player, true);
            assertFullMirrorItemTransferFlow(helper, player,
                    "minecraft:diamond_block", Items.DIAMOND_BLOCK, true, 0);
            helper.assertTrue(MirrorWorldManager.getItemTransferPermission(player.getUUID()),
                    "/iwm itemtransfer true must survive returning from a mirror session");
            assertFullMirrorItemTransferFlow(helper, player,
                    "minecraft:emerald_block", Items.EMERALD_BLOCK, true, 1);

            runItemTransferCommand(player, false);
            assertFullMirrorItemTransferFlow(helper, player,
                    "minecraft:redstone_block", Items.REDSTONE_BLOCK, false, 2);
        } finally {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(previousAllowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig);
            MirrorConfig.COPY_CHUNKS_PER_TICK.set(previousCopyChunksPerTick);
            MirrorWorldManager.clearAllSessions(helper.getLevel().getServer());
            WorldCopyService.clearAllTasks();
            runPlayerCommand(player, "clear @s");
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void itemTransferConfigDefaultControlsMirrorExit(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        boolean previousAllowItemTransfer = MirrorConfig.ALLOW_ITEM_TRANSFER.get();
        MirrorConfigState previousActiveConfig = MirrorConfig.activeMirrorConfigState();
        try {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            player.setGameMode(GameType.SURVIVAL);

            MirrorConfig.ALLOW_ITEM_TRANSFER.set(false);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withItemTransfer(MirrorKind.DIMENSION, false));
            player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));
            MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.DIMENSION, false);
            player.getInventory().items.set(4, new ItemStack(Items.DIAMOND_BLOCK));
            MirrorWorldManager.restorePlayerForMirrorExit(player);
            helper.assertTrue(player.getInventory().items.get(4).is(Items.GOLD_INGOT),
                    "Disabled item transfer config must restore the entry inventory");

            MirrorConfig.ALLOW_ITEM_TRANSFER.set(true);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withItemTransfer(MirrorKind.DIMENSION, true));
            player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));
            MirrorWorldManager.preparePlayerForMirrorEntry(player, MirrorKind.DIMENSION, false);
            player.getInventory().items.set(4, new ItemStack(Items.EMERALD_BLOCK));
            MirrorWorldManager.restorePlayerForMirrorExit(player);
            helper.assertTrue(player.getInventory().items.get(4).is(Items.EMERALD_BLOCK),
                    "Enabled item transfer config must keep mirror-world inventory changes");
        } finally {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(previousAllowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void persistentRecordsTrackSourceSession(GameTestHelper helper) {
        UUID recordId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID sourceSessionId = UUID.randomUUID();
        PersistentMirrorRecord record = new PersistentMirrorRecord(
                recordId,
                ownerId,
                sourceSessionId,
                "duplicate guard",
                MirrorKind.HEAVEN,
                0,
                Level.OVERWORLD,
                BlockPos.ZERO,
                BlockPos.ZERO.above(),
                false,
                123L,
                false
        );

        CompoundTag saved = record.save();
        helper.assertTrue(saved.getUUID("source_session").equals(sourceSessionId),
                "Persistent records must write the source session id");

        PersistentMirrorRecord loaded = PersistentMirrorRecord.load(saved);
        helper.assertTrue(loaded.sourceSessionId().equals(sourceSessionId),
                "Persistent records must reload the source session id");

        PersistentMirrorData data = new PersistentMirrorData();
        data.addRecord(loaded);
        helper.assertTrue(data.getRecordBySourceSession(sourceSessionId).orElseThrow() == loaded,
                "Persistent data must find a saved record by source session id");
        helper.assertFalse(data.getRecordBySourceSession(UUID.randomUUID()).isPresent(),
                "Persistent data must not match unrelated source sessions");
        helper.assertTrue(loaded.selector().equals("slot_1"),
                "Persistent records must expose a player-facing slot selector");
        helper.assertTrue(data.getRecordBySelector("slot_1", candidate -> true).orElseThrow() == loaded,
                "Persistent data must resolve player-facing slot selectors");
        helper.assertTrue(data.getRecordBySelector("1", candidate -> true).orElseThrow() == loaded,
                "Persistent data must resolve numeric slot selectors");
        loaded.setName("renamed mirror");
        helper.assertTrue(data.getRecordBySelector("renamed mirror", candidate -> true).orElseThrow() == loaded,
                "Persistent data must resolve a unique renamed mirror by name");
        helper.assertFalse(data.getRecordBySelector("slot_2", candidate -> true).isPresent(),
                "Persistent data must not resolve empty slots");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void persistentDataRemovesUnreadyRecords(GameTestHelper helper) {
        PersistentMirrorData data = new PersistentMirrorData();
        PersistentMirrorRecord unready = testRecord(UUID.randomUUID(), UUID.randomUUID(), 0, false);
        PersistentMirrorRecord ready = testRecord(UUID.randomUUID(), UUID.randomUUID(), 1, true);

        data.addRecord(unready);
        data.addRecord(ready);

        List<PersistentMirrorRecord> removed = data.removeUnreadyRecords();
        helper.assertTrue(removed.size() == 1 && removed.get(0) == unready,
                "Persistent data must return only unready records for recovery cleanup");
        helper.assertFalse(data.getRecord(unready.id()).isPresent(),
                "Interrupted persistent records must be removed from saved data");
        helper.assertTrue(data.getRecord(ready.id()).orElseThrow() == ready,
                "Ready persistent records must survive unready recovery cleanup");
        helper.assertTrue(data.allocateDimensionIndex() == 0,
                "Removing interrupted records must release their persistent slot");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void repairDeadDataSkipsReadyPersistentRecords(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        PersistentMirrorData data = PersistentMirrorData.get(player.getServer());
        for (PersistentMirrorRecord record : List.copyOf(data.records())) {
            data.removeRecord(record.id());
        }

        PersistentMirrorRecord interrupted = testRecord(UUID.randomUUID(), UUID.randomUUID(), 6, false);
        PersistentMirrorRecord ready = testRecord(UUID.randomUUID(), UUID.randomUUID(), 7, true);
        data.addRecord(interrupted);
        data.addRecord(ready);

        PersistentMirrorManager.DeadDataRepairResult result =
                PersistentMirrorManager.repairDeadPersistentData(player.getServer());

        helper.assertTrue(result.recordsRemoved() == 1,
                "Repair must remove exactly one interrupted persistent record");
        helper.assertFalse(data.getRecord(interrupted.id()).isPresent(),
                "Repair must remove interrupted persistent records with no live task or player");
        helper.assertTrue(data.getRecord(ready.id()).isPresent(),
                "Repair must not remove ready persistent records");

        data.removeRecord(ready.id());
        WorldCopyService.clearAllTasks();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void persistentSaveSourceAndShutdownCleanupDoNotLeaveDeadDimensions(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        clearDimensionPoolTestState(player);

        MirrorSession sourceSession = MirrorWorldManager.createSession(
                player, BlockPos.ZERO, MirrorKind.HEAVEN, true).orElseThrow();
        int heldDimIndex = sourceSession.getDimensionIndex();

        helper.assertTrue(dimensionPoolData(player).isMarkedForCleanup(heldDimIndex),
                "Allocated temporary dimensions must be marked dirty for restart cleanup");

        helper.assertTrue(MirrorWorldManager.retainTemporarySourceForPersistentSave(sourceSession),
                "Persistent save must retain its temporary source session while copying");

        MirrorWorldManager.handlePlayerDisconnect(player, player.getServer());
        helper.assertTrue(DimensionPool.getDimensionState(heldDimIndex) == DimensionPool.DimensionState.IN_USE,
                "Disconnect cleanup must not release a temporary source dimension before persistent copy finishes");

        MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSession.getSessionId(), player.getServer());
        helper.assertTrue(DimensionPool.getDimensionState(heldDimIndex) == DimensionPool.DimensionState.CLEANING,
                "Temporary source dimension must enter cleanup once persistent copy releases it");

        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);

        MirrorSession activeSession = MirrorWorldManager.createSession(
                player, BlockPos.ZERO.above(), MirrorKind.DIMENSION, false).orElseThrow();
        int activeDimIndex = activeSession.getDimensionIndex();

        helper.assertTrue(dimensionPoolData(player).isMarkedForCleanup(activeDimIndex),
                "Active temporary dimensions must persist a cleanup marker before shutdown");

        MirrorWorldManager.clearAllSessions(player.getServer());
        helper.assertTrue(DimensionPool.getDimensionState(activeDimIndex) == DimensionPool.DimensionState.CLEANING,
                "Server shutdown cleanup must leave active temporary dimensions marked for cleanup");

        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void startupRecoveryHandlesInterruptedTemporaryCleanup(GameTestHelper helper) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        clearDimensionPoolTestState(player);

        MirrorSession activeSession = MirrorWorldManager.createSession(
                player, BlockPos.ZERO, MirrorKind.FIRST_DREAM, false).orElseThrow();
        int dimIndex = activeSession.getDimensionIndex();

        helper.assertTrue(dimensionPoolData(player).isMarkedForCleanup(dimIndex),
                "Allocated temporary dimensions must persist a cleanup marker before any crash");

        MirrorWorldManager.clearAllSessions(player.getServer());
        WorldCopyService.clearAllTasks();
        helper.assertFalse(WorldCopyService.hasPendingCleanup(dimIndex),
                "Crash simulation must leave no in-memory cleanup task");

        DimensionPool.initializeWithServer(player.getServer());

        if (DimensionPool.getDimensionLevel(player.getServer(), dimIndex) == null) {
            helper.assertTrue(DimensionPool.getDimensionState(dimIndex) == DimensionPool.DimensionState.AVAILABLE,
                    "Startup recovery must release empty dirty temporary dimensions when the mirror world is unavailable");
            helper.assertFalse(dimensionPoolData(player).isMarkedForCleanup(dimIndex),
                    "Startup recovery must clear empty cleanup markers after releasing the temporary dimension");
            helper.assertFalse(WorldCopyService.hasPendingCleanup(dimIndex),
                    "Startup recovery must not queue cleanup without a loaded mirror world or saved cleanup work");
        } else {
            helper.assertTrue(DimensionPool.getDimensionState(dimIndex) == DimensionPool.DimensionState.CLEANING,
                    "Startup recovery must restore dirty temporary dimensions to CLEANING");
            helper.assertTrue(WorldCopyService.hasPendingCleanup(dimIndex),
                    "Startup recovery must requeue cleanup for dirty temporary dimensions");
        }

        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void mirrorCopySkipsNativePhysicsNamespaces(GameTestHelper helper) {
        helper.assertTrue(WorldCopyService.isCopyUnsafeNativePhysicsNamespace("sable"),
                "Sable physics objects must not be copied into disposable mirror dimensions");
        helper.assertTrue(WorldCopyService.isCopyUnsafeNativePhysicsNamespace("sable_rapier"),
                "Sable Rapier native objects must not be copied into disposable mirror dimensions");
        helper.assertTrue(WorldCopyService.isCopyUnsafeNativePhysicsNamespace("synaxis"),
                "Synaxis objects backed by Sable physics must not be copied into disposable mirror dimensions");
        helper.assertFalse(WorldCopyService.isCopyUnsafeNativePhysicsNamespace("minecraft"),
                "Vanilla content must remain copyable");
        helper.assertFalse(WorldCopyService.isCopyUnsafeNativePhysicsNamespace(null),
                "Missing namespaces must not be treated as native physics content");
        helper.succeed();
    }

    private static MirrorConfigState strictGateBaseConfigState(int copyRadius) {
        MirrorConfigState state = MirrorConfigState.defaults();
        for (MirrorKind kind : MirrorKind.values()) {
            state = state
                    .withAccess(kind, MirrorAccess.ALL)
                    .withMobSpawning(kind, false)
                    .withItemTransfer(kind, false)
                    .withCopyChunkRadius(kind, copyRadius);
        }
        return state;
    }

    private static MirrorConfigState strictGateOptionTargetState(MirrorConfigState base) {
        MirrorConfigState state = base;
        for (MirrorKind kind : MirrorKind.values()) {
            state = state
                    .withMobSpawning(kind, true)
                    .withItemTransfer(kind, true)
                    .withCopyChunkRadius(kind, 2 + kind.ordinal());
        }
        return state;
    }

    private static int assertAccessRestartGateWithMirrorUse(GameTestHelper helper, ServerPlayer player,
                                                           ServerPlayer admin, MirrorConfigState initialState) {
        MirrorConfigState state = initialState;
        int checks = 0;
        int scenarioIndex = 0;
        for (MirrorKind kind : MirrorKind.values()) {
            state = state.withAccess(kind, MirrorAccess.ADMIN);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(state);
            helper.assertTrue(useMirrorItemOnBlock(helper, player, kind, scenarioIndex++),
                    kind.id() + " saved ADMIN must not affect normal mirror use before restart");
            checks++;
            MirrorConfig.refreshServerConfigSnapshot();
            helper.assertFalse(useMirrorItemOnBlock(helper, player, kind, scenarioIndex++),
                    kind.id() + " ADMIN must block normal players after restart");
            checks++;
            helper.assertTrue(useMirrorItemOnBlock(helper, admin, kind, scenarioIndex++),
                    kind.id() + " ADMIN must allow admins after restart");
            checks++;

            state = state.withAccess(kind, MirrorAccess.NONE);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(state);
            helper.assertTrue(useMirrorItemOnBlock(helper, admin, kind, scenarioIndex++),
                    kind.id() + " saved NONE must not affect admin mirror use before restart");
            checks++;
            MirrorConfig.refreshServerConfigSnapshot();
            helper.assertFalse(useMirrorItemOnBlock(helper, player, kind, scenarioIndex++),
                    kind.id() + " NONE must block normal players after restart");
            checks++;
            helper.assertFalse(useMirrorItemOnBlock(helper, admin, kind, scenarioIndex++),
                    kind.id() + " NONE must block admins after restart");
            checks++;

            state = state.withAccess(kind, MirrorAccess.ALL);
            MirrorConfig.setConfiguredMirrorConfigStateForTesting(state);
            helper.assertFalse(useMirrorItemOnBlock(helper, player, kind, scenarioIndex++),
                    kind.id() + " saved ALL must not affect normal players before restart from NONE");
            checks++;
            MirrorConfig.refreshServerConfigSnapshot();
            helper.assertTrue(useMirrorItemOnBlock(helper, player, kind, scenarioIndex++),
                    kind.id() + " ALL must allow normal players after restart");
            checks++;
        }
        return checks;
    }

    private static boolean useMirrorItemOnBlock(GameTestHelper helper, ServerPlayer player,
                                                MirrorKind kind, int scenarioIndex) {
        ServerLevel sourceLevel = helper.getLevel();
        BlockPos sourcePos = new BlockPos(24 + scenarioIndex * 4, 64, 24 + kind.ordinal() * 4);
        MirrorWorldManager.clearAllSessions(player.getServer());
        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);

        try {
            sourceLevel.setBlock(sourcePos, Blocks.STONE.defaultBlockState(), 3);
            sourceLevel.removeBlock(sourcePos.above(), false);
            sourceLevel.removeBlock(sourcePos.above(2), false);
            player.teleportTo(sourceLevel, sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 2.5,
                    180.0F, 0.0F);
            player.setGameMode(GameType.SURVIVAL);
            DimensionMirrorItem.clearCooldown(player.getUUID());

            ItemStack mirrorStack = mirrorStackForKind(kind);
            player.setItemInHand(InteractionHand.MAIN_HAND, mirrorStack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sourcePos), Direction.UP, sourcePos, false);
            InteractionResult result = mirrorStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

            return result.consumesAction()
                    && MirrorWorldManager.getPlayerOwnedSession(player.getUUID())
                    .filter(session -> session.getKind() == kind)
                    .isPresent();
        } finally {
            sourceLevel.getEntitiesOfClass(MirrorPortalEntity.class,
                            new net.minecraft.world.phys.AABB(sourcePos.above()).inflate(4.0))
                    .forEach(MirrorPortalEntity::discard);
            sourceLevel.removeBlock(sourcePos, false);
            sourceLevel.removeBlock(sourcePos.above(), false);
            sourceLevel.removeBlock(sourcePos.above(2), false);
            DimensionMirrorItem.clearCooldown(player.getUUID());
            MirrorWorldManager.clearAllSessions(player.getServer());
            WorldCopyService.clearAllTasks();
            clearDimensionPoolTestState(player);
        }
    }

    private static void assertItemTransferActiveBehavior(GameTestHelper helper, MirrorKind kind,
                                                         boolean expectTransfer, int scenarioIndex) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        MirrorWorldManager.clearItemTransferPermission(player.getUUID());
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, kind, false);

        Item expectedItem = scenarioIndex % 2 == 0 ? Items.DIAMOND_BLOCK : Items.EMERALD_BLOCK;
        player.getInventory().items.set(4, new ItemStack(expectedItem));
        MirrorWorldManager.restorePlayerForMirrorExit(player);

        if (expectTransfer) {
            helper.assertTrue(player.getInventory().items.get(4).is(expectedItem),
                    kind.id() + " item-transfer config must let mirror-world inventory changes leave the mirror");
        } else {
            helper.assertTrue(player.getInventory().items.get(4).is(Items.GOLD_INGOT),
                    kind.id() + " item-transfer config must restore the entry inventory when disabled");
        }
        MirrorWorldManager.clearItemTransferPermission(player.getUUID());
    }

    private static int assertWorldCopyActiveBehavior(GameTestHelper helper, MirrorKind kind,
                                                     boolean expectedMobSpawning, int expectedRadius,
                                                     int scenarioIndex) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        ServerLevel sourceLevel = helper.getLevel();
        BlockPos sourcePos = new BlockPos(96 + scenarioIndex * 4, 64, 96 + kind.ordinal() * 4);
        boolean previousSourceMobRule = sourceLevel.getGameRules()
                .getRule(GameRules.RULE_DOMOBSPAWNING)
                .get();

        try {
            sourceLevel.getGameRules()
                    .getRule(GameRules.RULE_DOMOBSPAWNING)
                    .set(false, sourceLevel.getServer());
            clearDimensionPoolTestState(player);
            sourceLevel.setBlock(sourcePos, Blocks.STONE.defaultBlockState(), 3);
            sourceLevel.removeBlock(sourcePos.above(), false);
            sourceLevel.removeBlock(sourcePos.above(2), false);
            player.teleportTo(sourceLevel, sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 2.5,
                    180.0F, 0.0F);
            player.setGameMode(GameType.SURVIVAL);
            DimensionMirrorItem.clearCooldown(player.getUUID());

            ItemStack mirrorStack = mirrorStackForKind(kind);
            player.setItemInHand(InteractionHand.MAIN_HAND, mirrorStack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sourcePos), Direction.UP, sourcePos, false);
            InteractionResult result = mirrorStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(result.consumesAction(), kind.id() + " mirror use must create an entry portal");

            MirrorSession session = MirrorWorldManager.getPlayerOwnedSession(player.getUUID())
                    .orElseThrow(() -> new IllegalStateException("Mirror use did not create a player-owned session"));
            ServerLevel mirrorLevel = DimensionPool.getDimensionLevel(sourceLevel.getServer(), session.getDimensionIndex());
            helper.assertTrue(mirrorLevel != null,
                    "GameTest server must load the session's assigned mirror world");
            MirrorPortalEntity entryPortal = findEntryPortal(sourceLevel, session);
            entryPortal.tick();

            int expectedTotalChunks = (expectedRadius * 2 + 1) * (expectedRadius * 2 + 1);
            int actualTotalChunks = pendingCopyTask(session.getDimensionIndex()).getTotalChunks();
            helper.assertTrue(actualTotalChunks == expectedTotalChunks,
                    kind.id() + " copy radius must create the expected copy task size");
            helper.assertTrue(mirrorLevel.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).get() == expectedMobSpawning,
                    kind.id() + " mob-spawning config must control the mirror world's mob spawning game rule");
            return 2;
        } finally {
            sourceLevel.getEntitiesOfClass(MirrorPortalEntity.class,
                            new net.minecraft.world.phys.AABB(sourcePos.above()).inflate(4.0))
                    .forEach(MirrorPortalEntity::discard);
            sourceLevel.removeBlock(sourcePos, false);
            sourceLevel.removeBlock(sourcePos.above(), false);
            sourceLevel.removeBlock(sourcePos.above(2), false);
            sourceLevel.getGameRules()
                    .getRule(GameRules.RULE_DOMOBSPAWNING)
                    .set(previousSourceMobRule, sourceLevel.getServer());
            DimensionMirrorItem.clearCooldown(player.getUUID());
            MirrorWorldManager.clearAllSessions(player.getServer());
            WorldCopyService.clearAllTasks();
            clearDimensionPoolTestState(player);
        }
    }

    @SuppressWarnings("unchecked")
    private static WorldCopyService.CopyTask pendingCopyTask(int dimensionIndex) {
        try {
            Field field = WorldCopyService.class.getDeclaredField("copyTasks");
            field.setAccessible(true);
            Map<Integer, WorldCopyService.CopyTask> tasks =
                    (Map<Integer, WorldCopyService.CopyTask>) field.get(null);
            WorldCopyService.CopyTask task = tasks.get(dimensionIndex);
            if (task == null) {
                throw new IllegalStateException("No pending copy task for mirror dimension " + dimensionIndex);
            }
            return task;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inspect pending mirror copy task", e);
        }
    }

    private static ItemStack mirrorStackForKind(MirrorKind kind) {
        return switch (kind) {
            case DIMENSION -> new ItemStack(ModItems.DIMENSION_MIRROR.get());
            case HEAVEN -> new ItemStack(ModItems.HEAVEN_MIRROR.get());
            case FIRST_DREAM -> new ItemStack(ModItems.FIRST_DREAM_MIRROR.get());
        };
    }

    private static void clearDimensionPoolTestState(ServerPlayer player) {
        DimensionPool.initializeWithServer(player.getServer());
        for (int dimIndex = 0; dimIndex < ModDimensions.getPoolSize(); dimIndex++) {
            WorldCopyService.cancelCleanupTask(dimIndex);
            DimensionPool.markDimensionAvailable(dimIndex);
        }
    }

    private static DimensionPool.DimensionPoolData dimensionPoolData(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
                DimensionPool.DimensionPoolData::load,
                DimensionPool.DimensionPoolData::new,
                DimensionPool.DimensionPoolData.DATA_NAME
        );
    }

    private static void assertEnderChestTransferScenario(GameTestHelper helper, MirrorKind kind,
                                                         boolean allowItemTransfer) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        boolean previousAllowItemTransfer = MirrorConfig.ALLOW_ITEM_TRANSFER.get();
        MirrorConfigState previousActiveConfig = MirrorConfig.activeMirrorConfigState();
        try {
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(allowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withItemTransfer(kind, allowItemTransfer));
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            player.setGameMode(GameType.SURVIVAL);
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));

            MirrorWorldManager.preparePlayerForMirrorEntry(player, kind, false);

            player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND_BLOCK));
            MirrorWorldManager.restorePlayerForMirrorExit(player);

            ItemStack result = player.getEnderChestInventory().getItem(0);
            if (allowItemTransfer) {
                helper.assertTrue(result.is(Items.DIAMOND_BLOCK),
                        kind.id() + " ender chest must keep mirror-world items when item transfer config is enabled");
            } else {
                helper.assertTrue(result.is(Items.EMERALD),
                        kind.id() + " ender chest must restore saved items when item transfer config is disabled");
            }
        } finally {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(previousAllowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig);
        }
    }

    private static void assertVanillaChestTransferScenario(GameTestHelper helper, MirrorKind kind,
                                                           boolean allowItemTransfer, int scenarioIndex) {
        ServerPlayer player = makeConnectedServerPlayer(helper);
        ServerLevel sourceLevel = helper.getLevel();
        BlockPos sourceChestPos = new BlockPos(8 + scenarioIndex * 4, 64, 8);
        BlockPos mirrorChestPos = sourceChestPos.east();
        boolean previousAllowItemTransfer = MirrorConfig.ALLOW_ITEM_TRANSFER.get();
        MirrorConfigState previousActiveConfig = MirrorConfig.activeMirrorConfigState();

        try {
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(allowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withItemTransfer(kind, allowItemTransfer));
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            setChestItem(sourceLevel, sourceChestPos, new ItemStack(Items.EMERALD));
            setChestItem(sourceLevel, mirrorChestPos, new ItemStack(Items.DIAMOND_BLOCK));

            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));
            MirrorWorldManager.preparePlayerForMirrorEntry(player, kind, false);

            player.getInventory().items.set(4, getChestItem(sourceLevel, mirrorChestPos).copy());
            MirrorWorldManager.restorePlayerForMirrorExit(player);

            helper.assertTrue(getChestItem(sourceLevel, sourceChestPos).is(Items.EMERALD),
                    kind.id() + " mirror chest changes must not write back into the source vanilla chest");
            if (allowItemTransfer) {
                helper.assertTrue(player.getInventory().items.get(4).is(Items.DIAMOND_BLOCK),
                        kind.id() + " vanilla chest loot must be carriable only when item transfer is enabled");
            } else {
                helper.assertTrue(player.getInventory().items.get(4).is(Items.GOLD_INGOT),
                        kind.id() + " vanilla chest loot must not leave the mirror when item transfer is disabled");
            }
        } finally {
            MirrorWorldManager.clearItemTransferPermission(player.getUUID());
            MirrorConfig.ALLOW_ITEM_TRANSFER.set(previousAllowItemTransfer);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig);
            sourceLevel.removeBlock(sourceChestPos, false);
            sourceLevel.removeBlock(mirrorChestPos, false);
        }
    }

    private static void setChestItem(ServerLevel level, BlockPos pos, ItemStack stack) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = requireChest(level, pos);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, ItemStack.EMPTY);
        }
        chest.setItem(0, stack.copy());
        chest.setChanged();
    }

    private static ItemStack getChestItem(ServerLevel level, BlockPos pos) {
        return requireChest(level, pos).getItem(0);
    }

    private static ChestBlockEntity requireChest(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            return chest;
        }
        throw new IllegalStateException("Expected vanilla chest at " + pos + " in " + level.dimension().location());
    }

    private static void assertMirrorItemTransferFlow(GameTestHelper helper, ServerPlayer player, MirrorKind kind,
                                                     boolean expectTransfer, String mirrorWorldItemId, Item expectedItem) {
        runPlayerCommand(player, "clear @s");
        player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));

        MirrorWorldManager.preparePlayerForMirrorEntry(player, kind, false);

        runPlayerCommand(player, "give @s " + mirrorWorldItemId);
        helper.assertTrue(inventoryContains(player, expectedItem),
                "Mirror-world command must give the test item before returning");

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        if (expectTransfer) {
            helper.assertTrue(inventoryContains(player, expectedItem),
                    kind.id() + " enabled item transfer command must let mirror-world items leave the mirror");
        } else {
            helper.assertFalse(inventoryContains(player, expectedItem),
                    kind.id() + " disabled item transfer command must block mirror-world items from leaving the mirror");
            helper.assertTrue(inventoryContains(player, Items.GOLD_INGOT),
                    kind.id() + " disabled item transfer command must restore the entry inventory");
        }
    }

    private static void assertFullMirrorItemTransferFlow(GameTestHelper helper, ServerPlayer player,
                                                        String mirrorWorldItemId, Item expectedItem,
                                                        boolean expectTransfer, int scenarioIndex) {
        runPlayerCommand(player, "clear @s");
        player.getInventory().items.set(4, new ItemStack(Items.GOLD_INGOT));
        DimensionMirrorItem.clearCooldown(player.getUUID());

        ServerLevel sourceLevel = helper.getLevel();
        BlockPos sourcePos = new BlockPos(12 + scenarioIndex * 4, 64, 12);
        sourceLevel.setBlock(sourcePos, Blocks.STONE.defaultBlockState(), 3);
        sourceLevel.removeBlock(sourcePos.above(), false);
        sourceLevel.removeBlock(sourcePos.above(2), false);
        player.teleportTo(sourceLevel, sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 2.5,
                180.0F, 0.0F);

        ItemStack mirrorStack = new ItemStack(ModItems.DIMENSION_MIRROR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, mirrorStack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sourcePos), Direction.UP, sourcePos, false);
        InteractionResult result = mirrorStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(result.consumesAction(), "Using the mirror item must create an entry portal");

        MirrorSession session = MirrorWorldManager.getPlayerOwnedSession(player.getUUID())
                .orElseThrow(() -> new IllegalStateException("Mirror use did not create a player-owned session"));
        helper.assertTrue(DimensionPool.getDimensionLevel(sourceLevel.getServer(), session.getDimensionIndex()) != null,
                "GameTest server must load the session's assigned mirror world");
        MirrorPortalEntity entryPortal = findEntryPortal(sourceLevel, session);

        for (int tick = 0; tick < 160 && !session.isCopyComplete(); tick++) {
            entryPortal.tick();
            WorldCopyService.processCopyQueues(sourceLevel.getServer());
        }
        helper.assertTrue(session.isCopyComplete(), "Entry portal must finish the world copy before teleporting");

        player.teleportTo(sourceLevel, entryPortal.getX(), entryPortal.getY(), entryPortal.getZ(),
                player.getYRot(), player.getXRot());
        for (int tick = 0; tick < 20 && !MirrorWorldManager.isInMirrorWorld(player); tick++) {
            entryPortal.tick();
        }

        helper.assertTrue(MirrorWorldManager.isInMirrorWorld(player),
                "Standing in the completed entry portal must teleport the player to the mirror world");
        helper.assertTrue(player.level().dimension().equals(ModDimensions.getMirrorWorld(session.getDimensionIndex())),
                "Entry portal must move the player into the session's assigned mirror world");

        runPlayerCommand(player, "give @s " + mirrorWorldItemId);
        helper.assertTrue(inventoryContains(player, expectedItem),
                "Mirror-world command must give the test item before returning");

        runPlayerCommand(player, "iwm return");
        helper.assertFalse(MirrorWorldManager.isInMirrorWorld(player),
                "/iwm return must move the player out of the mirror world");
        helper.assertTrue(player.level().dimension().equals(sourceLevel.dimension()),
                "/iwm return must restore the player's original dimension");

        if (expectTransfer) {
            helper.assertTrue(inventoryContains(player, expectedItem),
                    "Enabled /iwm itemtransfer must let mirror-world items leave the mirror");
        } else {
            helper.assertFalse(inventoryContains(player, expectedItem),
                    "Disabled /iwm itemtransfer must block mirror-world items from leaving the mirror");
            helper.assertTrue(inventoryContains(player, Items.GOLD_INGOT),
                    "Disabled /iwm itemtransfer must restore the entry inventory");
        }
    }

    private static MirrorPortalEntity findEntryPortal(ServerLevel level, MirrorSession session) {
        return level.getEntitiesOfClass(MirrorPortalEntity.class, new net.minecraft.world.phys.AABB(
                        session.getSourcePosition().above()).inflate(2.0))
                .stream()
                .filter(portal -> !portal.isReturnPortal())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Mirror use did not spawn an entry portal"));
    }

    private static boolean inventoryContains(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().anyMatch(stack -> stack.is(item));
    }

    private static void runPlayerCommand(ServerPlayer player, String command) {
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4),
                command
        );
    }

    private static void runItemTransferCommand(ServerPlayer player, boolean allowed) {
        String command = "iwm itemtransfer @s " + allowed;
        runPlayerCommand(player, command);
        if (MirrorWorldManager.getItemTransferPermission(player.getUUID()) != allowed) {
            throw new IllegalStateException("Command did not update item transfer permission in GameTest: /" + command);
        }
    }

    private static boolean canCreateTemporarySession(ServerPlayer player, MirrorKind kind) {
        MirrorWorldManager.clearAllSessions(player.getServer());
        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);
        boolean created = MirrorWorldManager.createSession(
                player,
                new BlockPos(kind.ordinal() + 1, 64, kind.ordinal() + 1),
                kind,
                false
        ).isPresent();
        MirrorWorldManager.clearAllSessions(player.getServer());
        WorldCopyService.clearAllTasks();
        clearDimensionPoolTestState(player);
        return created;
    }

    private static void addEfficiency(ItemStack stack, int level) {
        stack.enchant(Enchantments.BLOCK_EFFICIENCY, level);
    }

    private static PersistentMirrorRecord testRecord(UUID recordId, UUID sourceSessionId, int dimensionIndex, boolean ready) {
        BlockPos sourcePos = new BlockPos(dimensionIndex, 64, dimensionIndex);
        return new PersistentMirrorRecord(
                recordId,
                UUID.randomUUID(),
                sourceSessionId,
                "record " + dimensionIndex,
                MirrorKind.HEAVEN,
                dimensionIndex,
                Level.OVERWORLD,
                sourcePos,
                sourcePos.above(),
                false,
                dimensionIndex,
                ready
        );
    }

    private static ServerPlayer makeConnectedServerPlayer(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "test" + playerId.toString().replace("-", "").substring(0, 8))
        );
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
        return player;
    }
}
