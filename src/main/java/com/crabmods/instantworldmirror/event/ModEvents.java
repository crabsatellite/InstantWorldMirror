package com.crabmods.instantworldmirror.event;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.command.ModCommands;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
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
     * Player death event - cleanup session when dying with an active mirror world session.
     * Covers two scenarios:
     * 1. Player dies while in a mirror world dimension
     * 2. Player dies in a non-mirror dimension (End, Nether) but still has an active
     *    mirror session (e.g., entered End via /setblock end_portal from mirror world)
     *
     * IMPORTANT: Does NOT restore inventory here. Inventory restoration is deferred to
     * the respawn event to avoid corrupting vanilla death processing.
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            boolean inMirrorWorld = MirrorWorldManager.isInMirrorWorld(player);
            boolean hasActiveSession = MirrorWorldManager.getPlayerCurrentSession(player.getUUID()).isPresent();

            if (inMirrorWorld || hasActiveSession) {
                if (inMirrorWorld) {
                    InstantWorldMirror.LOGGER.info(
                            "Player {} died in Mirror World, cleaning up session",
                            player.getName().getString());
                } else {
                    InstantWorldMirror.LOGGER.info(
                            "Player {} died in {} with active mirror session, cleaning up",
                            player.getName().getString(),
                            player.level().dimension().location());
                }

                MirrorWorldManager.handleMirrorWorldDeath(player, player.getServer());
            }
        }
    }

    /**
     * Player respawn event - handle inventory restoration for mirror world deaths.
     * Inventory restore is deferred from the death event to here to avoid crashes.
     * Also ensures players who respawn inside a mirror world are teleported to the overworld.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Check if this player died in a mirror world and needs inventory restore
            if (MirrorWorldManager.diedInMirrorWorld(player)) {
                // Safe to restore inventory now - the player entity has been fully recreated
                MirrorWorldManager.handleMirrorDeathRespawn(player);
                InstantWorldMirror.LOGGER.info(
                        "Restored inventory for {} on respawn after mirror world death",
                        player.getName().getString());
            }

            // If player somehow respawns in mirror world, force teleport to overworld
            if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                ServerLevel overworld = player.getServer().overworld();
                BlockPos spawnPos = overworld.getSharedSpawnPos();

                MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
                try {
                    player.teleportTo(
                            overworld,
                            spawnPos.getX() + 0.5,
                            spawnPos.getY(),
                            spawnPos.getZ() + 0.5,
                            player.getYRot(),
                            player.getXRot()
                    );
                } finally {
                    MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
                }
                InstantWorldMirror.LOGGER.info("Force teleported {} to overworld after respawn in mirror world",
                        player.getName().getString());
            }

            // Safety net: clear any leftover stale mirror data after respawn
            if (MirrorWorldManager.hasSavedInventory(player)) {
                InstantWorldMirror.LOGGER.warn(
                        "Player {} respawned with stale mirror inventory data, clearing",
                        player.getName().getString());
                MirrorWorldManager.clearSavedData(player);
            }
        }
    }

    /**
     * Player dimension change event - detect when player leaves mirror world via commands/other means.
     * This catches /tp, /execute, other mod teleporters, etc.
     * Skips processing if the death handler already cleaned up session data.
     */
    @SubscribeEvent
    public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Skip if the death handler already processed this player.
            // The respawn after death causes a dimension change, but session cleanup
            // was already handled by the death event. Processing it again would
            // double-restore inventory or operate on stale state.
            if (MirrorWorldManager.diedInMirrorWorld(player)) {
                return;
            }

            // Check if player LEFT a mirror world (not entered one)
            if (ModDimensions.isMirrorWorld(event.getFrom()) && !ModDimensions.isMirrorWorld(event.getTo())) {
                // Only process if the player still has an active session
                if (MirrorWorldManager.getPlayerCurrentSession(player.getUUID()).isPresent()) {
                    InstantWorldMirror.LOGGER.info("Player {} left Mirror World via external means (from {} to {})",
                            player.getName().getString(),
                            event.getFrom().location(),
                            event.getTo().location());

                    MirrorWorldManager.handleExternalExit(player, player.getServer());
                }
            }
        }
    }

    /**
     * Player logout event - cleanup player data and session, save cooldown
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Save cooldown before logout
            DimensionMirrorItem.saveCooldown(player);
            
            // Handle disconnect - remove from session and cleanup if needed
            MirrorWorldManager.handlePlayerDisconnect(player, player.getServer());
            
            // Clean up lazy tracking cache for this player
            lastTrackedChunkPos.remove(player.getUUID());
            
            if (MirrorWorldManager.isInMirrorWorld(player)) {
                InstantWorldMirror.LOGGER.info("Player {} logged out from Mirror World", 
                        player.getName().getString());
            }
        }
    }

    /**
     * Block place event - prevent placing portal-related blocks in mirror world
     * Only restricts survival and adventure players
     * Also track chunk modifications for cleanup
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Level level = (Level) event.getLevel();
        
        if (ModDimensions.isMirrorWorld(level.dimension())) {
            BlockState state = event.getPlacedBlock();
            
            // Prevent placing end portal frames (only for survival/adventure players)
            if (state.is(Blocks.END_PORTAL_FRAME)) {
                if (event.getEntity() instanceof ServerPlayer player) {
                    GameType gameType = player.gameMode.getGameModeForPlayer();
                    if (gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE) {
                        event.setCanceled(true);
                        return;
                    }
                    // Creative/Spectator players can place end portal frames
                } else {
                    // Non-player entity - block it to be safe
                    event.setCanceled(true);
                    return;
                }
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
     * Mob spawn event - prevent natural mob spawning in mirror world if configured
     * This catches all natural spawns (passive, hostile, ambient, water creatures, etc.)
     * BUT allows player-triggered spawns (spawn eggs, built structures like wither/iron golem, etc.)
     */
    @SubscribeEvent
    public static void onMobSpawn(FinalizeSpawnEvent event) {
        Level level = event.getLevel().getLevel();
        
        if (ModDimensions.isMirrorWorld(level.dimension())) {
            // Check if mob spawning is enabled (uses runtime override or config)
            if (!MirrorConfig.isMobSpawningEnabled()) {
                MobSpawnType spawnType = event.getSpawnType();
                
                // Allow player-triggered spawns:
                // - SPAWN_EGG: Player used a spawn egg
                // - TRIGGERED: Built structures (wither, iron golem, snow golem, etc.)
                // - COMMAND: Summoned via /summon command
                // - BUCKET: Player placed a fish bucket
                // - CONVERSION: Mob converted (zombie villager cured, etc.)
                // - BREEDING: Player bred animals
                // - DISPENSER: Dispenser spawned mob
                // - EVENT: Special event spawns (usually player-related)
                if (spawnType == MobSpawnType.SPAWN_EGG ||
                    spawnType == MobSpawnType.TRIGGERED ||
                    spawnType == MobSpawnType.COMMAND ||
                    spawnType == MobSpawnType.BUCKET ||
                    spawnType == MobSpawnType.CONVERSION ||
                    spawnType == MobSpawnType.BREEDING ||
                    spawnType == MobSpawnType.DISPENSER ||
                    spawnType == MobSpawnType.EVENT) {
                    // Allow these spawns
                    return;
                }
                
                // Block natural spawns:
                // - NATURAL: Regular mob spawning (hostile at night, passive in the wild)
                // - CHUNK_GENERATION: Mobs spawned during chunk generation
                // - SPAWNER: Mob spawner blocks (can be considered non-player if desired)
                // - STRUCTURE: Structure spawns (like village golems, mansion vindicators)
                // - PATROL: Illager patrols
                // - REINFORCEMENT: Zombie reinforcements
                // - JOCKEY: Spider jockey spawns
                event.setSpawnCancelled(true);
            }
        }
    }

    /**
     * Dimension travel event - prevent using any portal for dimension travel in mirror world.
     * This intercepts all portal types (nether portal, end portal, mod portals, etc.)
     * Blocks ALL players regardless of game mode. The only way out is through the mod's return portal.
     * Only allows teleportation initiated by our mod (marked with whitelist).
     */
    @SubscribeEvent
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();

        // Check if entity is currently in any mirror world
        if (ModDimensions.isMirrorWorld(entity.level().dimension())) {
            // Check if this is a player being teleported by our mod
            if (entity instanceof ServerPlayer player) {
                if (MirrorWorldManager.isBeingTeleportedByMod(player.getUUID())) {
                    // Allow this teleport - it's our own return teleportation
                    return;
                }
            }

            // Block ALL dimension travel from mirror world, regardless of game mode.
            // The only way out should be through the mod's return portal system.
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

    // Tick counter for periodic tasks (avoids expensive gameTime checks)
    private static int tickCounter = 0;
    
    // Lazy tracking: cache player chunk positions to avoid redundant tracking
    // playerUUID -> last tracked chunk position (packed as long)
    private static final java.util.Map<java.util.UUID, Long> lastTrackedChunkPos = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CHUNK_TRACK_DISTANCE_THRESHOLD = 2; // Only retrack if moved 2+ chunks
    
    /**
     * World tick event - process async copy and cleanup queues
     * Optimized: Uses tick counter instead of gameTime modulo for better performance
     */
    @SubscribeEvent
    public static void onWorldTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return; // Early exit for client levels
        }
        
        // Only process on overworld tick to avoid duplicate processing
        if (serverLevel.dimension().equals(Level.OVERWORLD)) {
            tickCounter++;
            
            // Process copy/cleanup queues every tick ONLY if there are active tasks
            // The methods have early exit checks internally
            WorldCopyService.processCopyQueues(serverLevel.getServer());
            WorldCopyService.processCleanupQueues(serverLevel.getServer());
            
            // Fallback: Check for stale sessions at configurable interval
            int staleCleanupTicks = MirrorConfig.getStaleSessionCleanupTicks();
            if (staleCleanupTicks > 0 && tickCounter % staleCleanupTicks == 0) {
                MirrorWorldManager.cleanupStaleSessions(serverLevel.getServer());
            }
            
            // Reset counter periodically to avoid overflow (every ~3.6 hours at 20 tps)
            if (tickCounter >= 262144) {
                tickCounter = 0;
            }
            return; // Exit early for overworld - no mirror world processing needed
        }
        
        // For mirror dimensions: sync time/weather and track player chunks
        if (!ModDimensions.isMirrorWorld(serverLevel.dimension())) {
            return; // Not a mirror world, nothing to do
        }
        
        int dimIndex = ModDimensions.getMirrorWorldIndex(serverLevel.dimension());
        if (dimIndex < 0) {
            return; // Invalid dimension index
        }
        
        long gameTime = serverLevel.getGameTime();
        
        // OPTIMIZED: Lazy track player positions - only when moved significantly
        // Check every 10 ticks but only actually track if player moved 2+ chunks
        if (gameTime % 10 == 0 && !serverLevel.players().isEmpty()) {
            int viewDistance = serverLevel.getServer().getPlayerList().getViewDistance();
            for (ServerPlayer player : serverLevel.players()) {
                int chunkX = player.getBlockX() >> 4;
                int chunkZ = player.getBlockZ() >> 4;
                long currentChunkPacked = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                
                // Check if we need to retrack (first time or moved enough)
                Long lastPos = lastTrackedChunkPos.get(player.getUUID());
                if (lastPos == null) {
                    // First time tracking this player
                    WorldCopyService.trackChunksInRadius(dimIndex, chunkX, chunkZ, viewDistance);
                    lastTrackedChunkPos.put(player.getUUID(), currentChunkPacked);
                } else {
                    // Check distance moved
                    int lastChunkX = (int) (lastPos >> 32);
                    int lastChunkZ = lastPos.intValue();
                    int distX = Math.abs(chunkX - lastChunkX);
                    int distZ = Math.abs(chunkZ - lastChunkZ);
                    
                    // Only retrack if moved beyond threshold
                    if (distX >= CHUNK_TRACK_DISTANCE_THRESHOLD || distZ >= CHUNK_TRACK_DISTANCE_THRESHOLD) {
                        WorldCopyService.trackChunksInRadius(dimIndex, chunkX, chunkZ, viewDistance);
                        lastTrackedChunkPos.put(player.getUUID(), currentChunkPacked);
                    }
                }
            }
        }
        
        // Sync time and weather every 20 ticks (1 second)
        if (gameTime % 20 == 0) {
            ResourceKey<Level> sourceDimKey = DimensionPool.getSourceDimension(dimIndex);
            ServerLevel sourceLevel = serverLevel.getServer().getLevel(sourceDimKey);
            
            // Fallback to overworld if source level is unavailable
            if (sourceLevel == null) {
                sourceLevel = serverLevel.getServer().overworld();
            }
            
            // Sync time - includes NeoForge extended time properties
            syncLevelTime(sourceLevel, serverLevel);
            
            // Sync weather - only update if different
            boolean sourceRaining = sourceLevel.isRaining();
            boolean sourceThundering = sourceLevel.isThundering();
            
            if (sourceRaining != serverLevel.isRaining() || sourceThundering != serverLevel.isThundering()) {
                serverLevel.setWeatherParameters(
                    sourceRaining ? 0 : 6000,
                    sourceRaining ? 6000 : 0,
                    sourceRaining,
                    sourceThundering
                );
            }
        }
    }
    
    /**
     * Synchronize time from source level to mirror level
     */
    private static void syncLevelTime(ServerLevel source, ServerLevel mirror) {
        // Sync day time (visual time cycle)
        mirror.setDayTime(source.getDayTime());
        
        // Sync game time (total ticks elapsed) - use serverLevelData for write access
        try {
            net.minecraft.world.level.storage.ServerLevelData mirrorData = 
                    (net.minecraft.world.level.storage.ServerLevelData) mirror.getLevelData();
            mirrorData.setGameTime(source.getGameTime());
        } catch (Exception e) {
            // Ignore if cast fails or method unavailable
        }
    }

    /**
     * Player login event - if player logs in to mirror world, teleport back to overworld and restore inventory.
     * Also restores any saved item cooldowns and clears stale death flags.
     *
     * BUG FIX: Previously called forceReturn() after teleporting to overworld, but forceReturn()
     * delegates to returnToOverworld() which checks isInMirrorWorld() - after teleportation the
     * player is already in the overworld, so the check fails and inventory is never restored.
     * Now directly calls restorePlayerInventory() / clearSavedData() instead.
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Restore any saved cooldown first
            DimensionMirrorItem.restoreCooldown(player);

            // Safety net: clear stale death flag that might persist from a server crash
            // between death and respawn. Without this, the flag would remain until
            // the next death/respawn cycle.
            if (MirrorWorldManager.diedInMirrorWorld(player)) {
                InstantWorldMirror.LOGGER.info(
                        "Clearing stale mirror death flag for {} on login",
                        player.getName().getString());
                // If player also has saved inventory, it will be handled below.
                // If not, just clear the orphaned flag.
                if (!MirrorWorldManager.hasSavedInventory(player)) {
                    MirrorWorldManager.clearSavedData(player);
                }
            }

            // Check if player has saved inventory data (might have been in mirror world before server restart)
            if (MirrorWorldManager.hasSavedInventory(player)) {
                player.getServer().execute(() -> {
                    // If in mirror world, teleport back to overworld first
                    if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                        ServerLevel overworld = player.getServer().overworld();
                        BlockPos spawnPos = overworld.getSharedSpawnPos();

                        MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
                        try {
                            player.teleportTo(
                                    overworld,
                                    spawnPos.getX() + 0.5,
                                    spawnPos.getY(),
                                    spawnPos.getZ() + 0.5,
                                    player.getYRot(),
                                    player.getXRot()
                            );
                        } finally {
                            MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
                        }
                    }
                    // Directly restore inventory instead of calling forceReturn().
                    // forceReturn() -> returnToOverworld() -> isInMirrorWorld() would fail
                    // because the player has already been teleported to the overworld above.
                    MirrorWorldManager.restorePlayerInventoryOnLogin(player);
                    InstantWorldMirror.LOGGER.info("Player {} had saved inventory data, restored on login",
                            player.getName().getString());
                });
            } else if (ModDimensions.isMirrorWorld(player.level().dimension())) {
                // Player in mirror world but no saved data (abnormal situation), force teleport to overworld
                player.getServer().execute(() -> {
                    ServerLevel overworld = player.getServer().overworld();
                    BlockPos spawnPos = overworld.getSharedSpawnPos();

                    MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
                    try {
                        player.teleportTo(
                                overworld,
                                spawnPos.getX() + 0.5,
                                spawnPos.getY(),
                                spawnPos.getZ() + 0.5,
                                player.getYRot(),
                                player.getXRot()
                        );
                    } finally {
                        MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
                    }
                    InstantWorldMirror.LOGGER.info("Player {} was in Mirror World on login (no saved data), teleported to Overworld",
                            player.getName().getString());
                });
            }
        }
    }

    /**
     * Server stopping event - cleanup all sessions and save player cooldowns
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Save cooldowns for all online players
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            DimensionMirrorItem.saveCooldown(player);
        }
        
        // Clear the in-memory cooldown map
        DimensionMirrorItem.clearAllCooldowns();
        
        MirrorWorldManager.clearAllSessions();
        WorldCopyService.clearAllTasks();
        InstantWorldMirror.LOGGER.info("Server stopping, mirror sessions and tasks cleared.");
    }
}
