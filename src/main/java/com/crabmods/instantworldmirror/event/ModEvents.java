package com.crabmods.instantworldmirror.event;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.command.ModCommands;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
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
            if (player.level().dimension().equals(ModDimensions.MIRROR_WORLD)) {
                // Teleport to overworld spawn point
                MirrorWorldManager.forceReturn(player);
            }
        }
    }

    /**
     * Player logout event - cleanup player data
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (MirrorWorldManager.isInMirrorWorld(player)) {
                // Player logged out from mirror world, will be in overworld next login
                InstantWorldMirror.LOGGER.info("Player {} logged out from Mirror World", player.getName().getString());
                
                // Delayed cleanup of mirror world (ensure player has fully left)
                player.getServer().execute(() -> {
                    MirrorWorldManager.cleanupMirrorWorldIfEmpty(player.getServer());
                });
            }
        }
    }

    /**
     * Block place event - prevent placing portal-related blocks in mirror world
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Level level = (Level) event.getLevel();
        
        if (level.dimension().equals(ModDimensions.MIRROR_WORLD)) {
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
        
        if (level.dimension().equals(ModDimensions.MIRROR_WORLD)) {
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
        
        // Check if entity is currently in mirror world
        if (entity.level().dimension().equals(ModDimensions.MIRROR_WORLD)) {
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
     * World tick event - sync overworld weather and time to mirror world
     */
    @SubscribeEvent
    public static void onWorldTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel mirrorWorld) {
            if (mirrorWorld.dimension().equals(ModDimensions.MIRROR_WORLD)) {
                ServerLevel overworld = mirrorWorld.getServer().overworld();
                
                // Sync every 20 ticks (1 second)
                if (mirrorWorld.getGameTime() % 20 == 0) {
                    // Sync time
                    mirrorWorld.setDayTime(overworld.getDayTime());
                    
                    // Sync weather - using public methods
                    if (overworld.isRaining() != mirrorWorld.isRaining()) {
                        if (overworld.isRaining()) {
                            mirrorWorld.setWeatherParameters(0, 6000, true, overworld.isThundering());
                        } else {
                            mirrorWorld.setWeatherParameters(6000, 0, false, false);
                        }
                    }
                    if (overworld.isThundering() != mirrorWorld.isThundering()) {
                        mirrorWorld.setWeatherParameters(0, 6000, mirrorWorld.isRaining(), overworld.isThundering());
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
                    if (player.level().dimension().equals(ModDimensions.MIRROR_WORLD)) {
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
            } else if (player.level().dimension().equals(ModDimensions.MIRROR_WORLD)) {
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
}
