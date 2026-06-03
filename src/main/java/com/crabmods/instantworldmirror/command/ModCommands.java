package com.crabmods.instantworldmirror.command;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.entity.ModEntities;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.PersistentMirrorData;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import com.crabmods.instantworldmirror.world.PersistentMirrorRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Mod command registration class
 */
public class ModCommands {

    // Custom suggestion provider for mirror world dimensions
    private static final SuggestionProvider<CommandSourceStack> MIRROR_DIMENSION_SUGGESTIONS = (context, builder) -> {
        int poolSize = ModDimensions.getPoolSize();
        return SharedSuggestionProvider.suggestResource(
                IntStream.range(0, poolSize)
                        .mapToObj(i -> ModDimensions.getMirrorWorld(i).location())
                        .toList(),
                builder
        );
    };

    private static final SuggestionProvider<CommandSourceStack> PERSISTENT_RECORD_SUGGESTIONS = (context, builder) -> {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            return suggestPersistentRecords(player, builder);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> PERSISTENT_NAME_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    Stream.of(MirrorKind.values()).map(MirrorKind::defaultName).toList(),
                    builder
            );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("iwm")
                        // /iwm return - Force return to overworld
                        .then(Commands.literal("return")
                                .executes(ModCommands::returnCommand)
                        )
                        // /iwm mob on/off/status - Control mob spawning
                        .then(Commands.literal("mob")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("on")
                                        .executes(ModCommands::mobOnCommand)
                                )
                                .then(Commands.literal("off")
                                        .executes(ModCommands::mobOffCommand)
                                )
                                .then(Commands.literal("status")
                                        .executes(ModCommands::mobStatusCommand)
                                )
                        )
                        // /iwm allow <player> - Allow player to enter mirror world
                        .then(Commands.literal("allow")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ModCommands::allowCommand)
                                )
                        )
                        // /iwm deny <player> - Deny player from entering mirror world
                        .then(Commands.literal("deny")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ModCommands::denyCommand)
                                )
                        )
                        // /iwm itemtransfer <player> <true/false> - Control item transfer permission
                        .then(Commands.literal("itemtransfer")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("allowed", BoolArgumentType.bool())
                                                .executes(ModCommands::itemTransferCommand)
                                        )
                                )
                        )
                        // /iwm status - View dimension pool status
                        .then(Commands.literal("status")
                                .requires(source -> source.hasPermission(2))
                                .executes(ModCommands::statusCommand)
                        )
                        // /iwm persistent - Manage persistent mirror worlds
                        .then(Commands.literal("persistent")
                                .then(Commands.literal("menu")
                                        .executes(ModCommands::persistentMenuCommand)
                                )
                                .then(Commands.literal("save")
                                        .executes(context -> persistentSaveCommand(context, ""))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .suggests(PERSISTENT_NAME_SUGGESTIONS)
                                                .executes(context -> persistentSaveCommand(
                                                        context, StringArgumentType.getString(context, "name")))
                                        )
                                )
                                .then(Commands.literal("enter")
                                        .then(Commands.argument("mirror", StringArgumentType.string())
                                                .suggests(PERSISTENT_RECORD_SUGGESTIONS)
                                                .executes(ModCommands::persistentEnterCommand)
                                        )
                                )
                                .then(Commands.literal("leave")
                                        .executes(ModCommands::persistentLeaveCommand)
                                )
                                .then(Commands.literal("delete")
                                        .then(Commands.argument("mirror", StringArgumentType.string())
                                                .suggests(PERSISTENT_RECORD_SUGGESTIONS)
                                                .executes(ModCommands::persistentDeleteCommand)
                                        )
                                )
                                .then(Commands.literal("rename")
                                        .then(Commands.argument("mirror", StringArgumentType.string())
                                                .suggests(PERSISTENT_RECORD_SUGGESTIONS)
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .suggests(PERSISTENT_NAME_SUGGESTIONS)
                                                        .executes(ModCommands::persistentRenameCommand)
                                                )
                                        )
                                )
                                .then(Commands.literal("grant")
                                        .requires(source -> source.hasPermission(3))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(context -> persistentGrantCommand(context, true))
                                        )
                                )
                                .then(Commands.literal("revoke")
                                        .requires(source -> source.hasPermission(3))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(context -> persistentGrantCommand(context, false))
                                        )
                                )
                        )
                        // /iwm forceclear <dimension> - Force clear a dimension (with tab completion)
                        .then(Commands.literal("forceclear")
                                .requires(source -> source.hasPermission(3))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .suggests(MIRROR_DIMENSION_SUGGESTIONS)
                                        .executes(ModCommands::forceClearCommand)
                                )
                        )
                        // /iwm purge - Completely delete mirror world save files (requires restart)
                        .then(Commands.literal("purge")
                                .requires(source -> source.hasPermission(3))
                                .executes(ModCommands::purgeCommand)
                        )
        );
    }

    private static int returnCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (source.getEntity() instanceof ServerPlayer player) {
            if (MirrorWorldManager.isInMirrorWorld(player)) {
                MirrorWorldManager.forceReturn(player);
                source.sendSuccess(() -> Component.translatable("command.instantworldmirror.return.success"), false);
                return 1;
            } else {
                source.sendFailure(Component.translatable("command.instantworldmirror.return.not_in_mirror"));
                return 0;
            }
        }
        
        source.sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentMenuCommand(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            PersistentMirrorManager.openMirrorMenu(player, DimensionMirrorItem.findMirrorStack(player));
            return 1;
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentSaveCommand(CommandContext<CommandSourceStack> context, String name) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            return PersistentMirrorManager.saveCurrentTemporaryMirror(player, name) ? 1 : 0;
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentEnterCommand(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            Optional<UUID> recordId = getPersistentRecordId(context, player);
            return recordId.map(id -> PersistentMirrorManager.enterPersistentMirror(player, id) ? 1 : 0).orElse(0);
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentLeaveCommand(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            return PersistentMirrorManager.leavePersistentMirror(player) ? 1 : 0;
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentDeleteCommand(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            Optional<UUID> recordId = getPersistentRecordId(context, player);
            return recordId.map(id -> PersistentMirrorManager.deleteRecord(player, id) ? 1 : 0).orElse(0);
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentRenameCommand(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            Optional<UUID> recordId = getPersistentRecordId(context, player);
            String name = StringArgumentType.getString(context, "name");
            return recordId.map(id -> PersistentMirrorManager.renameRecord(player, id, name) ? 1 : 0).orElse(0);
        }
        context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_only"));
        return 0;
    }

    private static int persistentGrantCommand(CommandContext<CommandSourceStack> context, boolean granted) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            PersistentMirrorManager.setCreationGrant(context.getSource().getServer(), target.getUUID(), granted);
            context.getSource().sendSuccess(
                    () -> Component.translatable(granted
                                    ? "command.instantworldmirror.persistent.grant.success"
                                    : "command.instantworldmirror.persistent.revoke.success",
                            target.getName().getString()),
                    true
            );
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_not_found"));
            return 0;
        }
    }

    private static Optional<UUID> getPersistentRecordId(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        String selector = StringArgumentType.getString(context, "mirror");
        Optional<PersistentMirrorRecord> record = PersistentMirrorManager.resolveRecordSelector(player, selector);
        if (record.isEmpty()) {
            context.getSource().sendFailure(
                    Component.translatable("command.instantworldmirror.persistent.selector_not_found", selector));
        }
        return record.map(PersistentMirrorRecord::id);
    }

    private static CompletableFuture<Suggestions> suggestPersistentRecords(ServerPlayer player, SuggestionsBuilder builder) {
        for (PersistentMirrorRecord record : PersistentMirrorManager.getAccessibleRecords(player)) {
            suggestIfMatching(builder, record.selector());
            if (isSimpleSelectorName(record.name())) {
                suggestIfMatching(builder, record.name());
            }
        }
        return builder.buildFuture();
    }

    private static void suggestIfMatching(SuggestionsBuilder builder, String value) {
        String remaining = builder.getRemainingLowerCase();
        if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
            builder.suggest(value);
        }
    }

    private static boolean isSimpleSelectorName(String value) {
        return value != null && !value.isBlank() && value.chars().noneMatch(Character::isWhitespace);
    }

    private static int mobOnCommand(CommandContext<CommandSourceStack> context) {
        // Enable mob spawning at runtime
        MirrorConfig.setRuntimeMobSpawning(true);
        
        // Also set doMobSpawning game rule in all active mirror worlds
        MinecraftServer server = context.getSource().getServer();
        setMirrorWorldsMobSpawning(server, true);
        
        context.getSource().sendSuccess(
                () -> Component.translatable("command.instantworldmirror.mob.on"), 
                true
        );
        return 1;
    }

    private static int mobOffCommand(CommandContext<CommandSourceStack> context) {
        // Disable mob spawning at runtime
        MirrorConfig.setRuntimeMobSpawning(false);
        
        // Also set doMobSpawning game rule in all active mirror worlds
        MinecraftServer server = context.getSource().getServer();
        setMirrorWorldsMobSpawning(server, false);
        
        context.getSource().sendSuccess(
                () -> Component.translatable("command.instantworldmirror.mob.off"), 
                true
        );
        return 1;
    }
    
    /**
     * Set doMobSpawning game rule in all mirror world dimensions
     */
    private static void setMirrorWorldsMobSpawning(MinecraftServer server, boolean enabled) {
        for (int i = 0; i < ModDimensions.getPoolSize(); i++) {
            ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, i);
            if (mirrorWorld != null) {
                mirrorWorld.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(enabled, server);
            }
        }

        for (int i = 0; i < ModDimensions.MAX_PERSISTENT_MIRROR_WORLD_POOL_SIZE; i++) {
            ServerLevel persistentWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(i));
            if (persistentWorld != null) {
                persistentWorld.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(enabled, server);
            }
        }
    }

    private static int mobStatusCommand(CommandContext<CommandSourceStack> context) {
        String status = MirrorConfig.getMobSpawningStatus();
        context.getSource().sendSuccess(
                () -> Component.translatable("command.instantworldmirror.mob.status", status), 
                false
        );
        return 1;
    }

    private static int allowCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            MirrorWorldManager.setAccessPermission(target.getUUID(), true);
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.instantworldmirror.allow.success", target.getName().getString()),
                    true
            );
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_not_found"));
            return 0;
        }
    }

    private static int denyCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            MirrorWorldManager.setAccessPermission(target.getUUID(), false);
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.instantworldmirror.deny.success", target.getName().getString()),
                    true
            );
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_not_found"));
            return 0;
        }
    }

    private static int itemTransferCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
            boolean allowed = BoolArgumentType.getBool(context, "allowed");
            
            MirrorWorldManager.setItemTransferPermission(target.getUUID(), allowed);
            
            if (allowed) {
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.instantworldmirror.itemtransfer.enabled", target.getName().getString()),
                        true
                );
            } else {
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.instantworldmirror.itemtransfer.disabled", target.getName().getString()),
                        true
                );
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.translatable("command.instantworldmirror.player_not_found"));
            return 0;
        }
    }
    
    /**
     * /mirror status - View dimension pool status and player counts
     */
    private static int statusCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("command.instantworldmirror.server_unavailable"));
            return 0;
        }
        int poolSize = ModDimensions.getPoolSize();
        
        source.sendSuccess(() -> Component.translatable("command.instantworldmirror.status.header"), false);
        source.sendSuccess(() -> Component.translatable("command.instantworldmirror.status.summary",
                DimensionPool.getAvailableCount(),
                DimensionPool.getInUseCount(),
                DimensionPool.getCleaningCount()), false);
        
        // Show each dimension's status
        for (int i = 0; i < poolSize; i++) {
            final int dimIndex = i;
            DimensionPool.DimensionState state = DimensionPool.getDimensionState(i);
            String stateKey = switch (state) {
                case AVAILABLE -> "command.instantworldmirror.status.available";
                case IN_USE -> "command.instantworldmirror.status.in_use";
                case CLEANING -> "command.instantworldmirror.status.cleaning";
            };
            
            // Get player count directly from the dimension level (most reliable)
            int playerCount = 0;
            if (server != null) {
                ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(server, i);
                if (mirrorWorld != null) {
                    playerCount = mirrorWorld.players().size();
                }
            }
            
            final int finalPlayerCount = playerCount;
            final String finalStateKey = stateKey;
            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.status.entry", dimIndex, 
                    Component.translatable(finalStateKey), finalPlayerCount
            ), false);
        }

        PersistentMirrorData persistentData = PersistentMirrorData.get(server);
        int persistentSlots = ModDimensions.MAX_PERSISTENT_MIRROR_WORLD_POOL_SIZE;
        int persistentRecords = persistentData.records().size();
        long readyRecords = persistentData.records().stream().filter(PersistentMirrorRecord::ready).count();
        long copyingRecords = persistentRecords - readyRecords;
        int freePersistentSlots = Math.max(0, persistentSlots - persistentRecords);

        source.sendSuccess(() -> Component.translatable("command.instantworldmirror.status.persistent_header"), false);
        source.sendSuccess(() -> Component.translatable("command.instantworldmirror.status.persistent_summary",
                freePersistentSlots,
                readyRecords,
                copyingRecords,
                persistentRecords,
                persistentSlots), false);

        for (int i = 0; i < persistentSlots; i++) {
            final int slotNumber = i + 1;
            Optional<PersistentMirrorRecord> record = persistentData.getRecordByDimensionIndex(i);
            ServerLevel persistentWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(i));
            final int persistentPlayerCount = persistentWorld != null ? persistentWorld.players().size() : 0;

            if (record.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.instantworldmirror.status.persistent_entry_empty",
                        slotNumber,
                        persistentPlayerCount
                ), false);
                continue;
            }

            PersistentMirrorRecord persistentRecord = record.get();
            final String persistentName = persistentRecord.name();
            final String kindTranslationKey = persistentRecord.kind().translationKey();
            final String stateTranslationKey = persistentRecord.ready()
                    ? "command.instantworldmirror.status.persistent_ready"
                    : "command.instantworldmirror.status.persistent_copying";

            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.status.persistent_entry",
                    slotNumber,
                    persistentName,
                    Component.translatable(kindTranslationKey),
                    Component.translatable(stateTranslationKey),
                    persistentPlayerCount
            ), false);
        }
        
        return 1;
    }
    
    /**
     * /mirror forceclear <dimension> - Force clear a dimension and return all players to spawn
     * This will always start a cleanup process regardless of the dimension state
     */
    private static int forceClearCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        try {
            ServerLevel dimension = DimensionArgument.getDimension(context, "dimension");
            ResourceKey<Level> dimKey = dimension.dimension();
            
            // Validate that this is a mirror world dimension
            int dimIndex = ModDimensions.getMirrorWorldIndex(dimKey);
            if (dimIndex < 0) {
                source.sendFailure(Component.translatable("command.instantworldmirror.forceclear.invalid_dim", 
                        ModDimensions.getPoolSize() - 1));
                return 0;
            }
            
            // Perform force clear (works for any state - IN_USE, CLEANING, or AVAILABLE)
            if (source.getServer() != null) {
                DimensionPool.DimensionState state = DimensionPool.getDimensionState(dimIndex);
                int playersReturned = MirrorWorldManager.forceClearDimension(dimIndex, source.getServer());
                
                source.sendSuccess(() -> Component.translatable(
                        "command.instantworldmirror.forceclear.success",
                        dimIndex, playersReturned
                ), true);
                
                // Additional info about previous state
                source.sendSuccess(() -> Component.translatable(
                        "command.instantworldmirror.forceclear.state_info",
                        state.name()
                ), false);
                return 1;
            }
            
            source.sendFailure(Component.translatable("command.instantworldmirror.server_unavailable"));
            return 0;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.instantworldmirror.forceclear.invalid_dim", 
                    ModDimensions.getPoolSize() - 1));
            return 0;
        }
    }
    
    /**
     * /iwm purge - Completely delete all mirror world save files
     * This marks them for deletion and requires a server restart to take effect
     * Requires permission level 3 or above
     * 
     * Process:
     * 1. Enable purge mode to prevent new mirror sessions
     * 2. Force return all players in mirror worlds to overworld spawn
     * 3. Delete all mirror world save directories
     * 4. Notify that restart is required
     */
    private static int purgeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        
        if (server == null) {
            source.sendFailure(Component.translatable("command.instantworldmirror.server_unavailable"));
            return 0;
        }
        
        // Step 1: Enable purge mode to prevent new mirror sessions
        MirrorWorldManager.enablePurgeMode();
        source.sendSuccess(() -> Component.translatable(
                "command.instantworldmirror.purge.mode_enabled"
        ), true);
        
        // Step 2: Force return all players in mirror worlds to overworld spawn
        int totalPlayersReturned = 0;
        for (int i = 0; i < ModDimensions.getPoolSize(); i++) {
            totalPlayersReturned += MirrorWorldManager.forceClearDimension(i, server);
        }
        
        // Step 3: Remove all existing mirror portal entities from all dimensions
        int portalsRemoved = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (MirrorPortalEntity portal : level.getEntities(ModEntities.MIRROR_PORTAL.get(), entity -> true)) {
                portal.discard();
                portalsRemoved++;
            }
        }
        final int finalPortalsRemoved = portalsRemoved;
        if (portalsRemoved > 0) {
            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.purge.portals_removed",
                    finalPortalsRemoved
            ), true);
        }
        
        // Step 4: Delete all mirror world save directories
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        Path dimensionsPath = worldPath.resolve("dimensions").resolve(InstantWorldMirror.MODID);
        
        int deletedCount = 0;
        int failedCount = 0;
        
        // Delete all mirror world directories
        for (int i = 0; i < ModDimensions.MAX_MIRROR_WORLD_POOL_SIZE; i++) {
            Path mirrorWorldPath = dimensionsPath.resolve("mirror_world_" + i);
            if (Files.exists(mirrorWorldPath)) {
                try {
                    // Recursively delete the directory
                    deleteDirectoryRecursively(mirrorWorldPath);
                    deletedCount++;
                    InstantWorldMirror.LOGGER.info("Deleted mirror world directory: {}", mirrorWorldPath);
                } catch (IOException e) {
                    failedCount++;
                    InstantWorldMirror.LOGGER.error("Failed to delete mirror world directory: {}", mirrorWorldPath, e);
                }
            }
        }
        
        final int finalDeletedCount = deletedCount;
        final int finalFailedCount = failedCount;
        final int finalPlayersReturned = totalPlayersReturned;
        
        if (deletedCount > 0) {
            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.purge.success",
                    finalDeletedCount, finalPlayersReturned
            ), true);
            
            // Warn about restart requirement
            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.purge.restart_required"
            ), false);
        }
        
        if (failedCount > 0) {
            source.sendFailure(Component.translatable(
                    "command.instantworldmirror.purge.partial_failure",
                    finalFailedCount
            ));
        }
        
        if (deletedCount == 0 && failedCount == 0) {
            source.sendSuccess(() -> Component.translatable(
                    "command.instantworldmirror.purge.nothing_to_delete"
            ), false);
        }
        
        return deletedCount > 0 ? 1 : 0;
    }
    
    /**
     * Recursively delete a directory and all its contents
     */
    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
            }
        }
    }
}
