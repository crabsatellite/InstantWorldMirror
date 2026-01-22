package com.crabmods.instantworldmirror.command;

import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("iwm")
                        // /iwm return - Force return to overworld
                        .then(Commands.literal("return")
                                .executes(ModCommands::returnCommand)
                        )
                        // /iwm mob on/off - Control mob spawning
                        .then(Commands.literal("mob")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("on")
                                        .executes(ModCommands::mobOnCommand)
                                )
                                .then(Commands.literal("off")
                                        .executes(ModCommands::mobOffCommand)
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
                        // /iwm forceclear <dimension> - Force clear a dimension (with tab completion)
                        .then(Commands.literal("forceclear")
                                .requires(source -> source.hasPermission(3))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .suggests(MIRROR_DIMENSION_SUGGESTIONS)
                                        .executes(ModCommands::forceClearCommand)
                                )
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

    private static int mobOnCommand(CommandContext<CommandSourceStack> context) {
        // Note: Runtime config modification requires special handling
        // This only sends a message, actual config change needs config system
        context.getSource().sendSuccess(
                () -> Component.translatable("command.instantworldmirror.mob.on"), 
                true
        );
        return 1;
    }

    private static int mobOffCommand(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
                () -> Component.translatable("command.instantworldmirror.mob.off"), 
                true
        );
        return 1;
    }

    private static int allowCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "player");
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
            if (source.getServer() != null) {
                ServerLevel mirrorWorld = DimensionPool.getDimensionLevel(source.getServer(), i);
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
}
