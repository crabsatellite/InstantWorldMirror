package com.crabmods.instantworldmirror.event;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.command.ModCommands;
import com.crabmods.instantworldmirror.world.DimensionPool;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.WorldCopyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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
     * Also track chunk modifications for cleanup
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
            
            // Track this chunk as modified (for cleanup)
            int dimIndex = ModDimensions.getMirrorWorldIndex(level.dimension());
            if (dimIndex >= 0) {
                BlockPos pos = event.getPos();
                WorldCopyService.trackModifiedChunk(dimIndex, pos.getX() >> 4, pos.getZ() >> 4);
            }
        }
    }
    
    /**
     * Block break event - track chunk modifications for cleanup
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Level level = (Level) event.getLevel();
        
        if (ModDimensions.isMirrorWorld(level.dimension())) {
            // Track this chunk as modified (for cleanup)
            int dimIndex = ModDimensions.getMirrorWorldIndex(level.dimension());
            if (dimIndex >= 0) {
                BlockPos pos = event.getPos();
                WorldCopyService.trackModifiedChunk(dimIndex, pos.getX() >> 4, pos.getZ() >> 4);
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
     * BUT allows returning to the original dimension the player came from
     */
    @SubscribeEvent
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        
        // Check if entity is currently in any mirror world
        if (ModDimensions.isMirrorWorld(entity.level().dimension())) {
            ResourceKey<Level> targetDimension = event.getDimension();
            
            // Check if this is a player returning to their original dimension
            if (entity instanceof ServerPlayer player) {
                ResourceKey<Level> originalDimension = MirrorWorldManager.getPlayerOriginalDimension(player);
                
                // Allow teleportation to the player's original dimension (where they came from)
                // This supports returning to End, Nether, or any other modded dimension
                if (originalDimension != null && targetDimension.equals(originalDimension)) {
                    // Allow this teleport
                    return;
                }
            }
            
            // Block all other dimension travel (nether portal, end portal, etc.)
            // Only our mirror portal return system should be used
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
            
            // For mirror dimensions: sync time/weather and track player chunks
            if (ModDimensions.isMirrorWorld(serverLevel.dimension())) {
                int dimIndex = ModDimensions.getMirrorWorldIndex(serverLevel.dimension());
                
                // Track all player positions every 10 ticks (for cleanup purposes)
                // This ensures chunks that players explore are marked for cleanup
                if (serverLevel.getGameTime() % 10 == 0 && dimIndex >= 0) {
                    for (ServerPlayer player : serverLevel.players()) {
                        int chunkX = player.getBlockX() >> 4;
                        int chunkZ = player.getBlockZ() >> 4;
                        // Track the chunk the player is in plus a small radius for view distance
                        WorldCopyService.trackChunksInRadius(dimIndex, chunkX, chunkZ, 2);
                    }
                }
                
                // Sync time and weather every 20 ticks (1 second)
                // Use the SOURCE dimension for this mirror world, not always overworld
                if (serverLevel.getGameTime() % 20 == 0 && dimIndex >= 0) {
                    ResourceKey<Level> sourceDimKey = DimensionPool.getSourceDimension(dimIndex);
                    ServerLevel sourceLevel = serverLevel.getServer().getLevel(sourceDimKey);
                    
                    // Fallback to overworld if source level is unavailable
                    if (sourceLevel == null) {
                        sourceLevel = serverLevel.getServer().overworld();
                    }
                    
                    // Sync time - Note: The End has fixed time (18000), Nether has no daylight cycle
                    // We sync time regardless, let the dimension type handle rendering
                    serverLevel.setDayTime(sourceLevel.getDayTime());
                    
                    // Sync weather - Note: The End and Nether don't have weather
                    // but we sync anyway for consistency
                    boolean sourceRaining = sourceLevel.isRaining();
                    boolean mirrorRaining = serverLevel.isRaining();
                    boolean sourceThundering = sourceLevel.isThundering();
                    boolean mirrorThundering = serverLevel.isThundering();
                    
                    // Only update if different
                    if (sourceRaining != mirrorRaining || sourceThundering != mirrorThundering) {
                        serverLevel.setWeatherParameters(
                            sourceRaining ? 0 : 6000,
                            sourceRaining ? 6000 : 0,
                            sourceRaining,
                            sourceThundering
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
