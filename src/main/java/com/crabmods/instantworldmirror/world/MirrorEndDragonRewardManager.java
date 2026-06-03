package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;

public final class MirrorEndDragonRewardManager {
    private static final String DRAGON_EGG_PLACED_TAG = InstantWorldMirror.MODID + ":generated_dragon_egg_placed";
    private static final ResourceLocation END_ROOT_ADVANCEMENT = ResourceLocation.withDefaultNamespace("end/root");
    private static final ResourceLocation END_DRAGON_ADVANCEMENT = ResourceLocation.withDefaultNamespace("end/kill_dragon");

    private MirrorEndDragonRewardManager() {
    }

    public static boolean tryHandleGeneratedMirrorDragonDeath(
            ServerLevel level,
            EnderDragon dragon,
            DamageSource damageSource
    ) {
        if (!ModDimensions.isAnyMirrorWorld(level.dimension())) {
            return false;
        }
        return handleGeneratedContentDeath(level, dragon, damageSource);
    }

    static boolean handleGeneratedContentDeath(ServerLevel level, EnderDragon dragon, DamageSource damageSource) {
        if (!MirrorBossBarManager.isGeneratedContentEntity(dragon)) {
            return false;
        }

        boolean handled = placeDragonEggForGeneratedContent(level, dragon);
        ServerPlayer killer = resolvePlayerKiller(dragon, damageSource);
        if (killer != null) {
            handled |= awardVanillaEndDragonAdvancement(killer);
        }

        return handled;
    }

    static boolean placeDragonEggForGeneratedContent(ServerLevel level, EnderDragon dragon) {
        if (!MirrorBossBarManager.isGeneratedContentEntity(dragon)
                || dragon.getPersistentData().getBoolean(DRAGON_EGG_PLACED_TAG)) {
            return false;
        }

        BlockPos eggPos = resolveDragonEggPos(level, dragon);
        if (!level.getWorldBorder().isWithinBounds(eggPos)) {
            return false;
        }

        if (!level.getBlockState(eggPos).is(Blocks.DRAGON_EGG)
                && !level.setBlockAndUpdate(eggPos, Blocks.DRAGON_EGG.defaultBlockState())) {
            return false;
        }

        dragon.getPersistentData().putBoolean(DRAGON_EGG_PLACED_TAG, true);
        return true;
    }

    static BlockPos resolveDragonEggPos(ServerLevel level, EnderDragon dragon) {
        BlockPos origin = dragon.getFightOrigin();
        if (origin == null) {
            origin = BlockPos.ZERO;
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, EndPodiumFeature.getLocation(origin));
    }

    static ServerPlayer resolvePlayerKiller(EnderDragon dragon, DamageSource damageSource) {
        Entity sourceEntity = damageSource.getEntity();
        if (sourceEntity instanceof ServerPlayer player) {
            return player;
        }

        LivingEntity killCredit = dragon.getKillCredit();
        if (killCredit instanceof ServerPlayer player) {
            return player;
        }

        return null;
    }

    static boolean awardVanillaEndDragonAdvancement(ServerPlayer player) {
        boolean awarded = false;
        awarded |= awardAdvancementCriterion(player, END_ROOT_ADVANCEMENT, "entered_end");
        awarded |= awardAdvancementCriterion(player, END_DRAGON_ADVANCEMENT, "killed_dragon");
        return awarded;
    }

    private static boolean awardAdvancementCriterion(ServerPlayer player, ResourceLocation advancementId, String criterion) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        AdvancementHolder advancement = server.getAdvancements().get(advancementId);
        return advancement != null && player.getAdvancements().award(advancement, criterion);
    }
}
