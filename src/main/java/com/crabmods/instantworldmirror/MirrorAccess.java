package com.crabmods.instantworldmirror;

import java.util.Locale;

/**
 * Server-side access policy for a mirror kind.
 */
public enum MirrorAccess {
    NONE("message.instantworldmirror.config.access.none"),
    ADMIN("message.instantworldmirror.config.access.admin"),
    ALL("message.instantworldmirror.config.access.all");

    private final String translationKey;

    MirrorAccess(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public MirrorAccess next() {
        return switch (this) {
            case ALL -> ADMIN;
            case ADMIN -> NONE;
            case NONE -> ALL;
        };
    }

    public boolean allows(boolean admin) {
        return switch (this) {
            case NONE -> false;
            case ADMIN -> admin;
            case ALL -> true;
        };
    }

    public static MirrorAccess parseFlexible(Object raw, MirrorAccess fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof MirrorAccess access) {
            return access;
        }
        if (raw instanceof Boolean enabled) {
            return enabled ? ALL : NONE;
        }
        String value = raw.toString().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "all", "true", "yes", "y", "on", "enabled", "enable", "o", "0", "1" -> ALL;
            case "admin", "admins", "op", "ops", "operator", "operators" -> ADMIN;
            case "none", "false", "no", "n", "off", "disabled", "disable", "x" -> NONE;
            default -> fallback;
        };
    }
}
