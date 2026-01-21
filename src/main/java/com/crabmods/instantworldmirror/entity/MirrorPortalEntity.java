package com.crabmods.instantworldmirror.entity;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
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
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirror Portal Entity
 * Displays as a placed mirror model, lasts 5 seconds before disappearing
 * 
 * Entry Portal (Overworld):
 * - Created by a player using the Dimension Mirror item
 * - Bound to a MirrorSession
 * - Anyone can enter through this portal (joins the session)
 * 
 * Return Portal (Mirror World):
 * - Created by a player in the mirror world
 * - Not bound to a session (just returns player to overworld)
 * - Only the portal owner can use it
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

    // Teleport cooldown (prevents instant return after entering mirror world)
    // Maps player UUID to tick count when they last teleported
    private static final java.util.Map<UUID, Long> teleportCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    public MirrorPortalEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true; // No collision
    }

    /**
     * Create a return portal (in mirror world)
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn) {
        this(ModEntities.MIRROR_PORTAL.get(), level);
        this.setPos(x, y, z);
        this.ownerUUID = owner;
        this.isReturnPortal = isReturn;
        this.sessionId = null;
        this.clickPos = null;
        // Return portal doesn't need world copy, ready immediately
        if (isReturn) {
            this.worldCopyComplete = true;
            this.entityData.set(DATA_LOADING, false); // Not loading - ready to use
            this.entityData.set(DATA_IS_RETURN, true); // Mark as return portal for client
            // Set lifetime for return portal from config
            this.entityData.set(DATA_LIFETIME, MirrorConfig.getReturnPortalLifetimeTicks());
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
        
        // Bind this portal to the session
        MirrorWorldManager.bindPortalToSession(session.getSessionId(), this.getUUID());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Default to entry portal lifetime, will be updated when copy completes
        builder.define(DATA_LIFETIME, MirrorConfig.getEntryPortalLifetimeTicks());
        builder.define(DATA_LOADING, true); // Default to loading state
        builder.define(DATA_IS_RETURN, false); // Default to entry portal
    }

    @Override
    public void tick() {
        super.tick();

        // Cache isClientSide check - called multiple times
        boolean isClient = this.level().isClientSide;

        // Server side: async world copy and inactive portal cleanup
        if (!isClient) {
            ServerLevel serverLevel = (ServerLevel) this.level();
            
            // Start async world copy on first tick (only for entry portals)
            if (!isReturnPortal && !worldCopyStarted && clickPos != null && sessionId != null) {
                worldCopyStarted = true;
                startAsyncWorldCopy(serverLevel);
            }
            
            // Check if session's world copy is complete (only check every 5 ticks to reduce overhead)
            if (!isReturnPortal && worldCopyStarted && !worldCopyComplete && sessionId != null && this.tickCount % 5 == 0) {
                Optional<MirrorSession> sessionOpt = MirrorWorldManager.getSession(sessionId);
                if (sessionOpt.isPresent() && sessionOpt.get().isCopyComplete()) {
                    worldCopyComplete = true;
                    InstantWorldMirror.LOGGER.info("World copy detected complete for session {}", sessionId);
                }
            }
            
            // Check for inactive portal conditions (entry portals only)
            if (!isReturnPortal && !worldCopyComplete) {
                boolean shouldRemove = false;
                String removeReason = null;
                
                // Condition 1: Loading timeout - portal stuck in loading state too long
                int maxLoadingTicks = MirrorConfig.getMaxPortalLoadingTicks();
                if (this.tickCount > maxLoadingTicks) {
                    shouldRemove = true;
                    removeReason = "loading timeout (exceeded " + (maxLoadingTicks / 20) + " seconds)";
                }
                
                // Condition 2: Session no longer exists
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
                    // Cancel session if exists
                    if (sessionId != null) {
                        MirrorWorldManager.cancelSession(sessionId, serverLevel.getServer());
                    }
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

        // Get owner player
        Player owner = serverLevel.getPlayerByUUID(ownerUUID);
        if (owner instanceof ServerPlayer serverPlayer) {
            // Queue async world copy (non-blocking)
            MirrorWorldManager.prepareWorldCopy(serverPlayer, sessionOpt.get());
            // Copy is now in progress - check session.isCopyComplete() each tick
            InstantWorldMirror.LOGGER.info("Started async world copy for session {}", sessionId);
        } else {
            // Player not found, mark complete
            worldCopyComplete = true;
        }
    }

    /**
     * Check if player is on teleport cooldown
     */
    private static boolean isOnCooldown(UUID playerId, long currentTick) {
        Long lastTeleport = teleportCooldowns.get(playerId);
        if (lastTeleport == null) return false;
        return (currentTick - lastTeleport) < MirrorConfig.TELEPORT_COOLDOWN.get();
    }

    /**
     * Set player teleport cooldown
     */
    public static void setTeleportCooldown(UUID playerId, long currentTick) {
        teleportCooldowns.put(playerId, currentTick);
    }

    /**
     * Check player collision and teleport
     */
    private void checkPlayerCollision(ServerLevel serverLevel) {
        AABB boundingBox = this.getBoundingBox().inflate(0.5);
        List<Player> players = serverLevel.getEntitiesOfClass(Player.class, boundingBox);
        long currentTick = serverLevel.getGameTime();

        for (Player player : players) {
            if (player instanceof ServerPlayer serverPlayer) {
                // Check cooldown first
                if (isOnCooldown(serverPlayer.getUUID(), currentTick)) {
                    continue;
                }

                if (isReturnPortal) {
                    // Return portal: only owner can use
                    if (ownerUUID != null && !player.getUUID().equals(ownerUUID)) {
                        continue;
                    }
                    
                    // Set cooldown and return to overworld
                    setTeleportCooldown(serverPlayer.getUUID(), currentTick);
                    MirrorWorldManager.returnToOverworld(serverPlayer);
                } else {
                    // Entry portal: anyone can use (joins the session)
                    
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
                        // Session was destroyed (e.g., all players left), remove this portal
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
                    
                    // Check if player already has an active session (prevents entering two sessions)
                    if (MirrorWorldManager.hasActiveSession(serverPlayer.getUUID())) {
                        // If player is the creator of this session, they can enter
                        if (!session.getCreatorId().equals(serverPlayer.getUUID())) {
                            serverPlayer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable(
                                            "message.instantworldmirror.already_has_session"),
                                    true
                            );
                            continue;
                        }
                    }

                    // Set cooldown and teleport to mirror world
                    setTeleportCooldown(serverPlayer.getUUID(), currentTick);
                    MirrorWorldManager.teleportToMirrorWorld(serverPlayer, session);
                }

                // Play teleport sound and remove portal
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                this.discard();
                break;
            }
        }
    }

    /**
     * Spawn ambient particle effects
     */
    private void spawnAmbientParticles() {
        if (isReturnPortal) {
            // Return portal - golden/orange particle effect
            if (this.random.nextInt(2) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 1.0;
                double y = this.getY() - 0.5 + this.random.nextDouble() * 2.0;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 1.0;
                this.level().addParticle(ParticleTypes.FLAME, x, y, z, 0, 0.05, 0);
            }
            if (this.random.nextInt(3) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 0.8;
                double y = this.getY() + this.random.nextDouble() * 1.5;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 0.8;
                this.level().addParticle(ParticleTypes.ENCHANT, x, y, z, 0, 0.2, 0);
            }
            // Golden spiral effect
            if (this.random.nextInt(4) == 0) {
                double angle = this.tickCount * 0.2;
                double radius = 0.5;
                double x = this.getX() + Math.cos(angle) * radius;
                double y = this.getY() + 0.5 + (this.tickCount % 20) * 0.05;
                double z = this.getZ() + Math.sin(angle) * radius;
                this.level().addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 0, 0.02, 0);
            }
        } else {
            // Entry portal - blue-purple particle effect
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
        compound.putInt("Lifetime", this.entityData.get(DATA_LIFETIME));
        compound.putBoolean("WorldCopyComplete", this.worldCopyComplete);
        compound.putBoolean("WorldCopyStarted", this.worldCopyStarted);
        if (this.clickPos != null) {
            compound.putInt("ClickPosX", this.clickPos.getX());
            compound.putInt("ClickPosY", this.clickPos.getY());
            compound.putInt("ClickPosZ", this.clickPos.getZ());
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

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
