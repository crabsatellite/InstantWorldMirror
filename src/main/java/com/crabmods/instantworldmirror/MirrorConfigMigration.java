package com.crabmods.instantworldmirror;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MirrorConfigMigration {
    private static final String CONFIG_FILE = "instantworldmirror-common.toml";

    private MirrorConfigMigration() {
    }

    public static void migrateCommonConfig() {
        migrateCommonConfig(FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE));
    }

    public static void migrateCommonConfig(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try (CommentedFileConfig config = CommentedFileConfig.builder(path)
                .sync()
                .autosave()
                .preserveInsertionOrder()
                .build()) {
            config.load();
            boolean changed = migrateAccess(config,
                    "enableWorldReflectionMirror", "worldReflectionMirrorAccess");
            changed |= migrateAccess(config,
                    "enableHeavenMirror", "heavenMirrorAccess");
            changed |= migrateAccess(config,
                    "enableFirstDreamMirror", "firstDreamMirrorAccess");
            changed |= ensureAccess(config, "strandedMirrorAccess", MirrorAccess.ALL);
            changed |= migrateBoolean(config,
                    "enableMobSpawning", "worldReflectionMirrorMobSpawning", false);
            changed |= migrateBoolean(config,
                    "enableMobSpawning", "heavenMirrorMobSpawning", false);
            changed |= migrateBoolean(config,
                    "enableMobSpawning", "firstDreamMirrorMobSpawning", true);
            changed |= migrateBoolean(config,
                    "enableMobSpawning", "strandedMirrorMobSpawning", false);
            changed |= removeLegacyKey(config, "enableMobSpawning");
            changed |= migrateBoolean(config,
                    "allowItemTransfer", "worldReflectionMirrorItemTransfer", false);
            changed |= migrateBoolean(config,
                    "allowItemTransfer", "heavenMirrorItemTransfer", false);
            changed |= migrateBoolean(config,
                    "allowItemTransfer", "firstDreamMirrorItemTransfer", false);
            changed |= migrateBoolean(config,
                    "allowItemTransfer", "strandedMirrorItemTransfer", false);
            changed |= removeLegacyKey(config, "allowItemTransfer");
            changed |= migrateInt(config,
                    "copyChunkRadius", "worldReflectionMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS);
            changed |= migrateInt(config,
                    "copyChunkRadius", "heavenMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS);
            changed |= migrateInt(config,
                    "copyChunkRadius", "firstDreamMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS);
            changed |= migrateInt(config,
                    "copyChunkRadius", "strandedMirrorCopyChunkRadius",
                    MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS);
            changed |= removeLegacyKey(config, "copyChunkRadius");
            changed |= migrateMirrorCooldown(config);
            if (changed) {
                config.save();
                InstantWorldMirror.LOGGER.info("Migrated legacy mirror config in {}", path);
            }
        } catch (RuntimeException e) {
            InstantWorldMirror.LOGGER.warn("Failed to migrate legacy mirror config {}: {}",
                    path, e.getMessage());
        }
    }

    private static boolean migrateAccess(CommentedFileConfig config, String legacyKey, String newKey) {
        boolean changed = normalizeExistingAccess(config, newKey);
        if (!config.contains(legacyKey)) {
            return changed;
        }

        Object legacyValue = config.get(legacyKey);
        MirrorAccess migrated = MirrorAccess.parseFlexible(legacyValue, MirrorAccess.ALL);
        if (!config.contains(newKey)) {
            config.set(newKey, migrated.name());
        }
        config.remove(legacyKey);
        return true;
    }

    private static boolean normalizeExistingAccess(CommentedFileConfig config, String key) {
        if (!config.contains(key)) {
            return false;
        }
        Object raw = config.get(key);
        MirrorAccess access = MirrorAccess.parseFlexible(raw, null);
        if (access == null || access.name().equals(raw)) {
            return false;
        }
        config.set(key, access.name());
        return true;
    }

    private static boolean ensureAccess(CommentedFileConfig config, String key, MirrorAccess defaultValue) {
        if (config.contains(key)) {
            return normalizeExistingAccess(config, key);
        }
        config.set(key, defaultValue.name());
        return true;
    }

    private static boolean removeLegacyKey(CommentedFileConfig config, String key) {
        if (!config.contains(key)) {
            return false;
        }
        config.remove(key);
        return true;
    }

    private static boolean migrateBoolean(CommentedFileConfig config, String legacyKey, String newKey, boolean defaultValue) {
        if (config.contains(newKey)) {
            return normalizeBoolean(config, newKey);
        }

        boolean value = defaultValue;
        if (config.contains(legacyKey)) {
            value = parseBoolean(config.get(legacyKey), defaultValue);
        }
        config.set(newKey, value);
        return true;
    }

    private static boolean normalizeBoolean(CommentedFileConfig config, String key) {
        Object raw = config.get(key);
        boolean parsed = parseBoolean(raw, false);
        if (raw instanceof Boolean value && value == parsed) {
            return false;
        }
        config.set(key, parsed);
        return true;
    }

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String value) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "true", "yes", "y", "on", "o", "1" -> true;
                case "false", "no", "n", "off", "x", "0" -> false;
                default -> defaultValue;
            };
        }
        return defaultValue;
    }

    private static boolean migrateInt(CommentedFileConfig config, String legacyKey, String newKey, int defaultValue) {
        if (config.contains(newKey)) {
            return normalizeInt(config, newKey);
        }

        int value = defaultValue;
        if (config.contains(legacyKey)) {
            value = parseInt(config.get(legacyKey), defaultValue);
        }
        config.set(newKey, MirrorKindSettings.clampCopyChunkRadius(value));
        return true;
    }

    private static boolean normalizeInt(CommentedFileConfig config, String key) {
        Object raw = config.get(key);
        int parsed = MirrorKindSettings.clampCopyChunkRadius(parseInt(raw, MirrorKindSettings.DEFAULT_COPY_CHUNK_RADIUS));
        if (raw instanceof Number value && value.intValue() == parsed) {
            return false;
        }
        config.set(key, parsed);
        return true;
    }

    private static int parseInt(Object raw, int defaultValue) {
        if (raw instanceof Number value) {
            return value.intValue();
        }
        if (raw instanceof String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean migrateMirrorCooldown(CommentedFileConfig config) {
        if (!config.contains("mirrorCooldown")) {
            return false;
        }

        Object raw = config.get("mirrorCooldown");
        int parsed = parseInt(raw, MirrorConfigState.DEFAULT_MIRROR_COOLDOWN_SECONDS);
        int migrated = parsed < MirrorConfigState.MIN_MIRROR_COOLDOWN_SECONDS
                ? MirrorConfigState.DEFAULT_MIRROR_COOLDOWN_SECONDS
                : MirrorConfigState.clampMirrorCooldownSeconds(parsed);
        if (raw instanceof Number value && value.intValue() == migrated) {
            return false;
        }
        config.set("mirrorCooldown", migrated);
        return true;
    }
}
