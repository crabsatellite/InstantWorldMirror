package com.crabmods.instantworldmirror.world;

import java.util.Locale;

/**
 * Selects how a mirror session obtains its initial terrain.
 */
public enum MirrorTerrainMode {
    COPIED,
    PRISTINE,
    SUPERFLAT,
    SNAPSHOT;

    public static MirrorTerrainMode defaultFor(MirrorKind kind) {
        if (kind == MirrorKind.STRANDED) {
            return SNAPSHOT;
        }
        return kind.usesPristineTerrain() ? PRISTINE : COPIED;
    }

    public static MirrorTerrainMode forMirror(MirrorKind kind, boolean superflat) {
        return superflat && kind == MirrorKind.HEAVEN ? SUPERFLAT : defaultFor(kind);
    }

    public static MirrorTerrainMode byId(String id, MirrorKind kind) {
        if (id != null && !id.isBlank()) {
            try {
                MirrorTerrainMode parsed = valueOf(id.trim().toUpperCase(Locale.ROOT));
                if (parsed.supports(kind)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return defaultFor(kind);
    }

    public boolean supports(MirrorKind kind) {
        return switch (this) {
            case COPIED -> kind == MirrorKind.DIMENSION || kind == MirrorKind.HEAVEN;
            case PRISTINE -> kind == MirrorKind.FIRST_DREAM;
            case SUPERFLAT -> kind == MirrorKind.HEAVEN;
            case SNAPSHOT -> kind == MirrorKind.STRANDED;
        };
    }
}
