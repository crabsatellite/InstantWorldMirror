package com.crabmods.instantworldmirror.event;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.command.ModCommands;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.WorldCopyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Game Event Handler Class
 */
@EventBusSubscriber(modid = InstantWorldMirror.MODID)
public class ModEvents {

    /**
     * Register commands
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
        InstantWorldMirror.LOGGER.info("Mirror commands registered");
    }

    /**
     * Player death event - return to overworld when dying in mirror world
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (MirrorWorldManager.isInMirrorWorld(player)) {
                // Died in mirror world, items stay in mirror world
                // Player will respawn in overworld (handled by respawn event)
                InstantWorldMirror.LOGGER.info("Player {} died in Mirror World", player.getName().getString());
            }
        }
    }

    /**
     * Player respawn event - ensure players who died in mirror world respawn in overworld
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // If player respawns in mirror world, force teleport to overworld
            if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                // Teleport to overworld spawn point
                MirrorWorldManager.forceReturn(player);
            }
        }
    }

    /**
     * Player dimension change event - detect when player leaves mirror world via commands/other means
     * This catches /tp, /execute, other mod teleporters, etc.
     */
    @SubscribeEvent
    public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Check if player LEFT a mirror world (not entered one)
            if (ModDimensions.isMirrorWorld(event.getFrom()) && !ModDimensions.isMirrorWorld(event.getTo())) {
                // Player left mirror world through non-portal means (command, other mod, etc.)
                InstantWorldMirror.LOGGER.info("Player {} left Mirror World via external means (from {} to {})", 
                        player.getName().getString(), 
                        event.getFrom().location(), 
                        event.getTo().location());
                
                // Clean up their session data
                MirrorWorldManager.handleExternalExit(player, player.getServer());
            }
        }
    }

    /**
     * Player logout event - cleanup player data and session
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Handle disconnect - remove from session and cleanup if needed
            MirrorWorldManager.handlePlayerDisconnect(player, player.getServer());
            
            if (MirrorWorldManager.isInMirrorWorld(player)) {
                InstantWorldMirror.LOGGER.info("Player {} logged out from Mirror World", 
                        player.getName().getString());
            }
        }
    }

    /**
     * Block place event - prevent placing portal-related blocks in mirror world
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Level level = (Level) event.getLevel();
        
        if (ModDimensions.isMirrorWorld(level.dimension())) {
            BlockState state = event.getPlacedBlock();
            
            // Prevent placing end portal frames
            if (state.is(Blocks.END_PORTAL_FRAME)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Nether portal spawn event - prevent nether portal activation in mirror world
     */
    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        Level level = (Level) event.getLevel();
        
        if (ModDimensions.isMirrorWorld(level.dimension())) {
            // Prevent nether portal spawn in mirror world
            event.setCanceled(true);
            InstantWorldMirror.LOGGER.info("Blocked nether portal spawn in Mirror World at {}", event.getPos());
        }
    }

    /**
     * Dimension travel event - prevent using any portal for dimension travel in mirror world
     * This intercepts all portal types (nether portal, end portal, mod portals, etc.)
     */
    @SubscribeEvent
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        
        // Check if entity is currently in any mirror world
        if (ModDimensions.isMirrorWorld(entity.level().dimension())) {
            // Only allow teleportation to overworld (via our mirror portal return)
            // Block all other dimension travel (nether, end, other mod dimensions, etc.)
            if (!event.getDimension().equals(Level.OVERWORLD)) {
                event.setCanceled(true);
                
                // If it's a player, send message
                if (entity instanceof ServerPlayer player) {
                    player.displayClientMessage(
                            Component.translatable("message.instantworldmirror.portal_blocked"),
                            true
                    );
                }
                
                InstantWorldMirror.LOGGER.info("Blocked dimension travel from Mirror World to {} for {}", 
                        event.getDimension().location(), entity.getName().getString());
            }
        }
    }

    /**
     * World tick event - process async copy and cleanup queues
     * Process once per tick, only on overworld (to avoid duplicate processing)
     */
    @SubscribeEvent
    public static void onWorldTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Only process on overworld tick to avoid duplicate processing
            if (serverLevel.dimension().equals(Level.OVERWORLD)) {
                // Process copy queues every tick (async copy system)
                WorldCopyService.processCopyQueues(serverLevel.getServer());
                
                // Process cleanup queues every tick (async cleanup system)
                WorldCopyService.processCleanupQueues(serverLevel.getServer());
            }
            
            // Sync time and weather for all mirror dimensions
            if (ModDimensions.isMirrorWorld(serverLevel.dimension())) {
                // Sync every 20 ticks (1 second) - check tick count first to avoid unnecessary dimension lookup
                if (serverLevel.getGameTime() % 20 == 0) {
                    ServerLevel overworld = serverLevel.getServer().overworld();
                    
                    // Sync time
                    serverLevel.setDayTime(overworld.getDayTime());
                    
                    // Sync weather - cache values to avoid repeated method calls
                    boolean overworldRaining = overworld.isRaining();
                    boolean mirrorRaining = serverLevel.isRaining();
                    boolean overworldThundering = overworld.isThundering();
                    boolean mirrorThundering = serverLevel.isThundering();
                    
                    // Only update if different
                    if (overworldRaining != mirrorRaining || overworldThundering != mirrorThundering) {
                        serverLevel.setWeatherParameters(
                            overworldRaining ? 0 : 6000,
                            overworldRaining ? 6000 : 0,
                            overworldRaining,
                            overworldThundering
                        );
                    }
                }
            }
        }
    }

    /**
     * Player login event - if player logs in to mirror world, teleport back to overworld and restore inventory
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Check if player has saved inventory data (might have been in mirror world before server restart)
            if (MirrorWorldManager.hasSavedInventory(player)) {
                // Player has saved data, restore inventory
                player.getServer().execute(() -> {
                    // If in mirror world, teleport back to overworld first
                    if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                        ServerLevel overworld = player.getServer().overworld();
                        BlockPos spawnPos = overworld.getSharedSpawnPos();
                        player.teleportTo(
                                overworld,
                                spawnPos.getX() + 0.5,
                                spawnPos.getY(),
                                spawnPos.getZ() + 0.5,
                                player.getYRot(),
                                player.getXRot()
                        );
                    }
                    // Restore inventory (forceReturn will automatically restore)
                    MirrorWorldManager.forceReturn(player);
                    InstantWorldMirror.LOGGER.info("Player {} had saved inventory data, restored on login", 
                            player.getName().getString());
                });
            } else if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                // Player in mirror world but no saved data (abnormal situation), force teleport to overworld
                player.getServer().execute(() -> {
                    ServerLevel overworld = player.getServer().overworld();
                    BlockPos spawnPos = overworld.getSharedSpawnPos();
                    player.teleportTo(
                            overworld,
                            spawnPos.getX() + 0.5,
                            spawnPos.getY(),
                            spawnPos.getZ() + 0.5,
                            player.getYRot(),
                            player.getXRot()
                    );
                    InstantWorldMirror.LOGGER.info("Player {} was in Mirror World on login (no saved data), teleported to Overworld", 
                            player.getName().getString());
                });
            }
        }
    }

    /**
     * Server stopping event - cleanup all sessions
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MirrorWorldManager.clearAllSessions();
        WorldCopyService.clearAllTasks();
        InstantWorldMirror.LOGGER.info("Server stopping, mirror sessions and tasks cleared.");
    }
}
