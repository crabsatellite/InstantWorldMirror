package com.crabmods.instantworldmirror.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

/**
 * Generates an in-memory copy of the source world's original chunk from its
 * seed and current chunk generator, without reading player-modified chunks.
 */
final class PristineTerrainGenerator {
    private static final int STRUCTURE_RADIUS = 10;
    private static final int BIOME_RADIUS = 2;
    private static final int TERRAIN_RADIUS = 1;

    private PristineTerrainGenerator() {
    }

    static ChunkAccess generateChunk(ServerLevel sourceWorld, int chunkX, int chunkZ) {
        GenerationCache cache = new GenerationCache(sourceWorld, chunkX, chunkZ, STRUCTURE_RADIUS);

        cache.generateStructureStarts();
        cache.generateStep(ChunkStatus.STRUCTURE_REFERENCES, BIOME_RADIUS);
        cache.generateStep(ChunkStatus.BIOMES, BIOME_RADIUS);
        cache.generateStep(ChunkStatus.NOISE, TERRAIN_RADIUS);
        cache.generateStep(ChunkStatus.SURFACE, TERRAIN_RADIUS);
        cache.generateStep(ChunkStatus.CARVERS, TERRAIN_RADIUS);
        cache.generateStep(ChunkStatus.FEATURES, 0);

        return cache.center();
    }

    private static final class GenerationCache {
        private final ServerLevel sourceWorld;
        private final ChunkGenerator generator;
        private final WorldGenContext context;
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final StaticCache2D<GenerationChunkHolder> holders;
        private final MemoryGenerationChunkHolder[][] holderGrid;

        GenerationCache(ServerLevel sourceWorld, int centerX, int centerZ, int radius) {
            this.sourceWorld = sourceWorld;
            this.generator = sourceWorld.getChunkSource().getGenerator();
            this.context = new WorldGenContext(
                    sourceWorld,
                    this.generator,
                    sourceWorld.getStructureManager(),
                    sourceWorld.getChunkSource().getLightEngine(),
                    null
            );
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            int size = radius * 2 + 1;
            this.holderGrid = new MemoryGenerationChunkHolder[size][size];
            this.holders = StaticCache2D.create(centerX, centerZ, radius, this::createHolder);
        }

        ChunkAccess center() {
            return holderAt(centerX, centerZ).chunk;
        }

        void generateStructureStarts() {
            generateWithin(STRUCTURE_RADIUS, holder -> {
                if (!holder.chunk.getPersistedStatus().isBefore(ChunkStatus.STRUCTURE_STARTS)) {
                    return;
                }
                if (sourceWorld.getServer().getWorldData().worldGenOptions().generateStructures()) {
                    generator.createStructures(
                            sourceWorld.registryAccess(),
                            sourceWorld.getChunkSource().getGeneratorState(),
                            sourceWorld.structureManager(),
                            holder.chunk,
                            sourceWorld.getStructureManager()
                    );
                }
                sourceWorld.onStructureStartsAvailable(holder.chunk);
                holder.chunk.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            });
        }

        void generateStep(ChunkStatus status, int generationRadius) {
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            generateWithin(generationRadius, holder -> step.apply(context, holders, holder.chunk).join());
        }

        private void generateWithin(int generationRadius, java.util.function.Consumer<MemoryGenerationChunkHolder> action) {
            for (int x = centerX - generationRadius; x <= centerX + generationRadius; x++) {
                for (int z = centerZ - generationRadius; z <= centerZ + generationRadius; z++) {
                    action.accept(holderAt(x, z));
                }
            }
        }

        private MemoryGenerationChunkHolder createHolder(int x, int z) {
            ProtoChunk chunk = new ProtoChunk(
                    new ChunkPos(x, z),
                    UpgradeData.EMPTY,
                    sourceWorld,
                    sourceWorld.registryAccess().registryOrThrow(Registries.BIOME),
                    null
            );
            MemoryGenerationChunkHolder holder = new MemoryGenerationChunkHolder(chunk);
            holderGrid[x - (centerX - radius)][z - (centerZ - radius)] = holder;
            return holder;
        }

        private MemoryGenerationChunkHolder holderAt(int x, int z) {
            return holderGrid[x - (centerX - radius)][z - (centerZ - radius)];
        }
    }

    private static final class MemoryGenerationChunkHolder extends GenerationChunkHolder {
        private final ProtoChunk chunk;

        MemoryGenerationChunkHolder(ProtoChunk chunk) {
            super(chunk.getPos());
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk.getPersistedStatus().isOrAfter(status) ? chunk : null;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return chunk.getPersistedStatus();
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }
}
