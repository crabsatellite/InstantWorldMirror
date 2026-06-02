package com.crabmods.instantworldmirror.world;

import com.mojang.datafixers.util.Either;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Generates an in-memory copy of the source world's original chunk from its
 * seed and current chunk generator, without reading player-modified chunks.
 */
final class PristineTerrainGenerator {
    private static final int STRUCTURE_RADIUS = 10;
    private static final int BIOME_RADIUS = 2;
    private static final int TERRAIN_RADIUS = 1;

    private static final Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> COMPLETE_CHUNK =
            chunk -> CompletableFuture.completedFuture(Either.left(chunk));

    private PristineTerrainGenerator() {
    }

    static ChunkAccess generateChunk(ServerLevel sourceWorld, int chunkX, int chunkZ) {
        GenerationCache cache = new GenerationCache(sourceWorld, chunkX, chunkZ, STRUCTURE_RADIUS);

        cache.generateStep(ChunkStatus.STRUCTURE_STARTS, STRUCTURE_RADIUS);
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
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final ProtoChunk[][] chunks;

        GenerationCache(ServerLevel sourceWorld, int centerX, int centerZ, int radius) {
            this.sourceWorld = sourceWorld;
            this.generator = sourceWorld.getChunkSource().getGenerator();
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            int size = radius * 2 + 1;
            this.chunks = new ProtoChunk[size][size];

            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    chunks[x - (centerX - radius)][z - (centerZ - radius)] = createChunk(x, z);
                }
            }
        }

        ChunkAccess center() {
            return chunkAt(centerX, centerZ);
        }

        void generateStep(ChunkStatus status, int generationRadius) {
            for (int x = centerX - generationRadius; x <= centerX + generationRadius; x++) {
                for (int z = centerZ - generationRadius; z <= centerZ + generationRadius; z++) {
                    ProtoChunk chunk = chunkAt(x, z);
                    if (!chunk.getStatus().isOrAfter(status)) {
                        generateStatus(chunk, status);
                    }
                }
            }
        }

        private void generateStatus(ProtoChunk chunk, ChunkStatus status) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = status.generate(
                    Util.backgroundExecutor(),
                    sourceWorld,
                    generator,
                    sourceWorld.getStructureManager(),
                    sourceWorld.getChunkSource().getLightEngine(),
                    COMPLETE_CHUNK,
                    chunksForRegion(chunk.getPos(), status.getRange())
            ).join();

            if (result.right().isPresent()) {
                throw new IllegalStateException("Failed to generate pristine chunk " + chunk.getPos() + " to " + status);
            }
        }

        private List<ChunkAccess> chunksForRegion(ChunkPos center, int regionRadius) {
            List<ChunkAccess> region = new ArrayList<>((regionRadius * 2 + 1) * (regionRadius * 2 + 1));
            for (int z = center.z - regionRadius; z <= center.z + regionRadius; z++) {
                for (int x = center.x - regionRadius; x <= center.x + regionRadius; x++) {
                    region.add(chunkAt(x, z));
                }
            }
            return region;
        }

        private ProtoChunk createChunk(int x, int z) {
            return new ProtoChunk(
                    new ChunkPos(x, z),
                    UpgradeData.EMPTY,
                    sourceWorld,
                    sourceWorld.registryAccess().registryOrThrow(Registries.BIOME),
                    null
            );
        }

        private ProtoChunk chunkAt(int x, int z) {
            return chunks[x - (centerX - radius)][z - (centerZ - radius)];
        }
    }
}
