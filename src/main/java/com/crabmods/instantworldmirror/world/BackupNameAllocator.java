package com.crabmods.instantworldmirror.world;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class BackupNameAllocator {
    private static final String BACKUP_SEPARATOR = " - Backup ";

    private BackupNameAllocator() {
    }

    static String next(String sourceName, Collection<String> existingNames, int maxLength) {
        String base = baseName(sourceName);
        Set<String> existing = existingNames.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            String suffix = BACKUP_SEPARATOR + index;
            int baseLimit = Math.max(1, maxLength - suffix.length());
            String candidateBase = base.length() > baseLimit ? base.substring(0, baseLimit) : base;
            String candidate = candidateBase + suffix;
            if (!existing.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a backup name");
    }

    private static String baseName(String sourceName) {
        String value = sourceName == null || sourceName.isBlank() ? "Mirror" : sourceName.trim();
        int separator = value.lastIndexOf(BACKUP_SEPARATOR);
        if (separator < 0) {
            return value;
        }
        String number = value.substring(separator + BACKUP_SEPARATOR.length());
        if (number.isEmpty() || number.chars().anyMatch(character -> !Character.isDigit(character))) {
            return value;
        }
        return value.substring(0, separator);
    }
}
