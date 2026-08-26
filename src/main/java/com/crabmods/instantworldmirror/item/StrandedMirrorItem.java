package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.network.ModNetworking;
import com.crabmods.instantworldmirror.network.StrandedCapturePromptPacket;
import com.crabmods.instantworldmirror.network.PersistentMirrorMenuPacket;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.StrandedSnapshotManager;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Captures and reopens named world slices across saves.
 */
public class StrandedMirrorItem extends DimensionMirrorItem {
    enum UseMode {
        MIRROR_WORLD,
        CAPTURE,
        LIBRARY
    }

    public StrandedMirrorItem(Properties properties) {
        super(properties, MirrorKind.STRANDED);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltipComponents,
                                TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);
        tooltipComponents.add(Component.translatable(
                "message.instantworldmirror.stranded.tooltip.capture").withStyle(ChatFormatting.YELLOW));
        tooltipComponents.add(Component.translatable(
                "message.instantworldmirror.stranded.tooltip.library").withStyle(ChatFormatting.YELLOW));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        UseMode mode = resolveUseMode(context.getLevel(), player, true);
        if (mode == UseMode.MIRROR_WORLD) {
            return super.useOn(context);
        }
        if (mode == UseMode.LIBRARY) {
            if (!context.getLevel().isClientSide && player instanceof ServerPlayer serverPlayer) {
                openLongTermMenu(serverPlayer);
            }
            return InteractionResult.SUCCESS;
        }
        if (!context.getLevel().isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockPos targetPos = context.getClickedPos();
            if (!StrandedSnapshotManager.canBeginRequest(serverPlayer, targetPos)) {
                return InteractionResult.FAIL;
            }
            if (ModNetworking.CHANNEL.isRemotePresent(serverPlayer.connection.connection)) {
                ModNetworking.sendToPlayer(new StrandedCapturePromptPacket(targetPos), serverPlayer);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        UseMode mode = resolveUseMode(level, player, false);
        if (mode == UseMode.MIRROR_WORLD) {
            return super.use(level, player, hand);
        }
        if (mode == UseMode.LIBRARY) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                openLongTermMenu(serverPlayer);
            }
            return InteractionResultHolder.consume(stack);
        }
        return InteractionResultHolder.consume(stack);
    }

    static UseMode resolveUseMode(Level level, Player player, boolean blockInteraction) {
        if (ModDimensions.isAnyMirrorWorld(level.dimension())) {
            return UseMode.MIRROR_WORLD;
        }
        return blockInteraction && !player.isShiftKeyDown() ? UseMode.CAPTURE : UseMode.LIBRARY;
    }

    private static void openLongTermMenu(ServerPlayer player) {
        if (ModNetworking.CHANNEL.isRemotePresent(player.connection.connection)) {
            PersistentMirrorManager.openStrandedLongTermMenu(player);
        }
    }
}
