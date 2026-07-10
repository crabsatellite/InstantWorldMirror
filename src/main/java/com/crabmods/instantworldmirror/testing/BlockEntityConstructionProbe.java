package com.crabmods.instantworldmirror.testing;

import com.crabmods.instantworldmirror.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * Reproduces block entities that cannot serve virtual methods until their constructor completes.
 */
public final class BlockEntityConstructionProbe {
    private BlockEntityConstructionProbe() {
    }

    public static Result verify() {
        ConstructorSensitiveBlockEntity blockEntity = new ConstructorSensitiveBlockEntity();
        Level level = blockEntity.getLevel();
        if (level != null) {
            throw new IllegalStateException("A newly constructed block entity must not already have a level");
        }
        if (blockEntity.preConstructionCalls != 0 || blockEntity.postConstructionCalls != 1) {
            throw new IllegalStateException("Unexpected getLevel calls: before="
                    + blockEntity.preConstructionCalls + ", after=" + blockEntity.postConstructionCalls);
        }
        return new Result(blockEntity.preConstructionCalls, blockEntity.postConstructionCalls);
    }

    public record Result(int preConstructionCalls, int postConstructionCalls) {
    }

    private static final class ConstructorSensitiveBlockEntity extends BlockEntity {
        private boolean constructionComplete;
        private int preConstructionCalls;
        private int postConstructionCalls;

        private ConstructorSensitiveBlockEntity() {
            super(ModBlocks.PORTAL_LIGHT_BLOCK_ENTITY.get(), BlockPos.ZERO,
                    ModBlocks.PORTAL_LIGHT_BLOCK.get().defaultBlockState());
            constructionComplete = true;
        }

        @Override
        @Nullable
        public Level getLevel() {
            if (!constructionComplete) {
                preConstructionCalls++;
                throw new IllegalStateException("getLevel was called before subclass construction completed");
            }
            postConstructionCalls++;
            return super.getLevel();
        }
    }
}
