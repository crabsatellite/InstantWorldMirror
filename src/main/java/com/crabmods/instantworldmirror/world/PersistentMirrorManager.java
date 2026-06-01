package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent mirror worlds independently from the temporary DimensionPool.
 */
public class PersistentMirrorManager {
    private static final Map<UUID, UUID> playerToPersistentMirror = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> pendingCopyCreators = new ConcurrentHashMap<>();

    public static boolean canCreatePersistentMirror(ServerPlayer player) {
        if (player.hasPermissions(3)) {
            return true;
        }
        MinecraftServer server = player.getServer();
        return server != null && PersistentMirrorData.get(server).hasCreationGrant(player.getUUID());
    }

    public static void setCreationGrant(MinecraftServer server, UUID playerId, boolean granted) {
        PersistentMirrorData.get(server).setCreationGrant(playerId, granted);
    }

    public static boolean isInPersistentMirror(ServerPlayer player) {
        return playerToPersistentMirror.containsKey(player.getUUID())
                || ModDimensions.isPersistentMirrorWorld(player.level().dimension());
    }

    public static boolean isPlayerInSandboxMirror(ServerPlayer player) {
        Optional<PersistentMirrorRecord> record = getCurrentRecord(player);
        return record.map(value -> value.kind().isSandbox()).orElse(false);
    }

    public static Optional<PersistentMirrorRecord> getCurrentRecord(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }

        UUID recordId = playerToPersistentMirror.get(player.getUUID());
        if (recordId != null) {
            return PersistentMirrorData.get(server).getRecord(recordId);
        }

        int dimensionIndex = ModDimensions.getPersistentMirrorWorldIndex(player.level().dimension());
        if (dimensionIndex >= 0) {
            return PersistentMirrorData.get(server).getRecordByDimensionIndex(dimensionIndex);
        }

        return Optional.empty();
    }

    public static ServerLevel getSourceLevelForPersistentDimension(MinecraftServer server, int dimensionIndex) {
        return PersistentMirrorData.get(server).getRecordByDimensionIndex(dimensionIndex)
                .map(PersistentMirrorRecord::sourceDimension)
                .map(server::getLevel)
                .orElse(server.overworld());
    }

    public static void openMirrorMenu(ServerPlayer player, MirrorKind heldKind) {
        openMirrorMenu(player, heldKind, false);
    }

    public static void openMirrorMenu(ServerPlayer player, ItemStack heldStack) {
        if (!DimensionMirrorItem.hasPermanence(player.level(), heldStack)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanence_required"), true);
            return;
        }

        openMirrorMenu(player, DimensionMirrorItem.getMirrorKind(heldStack), true);
    }

    private static void openMirrorMenu(ServerPlayer player, MirrorKind heldKind, boolean heldHasPermanence) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        player.sendSystemMessage(Component.literal("=== Mirror Menu ===").withStyle(ChatFormatting.GOLD));

        Optional<MirrorSession> temporarySession = MirrorWorldManager.getPlayerCurrentSession(player.getUUID());
        if (temporarySession.isPresent()) {
            showTemporaryMirrorMenu(player, temporarySession.get(), heldKind, heldHasPermanence);
            return;
        }

        Optional<PersistentMirrorRecord> currentPersistent = getCurrentRecord(player);
        if (currentPersistent.isPresent()) {
            showPersistentInsideMenu(player, currentPersistent.get());
            return;
        }

        showPersistentListMenu(player, heldKind, PersistentMirrorData.get(server).records());
    }

    private static void showTemporaryMirrorMenu(ServerPlayer player, MirrorSession session, MirrorKind heldKind,
                                                boolean heldHasPermanence) {
        player.sendSystemMessage(Component.literal("Current temporary mirror: " + labelFor(session.isSandboxMode()))
                .withStyle(ChatFormatting.GRAY));

        if (!session.isCopyComplete()) {
            player.sendSystemMessage(Component.literal("World copy is still running. Save after it completes.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        MirrorKind sessionKind = MirrorKind.fromSandboxMode(session.isSandboxMode());
        if (!heldHasPermanence) {
            player.sendSystemMessage(Component.translatable("message.instantworldmirror.permanence_required")
                    .withStyle(ChatFormatting.RED));
        } else if (heldKind != sessionKind) {
            player.sendSystemMessage(Component.translatable("message.instantworldmirror.permanent_type_mismatch")
                    .withStyle(ChatFormatting.RED));
        } else if (canCreatePersistentMirror(player)) {
            player.sendSystemMessage(button(
                    "[Save this mirror permanently]",
                    "/iwm persistent save",
                    "Copy this temporary mirror into a persistent slot.",
                    ChatFormatting.GREEN
            ));
        } else {
            player.sendSystemMessage(Component.literal("Permanent save requires an operator or a creation grant.")
                    .withStyle(ChatFormatting.RED));
        }

        player.sendSystemMessage(button(
                "[Return to original world]",
                "/iwm return",
                "Leave this temporary mirror.",
                ChatFormatting.AQUA
        ));
    }

    private static void showPersistentInsideMenu(ServerPlayer player, PersistentMirrorRecord record) {
        player.sendSystemMessage(Component.literal("Persistent mirror: " + record.name())
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(button(
                "[Leave persistent mirror]",
                "/iwm persistent leave",
                "Return to the position where you entered.",
                ChatFormatting.AQUA
        ));

        if (canManageRecord(player, record)) {
            player.sendSystemMessage(button(
                    "[Delete this persistent mirror]",
                    "/iwm persistent delete " + record.id(),
                    "Delete the saved record and clear this persistent slot.",
                    ChatFormatting.RED
            ));
        }
    }

    private static void showPersistentListMenu(ServerPlayer player, MirrorKind heldKind,
                                               Collection<PersistentMirrorRecord> records) {
        player.sendSystemMessage(Component.literal("Showing " + labelFor(heldKind.isSandbox()) + " records.")
                .withStyle(ChatFormatting.GRAY));

        int shown = 0;
        for (PersistentMirrorRecord record : records) {
            if (record.kind() != heldKind || !canEnterRecord(player, record)) {
                continue;
            }

            ChatFormatting color = record.ready() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
            String status = record.ready() ? "" : " (copying)";
            player.sendSystemMessage(button(
                    "[" + record.name() + status + "]",
                    "/iwm persistent enter " + record.id(),
                    "Enter this persistent mirror.",
                    color
            ));
            shown++;
        }

        if (shown == 0) {
            player.sendSystemMessage(Component.literal("No accessible persistent mirrors for this mirror type.")
                    .withStyle(ChatFormatting.YELLOW));
        }

        player.sendSystemMessage(Component.literal("Create a temporary portal first, enter it, then use this menu there to save it permanently.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    public static boolean saveCurrentTemporaryMirror(ServerPlayer player, String requestedName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        if (!canCreatePersistentMirror(player)) {
            player.displayClientMessage(Component.literal("You do not have permission to save persistent mirrors."), false);
            return false;
        }

        Optional<MirrorSession> sessionOpt = MirrorWorldManager.getPlayerCurrentSession(player.getUUID());
        if (sessionOpt.isEmpty()) {
            player.displayClientMessage(Component.literal("Enter a temporary mirror before saving it permanently."), false);
            return false;
        }

        MirrorSession session = sessionOpt.get();
        if (!session.hasPersistentAccess()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanent_session_required"), false);
            return false;
        }

        if (!session.isCopyComplete()) {
            player.displayClientMessage(Component.literal("The temporary mirror is still copying. Try again when it finishes."), false);
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        int dimensionIndex = data.allocateDimensionIndex();
        if (dimensionIndex < 0) {
            player.displayClientMessage(Component.literal("No persistent mirror slots are available."), false);
            return false;
        }

        ServerLevel sourceMirrorWorld = server.getLevel(session.getMirrorDimension());
        ServerLevel targetMirrorWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(dimensionIndex));
        if (sourceMirrorWorld == null || targetMirrorWorld == null) {
            player.displayClientMessage(Component.literal("Persistent mirror dimension is not loaded."), false);
            return false;
        }

        UUID recordId = UUID.randomUUID();
        MirrorKind kind = MirrorKind.fromSandboxMode(session.isSandboxMode());
        String name = sanitizeName(requestedName, defaultName(kind, data.records().size() + 1));
        PersistentMirrorRecord record = new PersistentMirrorRecord(
                recordId,
                player.getUUID(),
                name,
                kind,
                dimensionIndex,
                session.getSourceDimension(),
                session.getSourcePosition(),
                session.getSourcePosition().above(),
                session.isSourceInWater(),
                System.currentTimeMillis(),
                false
        );

        data.addRecord(record);
        pendingCopyCreators.put(recordId, player.getUUID());
        int queuePosition = WorldCopyService.queuePersistentWorldCopy(record, sourceMirrorWorld, targetMirrorWorld);

        player.sendSystemMessage(Component.literal("Persistent mirror save queued: " + name + " (#" + queuePosition + ")")
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static void handlePersistentCopyComplete(UUID recordId, MinecraftServer server) {
        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> recordOpt = data.getRecord(recordId);
        if (recordOpt.isEmpty()) {
            return;
        }

        PersistentMirrorRecord record = recordOpt.get();
        record.setReady(true);
        data.setDirty();

        UUID creatorId = pendingCopyCreators.remove(recordId);
        if (creatorId != null) {
            ServerPlayer creator = server.getPlayerList().getPlayer(creatorId);
            if (creator != null) {
                creator.sendSystemMessage(Component.literal("Persistent mirror saved: " + record.name())
                        .withStyle(ChatFormatting.GREEN));
            }
        }

        InstantWorldMirror.LOGGER.info("Persistent mirror {} saved in slot {}", record.id(), record.dimensionIndex());
    }

    public static boolean enterPersistentMirror(ServerPlayer player, UUID recordId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        Optional<PersistentMirrorRecord> recordOpt = PersistentMirrorData.get(server).getRecord(recordId);
        if (recordOpt.isEmpty()) {
            player.displayClientMessage(Component.literal("Persistent mirror not found."), false);
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        if (!record.ready()) {
            player.displayClientMessage(Component.literal("This persistent mirror is still copying."), false);
            return false;
        }

        if (!hasMatchingPermanenceMirror(player, record.kind())) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanence_required"), false);
            return false;
        }

        if (!canEnterRecord(player, record)) {
            player.displayClientMessage(Component.literal("You cannot enter this persistent mirror."), false);
            return false;
        }

        if (MirrorWorldManager.hasActiveSession(player.getUUID()) || isInPersistentMirror(player)) {
            player.displayClientMessage(Component.literal("Leave your current mirror session first."), false);
            return false;
        }

        ServerLevel targetLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
        if (targetLevel == null) {
            player.displayClientMessage(Component.literal("Persistent mirror dimension is not loaded."), false);
            return false;
        }

        MirrorWorldManager.preparePlayerForMirrorEntry(player, record.kind().isSandbox(), true);
        playerToPersistentMirror.put(player.getUUID(), record.id());

        BlockPos safePos = MirrorWorldManager.findMirrorLandingPosition(targetLevel, record.entryPosition(), record.sourceInWater());
        if (safePos == null) {
            safePos = record.entryPosition();
            MirrorWorldManager.clearMirrorLandingArea(targetLevel, safePos);
        }

        MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
        try {
            player.teleportTo(
                    targetLevel,
                    safePos.getX() + 0.5,
                    safePos.getY(),
                    safePos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
        } finally {
            MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
        }

        MirrorWorldManager.syncMirrorEffectsForPlayer(player, record.sourceDimension(), ModDimensions.getMirrorEffectsKey(targetLevel.dimension()));
        targetLevel.playSound(null, safePos, SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.5F, 1.0F);
        player.displayClientMessage(Component.literal("Entered persistent mirror: " + record.name()), true);
        return true;
    }

    public static boolean leavePersistentMirror(ServerPlayer player) {
        return leavePersistentMirror(player, null);
    }

    public static boolean leavePersistentMirrorFromPosition(ServerPlayer player, BlockPos portalPos) {
        return leavePersistentMirror(player, portalPos);
    }

    private static boolean leavePersistentMirror(ServerPlayer player, BlockPos portalPos) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        Optional<PersistentMirrorRecord> recordOpt = getCurrentRecord(player);
        if (recordOpt.isEmpty()) {
            return leavePersistentMirrorWithoutRecord(player, server);
        }

        PersistentMirrorRecord record = recordOpt.get();
        ServerLevel targetLevel;
        BlockPos targetPos;

        if (portalPos != null && record.kind() == MirrorKind.DIMENSION
                && !portalPos.closerThan(record.entryPosition(), 16.0)) {
            targetLevel = server.getLevel(record.sourceDimension());
            if (targetLevel == null) {
                targetLevel = server.overworld();
            }
            targetPos = MirrorWorldManager.findMirrorLandingPosition(targetLevel, portalPos, record.sourceInWater());
            if (targetPos == null) {
                targetPos = portalPos;
            }
        } else {
            targetLevel = MirrorWorldManager.getSavedOriginalLevel(player, server);
            targetPos = MirrorWorldManager.getSavedOriginalPosition(player);
        }

        if (targetLevel == null) {
            targetLevel = server.overworld();
        }
        if (targetPos == null) {
            targetPos = targetLevel.getSharedSpawnPos();
        }

        MirrorWorldManager.restorePlayerForMirrorExit(player);

        MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
        try {
            player.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
        } finally {
            MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
        }

        playerToPersistentMirror.remove(player.getUUID());
        MirrorWorldManager.clearMirrorEffectsForPlayer(player, ModDimensions.getMirrorEffectsKey(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex())));
        player.displayClientMessage(Component.literal("Left persistent mirror."), true);
        return true;
    }

    public static boolean teleportToMirrorSpawn(ServerPlayer player) {
        Optional<PersistentMirrorRecord> recordOpt = getCurrentRecord(player);
        if (recordOpt.isEmpty() || !(player.level() instanceof ServerLevel mirrorLevel)) {
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        BlockPos safePos = MirrorWorldManager.findMirrorLandingPosition(mirrorLevel, record.entryPosition(), record.sourceInWater());
        if (safePos == null) {
            safePos = record.entryPosition();
            MirrorWorldManager.clearMirrorLandingArea(mirrorLevel, safePos);
        }

        player.teleportTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        mirrorLevel.playSound(null, safePos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.0F);

        if (!player.isCreative()) {
            DimensionMirrorItem.applyCooldown(player, DimensionMirrorItem.findMirrorStack(player));
        }

        player.displayClientMessage(Component.literal("Teleported to persistent mirror entrance."), true);
        return true;
    }

    public static void handlePersistentMirrorDeath(ServerPlayer player) {
        playerToPersistentMirror.remove(player.getUUID());
    }

    public static void handleExternalExit(ServerPlayer player) {
        playerToPersistentMirror.remove(player.getUUID());
        MirrorWorldManager.restorePlayerForMirrorExit(player);
        player.displayClientMessage(Component.literal("You left a persistent mirror. Your state was restored."), false);
    }

    public static boolean deleteRecord(ServerPlayer player, UUID recordId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> recordOpt = data.getRecord(recordId);
        if (recordOpt.isEmpty()) {
            player.displayClientMessage(Component.literal("Persistent mirror not found."), false);
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        if (!canManageRecord(player, record)) {
            player.displayClientMessage(Component.literal("You cannot delete this persistent mirror."), false);
            return false;
        }

        ServerLevel persistentLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
        if (persistentLevel != null) {
            for (ServerPlayer other : persistentLevel.players().toArray(ServerPlayer[]::new)) {
                leavePersistentMirror(other);
            }
            WorldCopyService.cleanupPersistentMirrorWorld(persistentLevel, record.sourcePosition());
        }

        data.removeRecord(record.id());
        player.displayClientMessage(Component.literal("Deleted persistent mirror: " + record.name()), false);
        return true;
    }

    private static boolean canEnterRecord(ServerPlayer player, PersistentMirrorRecord record) {
        return player.hasPermissions(3) || record.ownerId().equals(player.getUUID());
    }

    private static boolean hasMatchingPermanenceMirror(ServerPlayer player, MirrorKind kind) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (DimensionMirrorItem.hasPermanence(player.level(), stack)
                    && DimensionMirrorItem.getMirrorKind(stack) == kind) {
                return true;
            }
        }
        return false;
    }

    private static boolean leavePersistentMirrorWithoutRecord(ServerPlayer player, MinecraftServer server) {
        ServerLevel targetLevel = MirrorWorldManager.getSavedOriginalLevel(player, server);
        BlockPos targetPos = MirrorWorldManager.getSavedOriginalPosition(player);
        if (targetLevel == null) {
            targetLevel = server.overworld();
        }
        if (targetPos == null) {
            targetPos = targetLevel.getSharedSpawnPos();
        }

        MirrorWorldManager.restorePlayerForMirrorExit(player);
        playerToPersistentMirror.remove(player.getUUID());

        MirrorWorldManager.markPlayerBeingTeleported(player.getUUID());
        try {
            player.teleportTo(
                    targetLevel,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
        } finally {
            MirrorWorldManager.unmarkPlayerBeingTeleported(player.getUUID());
        }

        player.displayClientMessage(Component.literal("Left persistent mirror."), true);
        return true;
    }

    private static boolean canManageRecord(ServerPlayer player, PersistentMirrorRecord record) {
        return canEnterRecord(player, record);
    }

    private static Component button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static String sanitizeName(String requestedName, String fallback) {
        if (requestedName == null || requestedName.isBlank()) {
            return fallback;
        }
        String trimmed = requestedName.trim();
        if (trimmed.length() > 32) {
            return trimmed.substring(0, 32);
        }
        return trimmed;
    }

    private static String defaultName(MirrorKind kind, int index) {
        return labelFor(kind.isSandbox()) + " " + index;
    }

    private static String labelFor(boolean sandboxMode) {
        return sandboxMode ? "Heaven Mirror" : "Dimensional Mirror";
    }
}
