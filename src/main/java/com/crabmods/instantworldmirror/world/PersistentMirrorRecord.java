package com.crabmods.instantworldmirror.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/**
 * Saved metadata for one persistent mirror world slot.
 */
public class PersistentMirrorRecord {
    private final UUID id;
    private final UUID ownerId;
    private final UUID sourceSessionId;
    private String name;
    private final MirrorKind kind;
    private final int dimensionIndex;
    private final ResourceKey<Level> sourceDimension;
    private final BlockPos sourcePosition;
    private final BlockPos entryPosition;
    private final boolean sourceInWater;
    private final long createdAt;
    private boolean ready;

    public PersistentMirrorRecord(UUID id, UUID ownerId, UUID sourceSessionId, String name, MirrorKind kind, int dimensionIndex,
                                  ResourceKey<Level> sourceDimension, BlockPos sourcePosition,
                                  BlockPos entryPosition, boolean sourceInWater, long createdAt, boolean ready) {
        this.id = id;
        this.ownerId = ownerId;
        this.sourceSessionId = Objects.requireNonNull(sourceSessionId, "sourceSessionId");
        this.name = name;
        this.kind = kind;
        this.dimensionIndex = dimensionIndex;
        this.sourceDimension = sourceDimension;
        this.sourcePosition = sourcePosition;
        this.entryPosition = entryPosition;
        this.sourceInWater = sourceInWater;
        this.createdAt = createdAt;
        this.ready = ready;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID sourceSessionId() {
        return sourceSessionId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MirrorKind kind() {
        return kind;
    }

    public int dimensionIndex() {
        return dimensionIndex;
    }

    public ResourceKey<Level> sourceDimension() {
        return sourceDimension;
    }

    public BlockPos sourcePosition() {
        return sourcePosition;
    }

    public BlockPos entryPosition() {
        return entryPosition;
    }

    public boolean sourceInWater() {
        return sourceInWater;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean ready() {
        return ready;
    }

    public String selector() {
        return "slot_" + (dimensionIndex + 1);
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", ownerId);
        tag.putUUID("source_session", sourceSessionId);
        tag.putString("name", name);
        tag.putString("kind", kind.id());
        tag.putInt("dimension_index", dimensionIndex);
        tag.putString("source_dimension", sourceDimension.location().toString());
        writePos(tag, "source_pos", sourcePosition);
        writePos(tag, "entry_pos", entryPosition);
        tag.putBoolean("source_in_water", sourceInWater);
        tag.putLong("created_at", createdAt);
        tag.putBoolean("ready", ready);
        return tag;
    }

    public static PersistentMirrorRecord load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        UUID owner = tag.getUUID("owner");
        UUID sourceSessionId = tag.getUUID("source_session");
        String name = tag.getString("name");
        MirrorKind kind = MirrorKind.byId(tag.getString("kind"));
        int dimensionIndex = tag.getInt("dimension_index");
        ResourceLocation sourceLocation = ResourceLocation.tryParse(tag.getString("source_dimension"));
        ResourceKey<Level> sourceDimension = sourceLocation != null
                ? ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, sourceLocation)
                : Level.OVERWORLD;
        BlockPos sourcePos = readPos(tag, "source_pos");
        BlockPos entryPos = readPos(tag, "entry_pos");
        boolean sourceInWater = tag.getBoolean("source_in_water");
        long createdAt = tag.getLong("created_at");
        boolean ready = tag.getBoolean("ready");
        return new PersistentMirrorRecord(id, owner, sourceSessionId, name, kind, dimensionIndex, sourceDimension,
                sourcePos, entryPos, sourceInWater, createdAt, ready);
    }

    private static void writePos(CompoundTag tag, String key, BlockPos pos) {
        CompoundTag posTag = new CompoundTag();
        posTag.putInt("x", pos.getX());
        posTag.putInt("y", pos.getY());
        posTag.putInt("z", pos.getZ());
        tag.put(key, posTag);
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        CompoundTag posTag = tag.getCompound(key);
        return new BlockPos(posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z"));
    }
}
