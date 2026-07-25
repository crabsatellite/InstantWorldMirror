package com.crabmods.instantworldmirror;

import com.crabmods.instantworldmirror.world.MirrorKind;

public record MirrorConfigState(
        MirrorKindSettings worldReflectionMirror,
        MirrorKindSettings heavenMirror,
        MirrorKindSettings firstDreamMirror,
        int mirrorCooldownSeconds
) {
    public static final int MIN_MIRROR_COOLDOWN_SECONDS = 30;
    public static final int MAX_MIRROR_COOLDOWN_SECONDS = 3600;
    public static final int DEFAULT_MIRROR_COOLDOWN_SECONDS = 300;

    public static MirrorConfigState defaults() {
        return new MirrorConfigState(
                MirrorKindSettings.defaults(MirrorKind.DIMENSION),
                MirrorKindSettings.defaults(MirrorKind.HEAVEN),
                MirrorKindSettings.defaults(MirrorKind.FIRST_DREAM),
                DEFAULT_MIRROR_COOLDOWN_SECONDS
        );
    }

    public static MirrorConfigState allAccess(MirrorAccess access) {
        MirrorConfigState defaults = defaults();
        return new MirrorConfigState(
                defaults.worldReflectionMirror.withAccess(access),
                defaults.heavenMirror.withAccess(access),
                defaults.firstDreamMirror.withAccess(access),
                defaults.mirrorCooldownSeconds
        );
    }

    public MirrorKindSettings get(MirrorKind kind) {
        return switch (kind) {
            case DIMENSION -> worldReflectionMirror;
            case HEAVEN -> heavenMirror;
            case FIRST_DREAM -> firstDreamMirror;
        };
    }

    public MirrorConfigState withSettings(MirrorKind kind, MirrorKindSettings settings) {
        return switch (kind) {
            case DIMENSION -> new MirrorConfigState(settings, heavenMirror, firstDreamMirror, mirrorCooldownSeconds);
            case HEAVEN -> new MirrorConfigState(worldReflectionMirror, settings, firstDreamMirror, mirrorCooldownSeconds);
            case FIRST_DREAM -> new MirrorConfigState(worldReflectionMirror, heavenMirror, settings, mirrorCooldownSeconds);
        };
    }

    public MirrorConfigState withMirrorCooldownSeconds(int value) {
        return new MirrorConfigState(
                worldReflectionMirror,
                heavenMirror,
                firstDreamMirror,
                clampMirrorCooldownSeconds(value)
        );
    }

    public MirrorConfigState withAccess(MirrorKind kind, MirrorAccess access) {
        return withSettings(kind, get(kind).withAccess(access));
    }

    public MirrorConfigState withMobSpawning(MirrorKind kind, boolean value) {
        return withSettings(kind, get(kind).withMobSpawning(value));
    }

    public MirrorConfigState withItemTransfer(MirrorKind kind, boolean value) {
        return withSettings(kind, get(kind).withItemTransfer(value));
    }

    public MirrorConfigState withCopyChunkRadius(MirrorKind kind, int value) {
        return withSettings(kind, get(kind).withCopyChunkRadius(value));
    }

    public MirrorConfigState cycleAccess(MirrorKind kind) {
        return withSettings(kind, get(kind).cycleAccess());
    }

    public MirrorConfigState toggleMobSpawning(MirrorKind kind) {
        return withSettings(kind, get(kind).toggleMobSpawning());
    }

    public MirrorConfigState toggleItemTransfer(MirrorKind kind) {
        return withSettings(kind, get(kind).toggleItemTransfer());
    }

    public static int clampMirrorCooldownSeconds(int value) {
        return Math.max(MIN_MIRROR_COOLDOWN_SECONDS, Math.min(MAX_MIRROR_COOLDOWN_SECONDS, value));
    }
}
