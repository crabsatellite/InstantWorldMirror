package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server saved data for persistent mirror records and global creation grants.
 */
public class PersistentMirrorData extends SavedData {
    public static final String DATA_NAME = InstantWorldMirror.MODID + "_persistent_mirrors";

    private final Map<UUID, PersistentMirrorRecord> records = new ConcurrentHashMap<>();
    private final Set<UUID> creationGrants = ConcurrentHashMap.newKeySet();

    public static PersistentMirrorData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PersistentMirrorData::load,
                PersistentMirrorData::new,
                DATA_NAME
        );
    }

    public Collection<PersistentMirrorRecord> records() {
        return records.values().stream()
                .sorted(Comparator.comparingLong(PersistentMirrorRecord::createdAt))
                .toList();
    }

    public Optional<PersistentMirrorRecord> getRecord(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    public Optional<PersistentMirrorRecord> getRecordByDimensionIndex(int dimensionIndex) {
        return records.values().stream()
                .filter(record -> record.dimensionIndex() == dimensionIndex)
                .findFirst();
    }

    public Optional<PersistentMirrorRecord> getRecordBySourceSession(UUID sourceSessionId) {
        return records.values().stream()
                .filter(record -> sourceSessionId.equals(record.sourceSessionId()))
                .findFirst();
    }

    public List<PersistentMirrorRecord> removeUnreadyRecords() {
        List<PersistentMirrorRecord> unreadyRecords = records.values().stream()
                .filter(record -> !record.ready())
                .sorted(Comparator.comparingLong(PersistentMirrorRecord::createdAt))
                .toList();
        if (!unreadyRecords.isEmpty()) {
            for (PersistentMirrorRecord record : unreadyRecords) {
                records.remove(record.id());
            }
            setDirty();
        }
        return unreadyRecords;
    }

    public Optional<PersistentMirrorRecord> getRecordBySelector(String selector, Predicate<PersistentMirrorRecord> filter) {
        if (selector == null || selector.isBlank()) {
            return Optional.empty();
        }

        String normalized = selector.trim();
        try {
            UUID id = UUID.fromString(normalized);
            return getRecord(id).filter(filter);
        } catch (IllegalArgumentException ignored) {
        }

        OptionalInt slot = parseSlotSelector(normalized);
        if (slot.isPresent()) {
            return getRecordByDimensionIndex(slot.getAsInt() - 1).filter(filter);
        }

        List<PersistentMirrorRecord> nameMatches = records().stream()
                .filter(filter)
                .filter(record -> record.name().equalsIgnoreCase(normalized))
                .toList();
        return nameMatches.size() == 1 ? Optional.of(nameMatches.get(0)) : Optional.empty();
    }

    public void addRecord(PersistentMirrorRecord record) {
        records.put(record.id(), record);
        setDirty();
    }

    public void removeRecord(UUID id) {
        records.remove(id);
        setDirty();
    }

    public int allocateDimensionIndex() {
        for (int i = 0; i < ModDimensions.MAX_PERSISTENT_MIRROR_WORLD_POOL_SIZE; i++) {
            final int index = i;
            boolean used = records.values().stream().anyMatch(record -> record.dimensionIndex() == index);
            if (!used) {
                return index;
            }
        }
        return -1;
    }

    public boolean hasCreationGrant(UUID playerId) {
        return creationGrants.contains(playerId);
    }

    public void setCreationGrant(UUID playerId, boolean granted) {
        if (granted) {
            creationGrants.add(playerId);
        } else {
            creationGrants.remove(playerId);
        }
        setDirty();
    }

    public static PersistentMirrorData load(CompoundTag tag) {
        PersistentMirrorData data = new PersistentMirrorData();

        CompoundTag recordsTag = tag.getCompound("records");
        for (String key : recordsTag.getAllKeys()) {
            try {
                PersistentMirrorRecord record = PersistentMirrorRecord.load(recordsTag.getCompound(key));
                data.records.put(record.id(), record);
            } catch (Exception e) {
                InstantWorldMirror.LOGGER.warn("Failed to load persistent mirror record {}: {}", key, e.getMessage());
            }
        }

        ListTag grantsTag = tag.getList("creation_grants", 8);
        for (int i = 0; i < grantsTag.size(); i++) {
            try {
                data.creationGrants.add(UUID.fromString(grantsTag.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag recordsTag = new CompoundTag();
        for (PersistentMirrorRecord record : records.values()) {
            recordsTag.put(record.id().toString(), record.save());
        }
        tag.put("records", recordsTag);

        ListTag grantsTag = new ListTag();
        for (UUID playerId : creationGrants) {
            grantsTag.add(StringTag.valueOf(playerId.toString()));
        }
        tag.put("creation_grants", grantsTag);

        return tag;
    }

    private static OptionalInt parseSlotSelector(String selector) {
        String lower = selector.toLowerCase(Locale.ROOT);
        String numberText;
        if (lower.startsWith("slot_")) {
            numberText = lower.substring("slot_".length());
        } else if (lower.startsWith("slot")) {
            numberText = lower.substring("slot".length());
        } else {
            numberText = lower;
        }

        try {
            int slot = Integer.parseInt(numberText);
            return slot > 0 ? OptionalInt.of(slot) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
