package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors dimension-controller boss bars for generated entities that do not own
 * their own player visibility hooks.
 */
public final class MirrorBossBarManager {
    private static final String GENERATED_CONTENT_ENTITY_TAG = InstantWorldMirror.MODID + ":generated_content_entity";
    private static final double BOSS_BAR_RANGE_SQR = 192.0 * 192.0;
    private static final double ENDER_DRAGON_FIGHT_CENTER_Y_OFFSET = 128.0;
    private static final float FALLBACK_BOSS_HEALTH_THRESHOLD = 80.0F;
    private static final Map<UUID, ManagedBossBar> FALLBACK_BARS = new ConcurrentHashMap<>();

    private MirrorBossBarManager() {
    }

    public static void markGeneratedContentEntity(Entity entity) {
        entity.getPersistentData().putBoolean(GENERATED_CONTENT_ENTITY_TAG, true);
    }

    public static boolean isGeneratedContentEntity(Entity entity) {
        return entity.getPersistentData().getBoolean(GENERATED_CONTENT_ENTITY_TAG);
    }

    public static void tick(ServerLevel level) {
        if (!ModDimensions.isAnyMirrorWorld(level.dimension())) {
            return;
        }

        if (level.players().isEmpty()) {
            removeBarsForDimension(level.dimension());
            return;
        }

        Set<UUID> activeEntities = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof LivingEntity livingEntity && shouldUseFallbackBar(livingEntity)) {
                activeEntities.add(livingEntity.getUUID());
                ManagedBossBar managedBar = FALLBACK_BARS.compute(livingEntity.getUUID(), (uuid, existing) -> {
                    if (existing == null || !existing.dimension.equals(level.dimension())) {
                        if (existing != null) {
                            existing.bar.removeAllPlayers();
                        }
                        return new ManagedBossBar(level.dimension(), createFallbackBar(livingEntity));
                    }
                    return existing;
                });

                updateFallbackBar(managedBar.bar, livingEntity, level);
            }
        }

        removeInactiveBarsForDimension(level.dimension(), activeEntities);
    }

    static boolean shouldUseFallbackBar(LivingEntity entity) {
        return entity.isAlive()
                && !(entity instanceof Player)
                && entity instanceof Enemy
                && entity.getMaxHealth() >= FALLBACK_BOSS_HEALTH_THRESHOLD
                && isGeneratedContentEntity(entity)
                && !hasNativePlayerVisibilityHook(entity);
    }

    public static void clear() {
        for (ManagedBossBar managedBar : FALLBACK_BARS.values()) {
            managedBar.bar.removeAllPlayers();
        }
        FALLBACK_BARS.clear();
    }

    private static ServerBossEvent createFallbackBar(LivingEntity entity) {
        ServerBossEvent bar = new ServerBossEvent(
                entity.getDisplayName(),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS
        );
        bar.setPlayBossMusic(false);
        bar.setCreateWorldFog(false);
        return bar;
    }

    private static void updateFallbackBar(ServerBossEvent bar, LivingEntity entity, ServerLevel level) {
        bar.setName(entity.getDisplayName());
        float maxHealth = Math.max(1.0F, entity.getMaxHealth());
        bar.setProgress(Math.max(0.0F, Math.min(1.0F, entity.getHealth() / maxHealth)));

        Set<ServerPlayer> visiblePlayers = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (shouldShowFallbackBarToPlayer(entity, player)) {
                visiblePlayers.add(player);
                bar.addPlayer(player);
            }
        }

        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (!visiblePlayers.contains(player)) {
                bar.removePlayer(player);
            }
        }
    }

    static boolean shouldShowFallbackBarToPlayer(LivingEntity entity, ServerPlayer player) {
        if (entity instanceof EnderDragon dragon) {
            BlockPos origin = dragon.getFightOrigin();
            if (origin == null) {
                origin = BlockPos.ZERO;
            }
            double dx = player.getX() - origin.getX();
            double dy = player.getY() - (origin.getY() + ENDER_DRAGON_FIGHT_CENTER_Y_OFFSET);
            double dz = player.getZ() - origin.getZ();
            return dx * dx + dy * dy + dz * dz <= BOSS_BAR_RANGE_SQR;
        }

        return player.distanceToSqr(entity) <= BOSS_BAR_RANGE_SQR;
    }

    private static boolean hasNativePlayerVisibilityHook(Entity entity) {
        return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
    }

    private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
        try {
            Method method = entityClass.getMethod(methodName, ServerPlayer.class);
            return method.getDeclaringClass() != Entity.class;
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static void removeInactiveBarsForDimension(ResourceKey<Level> dimension, Set<UUID> activeEntities) {
        for (Map.Entry<UUID, ManagedBossBar> entry : new ArrayList<>(FALLBACK_BARS.entrySet())) {
            if (entry.getValue().dimension.equals(dimension) && !activeEntities.contains(entry.getKey())) {
                entry.getValue().bar.removeAllPlayers();
                FALLBACK_BARS.remove(entry.getKey());
            }
        }
    }

    private static void removeBarsForDimension(ResourceKey<Level> dimension) {
        for (Map.Entry<UUID, ManagedBossBar> entry : new ArrayList<>(FALLBACK_BARS.entrySet())) {
            if (entry.getValue().dimension.equals(dimension)) {
                entry.getValue().bar.removeAllPlayers();
                FALLBACK_BARS.remove(entry.getKey());
            }
        }
    }

    private static final class ManagedBossBar {
        private final ResourceKey<Level> dimension;
        private final ServerBossEvent bar;

        private ManagedBossBar(ResourceKey<Level> dimension, ServerBossEvent bar) {
            this.dimension = dimension;
            this.bar = bar;
        }
    }
}
