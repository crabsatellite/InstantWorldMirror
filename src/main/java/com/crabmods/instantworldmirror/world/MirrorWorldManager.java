package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirror World Teleportation Manager
 * Handles player entry and exit from the mirror world
 */
public class MirrorWorldManager {

    // Persistent data keys
    private static final String SAVED_INVENTORY_KEY = InstantWorldMirror.MODID + "_saved_inventory";
    private static final String ORIGINAL_POS_KEY = InstantWorldMirror.MODID + "_original_pos";
    private static final String ORIGINAL_DIM_KEY = InstantWorldMirror.MODID + "_original_dim";
    private static final String ITEM_TRANSFER_KEY = InstantWorldMirror.MODID + "_item_transfer";

    // Player's original position in overworld (memory cache, also persisted)
    private static final Map<UUID, BlockPos> playerOriginalPositions = new HashMap<>();
    
    // Player's original dimension (memory cache, also persisted)
    private static final Map<UUID, Level> playerOriginalDimensions = new HashMap<>();

    // Player item transfer permission (allows keeping items from mirror world)
    private static final Map<UUID, Boolean> playerItemTransferPermission = new HashMap<>();

    // Pre-copied world data (for portal entity)
    private static final Map<UUID, WorldCopyData> pendingWorldCopies = new HashMap<>();

    // Players denied access to mirror world
    private static final Map<UUID, Boolean> playerAccessDenied = new HashMap<>();

    /**
     * Check if player is allowed to enter mirror world
     */
    public static boolean canAccessMirrorWorld(ServerPlayer player) {
        return !playerAccessDenied.getOrDefault(player.getUUID(), false);
    }

    /**
     * Set player access permission for mirror world
     */
    public static void setAccessPermission(UUID playerId, boolean allowed) {
        if (allowed) {
            playerAccessDenied.remove(playerId);
        } else {
            playerAccessDenied.put(playerId, true);
        }
    }

    /**
     * Pre-copy world data (for portal entity use)
     */
    public static void prepareWorldCopy(ServerPlayer player, BlockPos clickedPos) {
        if (player.level().isClientSide) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        ServerLevel mirrorWorld = server.getLevel(ModDimensions.MIRROR_WORLD);
        if (mirrorWorld == null) {
            InstantWorldMirror.LOGGER.error("Mirror world dimension not found!");
            return;
        }

        // Get source world reference (current player's dimension)
        ServerLevel sourceWorld = (ServerLevel) player.level();

        // Copy world blocks to mirror world
        WorldCopyService.copyAreaAroundPosition(sourceWorld, mirrorWorld, clickedPos);

        // Save copy data
        pendingWorldCopies.put(player.getUUID(), new WorldCopyData(clickedPos, player.level()));

        InstantWorldMirror.LOGGER.info("World copy prepared for player {}", 
                player.getName().getString());
    }

    /**
     * Teleport player to mirror world (via portal entity)
     */
    public static boolean teleportToMirrorWorld(ServerPlayer player, BlockPos clickedPos) {
        if (player.level().isClientSide) {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Save player's current position
        playerOriginalPositions.put(player.getUUID(), player.blockPosition());
        playerOriginalDimensions.put(player.getUUID(), player.level());

        // Save player inventory (including mod items)
        savePlayerInventory(player);

        ServerLevel mirrorWorld = server.getLevel(ModDimensions.MIRROR_WORLD);
        if (mirrorWorld == null) {
            InstantWorldMirror.LOGGER.error("Mirror world dimension not found!");
            return false;
        }

        // Check for pre-copied data
        WorldCopyData copyData = pendingWorldCopies.remove(player.getUUID());
        BlockPos targetSourcePos = clickedPos;

        if (copyData != null) {
            targetSourcePos = copyData.sourcePos;
        } else {
            // If no pre-copied data, copy immediately
            ServerLevel sourceWorld = (ServerLevel) player.level();
            WorldCopyService.copyAreaAroundPosition(sourceWorld, mirrorWorld, clickedPos);
        }

        // Calculate target position (same coordinates, one block above)
        BlockPos targetPos = targetSourcePos.above();
        
        // Execute teleportation
        player.teleportTo(
                mirrorWorld,
                targetPos.getX() + 0.5,
                targetPos.getY(),
                targetPos.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
        );

        InstantWorldMirror.LOGGER.info("Player {} teleported to Mirror World at {}", 
                player.getName().getString(), targetPos);

        return true;
    }

    /**
     * Teleport player from mirror world back to overworld
     */
    public static boolean returnToOverworld(ServerPlayer player) {
        if (player.level().isClientSide) {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        // Check if player is in mirror world
        if (!isInMirrorWorld(player)) {
            return false;
        }

        // Get original position
        BlockPos originalPos = playerOriginalPositions.get(player.getUUID());
        Level originalDimension = playerOriginalDimensions.get(player.getUUID());

        ServerLevel targetLevel;
        BlockPos targetPos;

        if (originalPos != null && originalDimension != null) {
            targetLevel = server.getLevel(originalDimension.dimension());
            targetPos = originalPos;
        } else {
            // If no original position recorded, teleport to overworld spawn
            targetLevel = server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
        }

        if (targetLevel == null) {
            targetLevel = server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
        }

        // Check if item transfer is allowed (override restore rule, keep mirror world items)
        boolean allowItemTransfer = playerItemTransferPermission.getOrDefault(player.getUUID(), false);

        if (allowItemTransfer) {
            // Item transfer allowed: keep current inventory (mirror world items), don't restore original
            // Clear saved inventory data
            clearSavedData(player);
            InstantWorldMirror.LOGGER.info("Player {} allowed to keep mirror world items", player.getName().getString());
        } else {
            // Default behavior: restore pre-entry inventory
            restorePlayerInventory(player);
        }

        // Execute teleportation
        player.teleportTo(
                targetLevel,
                targetPos.getX() + 0.5,
                targetPos.getY(),
                targetPos.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
        );

        // Cleanup records
        playerOriginalPositions.remove(player.getUUID());
        playerOriginalDimensions.remove(player.getUUID());

        player.displayClientMessage(
                Component.translatable("message.instantworldmirror.returned"),
                true
        );

        InstantWorldMirror.LOGGER.info("Player {} returned to Overworld", player.getName().getString());

        return true;
    }

    /**
     * Check if player is in mirror world
     */
    public static boolean isInMirrorWorld(ServerPlayer player) {
        return player.level().dimension().equals(ModDimensions.MIRROR_WORLD);
    }

    /**
     * Set player item transfer permission
     */
    public static void setItemTransferPermission(UUID playerId, boolean allowed) {
        playerItemTransferPermission.put(playerId, allowed);
    }

    /**
     * Get player item transfer permission
     */
    public static boolean getItemTransferPermission(UUID playerId) {
        return playerItemTransferPermission.getOrDefault(playerId, MirrorConfig.ALLOW_ITEM_TRANSFER.get());
    }

    /**
     * Force return to overworld (for commands)
     */
    public static boolean forceReturn(ServerPlayer player) {
        return returnToOverworld(player);
    }

    /**
     * Cleanup mirror world (when no players are inside)
     */
    public static void cleanupMirrorWorldIfEmpty(MinecraftServer server) {
        ServerLevel mirrorWorld = server.getLevel(ModDimensions.MIRROR_WORLD);
        if (mirrorWorld == null) {
            return;
        }

        // Check if there are players in mirror world
        if (mirrorWorld.players().isEmpty()) {
            // Cleanup world data
            WorldCopyService.cleanupMirrorWorld(mirrorWorld);
            InstantWorldMirror.LOGGER.info("Mirror world cleanup completed - no players present");
        }
    }

    /**
     * Save player inventory to player's persistent data (including mod items)
     * Uses NBT serialization to ensure complete item data preservation
     * Data is stored in player's PersistentData, survives server restarts
     */
    private static void savePlayerInventory(ServerPlayer player) {
        // Create inventory NBT data
        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);
        
        // Save to player's persistent data
        CompoundTag persistentData = player.getPersistentData();
        persistentData.put(SAVED_INVENTORY_KEY, inventoryTag);
        
        // Also save original position info
        BlockPos pos = player.blockPosition();
        persistentData.putInt(ORIGINAL_POS_KEY + "_x", pos.getX());
        persistentData.putInt(ORIGINAL_POS_KEY + "_y", pos.getY());
        persistentData.putInt(ORIGINAL_POS_KEY + "_z", pos.getZ());
        persistentData.putString(ORIGINAL_DIM_KEY, player.level().dimension().location().toString());
        
        InstantWorldMirror.LOGGER.info("Saved inventory to persistent data for player {} ({} items)", 
                player.getName().getString(), inventoryTag.size());
    }

    /**
     * Restore inventory from player's persistent data
     * Supports all items (vanilla + mod), correctly restores after server restart
     */
    private static void restorePlayerInventory(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        
        if (persistentData.contains(SAVED_INVENTORY_KEY)) {
            ListTag savedInventory = persistentData.getList(SAVED_INVENTORY_KEY, 10); // 10 = CompoundTag type
            
            // First clear current inventory
            player.getInventory().clearContent();
            // Restore inventory from NBT
            player.getInventory().load(savedInventory);
            
            // Clear saved data
            persistentData.remove(SAVED_INVENTORY_KEY);
            persistentData.remove(ORIGINAL_POS_KEY + "_x");
            persistentData.remove(ORIGINAL_POS_KEY + "_y");
            persistentData.remove(ORIGINAL_POS_KEY + "_z");
            persistentData.remove(ORIGINAL_DIM_KEY);
            
            InstantWorldMirror.LOGGER.info("Restored inventory from persistent data for player {}", 
                    player.getName().getString());
        } else {
            InstantWorldMirror.LOGGER.warn("No saved inventory found in persistent data for player {}", 
                    player.getName().getString());
        }
    }

    /**
     * Check if player has saved inventory data
     */
    public static boolean hasSavedInventory(ServerPlayer player) {
        return player.getPersistentData().contains(SAVED_INVENTORY_KEY);
    }

    /**
     * Clear player's saved data (for admin commands or error handling)
     */
    public static void clearSavedData(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        persistentData.remove(SAVED_INVENTORY_KEY);
        persistentData.remove(ORIGINAL_POS_KEY + "_x");
        persistentData.remove(ORIGINAL_POS_KEY + "_y");
        persistentData.remove(ORIGINAL_POS_KEY + "_z");
        persistentData.remove(ORIGINAL_DIM_KEY);
        InstantWorldMirror.LOGGER.info("Cleared saved data for player {}", player.getName().getString());
    }

    /**
     * World copy data record
     */
    private record WorldCopyData(BlockPos sourcePos, Level sourceLevel) {
    }
}
