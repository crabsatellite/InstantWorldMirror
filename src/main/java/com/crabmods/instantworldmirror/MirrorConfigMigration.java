package com.crabmods.instantworldmirror;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLPaths;

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
            changed |= migrateBoolean(config, "enableMobSpawning",
                    "worldReflectionMirrorMobSpawning",
                    "heavenMirrorMobSpawning",
                    "firstDreamMirrorMobSpawning");
            changed |= migrateBoolean(config, "allowItemTransfer",
                    "worldReflectionMirrorItemTransfer",
                    "heavenMirrorItemTransfer",
                    "firstDreamMirrorItemTransfer");
            changed |= migrateInt(config, "copyChunkRadius",
                    "worldReflectionMirrorCopyChunkRadius",
                    "heavenMirrorCopyChunkRadius",
                    "firstDreamMirrorCopyChunkRadius");
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

    private static boolean migrateBoolean(CommentedFileConfig config, String legacyKey, String... newKeys) {
        if (!config.contains(legacyKey)) {
            return false;
        }

        Object legacyValue = config.get(legacyKey);
        boolean migrated = Boolean.TRUE.equals(legacyValue);
        for (String newKey : newKeys) {
            if (!config.contains(newKey)) {
                config.set(newKey, migrated);
            }
        }
        config.remove(legacyKey);
        return true;
    }

    private static boolean migrateInt(CommentedFileConfig config, String legacyKey, String... newKeys) {
        if (!config.contains(legacyKey)) {
            return false;
        }

        Object legacyValue = config.get(legacyKey);
        if (!(legacyValue instanceof Number number)) {
            config.remove(legacyKey);
            return true;
        }

        int migrated = MirrorKindSettings.clampCopyChunkRadius(number.intValue());
        for (String newKey : newKeys) {
            if (!config.contains(newKey)) {
                config.set(newKey, migrated);
            }
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

    private static boolean migrateMirrorCooldown(CommentedFileConfig config) {
        if (!config.contains("mirrorCooldown")) {
            return false;
        }

        Object raw = config.get("mirrorCooldown");
        int parsed = raw instanceof Number number
                ? number.intValue()
                : MirrorConfigState.DEFAULT_MIRROR_COOLDOWN_SECONDS;
        int migrated = parsed < MirrorConfigState.MIN_MIRROR_COOLDOWN_SECONDS
                ? MirrorConfigState.DEFAULT_MIRROR_COOLDOWN_SECONDS
                : MirrorConfigState.clampMirrorCooldownSeconds(parsed);
        if (raw instanceof Number number && number.intValue() == migrated) {
            return false;
        }
        config.set("mirrorCooldown", migrated);
        return true;
    }
}
