package com.crabmods.instantworldmirror.world;

import net.minecraft.core.BlockPos;
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
        return openRegion(sourceWorld, new BlockPos(chunkX << 4, 0, chunkZ << 4), 0)
                .generateChunk(chunkX, chunkZ);
    }

    static Region openRegion(ServerLevel sourceWorld, BlockPos centerPos, int copyRadius) {
        int centerChunkX = centerPos.getX() >> 4;
        int centerChunkZ = centerPos.getZ() >> 4;
        return new Region(sourceWorld, centerChunkX, centerChunkZ, copyRadius + STRUCTURE_RADIUS);
    }

    static final class Region {
        private final ServerLevel sourceWorld;
        private final ChunkGenerator generator;
        private final WorldGenContext context;
        private final int regionCenterX;
        private final int regionCenterZ;
        private final int radius;
        private final StaticCache2D<GenerationChunkHolder> holders;
        private final MemoryGenerationChunkHolder[][] holderGrid;

        Region(ServerLevel sourceWorld, int regionCenterX, int regionCenterZ, int radius) {
            this.sourceWorld = sourceWorld;
            this.generator = sourceWorld.getChunkSource().getGenerator();
            this.context = new WorldGenContext(
                    sourceWorld,
                    this.generator,
                    sourceWorld.getStructureManager(),
                    sourceWorld.getChunkSource().getLightEngine(),
                    null
            );
            this.regionCenterX = regionCenterX;
            this.regionCenterZ = regionCenterZ;
            this.radius = radius;
            int size = radius * 2 + 1;
            this.holderGrid = new MemoryGenerationChunkHolder[size][size];
            this.holders = StaticCache2D.create(regionCenterX, regionCenterZ, radius, this::createHolder);
        }

        ChunkAccess generateChunk(int chunkX, int chunkZ) {
            ensureWithin(chunkX, chunkZ, STRUCTURE_RADIUS);
            generateStructureStarts(chunkX, chunkZ);
            generateStep(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES, BIOME_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.BIOMES, BIOME_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.NOISE, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.SURFACE, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.CARVERS, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.FEATURES, 0);
            generateStep(chunkX, chunkZ, ChunkStatus.SPAWN, 0);
            return holderAt(chunkX, chunkZ).chunk;
        }

        private void generateStructureStarts(int chunkX, int chunkZ) {
            generateWithin(chunkX, chunkZ, STRUCTURE_RADIUS, holder -> {
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

        private void generateStep(int chunkX, int chunkZ, ChunkStatus status, int generationRadius) {
            ensureWithin(chunkX, chunkZ, generationRadius);
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            generateWithin(chunkX, chunkZ, generationRadius, holder -> {
                if (holder.chunk.getPersistedStatus().isBefore(status)) {
                    step.apply(context, holders, holder.chunk).join();
                }
            });
        }

        private void generateWithin(int chunkX, int chunkZ, int generationRadius,
                                    java.util.function.Consumer<MemoryGenerationChunkHolder> action) {
            for (int x = chunkX - generationRadius; x <= chunkX + generationRadius; x++) {
                for (int z = chunkZ - generationRadius; z <= chunkZ + generationRadius; z++) {
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
            holderGrid[x - minX()][z - minZ()] = holder;
            return holder;
        }

        private MemoryGenerationChunkHolder holderAt(int x, int z) {
            return holderGrid[x - minX()][z - minZ()];
        }

        private void ensureWithin(int chunkX, int chunkZ, int generationRadius) {
            if (chunkX - generationRadius < minX()
                    || chunkX + generationRadius > maxX()
                    || chunkZ - generationRadius < minZ()
                    || chunkZ + generationRadius > maxZ()) {
                throw new IllegalArgumentException("Pristine generation request outside cached region: "
                        + chunkX + ", " + chunkZ);
            }
        }

        private int minX() {
            return regionCenterX - radius;
        }

        private int maxX() {
            return regionCenterX + radius;
        }

        private int minZ() {
            return regionCenterZ - radius;
        }

        private int maxZ() {
            return regionCenterZ + radius;
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
