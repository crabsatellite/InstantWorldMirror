package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

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

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void firstDreamLootTablesUseMobSpawningConfig(GameTestHelper helper) {
        CompoundTag generatedChest = new CompoundTag();
        generatedChest.putString("id", "minecraft:chest");
        generatedChest.putString("LootTable", "minecraft:chests/simple_dungeon");
        generatedChest.putLong("LootTableSeed", 123L);

        try {
            MirrorConfig.setRuntimeMobSpawning(true);
            CompoundTag enabled = WorldCopyService.filterGeneratedLootTagForConfig(generatedChest, false);
            helper.assertTrue(enabled.contains("LootTable"),
                    "Generated loot tables must be kept when the unified generation config is enabled");
            helper.assertTrue(enabled.contains("LootTableSeed"),
                    "Generated loot seeds must be kept when the unified generation config is enabled");

            MirrorConfig.setRuntimeMobSpawning(false);
            CompoundTag disabled = WorldCopyService.filterGeneratedLootTagForConfig(generatedChest, false);
            helper.assertFalse(disabled.contains("LootTable"),
                    "Generated loot tables must be stripped when the unified generation config is disabled");
            helper.assertFalse(disabled.contains("LootTableSeed"),
                    "Generated loot seeds must be stripped when the unified generation config is disabled");

            CompoundTag refreshed = WorldCopyService.filterGeneratedLootTagForConfig(generatedChest, true);
            helper.assertTrue(refreshed.contains("LootTable"),
                    "Renewal refresh must keep generated loot tables even when the unified config is disabled");
            helper.assertTrue(refreshed.contains("LootTableSeed"),
                    "Renewal refresh must keep generated loot seeds even when the unified config is disabled");
        } finally {
            MirrorConfig.setRuntimeMobSpawning(null);
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
            helper.assertTrue(firstDreamRefreshTask.pristineTerrain,
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

        WorldCopyService.copyGeneratedBlockEntityTag(helper.getLevel(), targetChunk, spawnerPos, spawnerTag, true);

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
}
