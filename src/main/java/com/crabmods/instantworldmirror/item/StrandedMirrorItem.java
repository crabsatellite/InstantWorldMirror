package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.network.StrandedCapturePromptPacket;
import com.crabmods.instantworldmirror.network.StrandedSnapshotMenuPacket;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Captures and reopens named world slices across saves.
 */
public class StrandedMirrorItem extends DimensionMirrorItem {
    enum UseMode {
        MIRROR_WORLD,
        PERSISTENT_MENU,
        CAPTURE,
        SELECT
    }

    public StrandedMirrorItem(Properties properties) {
        super(properties, MirrorKind.STRANDED);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        UseMode mode = resolveUseMode(context.getLevel(), player, true);
        if (mode == UseMode.MIRROR_WORLD || mode == UseMode.PERSISTENT_MENU) {
            return super.useOn(context);
        }
        if (!context.getLevel().isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockPos targetPos = context.getClickedPos();
            if (!StrandedSnapshotManager.canBeginRequest(serverPlayer, targetPos)) {
                return InteractionResult.FAIL;
            }
            if (NetworkRegistry.hasChannel(serverPlayer.connection, StrandedCapturePromptPacket.TYPE.id())) {
                PacketDistributor.sendToPlayer(serverPlayer, new StrandedCapturePromptPacket(targetPos));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        UseMode mode = resolveUseMode(level, player, false);
        if (mode == UseMode.MIRROR_WORLD || mode == UseMode.PERSISTENT_MENU) {
            return super.use(level, player, hand);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockPos targetPos = player.blockPosition().below();
            if (!StrandedSnapshotManager.canBeginRequest(serverPlayer, targetPos)) {
                return InteractionResultHolder.fail(stack);
            }
            if (NetworkRegistry.hasChannel(serverPlayer.connection, StrandedSnapshotMenuPacket.TYPE.id())) {
                PacketDistributor.sendToPlayer(
                        serverPlayer, StrandedSnapshotMenuPacket.create(serverPlayer, targetPos));
            }
        }
        return InteractionResultHolder.consume(stack);
    }

    static UseMode resolveUseMode(Level level, Player player, boolean blockInteraction) {
        if (ModDimensions.isAnyMirrorWorld(level.dimension())) {
            return UseMode.MIRROR_WORLD;
        }
        if (player.isShiftKeyDown()) {
            return UseMode.PERSISTENT_MENU;
        }
        return blockInteraction ? UseMode.CAPTURE : UseMode.SELECT;
    }
}
