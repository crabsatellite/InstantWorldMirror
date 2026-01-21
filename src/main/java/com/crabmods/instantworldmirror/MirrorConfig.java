package com.crabmods.instantworldmirror;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod configuration class
 */
public class MirrorConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // World copy settings (in chunks)
    public static final ModConfigSpec.IntValue COPY_CHUNK_RADIUS = BUILDER
            .comment("Mirror world copy radius in chunks (default: 10 chunks)")
            .defineInRange("copyChunkRadius", 10, 1, 32);

    // Portal settings
    public static final ModConfigSpec.IntValue PORTAL_DURATION = BUILDER
            .comment("Portal duration in seconds (default: 5)")
            .defineInRange("portalDuration", 5, 1, 60);

    // Item rules
    public static final ModConfigSpec.BooleanValue ALLOW_ITEM_TRANSFER = BUILDER
            .comment("Allow items to be transferred back to overworld by default (default: false)")
            .define("allowItemTransfer", false);

    // Mob spawning
    public static final ModConfigSpec.BooleanValue ENABLE_MOB_SPAWNING = BUILDER
            .comment("Enable mob spawning in mirror world (default: false)")
            .define("enableMobSpawning", false);

    // Server limits
    public static final ModConfigSpec.IntValue MAX_MIRROR_WORLDS_PER_PLAYER = BUILDER
            .comment("Maximum mirror worlds per player on server (default: 1)")
            .defineInRange("maxMirrorWorldsPerPlayer", 1, 1, 5);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
