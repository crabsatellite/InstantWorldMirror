package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates an isolated copy of the source dimension through Minecraft's normal
 * ServerLevel chunk lifecycle, backed by a temporary save directory. This keeps
 * terrain, structures, generated loot, generated entities, and modded worldgen
 * on the same path the source dimension would use for first-time generation.
 */
final class PristineTerrainGenerator {
    private static final String SCRATCH_LEVEL_ID = "instantworldmirror_scratch";
    private static final int NATURAL_SPAWN_PASSES = 8;
    private static final ChunkProgressListener NOOP_CHUNK_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {
        }

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };
    private static final GameProfile SCRATCH_PLAYER_PROFILE = new GameProfile(
            UUID.fromString("3fe95b0a-61d9-4a88-9a2d-2df6a08d6a5f"),
            "[InstantWorldMirror]"
    );

    private PristineTerrainGenerator() {
    }

    static ChunkAccess generateChunk(ServerLevel sourceWorld, int chunkX, int chunkZ) {
        try (Region region = openRegion(sourceWorld, new BlockPos(chunkX << 4, 0, chunkZ << 4), 0)) {
            return region.generateChunk(chunkX, chunkZ);
        }
    }

    static Region openRegion(ServerLevel sourceWorld, BlockPos centerPos, int copyRadius) {
        return new Region(sourceWorld, centerPos, copyRadius);
    }

    static final class Region implements AutoCloseable {
        private final ServerLevel sourceWorld;
        private final ServerLevel scratchWorld;
        private final LevelStorageSource.LevelStorageAccess storageAccess;
        private final Path scratchRoot;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private final Map<Long, LevelChunk> generatedChunks = new HashMap<>();
        private final Map<Long, List<CompoundTag>> generatedEntityTagsByChunk = new HashMap<>();

        @Nullable
        private FakePlayer scratchPlayer;
        private boolean generatedContentPrepared;
        private boolean closed;

        Region(ServerLevel sourceWorld, BlockPos centerPos, int copyRadius) {
            this.sourceWorld = sourceWorld;
            int centerChunkX = centerPos.getX() >> 4;
            int centerChunkZ = centerPos.getZ() >> 4;
            this.minChunkX = centerChunkX - copyRadius;
            this.maxChunkX = centerChunkX + copyRadius;
            this.minChunkZ = centerChunkZ - copyRadius;
            this.maxChunkZ = centerChunkZ + copyRadius;

            MinecraftServer server = sourceWorld.getServer();
            this.scratchRoot = createScratchRoot(server);
            LevelStorageSource storageSource = LevelStorageSource.createDefault(scratchRoot);
            try {
                this.storageAccess = storageSource.createAccess(SCRATCH_LEVEL_ID);
                this.scratchWorld = createScratchWorld(sourceWorld, storageAccess);
                this.scratchWorld.noSave = true;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create scratch first-dream world", e);
            }
        }

        ChunkAccess generateChunk(int chunkX, int chunkZ) {
            ensureOpen();
            ensureWithinCopyBounds(chunkX, chunkZ);
            return generatedChunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                    ignored -> scratchWorld.getChunk(chunkX, chunkZ));
        }

        ChunkAccess getChunk(int chunkX, int chunkZ) {
            ensureOpen();
            ensureWithinCopyBounds(chunkX, chunkZ);
            LevelChunk chunk = generatedChunks.get(ChunkPos.asLong(chunkX, chunkZ));
            if (chunk == null) {
                throw new IllegalStateException("Scratch chunk was not generated before copy: " + chunkX + ", " + chunkZ);
            }
            return chunk;
        }

        void prepareGeneratedContentSnapshot(BlockPos playerAnchor, boolean includeGeneratedMobs) {
            ensureOpen();
            if (generatedContentPrepared) {
                return;
            }
            generatedContentPrepared = true;

            if (!includeGeneratedMobs) {
                return;
            }

            addScratchPlayer(playerAnchor);
            runGeneratedContentWarmup();
            snapshotGeneratedEntities();
        }

        void copyGeneratedEntitiesToChunk(int chunkX, int chunkZ, ServerLevel mirrorWorld,
                                          java.util.function.Function<CompoundTag, CompoundTag> freshUuidCopier) {
            List<CompoundTag> entityTags = generatedEntityTagsByChunk.get(ChunkPos.asLong(chunkX, chunkZ));
            if (entityTags == null || entityTags.isEmpty()) {
                return;
            }

            for (CompoundTag generatedEntityTag : entityTags) {
                try {
                    CompoundTag entityData = freshUuidCopier.apply(generatedEntityTag);
                    Entity entity = EntityType.loadEntityRecursive(
                            entityData,
                            mirrorWorld,
                            loadedEntity -> loadedEntity
                    );
                    if (entity != null
                            && !(entity instanceof net.minecraft.world.entity.player.Player)
                            && !(entity instanceof com.crabmods.instantworldmirror.entity.MirrorPortalEntity)) {
                        MirrorBossBarManager.markGeneratedContentEntity(entity);
                        mirrorWorld.addFreshEntity(entity);
                    }
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    InstantWorldMirror.LOGGER.warn("Failed to copy generated entity from scratch first-dream world: {}", errorMsg);
                }
            }
        }

        private void addScratchPlayer(BlockPos playerAnchor) {
            if (scratchPlayer != null) {
                return;
            }

            scratchPlayer = FakePlayerFactory.get(scratchWorld, SCRATCH_PLAYER_PROFILE);
            scratchPlayer.moveTo(
                    playerAnchor.getX() + 0.5,
                    playerAnchor.getY(),
                    playerAnchor.getZ() + 0.5,
                    0.0F,
                    0.0F
            );
            scratchWorld.addNewPlayer(scratchPlayer);
        }

        private void runGeneratedContentWarmup() {
            runDragonFightWarmup();
            runNaturalSpawnWarmup();
        }

        private void runDragonFightWarmup() {
            if (scratchWorld.dimension() != Level.END || scratchWorld.getDragonFight() == null || scratchPlayer == null) {
                return;
            }

            EndDragonFight fight = scratchWorld.getDragonFight();
            fight.skipArenaLoadedCheck();
            fight.addPlayer(scratchPlayer);
            for (int i = 0; i < 2 && scratchWorld.getDragons().isEmpty(); i++) {
                fight.tick();
                if (scratchWorld.getDragonFight() != null) {
                    scratchWorld.getDragonFight().addPlayer(scratchPlayer);
                }
            }
        }

        private void runNaturalSpawnWarmup() {
            if (scratchPlayer == null || generatedChunks.isEmpty()) {
                return;
            }

            NaturalSpawner.ChunkGetter chunkGetter = (chunkPos, consumer) -> {
                LevelChunk chunk = generatedChunks.get(chunkPos);
                if (chunk != null) {
                    consumer.accept(chunk);
                }
            };

            for (int pass = 0; pass < NATURAL_SPAWN_PASSES; pass++) {
                NaturalSpawner.SpawnState spawnState = NaturalSpawner.createState(
                        generatedChunks.size(),
                        scratchWorld.getAllEntities(),
                        chunkGetter,
                        new LocalMobCapCalculator(scratchWorld.getChunkSource().chunkMap)
                );

                for (LevelChunk chunk : generatedChunks.values()) {
                    NaturalSpawner.spawnForChunk(scratchWorld, chunk, spawnState, true, true, false);
                }
            }
        }

        private void snapshotGeneratedEntities() {
            generatedEntityTagsByChunk.clear();
            for (Entity entity : scratchWorld.getAllEntities()) {
                if (entity instanceof ServerPlayer
                        || entity instanceof com.crabmods.instantworldmirror.entity.MirrorPortalEntity
                        || entity.isRemoved()) {
                    continue;
                }

                int chunkX = (int) Math.floor(entity.getX()) >> 4;
                int chunkZ = (int) Math.floor(entity.getZ()) >> 4;
                if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                    continue;
                }

                CompoundTag entityData = new CompoundTag();
                if (!entity.save(entityData)) {
                    continue;
                }
                generatedEntityTagsByChunk
                        .computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), ignored -> new ArrayList<>())
                        .add(entityData);
            }
        }

        private static ServerLevel createScratchWorld(ServerLevel sourceWorld,
                                                      LevelStorageSource.LevelStorageAccess storageAccess) {
            MinecraftServer server = sourceWorld.getServer();
            LevelStem levelStem = resolveSourceLevelStem(sourceWorld);
            ServerLevelData levelData = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
            long seed = server.getWorldData().worldGenOptions().seed();
            ServerLevel scratchWorld = new ServerLevel(
                    server,
                    Util.backgroundExecutor(),
                    storageAccess,
                    levelData,
                    sourceWorld.dimension(),
                    levelStem,
                    NOOP_CHUNK_PROGRESS,
                    server.getWorldData().isDebugWorld(),
                    BiomeManager.obfuscateSeed(seed),
                    ImmutableList.of(),
                    false,
                    null
            ) {
                @Override
                public void onBlockStateChange(BlockPos pos, BlockState blockState, BlockState newState) {
                    // Scratch POI data is never copied back. Avoid queuing temporary POI updates on the real server.
                }
            };

            if (scratchWorld.dimension() == Level.END && scratchWorld.dimensionTypeRegistration().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.END)) {
                scratchWorld.setDragonFight(new EndDragonFight(scratchWorld, seed, EndDragonFight.Data.DEFAULT));
            }
            return scratchWorld;
        }

        private static LevelStem resolveSourceLevelStem(ServerLevel sourceWorld) {
            ResourceKey<LevelStem> sourceStemKey = ResourceKey.create(Registries.LEVEL_STEM, sourceWorld.dimension().location());
            return Optional.ofNullable(sourceWorld.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(sourceStemKey))
                    .orElseThrow(() -> new IllegalStateException("Missing level stem for source dimension " + sourceWorld.dimension().location()));
        }

        private static Path createScratchRoot(MinecraftServer server) {
            Path root = server.getWorldPath(LevelResource.ROOT)
                    .resolve("instantworldmirror")
                    .resolve("scratch")
                    .resolve(UUID.randomUUID().toString());
            try {
                Files.createDirectories(root);
                return root;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create scratch first-dream directory", e);
            }
        }

        private void ensureWithinCopyBounds(int chunkX, int chunkZ) {
            if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                throw new IllegalArgumentException("Pristine generation request outside copy region: "
                        + chunkX + ", " + chunkZ);
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Scratch first-dream region is already closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            if (scratchPlayer != null) {
                try {
                    scratchWorld.removePlayerImmediately(scratchPlayer, Entity.RemovalReason.DISCARDED);
                } catch (Exception e) {
                    InstantWorldMirror.LOGGER.debug("Failed to remove scratch first-dream player: {}", e.getMessage());
                }
            }

            try {
                FakePlayerFactory.unloadLevel(scratchWorld);
            } catch (Exception e) {
                InstantWorldMirror.LOGGER.debug("Failed to unload scratch first-dream fake players: {}", e.getMessage());
            }

            try {
                scratchWorld.close();
            } catch (IOException e) {
                InstantWorldMirror.LOGGER.debug("Failed to close scratch first-dream world: {}", e.getMessage());
            }

            try {
                storageAccess.deleteLevel();
            } catch (IOException e) {
                try {
                    storageAccess.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                InstantWorldMirror.LOGGER.debug("Failed to delete scratch first-dream level through storage access: {}", e.getMessage());
                deleteScratchRootFallback();
            }
        }

        private void deleteScratchRootFallback() {
            try (java.util.stream.Stream<Path> paths = Files.walk(scratchRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best-effort cleanup. The next run can reuse a different scratch directory.
                    }
                });
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }
}
