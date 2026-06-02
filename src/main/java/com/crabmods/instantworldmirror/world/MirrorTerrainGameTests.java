package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

        helper.assertTrue(generatedChunk.getStatus().isOrAfter(ChunkStatus.FEATURES),
                "First dream terrain generation must run through biome decoration");
        helper.assertFalse(generatedChunk.getBlockState(editedPos).is(Blocks.GOLD_BLOCK),
                "First dream terrain must not copy player edits from the current world chunk");

        helper.succeed();
    }
}
