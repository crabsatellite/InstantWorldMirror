package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
            CompoundTag enabled = WorldCopyService.filterGeneratedLootTagForConfig(generatedChest);
            helper.assertTrue(enabled.contains("LootTable"),
                    "Generated loot tables must be kept when the unified generation config is enabled");
            helper.assertTrue(enabled.contains("LootTableSeed"),
                    "Generated loot seeds must be kept when the unified generation config is enabled");

            MirrorConfig.setRuntimeMobSpawning(false);
            CompoundTag disabled = WorldCopyService.filterGeneratedLootTagForConfig(generatedChest);
            helper.assertFalse(disabled.contains("LootTable"),
                    "Generated loot tables must be stripped when the unified generation config is disabled");
            helper.assertFalse(disabled.contains("LootTableSeed"),
                    "Generated loot seeds must be stripped when the unified generation config is disabled");
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

        PristineTerrainGenerator.Region region = PristineTerrainGenerator.openRegion(helper.getLevel(), centerPos, 1);
        ChunkAccess centerChunk = region.generateChunk(centerChunkX, centerChunkZ);
        ChunkAccess edgeChunk = region.generateChunk(edgeChunkX, edgeChunkZ);

        helper.assertTrue(centerChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                "First dream region generation must complete the center chunk");
        helper.assertTrue(edgeChunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                "First dream region generation must complete edge chunks in one shared copy region");
        helper.assertFalse(edgeChunk.getBlockState(editedEdgePos).is(Blocks.GOLD_BLOCK),
                "First dream shared region terrain must not copy player edits from edge chunks");

        helper.succeed();
    }
}
