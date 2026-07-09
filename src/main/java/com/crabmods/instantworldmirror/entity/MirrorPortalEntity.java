package com.crabmods.instantworldmirror.entity;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.block.PortalLightBlockEntity;
import com.crabmods.instantworldmirror.registry.ModBlocks;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.MirrorSession;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirror Portal Entity
 * Displays as a placed mirror model, lasts 5 seconds before disappearing
 * 
 * Entry Portal (Overworld):
 * - Created by a player using the World Reflection Mirror item
 * - Bound to a MirrorSession
 * - Anyone can enter through this portal (joins the session)
 * 
 * Return Portal (Mirror World):
 * - Created when host enters mirror world (auto-generated) or manually placed by any player
 * - All players in the session can use any return portal
 * - Auto-generated portals: persist until session is destroyed
 * - Manually placed portals: discard after single use
 */
public class MirrorPortalEntity extends Entity {

    // Synced data: remaining lifetime (ticks)
    private static final EntityDataAccessor<Integer> DATA_LIFETIME = SynchedEntityData.defineId(
            MirrorPortalEntity.class, EntityDataSerializers.INT
    );

    // Synced data: whether currently loading (spinning animation)
    private static final EntityDataAccessor<Boolean> DATA_LOADING = SynchedEntityData.defineId(
            MirrorPortalEntity.class, EntityDataSerializers.BOOLEAN
    );
    
    // Synced data: whether this is a return portal (for client rendering)
    private static final EntityDataAccessor<Boolean> DATA_IS_RETURN = SynchedEntityData.defineId(
            MirrorPortalEntity.class, EntityDataSerializers.BOOLEAN
    );

    // Synced data: which mirror model and texture this portal should render.
    private static final EntityDataAccessor<String> DATA_MIRROR_KIND = SynchedEntityData.defineId(
            MirrorPortalEntity.class, EntityDataSerializers.STRING
    );

    // Owner UUID (who created this portal)
    private UUID ownerUUID;

    // Session ID this portal is bound to (null for return portals)
    private UUID sessionId;

    // Whether this is a return portal (in mirror world)
    private boolean isReturnPortal;

    // Click position (for world copy)
    private BlockPos clickPos;

    // Whether world copy has started
    private boolean worldCopyStarted = false;

    // Whether world copy is complete
    private boolean worldCopyComplete = false;
    
    // Dimension index for queue tracking (-1 if not assigned)
    private int dimensionIndex = -1;
    
    // Last displayed queue position (to avoid spamming same message)
    private int lastDisplayedQueuePosition = 0;
    
    // Whether copy is actively processing (queue position 1 and actually copying)
    private boolean copyActivelyProcessing = false;

    // Current light block position (for cleanup)
    private BlockPos currentLightPos = null;
    
    // Track how long each player has been standing on return portal (for anti-accidental trigger)
    // Key: Player UUID, Value: Ticks standing on portal
    private final Map<UUID, Integer> playerStandingTime = new HashMap<>();
    
    // Track players who have left the portal area at least once (to prevent immediate return after entering)
    // Player must leave portal area first before they can use the return portal
    // This only applies during the first 30 seconds after entering the mirror world
    private final java.util.Set<UUID> playersLeftPortalOnce = new java.util.HashSet<>();
    
    // Required time to stand on return portal before teleporting (in ticks, 20 ticks = 1 second)
    private static final int RETURN_PORTAL_STANDING_REQUIRED = 20;
    
    // Time window during which the "leave first" requirement applies (30 seconds = 600 ticks)
    private static final int LEAVE_FIRST_PROTECTION_TICKS = 600;
    
    // Whether this return portal was auto-generated (by host entering mirror world)
    // Auto-generated: permanent until session ends | Manually placed: discard after use
    private boolean isAutoGenerated = false;
    
    // Session ID for auto-generated return portals (tracks when to remove)
    private UUID returnPortalSessionId = null;

    public MirrorPortalEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true; // No collision
    }
    
    /**
     * Static factory method to spawn a return portal.
     * Use this method to ensure consistent portal creation across all code paths.
     * 
     * @param level The server level to spawn in
     * @param groundBlockPos The solid block position where the portal should appear above
     * @param owner The player UUID who owns this portal
     * @param isAutoGenerated If true, portal is permanent until session ends
     * @param sessionId The session ID (only used for auto-generated portals)
     * @return The spawned portal entity
     */
    public static MirrorPortalEntity spawnReturnPortal(ServerLevel level, BlockPos groundBlockPos, 
                                                        UUID owner, boolean isAutoGenerated, UUID sessionId) {
        MirrorKind mirrorKind = sessionId != null
                ? MirrorWorldManager.getSession(sessionId)
                .map(MirrorSession::getKind)
                .orElse(MirrorKind.DIMENSION)
                : MirrorKind.DIMENSION;
        return spawnReturnPortal(level, groundBlockPos, owner, isAutoGenerated, sessionId, mirrorKind);
    }

    public static MirrorPortalEntity spawnReturnPortal(ServerLevel level, BlockPos groundBlockPos,
                                                        UUID owner, boolean isAutoGenerated, UUID sessionId,
                                                        MirrorKind mirrorKind) {
        // Portal spawns above the ground block, same as DimensionMirrorItem
        BlockPos spawnPos = groundBlockPos.above();
        MirrorPortalEntity portal = new MirrorPortalEntity(
                level,
                spawnPos.getX() + 0.5,
                spawnPos.getY() + 0.5,
                spawnPos.getZ() + 0.5,
                owner,
                true,           // is return portal
                isAutoGenerated,
                sessionId,
                mirrorKind
        );
        
        level.addFreshEntity(portal);
        return portal;
    }

    /**
     * Create a return portal (in mirror world)
     * This constructor creates a manually placed return portal (will discard after use)
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn) {
        this(level, x, y, z, owner, isReturn, false, null, MirrorKind.DIMENSION);
    }
    
    /**
     * Create a return portal (in mirror world) with auto-generation flag
     * @param isAutoGen if true, this portal was auto-generated and will persist until session ends
     * @param sessionIdForReturn the session ID this portal belongs to (for auto-generated portals)
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn, 
                              boolean isAutoGen, UUID sessionIdForReturn) {
        this(level, x, y, z, owner, isReturn, isAutoGen, sessionIdForReturn, MirrorKind.DIMENSION);
    }

    /**
     * Create a return portal (in mirror world) with auto-generation and mirror kind.
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn,
                              boolean isAutoGen, UUID sessionIdForReturn, MirrorKind mirrorKind) {
        this(ModEntities.MIRROR_PORTAL.get(), level);
        this.setPos(x, y, z);
        this.ownerUUID = owner;
        this.isReturnPortal = isReturn;
        this.sessionId = null;
        this.clickPos = null;
        this.isAutoGenerated = isAutoGen;
        this.returnPortalSessionId = sessionIdForReturn;
        this.entityData.set(DATA_MIRROR_KIND, sanitizeMirrorKind(mirrorKind).id());
        // Return portal doesn't need world copy, ready immediately
        if (isReturn) {
            this.worldCopyComplete = true;
            this.entityData.set(DATA_LOADING, false); // Not loading - ready to use
            this.entityData.set(DATA_IS_RETURN, true); // Mark as return portal for client
            // Auto-generated return portals have permanent lifetime (-1), manually placed use config
            if (isAutoGen) {
                this.entityData.set(DATA_LIFETIME, -1); // Permanent until session ends
            } else {
                this.entityData.set(DATA_LIFETIME, MirrorConfig.getReturnPortalLifetimeTicks());
            }
        }
    }

    /**
     * Create an entry portal (in overworld) bound to a session
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, 
                              MirrorSession session, BlockPos clickPos) {
        this(ModEntities.MIRROR_PORTAL.get(), level);
        this.setPos(x, y, z);
        this.ownerUUID = owner;
        this.isReturnPortal = false;
        this.sessionId = session.getSessionId();
        this.clickPos = clickPos;
        this.entityData.set(DATA_MIRROR_KIND, session.getKind().id());
        
        // Bind this portal to the session
        MirrorWorldManager.bindPortalToSession(session.getSessionId(), this.getUUID());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Default to entry portal lifetime, will be updated when copy completes
        builder.define(DATA_LIFETIME, MirrorConfig.getEntryPortalLifetimeTicks());
        builder.define(DATA_LOADING, true); // Default to loading state
        builder.define(DATA_IS_RETURN, false); // Default to entry portal
        builder.define(DATA_MIRROR_KIND, MirrorKind.DIMENSION.id()); // Default to Dimension Mirror variant
    }
    
    /**
     * Called when the entity is removed (killed, discarded, etc.)
     * Cancel the session and world copy if still in progress
     * Note: PortalLightBlockEntity will automatically detect this and remove itself
     */
    @Override
    public void remove(RemovalReason reason) {
        // If this is an entry portal with a session, cancel it
        if (!isReturnPortal && sessionId != null && this.level() instanceof ServerLevel serverLevel) {
            // Cancel the world copy task if still running
            Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
            if (sessionOpt.isPresent()) {
                MirrorSession session = sessionOpt.get();
                int dimIndex = session.getDimensionIndex();
                if (dimIndex >= 0) {
                    // Cancel the copy task for this dimension
                    com.crabmods.instantworldmirror.world.WorldCopyService.cancelCopyTask(dimIndex);
                }
            }
            
            // Cancel the session
            MirrorWorldManager.cancelSession(sessionId, serverLevel.getServer());
            InstantWorldMirror.LOGGER.info("Portal removed (reason: {}), session {} cancelled", reason, sessionId);
        }
        
        super.remove(reason);
    }

    @Override
    public void tick() {
        super.tick();

        // Cache isClientSide check - called multiple times
        boolean isClient = this.level().isClientSide;

        // Server side: async world copy and inactive portal cleanup
        if (!isClient) {
            ServerLevel serverLevel = (ServerLevel) this.level();
            
            // Update light block position (delay first placement to tick 20 for return portals to allow chunk lighting to initialize)
            // Entry portals can place immediately since overworld chunks are already loaded
            int firstLightTick = isReturnPortal ? 20 : 1;
            if (this.tickCount == firstLightTick || (this.tickCount > firstLightTick && this.tickCount % 5 == 0)) {
                updateLightBlock();
            }
            
            // Start async world copy on first tick (only for entry portals)
            if (!isReturnPortal && !worldCopyStarted && clickPos != null && sessionId != null) {
                worldCopyStarted = true;
                startAsyncWorldCopy(serverLevel);
            }
            
            // Check if session's world copy is complete (only check every 5 ticks to reduce overhead)
            if (!isReturnPortal && worldCopyStarted && !worldCopyComplete && sessionId != null && this.tickCount % 5 == 0) {
                Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
                if (sessionOpt.isPresent()) {
                    MirrorSession session = sessionOpt.get();
                    if (session.isCopyComplete()) {
                        worldCopyComplete = true;
                        InstantWorldMirror.LOGGER.debug("World copy complete for session {}", sessionId);
                    } else {
                        // Update queue position display for owner
                        updateQueuePositionDisplay(serverLevel);
                    }
                }
            }
            
            // Check for inactive portal conditions (entry portals only)
            if (!isReturnPortal) {
                boolean shouldRemove = false;
                String removeReason = null;
                
                // Condition 1: Loading timeout - portal stuck in loading state too long (only when not complete)
                if (!worldCopyComplete) {
                    int maxLoadingTicks = MirrorConfig.getMaxPortalLoadingTicks();
                    if (this.tickCount > maxLoadingTicks) {
                        shouldRemove = true;
                        removeReason = "loading timeout (exceeded " + (maxLoadingTicks / 20) + " seconds)";
                    }
                }
                
                // Condition 2: Session no longer exists or is destroyed
                // IMPORTANT: Check this even after worldCopyComplete to clean up when host leaves
                if (sessionId != null && this.tickCount % 20 == 0) { // Check every second
                    Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
                    if (sessionOpt.isEmpty()) {
                        shouldRemove = true;
                        removeReason = "session no longer exists";
                    } else if (sessionOpt.get().isDestroyed()) {
                        shouldRemove = true;
                        removeReason = "session is destroyed";
                    }
                }
                
                // Condition 3: No session ID but world copy started (invalid state)
                if (worldCopyStarted && sessionId == null) {
                    shouldRemove = true;
                    removeReason = "invalid state (no session ID)";
                }
                
                // Remove inactive portal
                if (shouldRemove) {
                    InstantWorldMirror.LOGGER.warn("Removing inactive portal: {}", removeReason);
                    // Play disappear sound
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.3F, 0.5F);
                    // Cancel session if exists (only if not already destroyed)
                    if (sessionId != null && !removeReason.contains("destroyed")) {
                        MirrorWorldManager.cancelSession(sessionId, serverLevel.getServer());
                    }
                    this.discard();
                    return;
                }
            }
            
            // Auto-generated return portals: remove when session ends
            if (isReturnPortal && isAutoGenerated && returnPortalSessionId != null && this.tickCount % 20 == 0) {
                Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(returnPortalSessionId);
                if (sessionOpt.isEmpty() || sessionOpt.get().isDestroyed()) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.3F, 0.5F);
                    this.discard();
                    return;
                }
            }
        }

        // Check if world copy complete - disable loading state when done
        boolean isLoading = this.entityData.get(DATA_LOADING);
        if (worldCopyComplete && isLoading) {
            this.entityData.set(DATA_LOADING, false);
            isLoading = false;
            // Set entry portal lifetime after copy complete (from config)
            this.entityData.set(DATA_LIFETIME, MirrorConfig.getEntryPortalLifetimeTicks());
        }

        // Only decrease time when loading is complete (skip for permanent portals, -1)
        int lifetime = this.entityData.get(DATA_LIFETIME);
        if (!isLoading && lifetime != -1) {
            lifetime--;
            this.entityData.set(DATA_LIFETIME, lifetime);

            // Time's up, remove entity
            if (lifetime <= 0) {
                if (!isClient) {
                    onPortalTimeout();
                }
                this.discard();
                return;
            }
        }

        // Client side particle effects
        if (isClient) {
            spawnAmbientParticles();
        }

        // Server side check player collision (only when loading complete)
        if (!isClient && !isLoading) {
            checkPlayerCollision((ServerLevel) this.level());
        }
    }

    /**
     * Called when portal times out without anyone entering
     */
    private void onPortalTimeout() {
        // Play disappear sound
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.5F, 1.5F);

        // If this is an entry portal with a session, cancel the session
        if (!isReturnPortal && sessionId != null && this.level() instanceof ServerLevel serverLevel) {
            MirrorWorldManager.cancelSession(sessionId, serverLevel.getServer());
            InstantWorldMirror.LOGGER.info("Portal timed out, session {} cancelled", sessionId);
        }
    }

    /**
     * Update the light block at the entity's position.
     * Uses PortalLightBlock which automatically tracks this entity and removes itself when entity is gone.
     * Supports waterlogging so the light block can coexist with water.
     */
    private void updateLightBlock() {
        if (this.level().isClientSide) return;

        ServerLevel serverLevel = (ServerLevel) this.level();
        BlockPos newLightPos = BlockPos.containing(this.getX(), this.getY() + 1.0, this.getZ());

        // If position hasn't changed, verify the block still exists
        if (newLightPos.equals(currentLightPos)) {
            BlockState existingState = serverLevel.getBlockState(currentLightPos);
            if (existingState.is(ModBlocks.PORTAL_LIGHT_BLOCK.get())) {
                return; // Light block is still in place, nothing to do
            }
            // Light block was removed by something external, reset and re-place
            currentLightPos = null;
        }

        // Remove old light block (only if it was our block)
        removeLightBlock();

        // Check if position is suitable for placing the light block
        BlockState currentState = serverLevel.getBlockState(newLightPos);
        boolean isWater = currentState.getFluidState().isSource()
                && currentState.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;

        if (currentState.isAir() || currentState.canBeReplaced() || isWater) {
            // Create block state with waterlogged property if placing in water
            BlockState lightState = ModBlocks.PORTAL_LIGHT_BLOCK.get().defaultBlockState()
                    .setValue(com.crabmods.instantworldmirror.block.PortalLightBlock.WATERLOGGED, isWater);

            // Place our custom portal light block
            if (!serverLevel.setBlock(newLightPos, lightState, 3)) {
                return;
            }

            // Schedule water tick if waterlogged, so surrounding water stays consistent
            if (isWater) {
                serverLevel.scheduleTick(newLightPos, net.minecraft.world.level.material.Fluids.WATER,
                        net.minecraft.world.level.material.Fluids.WATER.getTickDelay(serverLevel));
            }

            // Set the block entity's portal reference
            BlockEntity be = serverLevel.getBlockEntity(newLightPos);
            if (be instanceof PortalLightBlockEntity lightBE) {
                lightBE.setPortalEntityId(this.getUUID());
            }

            currentLightPos = newLightPos;

            // Force comprehensive light update
            serverLevel.getLightEngine().checkBlock(newLightPos);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                serverLevel.getLightEngine().checkBlock(newLightPos.relative(dir));
            }
            serverLevel.getChunkSource().blockChanged(newLightPos);
        }
    }

    /**
     * Remove the light block when entity moves.
     * Note: When entity is removed, the PortalLightBlockEntity will automatically detect this and remove itself.
     */
    private void removeLightBlock() {
        if (this.level() == null || this.level().isClientSide) return;

        ServerLevel serverLevel = (ServerLevel) this.level();

        // Remove the tracked light block position
        if (currentLightPos != null) {
            BlockState state = serverLevel.getBlockState(currentLightPos);
            if (state.is(ModBlocks.PORTAL_LIGHT_BLOCK.get())) {
                // Restore water if the light block was waterlogged, otherwise place air
                boolean wasWaterlogged = state.getValue(com.crabmods.instantworldmirror.block.PortalLightBlock.WATERLOGGED);
                BlockState replacement = wasWaterlogged ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                serverLevel.setBlockAndUpdate(currentLightPos, replacement);
                serverLevel.getChunkSource().blockChanged(currentLightPos);
            }
            currentLightPos = null;
        }
    }

    /**
     * Start async world copy - queues copy task that processes over multiple ticks
     */
    private void startAsyncWorldCopy(ServerLevel serverLevel) {
        // Get session
        Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            InstantWorldMirror.LOGGER.warn("Session {} not found for portal", sessionId);
            worldCopyComplete = true;
            return;
        }
        
        MirrorSession session = sessionOpt.get();
        this.dimensionIndex = session.getDimensionIndex();

        // Get owner player
        Player owner = serverLevel.getPlayerByUUID(ownerUUID);
        if (owner instanceof ServerPlayer serverPlayer) {
            // Queue async world copy (non-blocking) - returns queue position
            int queuePosition = MirrorWorldManager.prepareWorldCopy(serverPlayer, session);
            this.lastDisplayedQueuePosition = queuePosition;
            
            // Notify player of queue position
            if (queuePosition > 1) {
                serverPlayer.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.instantworldmirror.queue_position", queuePosition),
                        true
                );
            } else {
                // Position 1 means we're actively processing
                this.copyActivelyProcessing = true;
                serverPlayer.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.instantworldmirror.copy_started"),
                        true
                );
            }
            
            // Copy is now in progress - check session.isCopyComplete() each tick
            InstantWorldMirror.LOGGER.debug("Started world copy for session {}, queue position: {}", 
                    sessionId, queuePosition);
        } else {
            // Player not found, mark complete
            worldCopyComplete = true;
        }
    }
    
    /**
     * Update queue position display for the portal owner
     */
    private void updateQueuePositionDisplay(ServerLevel serverLevel) {
        if (dimensionIndex < 0 || ownerUUID == null) return;
        
        Player owner = serverLevel.getPlayerByUUID(ownerUUID);
        if (!(owner instanceof ServerPlayer serverPlayer)) return;
        
        int currentQueuePosition = com.crabmods.instantworldmirror.world.WorldCopyService.getCopyQueuePosition(dimensionIndex);
        
        // Queue position 0 means task completed or not in queue anymore
        if (currentQueuePosition <= 0) return;
        
        // Check if copy just started (moved from waiting to position 1)
        if (currentQueuePosition == 1 && !copyActivelyProcessing) {
            copyActivelyProcessing = true;
            serverPlayer.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.instantworldmirror.copy_started"),
                    true
            );
            lastDisplayedQueuePosition = 1;
            return;
        }
        
        // Update queue position if changed (for waiting in queue)
        if (currentQueuePosition > 1 && currentQueuePosition != lastDisplayedQueuePosition) {
            lastDisplayedQueuePosition = currentQueuePosition;
            serverPlayer.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.instantworldmirror.queue_position", currentQueuePosition),
                    true
            );
        }
    }

    /**
     * Check player collision and teleport
     */
    private void checkPlayerCollision(ServerLevel serverLevel) {
        // Use a smaller collision box for return portal to prevent accidental triggers
        // Entity size is 0.8 x 1.5, so we don't inflate it
        AABB boundingBox = this.getBoundingBox();
        List<Player> players = serverLevel.getEntitiesOfClass(Player.class, boundingBox);
        
        // Track which players are currently in the bounding box (for return portal)
        java.util.Set<UUID> playersInBox = new java.util.HashSet<>();

        for (Player player : players) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (isReturnPortal) {
                    // Return portal: any player can use
                    playersInBox.add(player.getUUID());
                    
                    // Check if player has left the portal area at least once
                    // This prevents immediate return after entering the mirror world
                    // Only applies during the first 30 seconds after entering the mirror world
                    boolean isWithinProtectionPeriod = false;
                    net.minecraft.nbt.CompoundTag playerData = player.getPersistentData();
                    if (playerData.contains("MirrorWorldEnterTime")) {
                        long enterTime = playerData.getLong("MirrorWorldEnterTime");
                        long currentTime = this.level().getGameTime();
                        isWithinProtectionPeriod = (currentTime - enterTime) < LEAVE_FIRST_PROTECTION_TICKS;
                    }
                    
                    if (isWithinProtectionPeriod && !playersLeftPortalOnce.contains(player.getUUID())) {
                        // Player hasn't left portal area yet, mark them as "seen" so we can detect when they leave
                        // Put 0 in standing time just to track that we've seen this player
                        playerStandingTime.putIfAbsent(player.getUUID(), 0);
                        continue;
                    }
                    
                    // Increment standing time
                    int currentStandTime = playerStandingTime.getOrDefault(player.getUUID(), 0);
                    currentStandTime++;
                    playerStandingTime.put(player.getUUID(), currentStandTime);
                    
                    // Check if player has been standing long enough
                    if (currentStandTime < RETURN_PORTAL_STANDING_REQUIRED) {
                        // Not ready yet, show progress
                        float progress = (float) currentStandTime / RETURN_PORTAL_STANDING_REQUIRED;
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.portal_activating", 
                                        String.format("%.0f%%", progress * 100)),
                                true
                        );
                        continue;
                    }
                    
                    // Use the new method that considers portal position
                    // If this portal is far from where player entered, teleport to corresponding position
                    boolean success = MirrorWorldManager.returnToOverworldFromPosition(serverPlayer, this.blockPosition());
                    
                    if (success) {
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                        
                        // Discard manually placed portals, keep auto-generated ones
                        if (!isAutoGenerated) {
                            this.discard();
                        } else {
                            // Reset standing time for next player
                            playerStandingTime.clear();
                        }
                    } else {
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.return_failed"),
                                true
                        );
                    }
                    return;
                } else {
                    // Entry portal: Zoom-meeting style - host must enter first, then others can follow
                    
                    // Check access permission
                    if (!MirrorWorldManager.canAccessMirrorWorld(serverPlayer)) {
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.access_denied"),
                                true
                        );
                        continue;
                    }

                    // Get session
                    Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
                    if (sessionOpt.isEmpty()) {
                        InstantWorldMirror.LOGGER.info("Session {} no longer exists, removing portal", sessionId);
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.session_expired"),
                                true
                        );
                        this.discard();
                        return;
                    }

                    MirrorSession session = sessionOpt.get();
                    
                    // Check if session is destroyed
                    if (session.isDestroyed()) {
                        InstantWorldMirror.LOGGER.info("Session {} is destroyed, removing portal", sessionId);
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.session_expired"),
                                true
                        );
                        this.discard();
                        return;
                    }
                    
                    boolean isHost = session.isHost(serverPlayer.getUUID());
                    
                    // Non-host players must wait for host to enter first
                    if (!isHost && !session.hasHostEntered()) {
                        serverPlayer.displayClientMessage(
                                net.minecraft.network.chat.Component.translatable(
                                        "message.instantworldmirror.wait_for_host"),
                                true
                        );
                        continue;
                    }
                    
                    // Check if player already has an active session (prevents entering two sessions)
                    if (MirrorWorldManager.hasActiveSession(serverPlayer.getUUID())) {
                        // If player is the creator of this session, they can enter
                        if (!isHost) {
                            serverPlayer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable(
                                            "message.instantworldmirror.already_has_session"),
                                    true
                            );
                            continue;
                        }
                    }

                    // Set cooldown and teleport to mirror world
                    boolean success = MirrorWorldManager.teleportToMirrorWorld(serverPlayer, session);
                    
                    if (success) {
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                        
                        // Mark host as entered when host enters
                        if (isHost) {
                            session.markHostEntered();
                            InstantWorldMirror.LOGGER.info("Host {} entered session {}, portal now open for others",
                                    serverPlayer.getName().getString(), sessionId);
                        }
                        
                        // DON'T discard the portal - keep it open for other players
                        // Portal will be removed when:
                        // 1. Host exits (triggers session destroy)
                        // 2. Portal lifetime expires
                        // 3. Session is destroyed for other reasons
                    } else {
                        InstantWorldMirror.LOGGER.warn("Entry portal FAILED to teleport player {}", 
                                serverPlayer.getName().getString());
                    }
                    return;
                }
            }
        }
        
        // Clean up standing time for players who left the return portal area
        // Also mark players as "left portal once" when they leave
        if (isReturnPortal) {
            // Find players who were tracking but are no longer in the box
            for (UUID uuid : new java.util.HashSet<>(playerStandingTime.keySet())) {
                if (!playersInBox.contains(uuid)) {
                    // Player left the portal area, mark them as having left once
                    playersLeftPortalOnce.add(uuid);
                    playerStandingTime.remove(uuid);
                }
            }
        }
    }

    /**
     * Spawn ambient particle effects
     */
    private void spawnAmbientParticles() {
        // Portal particle effect
        if (this.random.nextInt(2) == 0) {
            double x = this.getX() + (this.random.nextDouble() - 0.5) * 1.0;
            double y = this.getY() - 0.5 + this.random.nextDouble() * 2.0;
            double z = this.getZ() + (this.random.nextDouble() - 0.5) * 1.0;
            this.level().addParticle(ParticleTypes.PORTAL, x, y, z, 0, 0.1, 0);
        }
        // White particle (END_ROD) - reduced by half
        if (this.random.nextInt(6) == 0) {
            double x = this.getX() + (this.random.nextDouble() - 0.5) * 0.6;
            double y = this.getY() + this.random.nextDouble() * 1.0;
            double z = this.getZ() + (this.random.nextDouble() - 0.5) * 0.6;
            this.level().addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.02, 0);
        }
        // Blue spiral effect
        if (this.random.nextInt(4) == 0) {
            double angle = this.tickCount * 0.2;
            double radius = 0.5;
            double x = this.getX() + Math.cos(angle) * radius;
            double y = this.getY() + 0.5 + (this.tickCount % 20) * 0.05;
            double z = this.getZ() + Math.sin(angle) * radius;
            this.level().addParticle(ParticleTypes.REVERSE_PORTAL, x, y, z, 0, 0.02, 0);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        if (compound.hasUUID("Owner")) {
            this.ownerUUID = compound.getUUID("Owner");
        }
        if (compound.hasUUID("SessionId")) {
            this.sessionId = compound.getUUID("SessionId");
        }
        this.isReturnPortal = compound.getBoolean("IsReturn");
        this.entityData.set(DATA_LIFETIME, compound.getInt("Lifetime"));
        this.entityData.set(DATA_IS_RETURN, this.isReturnPortal); // Sync to client
        this.entityData.set(DATA_MIRROR_KIND, readMirrorKind(compound).id()); // Sync model variant
        this.entityData.set(DATA_LOADING, !compound.getBoolean("WorldCopyComplete")); // Sync loading state
        this.worldCopyComplete = compound.getBoolean("WorldCopyComplete");
        this.worldCopyStarted = compound.getBoolean("WorldCopyStarted");
        if (compound.contains("ClickPosX")) {
            this.clickPos = new BlockPos(
                    compound.getInt("ClickPosX"),
                    compound.getInt("ClickPosY"),
                    compound.getInt("ClickPosZ")
            );
        }
        // Restore light block position for cleanup
        if (compound.contains("LightPosX")) {
            this.currentLightPos = new BlockPos(
                    compound.getInt("LightPosX"),
                    compound.getInt("LightPosY"),
                    compound.getInt("LightPosZ")
            );
        }
        // Restore auto-generated flag and return portal session ID
        this.isAutoGenerated = compound.getBoolean("IsAutoGenerated");
        if (compound.hasUUID("ReturnPortalSessionId")) {
            this.returnPortalSessionId = compound.getUUID("ReturnPortalSessionId");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        if (this.ownerUUID != null) {
            compound.putUUID("Owner", this.ownerUUID);
        }
        if (this.sessionId != null) {
            compound.putUUID("SessionId", this.sessionId);
        }
        compound.putBoolean("IsReturn", this.isReturnPortal);
        compound.putString("MirrorKind", getMirrorKind().id());
        compound.putBoolean("HeavenPortal", isHeavenPortal());
        compound.putInt("Lifetime", this.entityData.get(DATA_LIFETIME));
        compound.putBoolean("WorldCopyComplete", this.worldCopyComplete);
        compound.putBoolean("WorldCopyStarted", this.worldCopyStarted);
        if (this.clickPos != null) {
            compound.putInt("ClickPosX", this.clickPos.getX());
            compound.putInt("ClickPosY", this.clickPos.getY());
            compound.putInt("ClickPosZ", this.clickPos.getZ());
        }
        // Save light block position for cleanup on load
        if (this.currentLightPos != null) {
            compound.putInt("LightPosX", this.currentLightPos.getX());
            compound.putInt("LightPosY", this.currentLightPos.getY());
            compound.putInt("LightPosZ", this.currentLightPos.getZ());
        }
        // Save auto-generated flag and return portal session ID
        compound.putBoolean("IsAutoGenerated", this.isAutoGenerated);
        if (this.returnPortalSessionId != null) {
            compound.putUUID("ReturnPortalSessionId", this.returnPortalSessionId);
        }
    }

    // ==================== Getters ====================

    public int getLifetime() {
        return this.entityData.get(DATA_LIFETIME);
    }

    public Optional<UUID> getOwnerUUID() {
        return Optional.ofNullable(this.ownerUUID);
    }

    public Optional<UUID> getSessionId() {
        return Optional.ofNullable(this.sessionId);
    }

    public boolean isReturnPortal() {
        return this.entityData.get(DATA_IS_RETURN);
    }

    public boolean isLoading() {
        return this.entityData.get(DATA_LOADING);
    }

    public MirrorKind getMirrorKind() {
        return MirrorKind.byId(this.entityData.get(DATA_MIRROR_KIND));
    }

    public boolean isHeavenPortal() {
        return getMirrorKind() == MirrorKind.HEAVEN;
    }

    private static MirrorKind readMirrorKind(CompoundTag compound) {
        if (compound.contains("MirrorKind")) {
            return MirrorKind.byId(compound.getString("MirrorKind"));
        }
        return compound.getBoolean("HeavenPortal") ? MirrorKind.HEAVEN : MirrorKind.DIMENSION;
    }

    private static MirrorKind sanitizeMirrorKind(MirrorKind mirrorKind) {
        return mirrorKind != null ? mirrorKind : MirrorKind.DIMENSION;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
