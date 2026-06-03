package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
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
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
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

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void renewalRefreshUsesItemCooldownAndCreativeBypass(GameTestHelper helper) {
        ItemStack firstDreamMirror = new ItemStack(ModItems.FIRST_DREAM_MIRROR.get());
        ItemStack heavenMirror = new ItemStack(ModItems.HEAVEN_MIRROR.get());
        ModEnchantments.applyRenewal(helper.getLevel(), firstDreamMirror);

        try {
            MirrorConfig.setRuntimeMobSpawning(false);
            helper.assertTrue(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror),
                    "Renewal must be ready when the item cooldown is clear and generated content is disabled");

            DimensionMirrorItem.markGeneratedContentRefreshUsed(heavenMirror);
            helper.assertTrue(DimensionMirrorItem.getGeneratedContentRefreshRemainingMillis(heavenMirror) == 0,
                    "Renewal cooldown must ignore non-first-dream mirrors");

            DimensionMirrorItem.markGeneratedContentRefreshUsed(firstDreamMirror);
            helper.assertFalse(DimensionMirrorItem.shouldUseGeneratedContentRefresh(helper.getLevel(), firstDreamMirror),
                    "Renewal must respect the 10 minute item cooldown for non-creative use");
            long remainingMillis = DimensionMirrorItem.getGeneratedContentRefreshRemainingMillis(firstDreamMirror);
            helper.assertTrue(remainingMillis > 0
                            && remainingMillis <= DimensionMirrorItem.GENERATED_CONTENT_REFRESH_COOLDOWN_MILLIS,
                    "Renewal cooldown must be stored on the mirror item stack");
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

        MirrorWorldManager.restorePlayerForMirrorExit(player);
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

        MirrorWorldManager.restorePlayerForMirrorExit(player);
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
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "test-mock-player")
        );
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
        return player;
    }
}
