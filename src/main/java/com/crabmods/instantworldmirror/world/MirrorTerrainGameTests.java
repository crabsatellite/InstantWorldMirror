package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.MirrorConfigState;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.Map;

@GameTestHolder(InstantWorldMirror.MODID)
@PrefixGameTestTemplate(false)
public final class MirrorTerrainGameTests {
    private static final String TEMPLATE = "mirror_lifecycle_empty";

    private MirrorTerrainGameTests() {
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void firstDreamTerrainIgnoresCurrentWorldEdits(GameTestHelper helper) {
        BlockPos editedPos = helper.absolutePos(new BlockPos(0, 96, 0));
        helper.getLevel().setBlock(editedPos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);

        ChunkAccess generatedChunk = PristineTerrainGenerator.generateChunk(
                helper.getLevel(),
                editedPos.getX() >> 4,
                editedPos.getZ() >> 4
        );

        helper.assertTrue(generatedChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                "First dream terrain generation must run through biome decoration");
        helper.assertFalse(generatedChunk.getBlockState(editedPos).is(Blocks.GOLD_BLOCK),
                "First dream terrain must not copy player edits from the current world chunk");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "first_dream_loot", timeoutTicks = 40)
    public static void firstDreamLootChestOpensWithGeneratedItemsWhenMobSpawningIsDisabled(GameTestHelper helper) {
        BlockPos generatedChestPos = helper.absolutePos(new BlockPos(0, 2, 1));
        BlockPos copiedChestPos = helper.absolutePos(new BlockPos(2, 2, 1));
        helper.getLevel().setBlock(generatedChestPos, Blocks.CHEST.defaultBlockState(), 3);
        helper.getLevel().setBlock(copiedChestPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity generatedChest = (ChestBlockEntity) helper.getLevel().getBlockEntity(generatedChestPos);
        generatedChest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 123L);
        LevelChunk generatedChunk = (LevelChunk) helper.getLevel().getChunk(generatedChestPos);
        LevelChunk targetChunk = (LevelChunk) helper.getLevel().getChunk(copiedChestPos);

        MirrorConfigState previousActiveConfig = MirrorConfig.activeMirrorConfigState();
        try {
            MirrorConfig.setRuntimeMobSpawning(null);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig
                    .withMobSpawning(MirrorKind.FIRST_DREAM, false));

            WorldCopyService.copyGeneratedBlockEntity(
                    generatedChunk, generatedChestPos, helper.getLevel(), targetChunk, copiedChestPos);

            helper.assertTrue(helper.getLevel().getBlockEntity(copiedChestPos) instanceof ChestBlockEntity,
                    "First Dream generated chest must remain a live chest block entity");
            ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(copiedChestPos);
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            chest.unpackLootTable(player);

            boolean hasGeneratedLoot = false;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (!chest.getItem(slot).isEmpty()) {
                    hasGeneratedLoot = true;
                    break;
                }
            }
            helper.assertTrue(hasGeneratedLoot,
                    "Opening a First Dream generated loot chest must produce items even when mob spawning is disabled");
        } finally {
            MirrorConfig.setRuntimeMobSpawning(null);
            MirrorConfig.setActiveMirrorConfigStateForTesting(previousActiveConfig);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "superflat_terrain", timeoutTicks = 40)
    public static void superflatTerrainUsesVanillaLayersAndIgnoresSourceBlocks(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int chunkX = (origin.getX() >> 4) + 2;
        int chunkZ = origin.getZ() >> 4;
        int surfaceY = Math.max(64, helper.getLevel().getMinBuildHeight() + 8);
        BlockPos center = new BlockPos(chunkX * 16 + 8, surfaceY, chunkZ * 16 + 8);
        BlockPos sourceMarker = center.above(6);
        helper.getLevel().setBlock(sourceMarker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);

        WorldCopyService.CopyTask task = new WorldCopyService.CopyTask(
                UUID.randomUUID(), center, 0, helper.getLevel().dimension(), 0,
                MirrorTerrainMode.SUPERFLAT, false, false);
        int blocksPlaced = WorldCopyService.copyChunk(
                helper.getLevel(), helper.getLevel(), chunkX, chunkZ, task);

        helper.assertTrue(blocksPlaced == 16 * 16 * 4,
                "One superflat chunk must contain exactly four complete layers");
        assertSuperflatColumn(helper, center);
        assertSuperflatColumn(helper, new BlockPos(chunkX * 16, surfaceY, chunkZ * 16));
        assertSuperflatColumn(helper, new BlockPos(chunkX * 16 + 15, surfaceY, chunkZ * 16 + 15));
        helper.assertTrue(helper.getLevel().getBlockState(sourceMarker).isAir(),
                "Superflat generation must not copy source-world blocks above the platform");
        helper.assertTrue(helper.getLevel().getBlockState(center.below(4)).isAir(),
                "Superflat generation must keep the world below the bedrock layer empty");

        helper.succeed();
    }

    private static void assertSuperflatColumn(GameTestHelper helper, BlockPos surfacePos) {
        helper.assertTrue(helper.getLevel().getBlockState(surfacePos).is(Blocks.GRASS_BLOCK),
                "Superflat surface must be grass");
        helper.assertTrue(helper.getLevel().getBlockState(surfacePos.below()).is(Blocks.DIRT)
                        && helper.getLevel().getBlockState(surfacePos.below(2)).is(Blocks.DIRT),
                "Superflat surface must have two dirt layers");
        helper.assertTrue(helper.getLevel().getBlockState(surfacePos.below(3)).is(Blocks.BEDROCK),
                "Superflat base must be bedrock");
    }

    @GameTest(template = TEMPLATE, batch = "stranded_snapshot", timeoutTicks = 80)
    public static void strandedSnapshotRestoresBlocksAndBlockEntityNbtAtAnotherLocation(GameTestHelper helper) {
        var cacheRoot = StrandedSnapshotManager.cacheRootForTesting().toAbsolutePath().normalize();
        helper.assertTrue(cacheRoot.startsWith(
                        net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath().normalize())
                        && !cacheRoot.startsWith(
                        net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize()),
                "Stranded snapshot binaries must use a dedicated game-instance cache outside config and saves");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int sourceChunkX = (origin.getX() >> 4) + 2;
        int sourceChunkZ = origin.getZ() >> 4;
        int targetChunkX = sourceChunkX + 2;
        int targetChunkZ = sourceChunkZ + 2;
        int y = Math.max(64, helper.getLevel().getMinBuildHeight() + 8);

        BlockPos sourceMarker = new BlockPos(sourceChunkX * 16 + 3, y, sourceChunkZ * 16 + 5);
        BlockPos sourceChest = sourceMarker.east();
        helper.getLevel().setBlock(sourceMarker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(sourceChest, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(sourceChest);
        helper.assertTrue(chest != null, "Source snapshot chest must have a block entity");
        chest.setItem(3, new ItemStack(Items.EMERALD, 7));
        chest.setChanged();
        Entity sourceEntity = EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.assertTrue(sourceEntity != null, "Entity exclusion marker must be creatable");
        sourceEntity.moveTo(sourceMarker.getX() + 0.5, y + 1.0, sourceMarker.getZ() + 0.5);
        helper.getLevel().addFreshEntity(sourceEntity);

        UUID snapshotId = UUID.randomUUID();
        StrandedSnapshotManager.SnapshotSummary summary = new StrandedSnapshotManager.SnapshotSummary(
                snapshotId,
                UUID.randomUUID(),
                "GameTest snapshot",
                0,
                new BlockPos(sourceChunkX * 16 + 8, y, sourceChunkZ * 16 + 8),
                System.currentTimeMillis(),
                SharedConstants.getCurrentVersion().getName());
        try {
            CompoundTag captured = StrandedSnapshotManager.captureChunkForTesting(
                    helper.getLevel(), sourceChunkX, sourceChunkZ);
            helper.assertFalse(captured.contains("entities"),
                    "Cross-save snapshots must explicitly exclude normal entities");
            StrandedSnapshotManager.writeSnapshotForTesting(summary, Map.of(0L, captured));

            BlockPos targetCenter = new BlockPos(targetChunkX * 16 + 8, y, targetChunkZ * 16 + 8);
            WorldCopyService.CopyTask task = new WorldCopyService.CopyTask(
                    UUID.randomUUID(), targetCenter, 0, helper.getLevel().dimension(), 0,
                    MirrorTerrainMode.SNAPSHOT, false, false, snapshotId);
            int blocksPlaced = WorldCopyService.copyChunk(
                    null, helper.getLevel(), targetChunkX, targetChunkZ, task);

            BlockPos targetMarker = new BlockPos(targetChunkX * 16 + 3, y, targetChunkZ * 16 + 5);
            BlockPos targetChest = targetMarker.east();
            helper.assertTrue(blocksPlaced > 0,
                    "Stranded snapshot must load cached blocks without reading any current source world");
            helper.assertTrue(helper.getLevel().getBlockState(targetMarker).is(Blocks.DIAMOND_BLOCK),
                    "Stranded snapshot must preserve local block positions at a new world location");
            helper.assertTrue(helper.getLevel().getBlockEntity(targetChest) instanceof ChestBlockEntity,
                    "Stranded snapshot must recreate captured block entities");
            ChestBlockEntity restoredChest = (ChestBlockEntity) helper.getLevel().getBlockEntity(targetChest);
            helper.assertTrue(restoredChest.getItem(3).is(Items.EMERALD)
                            && restoredChest.getItem(3).getCount() == 7,
                    "Stranded snapshot must preserve chest inventory NBT");
            helper.assertTrue(helper.getLevel().getEntities(
                            sourceEntity, new net.minecraft.world.phys.AABB(targetMarker).inflate(4.0)).isEmpty(),
                    "Stranded snapshot loading must not duplicate normal entities across saves");
        } catch (Exception exception) {
            throw new AssertionError("Stranded snapshot round trip failed", exception);
        } finally {
            StrandedSnapshotManager.deleteSnapshotForTesting(snapshotId);
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void firstDreamRegionGeneratorCoversCopyEdges(GameTestHelper helper) {
        BlockPos centerPos = helper.absolutePos(BlockPos.ZERO);
        int centerChunkX = centerPos.getX() >> 4;
        int centerChunkZ = centerPos.getZ() >> 4;
        int edgeChunkX = centerChunkX + 1;
        int edgeChunkZ = centerChunkZ + 1;
        BlockPos editedEdgePos = new BlockPos((edgeChunkX << 4) + 1, 96, (edgeChunkZ << 4) + 1);
        helper.getLevel().setBlock(editedEdgePos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);

        try (PristineTerrainGenerator.Region region = PristineTerrainGenerator.openRegion(helper.getLevel(), centerPos, 1)) {
            ChunkAccess centerChunk = region.generateChunk(centerChunkX, centerChunkZ);
            ChunkAccess edgeChunk = region.generateChunk(edgeChunkX, edgeChunkZ);

            helper.assertTrue(centerChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                    "First dream region generation must complete the center chunk");
            helper.assertTrue(edgeChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                    "First dream region generation must complete edge chunks in one shared copy region");
            helper.assertFalse(edgeChunk.getBlockState(editedEdgePos).is(Blocks.GOLD_BLOCK),
                    "First dream shared region terrain must not copy player edits from edge chunks");
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void firstDreamCopyTaskPreparesWholeRegionBeforeChunkCopy(GameTestHelper helper) {
        BlockPos centerPos = helper.absolutePos(BlockPos.ZERO);
        int centerChunkX = centerPos.getX() >> 4;
        int centerChunkZ = centerPos.getZ() >> 4;
        WorldCopyService.CopyTask task = new WorldCopyService.CopyTask(
                UUID.randomUUID(),
                centerPos,
                1,
                helper.getLevel().dimension(),
                0,
                true
        );

        boolean rejectedEarlyCopy = false;
        try {
            task.generatePristineChunk(helper.getLevel(), centerChunkX, centerChunkZ);
        } catch (IllegalStateException e) {
            rejectedEarlyCopy = true;
        }

        helper.assertTrue(rejectedEarlyCopy,
                "First dream chunks must not be copied before the shared region is prepared");
        helper.assertFalse(task.preparePristineRegion(helper.getLevel(), task.getTotalChunks() - 1),
                "First dream region preparation must wait for every copied chunk");
        helper.assertTrue(task.preparePristineRegion(helper.getLevel(), 1),
                "First dream region preparation must complete after the final copied chunk is generated");

        try {
            ChunkAccess centerChunk = task.generatePristineChunk(helper.getLevel(), centerChunkX, centerChunkZ);
            helper.assertTrue(centerChunk instanceof LevelChunk,
                    "Prepared first dream chunks must come from a scratch ServerLevel lifecycle");
            helper.assertTrue(centerChunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL),
                    "Prepared first dream chunks must be fully generated before copying starts");
        } finally {
            task.closePristineRegion();
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void copyTaskLoadsMissingSourceChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int farChunkX = 1_000_000;
        int farChunkZ = 1_000_000;
        for (int attempts = 0; attempts < 16
                && level.getChunkSource().getChunkNow(farChunkX, farChunkZ) != null; attempts++) {
            farChunkX += 1_000;
            farChunkZ += 1_000;
        }
        helper.assertTrue(level.getChunkSource().getChunkNow(farChunkX, farChunkZ) == null,
                "The regression test needs an initially unloaded source chunk");

        WorldCopyService.CopyTask task = new WorldCopyService.CopyTask(
                UUID.randomUUID(),
                new BlockPos(farChunkX << 4, 64, farChunkZ << 4),
                0,
                level.dimension(),
                0,
                false
        );

        int[] chunkCoords = task.getNextChunk();
        int blocksCopied = WorldCopyService.copyChunk(level, level, chunkCoords[0], chunkCoords[1], task);

        helper.assertTrue(blocksCopied > 0,
                "Missing source chunks must be loaded and copied instead of being skipped as empty");
        helper.assertTrue(task.isCompleted(),
                "A one-chunk copy task may complete after the missing source chunk is actually copied");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamRefreshUsesScratchGeneratedContent(GameTestHelper helper) {
        WorldCopyService.CopyTask firstDreamRefreshTask = new WorldCopyService.CopyTask(
                UUID.randomUUID(),
                helper.absolutePos(BlockPos.ZERO),
                1,
                helper.getLevel().dimension(),
                0,
                true,
                true
        );

        try {
            helper.assertTrue(firstDreamRefreshTask.terrainMode == MirrorTerrainMode.PRISTINE,
                    "First dream refresh must use pristine terrain generation");
            helper.assertTrue(firstDreamRefreshTask.generatedContentRefresh,
                    "First dream refresh must request generated content from the scratch world");
        } finally {
            firstDreamRefreshTask.closePristineRegion();
        }

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamNaturalSpawnWarmupUsesVanillaDistance(GameTestHelper helper) {
        ChunkPos originChunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Entity marker = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(marker != null, "Natural spawn distance marker entity must be creatable");
        marker.moveTo(originChunk.x * 16 + 8.0D, 64.0D, originChunk.z * 16 + 8.0D);

        helper.assertTrue(PristineTerrainGenerator.isWithinVanillaNaturalSpawnDistance(originChunk, marker),
                "First dream warmup must include chunks near the scratch player");
        helper.assertFalse(PristineTerrainGenerator.isWithinVanillaNaturalSpawnDistance(
                        new ChunkPos(originChunk.x + 8, originChunk.z), marker),
                "First dream warmup must skip chunks outside vanilla natural-spawn range");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamRefreshCopiesSpawnerAsLiveBlockEntity(GameTestHelper helper) {
        BlockPos spawnerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        LevelChunk targetChunk = (LevelChunk) helper.getLevel().getChunk(spawnerPos);
        int sectionY = spawnerPos.getY() >> 4;
        LevelChunkSection section = targetChunk.getSection(sectionY - targetChunk.getMinSection());
        section.setBlockState(spawnerPos.getX() & 15, spawnerPos.getY() & 15, spawnerPos.getZ() & 15,
                Blocks.SPAWNER.defaultBlockState(), false);

        CompoundTag spawnerTag = new CompoundTag();
        spawnerTag.putString("id", "minecraft:mob_spawner");
        spawnerTag.putInt("x", spawnerPos.getX());
        spawnerTag.putInt("y", spawnerPos.getY());
        spawnerTag.putInt("z", spawnerPos.getZ());

        WorldCopyService.copyGeneratedBlockEntityTag(helper.getLevel(), targetChunk, spawnerPos, spawnerTag);

        helper.assertTrue(helper.getLevel().getBlockEntity(spawnerPos) instanceof SpawnerBlockEntity,
                "Renewal refresh must install generated spawners as live ticking block entities");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void generatedBossBarFallbackOnlyCoversBossesWithoutNativeBars(GameTestHelper helper) {
        EnderDragon dragon = EntityType.ENDER_DRAGON.create(helper.getLevel());
        WitherBoss wither = EntityType.WITHER.create(helper.getLevel());

        helper.assertTrue(dragon != null, "Ender dragon test entity must be creatable");
        helper.assertTrue(wither != null, "Wither test entity must be creatable");
        helper.assertFalse(MirrorBossBarManager.shouldUseFallbackBar(dragon),
                "Unmarked entities must not receive mirror boss bar fallback");

        MirrorBossBarManager.markGeneratedContentEntity(dragon);
        MirrorBossBarManager.markGeneratedContentEntity(wither);

        helper.assertTrue(MirrorBossBarManager.shouldUseFallbackBar(dragon),
                "Generated high-health hostile entities without native tracking need mirror boss bar fallback");
        helper.assertFalse(MirrorBossBarManager.shouldUseFallbackBar(wither),
                "Entities with native boss bar tracking must not get duplicate mirror fallback bars");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void generatedEndDragonBossBarUsesFightOriginVisibility(GameTestHelper helper) {
        EnderDragon dragon = EntityType.ENDER_DRAGON.create(helper.getLevel());
        WitherBoss wither = EntityType.WITHER.create(helper.getLevel());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        helper.assertTrue(dragon != null, "Ender dragon test entity must be creatable");
        helper.assertTrue(wither != null, "Wither test entity must be creatable");

        BlockPos origin = helper.absolutePos(new BlockPos(6, 0, 6));
        dragon.setFightOrigin(new BlockPos(origin.getX(), 0, origin.getZ()));
        dragon.moveTo(origin.getX() + 256.0D, 80.0D, origin.getZ() + 256.0D, 0.0F, 0.0F);
        player.moveTo(origin.getX() + 0.5D, 128.0D, origin.getZ() + 0.5D, 0.0F, 0.0F);

        helper.assertTrue(MirrorBossBarManager.shouldShowFallbackBarToPlayer(dragon, player),
                "Generated end dragon fallback bars must use the vanilla fight-origin visibility range");

        wither.moveTo(origin.getX() + 256.0D, 80.0D, origin.getZ() + 256.0D, 0.0F, 0.0F);
        helper.assertFalse(MirrorBossBarManager.shouldShowFallbackBarToPlayer(wither, player),
                "Non-dragon fallback bars must stay entity-distance based");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void generatedEndDragonDeathPlacesDragonEggOnce(GameTestHelper helper) {
        EnderDragon dragon = EntityType.ENDER_DRAGON.create(helper.getLevel());
        helper.assertTrue(dragon != null, "Ender dragon test entity must be creatable");

        BlockPos origin = helper.absolutePos(new BlockPos(3, 0, 3));
        dragon.setFightOrigin(new BlockPos(origin.getX(), 0, origin.getZ()));
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(3, 1, 3)),
                Blocks.END_STONE.defaultBlockState(), 3);

        helper.assertFalse(MirrorEndDragonRewardManager.placeDragonEggForGeneratedContent(helper.getLevel(), dragon),
                "Unmarked end dragons must not receive mirror dragon egg fallback");

        MirrorBossBarManager.markGeneratedContentEntity(dragon);
        BlockPos eggPos = MirrorEndDragonRewardManager.resolveDragonEggPos(helper.getLevel(), dragon);
        helper.assertTrue(MirrorEndDragonRewardManager.placeDragonEggForGeneratedContent(helper.getLevel(), dragon),
                "Generated end dragons must place the vanilla dragon egg fallback");

        helper.assertTrue(helper.getLevel().getBlockState(eggPos).is(Blocks.DRAGON_EGG),
                "Generated end dragon death must leave a dragon egg at the vanilla podium position");
        helper.assertFalse(MirrorEndDragonRewardManager.placeDragonEggForGeneratedContent(helper.getLevel(), dragon),
                "The same generated end dragon must not place duplicate dragon eggs");

        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void generatedEndDragonDeathAwardsVanillaAdvancement(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        helper.assertTrue(MirrorEndDragonRewardManager.awardVanillaEndDragonAdvancement(player),
                "Generated mirror end dragon deaths must award the vanilla End dragon advancement");

        var advancement = player.getServer().getAdvancements()
                .get(ResourceLocation.withDefaultNamespace("end/kill_dragon"));
        helper.assertTrue(advancement != null, "Vanilla end dragon advancement must be registered");
        helper.assertTrue(player.getAdvancements().getOrStartProgress(advancement).isDone(),
                "Generated mirror end dragon deaths must complete the vanilla end dragon advancement");
        helper.assertFalse(MirrorEndDragonRewardManager.awardVanillaEndDragonAdvancement(player),
                "Already-awarded mirror end dragon advancements must not be granted twice");

        helper.succeed();
    }
}
