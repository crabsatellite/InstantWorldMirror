package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.item.StrandedMirrorItem;
import com.crabmods.instantworldmirror.mixin.LevelChunkSectionAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores named, cross-save world slices outside individual save directories.
 */
public final class StrandedSnapshotManager {
    private static final int FORMAT_VERSION = 1;
    private static final String META_FILE = "meta.nbt";
    private static final Map<UUID, CaptureTask> CAPTURE_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Path> PREPARED_SNAPSHOT_DIRECTORIES = new ConcurrentHashMap<>();

    private StrandedSnapshotManager() {
    }

    public record SnapshotSummary(UUID id, UUID ownerId, String name, int radius,
                                  BlockPos sourceCenter, long createdAt, String minecraftVersion,
                                  int dataVersion) {
        public SnapshotSummary(UUID id, UUID ownerId, String name, int radius,
                               BlockPos sourceCenter, long createdAt, String minecraftVersion) {
            this(id, ownerId, name, radius, sourceCenter, createdAt, minecraftVersion,
                    inferDataVersion(minecraftVersion));
        }
    }

    public record SnapshotMenuEntry(SnapshotSummary summary, boolean available, boolean backupAvailable) {
    }

    public static boolean requestCapture(ServerPlayer player, BlockPos targetPos, String name) {
        ItemStack mirror = validateRequest(player, targetPos);
        if (mirror.isEmpty()) {
            return false;
        }
        boolean started = startCapture(player, targetPos, name);
        if (started) {
            applyUseCooldown(player, mirror);
        }
        return started;
    }

    public static boolean canBeginRequest(ServerPlayer player, BlockPos targetPos) {
        return !validateRequest(player, targetPos).isEmpty();
    }

    public static boolean requestOpen(ServerPlayer player, BlockPos targetPos, UUID snapshotId) {
        ItemStack mirror = validateRequest(player, targetPos);
        if (mirror.isEmpty()) {
            return false;
        }
        boolean opened = openSnapshot(
                player, targetPos, snapshotId, DimensionMirrorItem.hasPermanence(player.level(), mirror));
        if (opened) {
            applyUseCooldown(player, mirror);
        }
        return opened;
    }

    private static boolean startCapture(ServerPlayer player, BlockPos center, String requestedName) {
        if (CAPTURE_TASKS.containsKey(player.getUUID())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.capture_in_progress"), false);
            return false;
        }

        int radius = MirrorConfig.copyChunkRadius(MirrorKind.STRANDED);
        UUID id = UUID.randomUUID();
        String name = sanitizeName(requestedName);
        try {
            CaptureTask task = new CaptureTask(
                    id, player.getUUID(), player.level().dimension(), center, radius, name);
            CAPTURE_TASKS.put(player.getUUID(), task);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.capture_started", name, (radius * 2 + 1) * (radius * 2 + 1)), false);
            return true;
        } catch (IOException e) {
            InstantWorldMirror.LOGGER.error("Failed to initialize stranded snapshot capture", e);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.capture_failed"), false);
            return false;
        }
    }

    public static void processCaptureTasks(MinecraftServer server) {
        CaptureTask task = CAPTURE_TASKS.values().stream().findFirst().orElse(null);
        if (task == null) {
            return;
        }

        try {
            if (task.captureNext(server)) {
                CAPTURE_TASKS.remove(task.ownerId);
                ServerPlayer player = server.getPlayerList().getPlayer(task.ownerId);
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "message.instantworldmirror.stranded.capture_complete", task.name), false);
                }
            }
        } catch (Exception e) {
            CAPTURE_TASKS.remove(task.ownerId);
            task.discard();
            InstantWorldMirror.LOGGER.error("Failed to capture stranded snapshot {}", task.id, e);
            ServerPlayer player = server.getPlayerList().getPlayer(task.ownerId);
            if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.instantworldmirror.stranded.capture_failed"), false);
            }
        }
    }

    public static List<SnapshotSummary> listSnapshots(ServerPlayer player) {
        return listAccessibleSnapshots(player).stream()
                .filter(summary -> isCompatible(summary) && isComplete(summary))
                .toList();
    }

    public static List<SnapshotMenuEntry> listSnapshotMenuEntries(ServerPlayer player) {
        return listAccessibleSnapshots(player).stream()
                .map(summary -> {
                    boolean complete = isComplete(summary);
                    return new SnapshotMenuEntry(summary, isCompatible(summary) && complete, complete);
                })
                .toList();
    }

    private static List<SnapshotSummary> listAccessibleSnapshots(ServerPlayer player) {
        Path root = cacheRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SnapshotSummary> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .forEach(path -> readSummary(path)
                            .filter(summary -> canUse(player, summary))
                            .ifPresent(result::add));
        } catch (IOException e) {
            InstantWorldMirror.LOGGER.warn("Failed to list stranded snapshots: {}", e.getMessage());
        }
        result.sort(Comparator.comparingLong(SnapshotSummary::createdAt).reversed());
        return result;
    }

    public static boolean requestDelete(ServerPlayer player, UUID snapshotId) {
        SnapshotSummary summary = readSummary(snapshotDir(snapshotId)).orElse(null);
        if (summary == null || !canUse(player, summary)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.delete_failed"), false);
            return false;
        }
        if (MirrorWorldManager.isSnapshotInUse(snapshotId)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.snapshot_in_use"), false);
            return false;
        }
        deleteTree(snapshotDir(snapshotId));
        PREPARED_SNAPSHOT_DIRECTORIES.remove(snapshotId);
        if (Files.exists(snapshotDir(snapshotId))) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.delete_failed"), false);
            return false;
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.instantworldmirror.stranded.deleted", summary.name()), false);
        return true;
    }

    public static synchronized boolean requestBackup(ServerPlayer player, UUID snapshotId) {
        SnapshotSummary source = readSummary(snapshotDir(snapshotId)).orElse(null);
        if (source == null || !canUse(player, source) || !isComplete(source)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.backup_failed"), false);
            return false;
        }

        String name = BackupNameAllocator.next(
                source.name(),
                listAccessibleSnapshots(player).stream().map(SnapshotSummary::name).toList(),
                48);
        UUID backupId = UUID.randomUUID();
        Path temporary = cacheRoot().resolve(backupId + ".tmp");
        Path destination = snapshotDir(backupId);
        deleteTree(temporary);
        try {
            Files.createDirectories(temporary);
            for (int dx = -source.radius(); dx <= source.radius(); dx++) {
                for (int dz = -source.radius(); dz <= source.radius(); dz++) {
                    Files.copy(
                            snapshotDir(snapshotId).resolve(chunkFileName(dx, dz)),
                            temporary.resolve(chunkFileName(dx, dz)));
                }
            }
            SnapshotSummary backup = new SnapshotSummary(
                    backupId,
                    source.ownerId(),
                    name,
                    source.radius(),
                    source.sourceCenter(),
                    System.currentTimeMillis(),
                    source.minecraftVersion(),
                    source.dataVersion());
            NbtIo.writeCompressed(writeSummary(backup), temporary.resolve(META_FILE));
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination);
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.backup_created", name), false);
            return true;
        } catch (Exception e) {
            deleteTree(temporary);
            InstantWorldMirror.LOGGER.warn("Failed to back up stranded snapshot {}: {}", snapshotId, e.getMessage());
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.backup_failed"), false);
            return false;
        }
    }

    public static boolean openSnapshot(ServerPlayer player, BlockPos targetCenter, UUID snapshotId,
                                       boolean persistentAccess) {
        SnapshotSummary summary = readSummary(snapshotDir(snapshotId)).orElse(null);
        if (summary == null || !canUse(player, summary) || !isCompatible(summary) || !isComplete(summary)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.snapshot_unavailable"), false);
            return false;
        }
        if (!prepareSnapshot(summary, player.registryAccess())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.upgrade_failed"), false);
            return false;
        }

        Optional<MirrorSession> session = MirrorWorldManager.createSession(
                player,
                targetCenter,
                MirrorKind.STRANDED,
                persistentAccess,
                false,
                MirrorTerrainMode.SNAPSHOT,
                summary.id(),
                summary.radius(),
                summary.sourceCenter().getY()
        );
        if (session.isEmpty()) {
            return false;
        }

        ServerLevel level = (ServerLevel) player.level();
        BlockPos portalPos = targetCenter.above();
        level.playSound(null, targetCenter, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.2F);
        MirrorPortalEntity portal = new MirrorPortalEntity(
                level,
                portalPos.getX() + 0.5,
                portalPos.getY() + 0.5,
                portalPos.getZ() + 0.5,
                player.getUUID(),
                session.get(),
                targetCenter
        );
        level.addFreshEntity(portal);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.instantworldmirror.stranded.portal_created", summary.name()), true);
        return true;
    }

    static int copySnapshotChunk(ServerLevel mirrorWorld, int chunkX, int chunkZ,
                                 WorldCopyService.CopyTask task) {
        if (task.snapshotId == null) {
            return 0;
        }

        int centerChunkX = task.centerPos.getX() >> 4;
        int centerChunkZ = task.centerPos.getZ() >> 4;
        int dx = chunkX - centerChunkX;
        int dz = chunkZ - centerChunkZ;
        Path chunkPath = PREPARED_SNAPSHOT_DIRECTORIES
                .getOrDefault(task.snapshotId, snapshotDir(task.snapshotId))
                .resolve(chunkFileName(dx, dz));
        if (!Files.isRegularFile(chunkPath)) {
            return 0;
        }

        try {
            CompoundTag snapshot = NbtIo.readCompressed(chunkPath, NbtAccounter.unlimitedHeap());
            LevelChunk targetChunk = mirrorWorld.getChunk(chunkX, chunkZ);
            WorldCopyService.clearChunkForGeneratedCopy(mirrorWorld, targetChunk);
            int blocks = applySections(mirrorWorld, targetChunk, snapshot.getList("sections", CompoundTag.TAG_COMPOUND));
            applyBlockEntities(mirrorWorld, targetChunk, snapshot.getList("block_entities", CompoundTag.TAG_COMPOUND));
            targetChunk.setUnsaved(true);
            WorldCopyService.regenerateHeightmaps(targetChunk);
            targetChunk.initializeLightSources();
            WorldCopyService.relightChunk(mirrorWorld, targetChunk);
            return blocks;
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to load stranded snapshot chunk ({}, {}): {}",
                    dx, dz, e.getMessage());
            return 0;
        }
    }

    public static void clearTransientState() {
        CAPTURE_TASKS.values().forEach(CaptureTask::discard);
        CAPTURE_TASKS.clear();
        PREPARED_SNAPSHOT_DIRECTORIES.clear();
    }

    private static ItemStack validateRequest(ServerPlayer player, BlockPos targetPos) {
        ItemStack mirror = findStrandedMirror(player);
        if (mirror.isEmpty()
                || ModDimensions.isAnyMirrorWorld(player.level().dimension())
                || !MirrorConfig.canAccessMirrorKind(player, MirrorKind.STRANDED)
                || !MirrorWorldManager.canAccessMirrorWorld(player)
                || MirrorWorldManager.hasActiveSession(player.getUUID())
                || !player.blockPosition().closerThan(targetPos, 8.0)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.stranded.request_invalid"), false);
            return ItemStack.EMPTY;
        }

        ServerLevel level = (ServerLevel) player.level();
        if (!level.getBlockState(targetPos).isSolidRender(level, targetPos)
                || level.getBlockState(targetPos.above()).isSolid()
                || level.getBlockState(targetPos.above(2)).isSolid()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.no_space_for_portal"), false);
            return ItemStack.EMPTY;
        }

        if (!player.isCreative() && DimensionMirrorItem.getRemainingCooldownMillis(player.getUUID()) > 0) {
            int seconds = (int) Math.ceil(DimensionMirrorItem.getRemainingCooldownMillis(player.getUUID()) / 1000.0);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.instantworldmirror.mirror_use_cooldown", seconds), true);
            return ItemStack.EMPTY;
        }
        return mirror;
    }

    private static ItemStack findStrandedMirror(ServerPlayer player) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof StrandedMirrorItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void applyUseCooldown(ServerPlayer player, ItemStack mirror) {
        if (player.isCreative()) {
            DimensionMirrorItem.clearCooldown(player.getUUID());
            DimensionMirrorItem.syncCooldownToClient(player);
        } else {
            DimensionMirrorItem.applyCooldown(player, mirror);
        }
    }

    public static CompoundTag captureChunkForTesting(ServerLevel level, int chunkX, int chunkZ) {
        return captureChunk(level, chunkX, chunkZ);
    }

    public static void writeSnapshotForTesting(SnapshotSummary summary, Map<Long, CompoundTag> chunks) throws IOException {
        Path dir = snapshotDir(summary.id());
        PREPARED_SNAPSHOT_DIRECTORIES.remove(summary.id());
        Files.createDirectories(dir);
        for (Map.Entry<Long, CompoundTag> entry : chunks.entrySet()) {
            int dx = (int) (entry.getKey() >> 32);
            int dz = entry.getKey().intValue();
            NbtIo.writeCompressed(entry.getValue(), dir.resolve(chunkFileName(dx, dz)));
        }
        NbtIo.writeCompressed(writeSummary(summary), dir.resolve(META_FILE));
    }

    public static void deleteSnapshotForTesting(UUID id) {
        PREPARED_SNAPSHOT_DIRECTORIES.remove(id);
        deleteTree(snapshotDir(id));
    }

    static boolean prepareSnapshotForTesting(UUID id, RegistryAccess registryAccess) {
        return readSummary(snapshotDir(id))
                .filter(StrandedSnapshotManager::isCompatible)
                .filter(StrandedSnapshotManager::isComplete)
                .map(summary -> prepareSnapshot(summary, registryAccess))
                .orElse(false);
    }

    static Optional<SnapshotSummary> readSnapshotSummaryForTesting(UUID id) {
        return readSummary(snapshotDir(id));
    }

    static void removeSnapshotDataVersionForTesting(UUID id) throws IOException {
        Path meta = snapshotDir(id).resolve(META_FILE);
        CompoundTag tag = NbtIo.readCompressed(meta, NbtAccounter.unlimitedHeap());
        tag.remove("data_version");
        NbtIo.writeCompressed(tag, meta);
    }

    public static CompoundTag readSnapshotChunkForTesting(UUID id, boolean prepared) throws IOException {
        Path directory = prepared
                ? PREPARED_SNAPSHOT_DIRECTORIES.getOrDefault(id, snapshotDir(id))
                : snapshotDir(id);
        return NbtIo.readCompressed(directory.resolve(chunkFileName(0, 0)), NbtAccounter.unlimitedHeap());
    }

    static boolean hasUpgradeArtifactsForTesting(UUID id) {
        return StrandedSnapshotUpgrader.hasArtifacts(snapshotDir(id));
    }

    static Optional<CompoundTag> readUpgradeMarkerForTesting(UUID id) {
        return StrandedSnapshotUpgrader.readMarker(snapshotDir(id));
    }

    static Path cacheRootForTesting() {
        return cacheRoot();
    }

    private static CompoundTag captureChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        CompoundTag result = new CompoundTag();
        ListTag sections = new ListTag();

        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            CompoundTag sectionTag = encodeSection(level, section, chunk.getMinSection() + sectionIndex);
            if (sectionTag != null) {
                sections.add(sectionTag);
            }
        }
        result.put("sections", sections);

        ListTag blockEntities = new ListTag();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag data = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (data == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("local_x", pos.getX() & 15);
            entry.putInt("y", pos.getY());
            entry.putInt("local_z", pos.getZ() & 15);
            entry.put("data", data);
            blockEntities.add(entry);
        }
        result.put("block_entities", blockEntities);
        return result;
    }

    private static CompoundTag encodeSection(ServerLevel level, LevelChunkSection section, int sectionY) {
        if (section.hasOnlyAir()) {
            return null;
        }

        Map<BlockState, Integer> paletteIndexes = new LinkedHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int[] values = new int[4096];
        boolean hasContent = false;

        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (!WorldCopyService.isSnapshotCopyAllowed(state)) {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    hasContent |= !state.isAir();
                    Integer paletteIndex = paletteIndexes.get(state);
                    if (paletteIndex == null) {
                        paletteIndex = palette.size();
                        paletteIndexes.put(state, paletteIndex);
                        palette.add(state);
                    }
                    values[index++] = paletteIndex;
                }
            }
        }

        if (!hasContent) {
            return null;
        }

        CompoundTag result = new CompoundTag();
        result.putInt("y", sectionY);
        ListTag paletteTag = new ListTag();
        palette.forEach(state -> paletteTag.add(NbtUtils.writeBlockState(state)));
        result.put("palette", paletteTag);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        result.putInt("bits", bits);
        result.putLongArray("data", pack(values, bits));

        ListTag biomes = new ListTag();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> biome = section.getNoiseBiome(x, y, z);
                    String id = biome.unwrapKey()
                            .map(key -> key.location().toString())
                            .orElse(Biomes.PLAINS.location().toString());
                    biomes.add(StringTag.valueOf(id));
                }
            }
        }
        result.put("biomes", biomes);
        return result;
    }

    private static int applySections(ServerLevel level, LevelChunk targetChunk, ListTag sections) {
        int blocks = 0;
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder.Reference<Biome> plains = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);

        for (int i = 0; i < sections.size(); i++) {
            CompoundTag sectionTag = sections.getCompound(i);
            int sectionIndex = sectionTag.getInt("y") - targetChunk.getMinSection();
            if (sectionIndex < 0 || sectionIndex >= targetChunk.getSectionsCount()) {
                continue;
            }

            LevelChunkSection section = targetChunk.getSection(sectionIndex);
            ListTag paletteTag = sectionTag.getList("palette", CompoundTag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>(paletteTag.size());
            for (int paletteIndex = 0; paletteIndex < paletteTag.size(); paletteIndex++) {
                palette.add(NbtUtils.readBlockState(
                        level.holderLookup(Registries.BLOCK), paletteTag.getCompound(paletteIndex)));
            }

            int[] values = unpack(sectionTag.getLongArray("data"), sectionTag.getInt("bits"), 4096);
            int valueIndex = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int paletteIndex = values[valueIndex++];
                        BlockState state = paletteIndex >= 0 && paletteIndex < palette.size()
                                ? palette.get(paletteIndex)
                                : Blocks.AIR.defaultBlockState();
                        section.setBlockState(x, y, z, state, false);
                        if (!state.isAir()) {
                            blocks++;
                        }
                    }
                }
            }

            ListTag biomeTags = sectionTag.getList("biomes", StringTag.TAG_STRING);
            PalettedContainer<Holder<Biome>> biomeContainer = new PalettedContainer<>(
                    biomeRegistry.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES);
            for (int biomeIndex = 0; biomeIndex < Math.min(64, biomeTags.size()); biomeIndex++) {
                ResourceLocation location = ResourceLocation.tryParse(biomeTags.getString(biomeIndex));
                Holder<Biome> biome = location == null
                        ? plains
                        : biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, location)).orElse(plains);
                int x = biomeIndex & 3;
                int z = (biomeIndex >> 2) & 3;
                int y = (biomeIndex >> 4) & 3;
                biomeContainer.getAndSetUnchecked(x, y, z, biome);
            }
            ((LevelChunkSectionAccessor) (Object) section).setBiomes(biomeContainer);
        }
        return blocks;
    }

    private static void applyBlockEntities(ServerLevel level, LevelChunk targetChunk, ListTag blockEntities) {
        int chunkMinX = targetChunk.getPos().getMinBlockX();
        int chunkMinZ = targetChunk.getPos().getMinBlockZ();
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag entry = blockEntities.getCompound(i);
            BlockPos targetPos = new BlockPos(
                    chunkMinX + entry.getInt("local_x"),
                    entry.getInt("y"),
                    chunkMinZ + entry.getInt("local_z")
            );
            WorldCopyService.copyGeneratedBlockEntityTag(
                    level, targetChunk, targetPos, entry.getCompound("data"));
        }
    }

    private static long[] pack(int[] values, int bits) {
        long[] packed = new long[(values.length * bits + 63) / 64];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < values.length; i++) {
            long value = values[i] & mask;
            int bitIndex = i * bits;
            int word = bitIndex >>> 6;
            int offset = bitIndex & 63;
            packed[word] |= value << offset;
            if (offset + bits > 64) {
                packed[word + 1] |= value >>> (64 - offset);
            }
        }
        return packed;
    }

    private static int[] unpack(long[] packed, int bits, int size) {
        int[] values = new int[size];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < size; i++) {
            int bitIndex = i * bits;
            int word = bitIndex >>> 6;
            int offset = bitIndex & 63;
            long value = packed[word] >>> offset;
            if (offset + bits > 64) {
                value |= packed[word + 1] << (64 - offset);
            }
            values[i] = (int) (value & mask);
        }
        return values;
    }

    private static Optional<SnapshotSummary> readSummary(Path directory) {
        Path meta = directory.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(meta, NbtAccounter.unlimitedHeap());
            if (tag.getInt("format") != FORMAT_VERSION) {
                return Optional.empty();
            }
            UUID id = tag.getUUID("id");
            UUID owner = tag.getUUID("owner");
            BlockPos center = new BlockPos(tag.getInt("center_x"), tag.getInt("center_y"), tag.getInt("center_z"));
            int radius = tag.getInt("radius");
            if (radius < 0 || radius > 32) {
                return Optional.empty();
            }
            return Optional.of(new SnapshotSummary(
                    id,
                    owner,
                    tag.getString("name"),
                    radius,
                    center,
                    tag.getLong("created_at"),
                    tag.getString("minecraft_version"),
                    tag.contains("data_version", CompoundTag.TAG_INT)
                            ? tag.getInt("data_version")
                            : inferDataVersion(tag.getString("minecraft_version"))
            ));
        } catch (Exception e) {
            InstantWorldMirror.LOGGER.warn("Failed to read stranded snapshot metadata {}: {}", meta, e.getMessage());
            return Optional.empty();
        }
    }

    private static CompoundTag writeSummary(SnapshotSummary summary) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", FORMAT_VERSION);
        tag.putUUID("id", summary.id());
        tag.putUUID("owner", summary.ownerId());
        tag.putString("name", summary.name());
        tag.putInt("radius", summary.radius());
        tag.putInt("center_x", summary.sourceCenter().getX());
        tag.putInt("center_y", summary.sourceCenter().getY());
        tag.putInt("center_z", summary.sourceCenter().getZ());
        tag.putLong("created_at", summary.createdAt());
        tag.putString("minecraft_version", summary.minecraftVersion());
        tag.putInt("data_version", summary.dataVersion());
        return tag;
    }

    private static boolean canUse(ServerPlayer player, SnapshotSummary summary) {
        return player.getUUID().equals(summary.ownerId()) || MirrorConfig.canManageConfig(player);
    }

    private static boolean isCompatible(SnapshotSummary summary) {
        return (currentMinecraftVersion().equals(summary.minecraftVersion())
                && summary.dataVersion() == currentDataVersion())
                || StrandedSnapshotUpgrader.supports(
                summary.minecraftVersion(), summary.dataVersion());
    }

    private static boolean isComplete(SnapshotSummary summary) {
        Path directory = snapshotDir(summary.id());
        for (int dx = -summary.radius(); dx <= summary.radius(); dx++) {
            for (int dz = -summary.radius(); dz <= summary.radius(); dz++) {
                if (!Files.isRegularFile(directory.resolve(chunkFileName(dx, dz)))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String currentMinecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    private static int currentDataVersion() {
        return StrandedSnapshotUpgrader.currentDataVersion();
    }

    private static int inferDataVersion(String minecraftVersion) {
        if (currentMinecraftVersion().equals(minecraftVersion)) {
            return currentDataVersion();
        }
        return StrandedSnapshotUpgrader.SOURCE_MINECRAFT_VERSION.equals(minecraftVersion)
                ? StrandedSnapshotUpgrader.SOURCE_DATA_VERSION
                : -1;
    }

    private static synchronized boolean prepareSnapshot(SnapshotSummary summary, RegistryAccess registryAccess) {
        Path sourceDirectory = snapshotDir(summary.id());
        if (summary.dataVersion() == currentDataVersion()) {
            PREPARED_SNAPSHOT_DIRECTORIES.put(summary.id(), sourceDirectory);
            return true;
        }
        Optional<Path> upgraded = StrandedSnapshotUpgrader.prepare(
                summary, sourceDirectory, registryAccess);
        upgraded.ifPresent(path -> PREPARED_SNAPSHOT_DIRECTORIES.put(summary.id(), path));
        return upgraded.isPresent();
    }

    private static String sanitizeName(String requestedName) {
        String value = requestedName == null ? "" : requestedName.trim();
        if (value.isEmpty()) {
            value = "Stranded " + System.currentTimeMillis();
        }
        value = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]", " ");
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    private static Path cacheRoot() {
        return FMLPaths.GAMEDIR.get().resolve("instantworldmirror-cache").resolve("stranded_snapshots");
    }

    private static Path snapshotDir(UUID id) {
        return cacheRoot().resolve(id.toString());
    }

    static String chunkFileName(int dx, int dz) {
        return "chunk_" + dx + "_" + dz + ".nbt";
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class CaptureTask {
        private final UUID id;
        private final UUID ownerId;
        private final ResourceKey<Level> sourceDimension;
        private final BlockPos center;
        private final int radius;
        private final String name;
        private final Path temporaryDirectory;
        private int dx;
        private int dz;

        private CaptureTask(UUID id, UUID ownerId, ResourceKey<Level> sourceDimension, BlockPos center,
                            int radius, String name) throws IOException {
            this.id = id;
            this.ownerId = ownerId;
            this.sourceDimension = sourceDimension;
            this.center = center;
            this.radius = radius;
            this.name = name;
            this.dx = -radius;
            this.dz = -radius;
            this.temporaryDirectory = cacheRoot().resolve(id + ".tmp");
            Files.createDirectories(temporaryDirectory);
        }

        private boolean captureNext(MinecraftServer server) throws IOException {
            ServerLevel level = server.getLevel(sourceDimension);
            if (level == null) {
                throw new IOException("Source dimension is not loaded");
            }

            int chunkX = (center.getX() >> 4) + dx;
            int chunkZ = (center.getZ() >> 4) + dz;
            CompoundTag chunk = captureChunk(level, chunkX, chunkZ);
            NbtIo.writeCompressed(chunk, temporaryDirectory.resolve(chunkFileName(dx, dz)));

            dx++;
            if (dx > radius) {
                dx = -radius;
                dz++;
            }
            if (dz <= radius) {
                return false;
            }

            SnapshotSummary summary = new SnapshotSummary(
                    id, ownerId, name, radius, center, System.currentTimeMillis(), currentMinecraftVersion());
            NbtIo.writeCompressed(writeSummary(summary), temporaryDirectory.resolve(META_FILE));
            Path destination = snapshotDir(id);
            Files.createDirectories(destination.getParent());
            try {
                Files.move(temporaryDirectory, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryDirectory, destination);
            }
            return true;
        }

        private void discard() {
            deleteTree(temporaryDirectory);
        }
    }
}
