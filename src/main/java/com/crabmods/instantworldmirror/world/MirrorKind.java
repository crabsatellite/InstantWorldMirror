package com.crabmods.instantworldmirror.world;

/**
 * Server-side mirror behavior type.
 */
public enum MirrorKind {
    DIMENSION("dimension"),
    HEAVEN("heaven");

    private final String id;

    MirrorKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isSandbox() {
        return this == HEAVEN;
    }

    public static MirrorKind fromSandboxMode(boolean sandboxMode) {
        return sandboxMode ? HEAVEN : DIMENSION;
    }

    public static MirrorKind byId(String id) {
        for (MirrorKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return DIMENSION;
    }
}
