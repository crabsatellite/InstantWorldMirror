package com.crabmods.instantworldmirror.world;

import com.mojang.datafixers.util.Either;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
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
        private final int regionCenterX;
        private final int regionCenterZ;
        private final int radius;
        private final ProtoChunk[][] chunks;

        Region(ServerLevel sourceWorld, int regionCenterX, int regionCenterZ, int radius) {
            this.sourceWorld = sourceWorld;
            this.generator = sourceWorld.getChunkSource().getGenerator();
            this.regionCenterX = regionCenterX;
            this.regionCenterZ = regionCenterZ;
            this.radius = radius;
            int size = radius * 2 + 1;
            this.chunks = new ProtoChunk[size][size];

            for (int x = minX(); x <= maxX(); x++) {
                for (int z = minZ(); z <= maxZ(); z++) {
                    chunks[x - minX()][z - minZ()] = createChunk(x, z);
                }
            }
        }

        ChunkAccess generateChunk(int chunkX, int chunkZ) {
            ensureWithin(chunkX, chunkZ, STRUCTURE_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS, STRUCTURE_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES, BIOME_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.BIOMES, BIOME_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.NOISE, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.SURFACE, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.CARVERS, TERRAIN_RADIUS);
            generateStep(chunkX, chunkZ, ChunkStatus.FEATURES, 0);
            return chunkAt(chunkX, chunkZ);
        }

        private void generateStep(int chunkX, int chunkZ, ChunkStatus status, int generationRadius) {
            ensureWithin(chunkX, chunkZ, generationRadius);
            for (int x = chunkX - generationRadius; x <= chunkX + generationRadius; x++) {
                for (int z = chunkZ - generationRadius; z <= chunkZ + generationRadius; z++) {
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
            return chunks[x - minX()][z - minZ()];
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
}
