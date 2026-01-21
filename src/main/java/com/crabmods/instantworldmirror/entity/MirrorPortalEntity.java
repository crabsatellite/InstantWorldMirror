package com.crabmods.instantworldmirror.entity;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.registry.ModItems;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirror Portal Entity
 * Displays as a placed mirror model, lasts 5 seconds before disappearing
 * Only allows the creator to enter the portal
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

    // Default lifetime: 5 seconds = 100 ticks
    private static final int DEFAULT_LIFETIME = 100;

    // Owner UUID
    private UUID ownerUUID;

    // Whether this is a return portal (in mirror world)
    private boolean isReturnPortal;

    // Click position (for world copy)
    private BlockPos clickPos;

    // Whether world copy has started
    private boolean worldCopyStarted = false;

    // Whether world copy is complete
    private boolean worldCopyComplete = false;

    public MirrorPortalEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true; // No collision
    }

    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn) {
        this(ModEntities.MIRROR_PORTAL.get(), level);
        this.setPos(x, y, z);
        this.ownerUUID = owner;
        this.isReturnPortal = isReturn;
        this.clickPos = null;
        // Return portal doesn't need world copy, ready immediately
        if (isReturn) {
            this.worldCopyComplete = true;
        }
    }

    /**
     * Constructor with click position (for triggering async world copy)
     */
    public MirrorPortalEntity(Level level, double x, double y, double z, UUID owner, boolean isReturn, BlockPos clickPos) {
        this(level, x, y, z, owner, isReturn);
        this.clickPos = clickPos;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LIFETIME, DEFAULT_LIFETIME);
        builder.define(DATA_LOADING, true); // Default to loading state
    }

    @Override
    public void tick() {
        super.tick();

        int lifetime = this.entityData.get(DATA_LIFETIME);

        // Server side: async world copy
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            // Start async world copy on first tick
            if (!isReturnPortal && !worldCopyStarted && clickPos != null) {
                worldCopyStarted = true;
                startAsyncWorldCopy(serverLevel);
            }
        }

        // Check if world copy complete - disable loading state when done
        if (worldCopyComplete && this.entityData.get(DATA_LOADING)) {
            this.entityData.set(DATA_LOADING, false);
            // Reset lifetime after copy complete (5 seconds to enter)
            this.entityData.set(DATA_LIFETIME, DEFAULT_LIFETIME);
        }

        // Only decrease time when loading is complete
        if (!this.entityData.get(DATA_LOADING)) {
            lifetime--;
            this.entityData.set(DATA_LIFETIME, lifetime);

            // Time's up, remove entity
            if (lifetime <= 0) {
                if (!this.level().isClientSide) {
                    // Play disappear sound
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.5F, 1.5F);
                }
                this.discard();
                return;
            }
        }

        // Client side particle effects
        if (this.level().isClientSide) {
            spawnAmbientParticles();
        }

        // Server side check player collision (only when loading complete)
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            if (!this.entityData.get(DATA_LOADING)) {
                checkPlayerCollision(serverLevel);
            }
        }
    }

    /**
     * Async world copy (execute in batches on main thread to avoid lag)
     */
    private void startAsyncWorldCopy(ServerLevel serverLevel) {
        // Get owner player
        Player owner = serverLevel.getPlayerByUUID(ownerUUID);
        if (owner instanceof ServerPlayer serverPlayer) {
            // Execute world copy on main thread with delay (Minecraft world operations must be on main thread)
            serverLevel.getServer().execute(() -> {
                try {
                    // Execute world copy
                    MirrorWorldManager.prepareWorldCopy(serverPlayer, clickPos);
                    worldCopyComplete = true;
                    InstantWorldMirror.LOGGER.info("World copy completed for portal at {}", this.blockPosition());
                } catch (Exception e) {
                    InstantWorldMirror.LOGGER.error("World copy failed", e);
                    // Mark complete even on failure, let player enter (might be empty world)
                    worldCopyComplete = true;
                }
            });
        } else {
            // Player not found, mark complete
            worldCopyComplete = true;
        }
    }

    /**
     * Check player collision and teleport
     */
    private void checkPlayerCollision(ServerLevel serverLevel) {
        AABB boundingBox = this.getBoundingBox().inflate(0.5);
        List<Player> players = serverLevel.getEntitiesOfClass(Player.class, boundingBox);

        for (Player player : players) {
            if (player instanceof ServerPlayer serverPlayer) {
                // Check if owner
                if (ownerUUID != null && !player.getUUID().equals(ownerUUID)) {
                    continue; // Not owner, skip
                }

                // Check if player has permission to enter
                if (!isReturnPortal && !MirrorWorldManager.canAccessMirrorWorld(serverPlayer)) {
                    serverPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("message.instantworldmirror.access_denied"),
                            true
                    );
                    continue;
                }

                // Execute teleport
                if (isReturnPortal) {
                    MirrorWorldManager.returnToOverworld(serverPlayer);
                    // Cleanup mirror world after return
                    serverLevel.getServer().execute(() -> {
                        MirrorWorldManager.cleanupMirrorWorldIfEmpty(serverLevel.getServer());
                    });
                } else {
                    MirrorWorldManager.teleportToMirrorWorld(serverPlayer, this.blockPosition());
                }

                // Remove portal after teleport
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                this.discard();
                break;
            }
        }
    }

    /**
     * Spawn ambient particle effects
     * Overworld portal: blue-purple particles (Portal + End Rod)
     * Mirror world portal: golden particles (Flame + Enchant)
     */
    private void spawnAmbientParticles() {
        if (isReturnPortal) {
            // Return portal - golden/orange particle effect
            if (this.random.nextInt(2) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 1.0;
                double y = this.getY() + this.random.nextDouble() * 2.0;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 1.0;
                this.level().addParticle(ParticleTypes.FLAME, x, y, z, 0, 0.05, 0);
            }
            if (this.random.nextInt(3) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 0.8;
                double y = this.getY() + 0.5 + this.random.nextDouble() * 1.5;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 0.8;
                this.level().addParticle(ParticleTypes.ENCHANT, x, y, z, 0, 0.2, 0);
            }
            // Golden spiral effect
            if (this.random.nextInt(4) == 0) {
                double angle = this.tickCount * 0.2;
                double radius = 0.5;
                double x = this.getX() + Math.cos(angle) * radius;
                double y = this.getY() + 1.0 + (this.tickCount % 20) * 0.05;
                double z = this.getZ() + Math.sin(angle) * radius;
                this.level().addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 0, 0.02, 0);
            }
        } else {
            // Entry portal - blue-purple particle effect
            if (this.random.nextInt(2) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 1.0;
                double y = this.getY() + this.random.nextDouble() * 2.0;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 1.0;
                this.level().addParticle(ParticleTypes.PORTAL, x, y, z, 0, 0.1, 0);
            }
            // Light blue glow particles
            if (this.random.nextInt(3) == 0) {
                double x = this.getX() + (this.random.nextDouble() - 0.5) * 0.6;
                double y = this.getY() + 0.5 + this.random.nextDouble() * 1.0;
                double z = this.getZ() + (this.random.nextDouble() - 0.5) * 0.6;
                this.level().addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.02, 0);
            }
            // Blue spiral effect
            if (this.random.nextInt(4) == 0) {
                double angle = this.tickCount * 0.2;
                double radius = 0.5;
                double x = this.getX() + Math.cos(angle) * radius;
                double y = this.getY() + 1.0 + (this.tickCount % 20) * 0.05;
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
        this.isReturnPortal = compound.getBoolean("IsReturn");
        this.entityData.set(DATA_LIFETIME, compound.getInt("Lifetime"));
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

    /**
     * Get remaining lifetime
     */
    public int getLifetime() {
        return this.entityData.get(DATA_LIFETIME);
    }

    /**
     * Get owner UUID
     */
    public Optional<UUID> getOwnerUUID() {
        return Optional.ofNullable(this.ownerUUID);
    }

    /**
     * Is this a return portal
     */
    public boolean isReturnPortal() {
        return this.isReturnPortal;
    }

    /**
     * Is currently loading (spinning animation state)
     */
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
