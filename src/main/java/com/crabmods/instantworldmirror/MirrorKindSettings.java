package com.crabmods.instantworldmirror;

import com.crabmods.instantworldmirror.world.MirrorKind;

public record MirrorKindSettings(
        MirrorAccess access,
        boolean mobSpawning,
        boolean itemTransfer,
        int copyChunkRadius
) {
    public static final int DEFAULT_COPY_CHUNK_RADIUS = 10;

    public static MirrorKindSettings defaults(MirrorKind kind) {
        return new MirrorKindSettings(
                MirrorAccess.ALL,
                kind == MirrorKind.FIRST_DREAM,
                false,
                DEFAULT_COPY_CHUNK_RADIUS
        );
    }

    public MirrorKindSettings withAccess(MirrorAccess value) {
        return new MirrorKindSettings(value, mobSpawning, itemTransfer, copyChunkRadius);
    }

    public MirrorKindSettings withMobSpawning(boolean value) {
        return new MirrorKindSettings(access, value, itemTransfer, copyChunkRadius);
    }

    public MirrorKindSettings withItemTransfer(boolean value) {
        return new MirrorKindSettings(access, mobSpawning, value, copyChunkRadius);
    }

    public MirrorKindSettings withCopyChunkRadius(int value) {
        return new MirrorKindSettings(access, mobSpawning, itemTransfer, clampCopyChunkRadius(value));
    }

    public MirrorKindSettings cycleAccess() {
        return withAccess(access.next());
    }

    public MirrorKindSettings toggleMobSpawning() {
        return withMobSpawning(!mobSpawning);
    }

    public MirrorKindSettings toggleItemTransfer() {
        return withItemTransfer(!itemTransfer);
    }

    public static int clampCopyChunkRadius(int value) {
        return Math.max(1, Math.min(32, value));
    }
}
