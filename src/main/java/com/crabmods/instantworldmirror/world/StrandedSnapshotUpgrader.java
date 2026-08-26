package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.mojang.datafixers.DSL.TypeReference;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

final class StrandedSnapshotUpgrader {
    static final String SOURCE_MINECRAFT_VERSION = "1.20.1";
    static final int SOURCE_DATA_VERSION = 3465;

    private static final String UPGRADE_META_FILE = "upgrade.nbt";

    private StrandedSnapshotUpgrader() {
    }

    static boolean supports(String minecraftVersion, int dataVersion) {
        return SOURCE_MINECRAFT_VERSION.equals(minecraftVersion)
                && dataVersion == SOURCE_DATA_VERSION;
    }

    static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    static Optional<Path> prepare(StrandedSnapshotManager.SnapshotSummary summary,
                                  Path sourceDirectory, RegistryAccess registryAccess) {
        if (!supports(summary.minecraftVersion(), summary.dataVersion())) {
            return Optional.empty();
        }

        Path destination = upgradeDirectory(sourceDirectory);
        if (isComplete(destination, summary, registryAccess)) {
            return Optional.of(destination);
        }

        Path temporary = temporaryUpgradeDirectory(sourceDirectory);
        StrandedSnapshotManager.deleteTree(temporary);
        try {
            Files.createDirectories(temporary);
            UpgradeRequirements requirements = new UpgradeRequirements();
            for (int dx = -summary.radius(); dx <= summary.radius(); dx++) {
                for (int dz = -summary.radius(); dz <= summary.radius(); dz++) {
                    Path sourceChunk = sourceDirectory.resolve(
                            StrandedSnapshotManager.chunkFileName(dx, dz));
                    CompoundTag raw = NbtIo.readCompressed(sourceChunk, NbtAccounter.unlimitedHeap());
                    CompoundTag upgraded = upgradeChunk(
                            raw, summary.dataVersion(), registryAccess, requirements);
                    NbtIo.writeCompressed(upgraded, temporary.resolve(
                            StrandedSnapshotManager.chunkFileName(dx, dz)));
                }
            }

            CompoundTag marker = new CompoundTag();
            marker.putUUID("snapshot_id", summary.id());
            marker.putInt("source_data_version", summary.dataVersion());
            marker.putInt("target_data_version", currentDataVersion());
            marker.putInt("requirements_version", 1);
            putStringList(marker, "required_blocks", requirements.blocks);
            putStringList(marker, "required_biomes", requirements.biomes);
            putStringList(marker, "required_block_entities", requirements.blockEntities);
            putStringList(marker, "required_items", requirements.items);
            NbtIo.writeCompressed(marker, temporary.resolve(UPGRADE_META_FILE));

            StrandedSnapshotManager.deleteTree(destination);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination);
            }
            return Optional.of(destination);
        } catch (Exception e) {
            StrandedSnapshotManager.deleteTree(temporary);
            InstantWorldMirror.LOGGER.warn(
                    "Failed to upgrade stranded snapshot {} from Minecraft {}: {}",
                    summary.id(), summary.minecraftVersion(), e.getMessage());
            return Optional.empty();
        }
    }

    static boolean hasArtifacts(Path sourceDirectory) {
        return Files.exists(upgradeDirectory(sourceDirectory))
                || Files.exists(temporaryUpgradeDirectory(sourceDirectory));
    }

    static Optional<CompoundTag> readMarker(Path sourceDirectory) {
        Path marker = upgradeDirectory(sourceDirectory).resolve(UPGRADE_META_FILE);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            return Optional.of(NbtIo.readCompressed(marker, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path upgradeDirectory(Path sourceDirectory) {
        return sourceDirectory.resolve("upgrade_" + currentDataVersion());
    }

    private static Path temporaryUpgradeDirectory(Path sourceDirectory) {
        return sourceDirectory.resolve("upgrade_" + currentDataVersion() + ".tmp");
    }

    private static boolean isComplete(Path directory, StrandedSnapshotManager.SnapshotSummary summary,
                                      RegistryAccess registryAccess) {
        Path markerPath = directory.resolve(UPGRADE_META_FILE);
        if (!Files.isRegularFile(markerPath)) {
            return false;
        }
        try {
            CompoundTag marker = NbtIo.readCompressed(markerPath, NbtAccounter.unlimitedHeap());
            if (!summary.id().equals(marker.getUUID("snapshot_id"))
                    || marker.getInt("source_data_version") != summary.dataVersion()
                    || marker.getInt("target_data_version") != currentDataVersion()
                    || marker.getInt("requirements_version") != 1
                    || !hasRegistryEntries(marker, "required_blocks",
                    registryAccess.registryOrThrow(Registries.BLOCK))
                    || !hasRegistryEntries(marker, "required_biomes",
                    registryAccess.registryOrThrow(Registries.BIOME))
                    || !hasRegistryEntries(marker, "required_block_entities",
                    registryAccess.registryOrThrow(Registries.BLOCK_ENTITY_TYPE))
                    || !hasRegistryEntries(marker, "required_items",
                    registryAccess.registryOrThrow(Registries.ITEM))) {
                return false;
            }
            for (int dx = -summary.radius(); dx <= summary.radius(); dx++) {
                for (int dz = -summary.radius(); dz <= summary.radius(); dz++) {
                    if (!Files.isRegularFile(directory.resolve(
                            StrandedSnapshotManager.chunkFileName(dx, dz)))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static CompoundTag upgradeChunk(CompoundTag source, int sourceDataVersion,
                                            RegistryAccess registryAccess,
                                            UpgradeRequirements requirements) throws IOException {
        CompoundTag result = source.copy();
        Registry<Block> blocks = registryAccess.registryOrThrow(Registries.BLOCK);
        Registry<Biome> biomes = registryAccess.registryOrThrow(Registries.BIOME);
        Registry<BlockEntityType<?>> blockEntityTypes =
                registryAccess.registryOrThrow(Registries.BLOCK_ENTITY_TYPE);
        Registry<Item> items = registryAccess.registryOrThrow(Registries.ITEM);

        ListTag upgradedSections = new ListTag();
        ListTag sections = source.getList("sections", Tag.TAG_COMPOUND);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            CompoundTag section = sections.getCompound(sectionIndex).copy();
            ListTag upgradedPalette = new ListTag();
            ListTag palette = section.getList("palette", Tag.TAG_COMPOUND);
            for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                Tag fixed = updateData(
                        References.BLOCK_STATE, palette.getCompound(paletteIndex), sourceDataVersion);
                if (!(fixed instanceof CompoundTag state)) {
                    throw new IOException("DataFixer returned an invalid block state");
                }
                requirements.blocks.add(requireRegistryEntry(
                        blocks, state.getString("Name"), "block").toString());
                upgradedPalette.add(state);
            }
            section.put("palette", upgradedPalette);

            ListTag upgradedBiomes = new ListTag();
            ListTag sourceBiomes = section.getList("biomes", Tag.TAG_STRING);
            for (int biomeIndex = 0; biomeIndex < sourceBiomes.size(); biomeIndex++) {
                Tag fixed = updateData(
                        References.BIOME,
                        StringTag.valueOf(sourceBiomes.getString(biomeIndex)),
                        sourceDataVersion);
                if (!(fixed instanceof StringTag biome)) {
                    throw new IOException("DataFixer returned an invalid biome");
                }
                requirements.biomes.add(requireRegistryEntry(
                        biomes, biome.getAsString(), "biome").toString());
                upgradedBiomes.add(biome);
            }
            section.put("biomes", upgradedBiomes);
            upgradedSections.add(section);
        }
        result.put("sections", upgradedSections);

        ListTag upgradedBlockEntities = new ListTag();
        ListTag blockEntities = source.getList("block_entities", Tag.TAG_COMPOUND);
        for (int index = 0; index < blockEntities.size(); index++) {
            CompoundTag entry = blockEntities.getCompound(index).copy();
            CompoundTag data = entry.getCompound("data");
            ResourceLocation sourceId = ResourceLocation.tryParse(data.getString("id"));
            if (sourceId == null || !"minecraft".equals(sourceId.getNamespace())) {
                throw new IOException("Cannot safely upgrade modded block entity " + data.getString("id"));
            }
            Tag fixed = updateData(References.BLOCK_ENTITY, data, sourceDataVersion);
            if (!(fixed instanceof CompoundTag upgradedData)) {
                throw new IOException("DataFixer returned invalid block entity data for " + sourceId);
            }
            requirements.blockEntities.add(requireRegistryEntry(
                    blockEntityTypes, upgradedData.getString("id"), "block entity").toString());
            validateItemStackIds(upgradedData, items, requirements);
            entry.put("data", upgradedData);
            upgradedBlockEntities.add(entry);
        }
        result.put("block_entities", upgradedBlockEntities);
        return result;
    }

    private static Tag updateData(TypeReference reference, Tag value, int sourceDataVersion) {
        Dynamic<Tag> input = new Dynamic<>(NbtOps.INSTANCE, value);
        return DataFixers.getDataFixer()
                .update(reference, input, sourceDataVersion, currentDataVersion())
                .getValue();
    }

    private static <T> ResourceLocation requireRegistryEntry(
            Registry<T> registry, String rawId, String kind) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null || !registry.containsKey(id)) {
            throw new IOException("Missing " + kind + " " + rawId);
        }
        return id;
    }

    private static void validateItemStackIds(
            Tag value, Registry<Item> items, UpgradeRequirements requirements) throws IOException {
        if (value instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING)
                    && (compound.contains("Count", Tag.TAG_ANY_NUMERIC)
                    || compound.contains("count", Tag.TAG_ANY_NUMERIC))) {
                requirements.items.add(requireRegistryEntry(
                        items, compound.getString("id"), "item").toString());
            }
            for (String key : compound.getAllKeys()) {
                validateItemStackIds(compound.get(key), items, requirements);
            }
        } else if (value instanceof ListTag list) {
            for (Tag element : list) {
                validateItemStackIds(element, items, requirements);
            }
        }
    }

    private static void putStringList(CompoundTag target, String key, Set<String> values) {
        ListTag list = new ListTag();
        values.stream().sorted().map(StringTag::valueOf).forEach(list::add);
        target.put(key, list);
    }

    private static <T> boolean hasRegistryEntries(
            CompoundTag marker, String key, Registry<T> registry) {
        ListTag ids = marker.getList(key, Tag.TAG_STRING);
        for (int index = 0; index < ids.size(); index++) {
            ResourceLocation id = ResourceLocation.tryParse(ids.getString(index));
            if (id == null || !registry.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    private static final class UpgradeRequirements {
        private final Set<String> blocks = new LinkedHashSet<>();
        private final Set<String> biomes = new LinkedHashSet<>();
        private final Set<String> blockEntities = new LinkedHashSet<>();
        private final Set<String> items = new LinkedHashSet<>();
    }
}
