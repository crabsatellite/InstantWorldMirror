package com.crabmods.instantworldmirror.world;

/**
 * Server-side mirror behavior type.
 */
public enum MirrorKind {
    DIMENSION("dimension", "item.instantworldmirror.dimension_mirror", "World Reflection Mirror"),
    HEAVEN("heaven", "item.instantworldmirror.heaven_mirror", "Heaven Mirror"),
    FIRST_DREAM("first_dream", "item.instantworldmirror.first_dream_mirror", "First Dream Mirror");

    private final String id;
    private final String translationKey;
    private final String defaultName;

    MirrorKind(String id, String translationKey, String defaultName) {
        this.id = id;
        this.translationKey = translationKey;
        this.defaultName = defaultName;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public String defaultName() {
        return defaultName;
    }

    public boolean isSandbox() {
        return this == HEAVEN;
    }

    public boolean usesPristineTerrain() {
        return this == FIRST_DREAM;
    }

    public boolean usesHeavenVisuals() {
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
