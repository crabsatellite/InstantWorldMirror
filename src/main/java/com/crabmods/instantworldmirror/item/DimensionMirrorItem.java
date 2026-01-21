package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dimension Mirror - Used to open a portal to the Mirror World
 */
public class DimensionMirrorItem extends Item {

    public DimensionMirrorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        BlockState state = level.getBlockState(pos);

        if (player == null) {
            return InteractionResult.PASS;
        }

        // Check if block is solid (non-transparent)
        if (!state.isSolidRender(level, pos)) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.invalid_block"),
                    true
            );
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            ServerLevel serverLevel = (ServerLevel) level;
            ServerPlayer serverPlayer = (ServerPlayer) player;

            // Play portal spawn sound
            level.playSound(null, pos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.2F);

            // Spawn particle effects
            spawnPortalParticles(serverLevel, pos);

            // Check player's current dimension
            boolean isInMirrorWorld = MirrorWorldManager.isInMirrorWorld(serverPlayer);

            // Spawn portal entity (shows spinning mirror first, world copy handled by entity)
            BlockPos spawnPos = pos.above();
            MirrorPortalEntity portal = new MirrorPortalEntity(
                    level,
                    spawnPos.getX() + 0.5,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5,
                    player.getUUID(),
                    isInMirrorWorld, // If in mirror world, this is a return portal
                    pos // Pass clicked position for world copy
            );

            level.addFreshEntity(portal);

            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.portal_created"),
                    true
            );

            return InteractionResult.SUCCESS;
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Spawn portal particle effects
     */
    private void spawnPortalParticles(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;

        for (int i = 0; i < 20; i++) {
            double offsetX = (level.random.nextDouble() - 0.5) * 2;
            double offsetY = level.random.nextDouble() * 2;
            double offsetZ = (level.random.nextDouble() - 0.5) * 2;

            level.sendParticles(
                    ParticleTypes.PORTAL,
                    x + offsetX, y + offsetY, z + offsetZ,
                    1, 0, 0, 0, 0
            );
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // Give item enchantment glow effect
        return true;
    }
}
