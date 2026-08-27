package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.network.PersistentMirrorMenuPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.List;
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
    private static final Map<UUID, UUID> pendingCopySourceSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> pendingBackupSources = new ConcurrentHashMap<>();

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

    public static void clearPlayerTracking(UUID playerId) {
        playerToPersistentMirror.remove(playerId);
    }

    public static void clearTransientState() {
        playerToPersistentMirror.clear();
        pendingCopyCreators.clear();
        pendingCopySourceSessions.clear();
        pendingBackupSources.clear();
    }

    public record DeadDataRepairResult(int recordsRemoved, int worldsCleaned, int liveRecordsSkipped) {
    }

    public static DeadDataRepairResult repairDeadPersistentData(MinecraftServer server) {
        PersistentMirrorData data = PersistentMirrorData.get(server);
        int recordsRemoved = 0;
        int worldsCleaned = 0;
        int liveRecordsSkipped = 0;

        for (PersistentMirrorRecord record : List.copyOf(data.records())) {
            if (record.ready()) {
                continue;
            }

            ServerLevel persistentLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
            int playerCount = persistentLevel != null ? persistentLevel.players().size() : 0;
            if (playerCount > 0 || WorldCopyService.hasPendingPersistentCopy(record.dimensionIndex())) {
                liveRecordsSkipped++;
                continue;
            }

            pendingCopyCreators.remove(record.id());
            boolean backup = pendingBackupSources.remove(record.id()) != null;
            UUID sourceSessionId = pendingCopySourceSessions.remove(record.id());
            if (sourceSessionId == null && !backup) {
                sourceSessionId = record.sourceSessionId();
            }
            if (sourceSessionId != null) {
                MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSessionId, server);
            }
            WorldCopyService.cancelPersistentCopyTask(record.dimensionIndex());
            data.removeRecord(record.id());
            recordsRemoved++;

            if (persistentLevel != null) {
                WorldCopyService.cleanupPersistentMirrorWorld(persistentLevel, record.sourcePosition(), record.kind());
                worldsCleaned++;
            }
        }

        return new DeadDataRepairResult(recordsRemoved, worldsCleaned, liveRecordsSkipped);
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
        openMirrorMenu(player, heldKind, false, false);
    }

    public static void openMirrorMenu(ServerPlayer player, ItemStack heldStack) {
        if (!DimensionMirrorItem.hasPermanence(player.level(), heldStack)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanence_required"), true);
            return;
        }

        MirrorKind heldKind = DimensionMirrorItem.getMirrorKind(heldStack);
        boolean createSuperflatPersistentMirror = heldKind == MirrorKind.HEAVEN
                && DimensionMirrorItem.hasSuperflat(player.level(), heldStack);
        openMirrorMenu(player, heldKind, true, createSuperflatPersistentMirror);
    }

    private static void openMirrorMenu(ServerPlayer player, MirrorKind heldKind, boolean heldHasPermanence,
                                       boolean createSuperflatPersistentMirror) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        Optional<MirrorSession> temporarySession = MirrorWorldManager.getPlayerCurrentSession(player.getUUID());
        if (temporarySession.isPresent()) {
            sendTemporaryMirrorMenu(player, temporarySession.get(), heldKind, heldHasPermanence);
            return;
        }

        Optional<PersistentMirrorRecord> currentPersistent = getCurrentRecord(player);
        if (currentPersistent.isPresent()) {
            sendPersistentInsideMenu(player, currentPersistent.get());
            return;
        }

        if (createSuperflatPersistentMirror) {
            ensureSuperflatPersistentMirror(player, player.blockPosition().below());
        }

        sendPersistentListMenu(player, heldKind, PersistentMirrorData.get(server).records());
    }

    public static Optional<PersistentMirrorRecord> ensureSuperflatPersistentMirror(ServerPlayer player,
                                                                                    BlockPos sourcePosition) {
        MinecraftServer server = player.getServer();
        if (server == null || !MirrorConfig.canAccessMirrorKind(player, MirrorKind.HEAVEN)) {
            return Optional.empty();
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> existing = data.records().stream()
                .filter(record -> record.ownerId().equals(player.getUUID()))
                .filter(record -> record.kind() == MirrorKind.HEAVEN)
                .filter(record -> record.terrainMode() == MirrorTerrainMode.SUPERFLAT)
                .findFirst();
        if (existing.isPresent()) {
            return existing;
        }

        if (!canCreatePersistentMirror(player)) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.persistent.no_save_permission"), false);
            return Optional.empty();
        }

        int dimensionIndex = data.allocateDimensionIndex();
        if (dimensionIndex < 0) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.no_slots"), false);
            return Optional.empty();
        }

        ServerLevel sourceLevel = (ServerLevel) player.level();
        ServerLevel targetLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(dimensionIndex));
        if (targetLevel == null) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.persistent.dimension_not_loaded"), false);
            return Optional.empty();
        }

        UUID recordId = UUID.randomUUID();
        String name = "Superflat " + (data.records().size() + 1);
        PersistentMirrorRecord record = new PersistentMirrorRecord(
                recordId,
                player.getUUID(),
                null,
                name,
                MirrorKind.HEAVEN,
                dimensionIndex,
                sourceLevel.dimension(),
                sourcePosition,
                sourcePosition.above(),
                false,
                System.currentTimeMillis(),
                false,
                MirrorTerrainMode.SUPERFLAT
        );

        int queuePosition;
        try {
            data.addRecord(record);
            pendingCopyCreators.put(recordId, player.getUUID());
            queuePosition = WorldCopyService.queuePersistentWorldCopy(record, sourceLevel, targetLevel);
        } catch (RuntimeException exception) {
            data.removeRecord(recordId);
            pendingCopyCreators.remove(recordId);
            InstantWorldMirror.LOGGER.error("Failed to create automatic Superflat persistent mirror for {}",
                    player.getName().getString(), exception);
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.persistent.create_failed"), false);
            return Optional.empty();
        }

        player.sendSystemMessage(Component.translatable(
                "message.instantworldmirror.persistent.save_queued", name, queuePosition).withStyle(ChatFormatting.GREEN));
        return Optional.of(record);
    }

    public static void openStrandedLongTermMenu(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            sendPersistentListMenu(player, MirrorKind.STRANDED, PersistentMirrorData.get(server).records());
        }
    }

    private static void sendTemporaryMirrorMenu(ServerPlayer player, MirrorSession session, MirrorKind heldKind,
                                                boolean heldHasPermanence) {
        MirrorKind sessionKind = session.getKind();
        String statusKey = "";
        boolean showSaveButton = false;
        boolean showReturnButton = false;
        if (!session.isCopyComplete()) {
            statusKey = "message.instantworldmirror.persistent.copy_running";
        } else if (!heldHasPermanence) {
            statusKey = "message.instantworldmirror.permanence_required";
        } else if (heldKind != sessionKind) {
            statusKey = "message.instantworldmirror.permanent_type_mismatch";
        } else if (hasSavedPersistentRecord(player, session.getSessionId())) {
            statusKey = "message.instantworldmirror.persistent.already_saved";
        } else if (canCreatePersistentMirror(player)) {
            showSaveButton = true;
        } else {
            statusKey = "message.instantworldmirror.persistent.save.requires_grant";
        }

        if (session.isCopyComplete()) {
            showReturnButton = true;
        }

        PacketDistributor.sendToPlayer(player, PersistentMirrorMenuPacket.temporary(
                session.getKind().translationKey(),
                statusKey,
                showSaveButton,
                showReturnButton
        ));
    }

    private static void sendPersistentInsideMenu(ServerPlayer player, PersistentMirrorRecord record) {
        boolean canManage = canManageRecord(player, record);
        PacketDistributor.sendToPlayer(player, PersistentMirrorMenuPacket.inside(
                record.name(),
                record.selector(),
                canManage,
                canManage
        ));
    }

    private static void sendPersistentListMenu(ServerPlayer player, MirrorKind heldKind,
                                               Collection<PersistentMirrorRecord> records) {
        List<PersistentMirrorMenuPacket.Entry> entries = new java.util.ArrayList<>();
        if (!MirrorConfig.canAccessMirrorKind(player, heldKind)) {
            PacketDistributor.sendToPlayer(player, PersistentMirrorMenuPacket.list(heldKind.translationKey(), entries));
            return;
        }

        for (PersistentMirrorRecord record : records) {
            if (record.kind() != heldKind || !canEnterRecord(player, record)) {
                continue;
            }

            entries.add(new PersistentMirrorMenuPacket.Entry(
                    record.name(),
                    record.selector(),
                    record.ready(),
                    canManageRecord(player, record)
            ));
        }

        PacketDistributor.sendToPlayer(player, PersistentMirrorMenuPacket.list(heldKind.translationKey(), entries));
    }

    public static boolean saveCurrentTemporaryMirror(ServerPlayer player, String requestedName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        if (!canCreatePersistentMirror(player)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.no_save_permission"), false);
            return false;
        }

        Optional<MirrorSession> sessionOpt = MirrorWorldManager.getPlayerCurrentSession(player.getUUID());
        if (sessionOpt.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.enter_temporary_first"), false);
            return false;
        }

        MirrorSession session = sessionOpt.get();
        if (!session.hasPersistentAccess()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanent_session_required"), false);
            return false;
        }

        if (!session.isCopyComplete()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.temporary_copying"), false);
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        if (data.getRecordBySourceSession(session.getSessionId()).isPresent()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.already_saved"), false);
            return false;
        }

        int dimensionIndex = data.allocateDimensionIndex();
        if (dimensionIndex < 0) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.no_slots"), false);
            return false;
        }

        ServerLevel sourceMirrorWorld = server.getLevel(session.getMirrorDimension());
        ServerLevel targetMirrorWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(dimensionIndex));
        if (sourceMirrorWorld == null || targetMirrorWorld == null) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.dimension_not_loaded"), false);
            return false;
        }

        UUID recordId = UUID.randomUUID();
        MirrorKind kind = session.getKind();
        String name = sanitizeName(requestedName, defaultName(kind, data.records().size() + 1));
        PersistentMirrorRecord record = new PersistentMirrorRecord(
                recordId,
                player.getUUID(),
                session.getSessionId(),
                name,
                kind,
                dimensionIndex,
                session.getSourceDimension(),
                session.getSourcePosition(),
                session.getSourcePosition().above(),
                session.isSourceInWater(),
                System.currentTimeMillis(),
                false,
                session.getTerrainMode()
        );

        if (!MirrorWorldManager.retainTemporarySourceForPersistentSave(session)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.temporary_copying"), false);
            return false;
        }

        int queuePosition;
        try {
            data.addRecord(record);
            pendingCopyCreators.put(recordId, player.getUUID());
            pendingCopySourceSessions.put(recordId, session.getSessionId());
            queuePosition = WorldCopyService.queuePersistentWorldCopy(record, sourceMirrorWorld, targetMirrorWorld);
        } catch (RuntimeException e) {
            data.removeRecord(recordId);
            pendingCopyCreators.remove(recordId);
            pendingCopySourceSessions.remove(recordId);
            MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(session.getSessionId(), server);
            throw e;
        }

        player.sendSystemMessage(Component.translatable("message.instantworldmirror.persistent.save_queued", name, queuePosition)
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static synchronized boolean backupRecord(ServerPlayer player, UUID recordId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        PersistentMirrorRecord source = data.getRecord(recordId).orElse(null);
        if (source == null || !canManageRecord(player, source)) {
            player.displayClientMessage(Component.translatable(
                    "message.instantworldmirror.persistent.cannot_backup"), false);
            return false;
        }
        if (!source.ready()) {
            player.displayClientMessage(Component.translatable(
                    "message.instantworldmirror.persistent.backup_source_not_ready"), false);
            return false;
        }

        int dimensionIndex = data.allocateDimensionIndex();
        if (dimensionIndex < 0) {
            player.displayClientMessage(Component.translatable(
                    "message.instantworldmirror.persistent.no_slots"), false);
            return false;
        }
        ServerLevel sourceWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(source.dimensionIndex()));
        ServerLevel targetWorld = server.getLevel(ModDimensions.getPersistentMirrorWorld(dimensionIndex));
        if (sourceWorld == null || targetWorld == null) {
            player.displayClientMessage(Component.translatable(
                    "message.instantworldmirror.persistent.dimension_not_loaded"), false);
            return false;
        }

        UUID backupId = UUID.randomUUID();
        String name = BackupNameAllocator.next(
                source.name(), data.records().stream().map(PersistentMirrorRecord::name).toList(), 32);
        PersistentMirrorRecord backup = new PersistentMirrorRecord(
                backupId,
                source.ownerId(),
                UUID.randomUUID(),
                name,
                source.kind(),
                dimensionIndex,
                source.sourceDimension(),
                source.sourcePosition(),
                source.entryPosition(),
                source.sourceInWater(),
                System.currentTimeMillis(),
                false,
                source.terrainMode());
        int queuePosition;
        try {
            data.addRecord(backup);
            pendingCopyCreators.put(backupId, player.getUUID());
            pendingBackupSources.put(backupId, source.id());
            queuePosition = WorldCopyService.queuePersistentWorldBackup(backup, sourceWorld, targetWorld);
        } catch (RuntimeException e) {
            data.removeRecord(backupId);
            pendingCopyCreators.remove(backupId);
            pendingBackupSources.remove(backupId);
            throw e;
        }

        player.sendSystemMessage(Component.translatable(
                "message.instantworldmirror.persistent.backup_queued", name, queuePosition)
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static void handlePersistentCopyComplete(UUID recordId, MinecraftServer server) {
        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> recordOpt = data.getRecord(recordId);
        if (recordOpt.isEmpty()) {
            pendingCopyCreators.remove(recordId);
            boolean backup = pendingBackupSources.remove(recordId) != null;
            UUID sourceSessionId = pendingCopySourceSessions.remove(recordId);
            if (sourceSessionId != null && !backup) {
                MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSessionId, server);
            }
            return;
        }

        PersistentMirrorRecord record = recordOpt.get();
        boolean backup = pendingBackupSources.remove(recordId) != null;
        record.setReady(true);
        data.setDirty();
        UUID sourceSessionId = pendingCopySourceSessions.remove(recordId);
        if (sourceSessionId == null && !backup) {
            sourceSessionId = record.sourceSessionId();
        }
        if (sourceSessionId != null) {
            MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSessionId, server);
        }

        UUID creatorId = pendingCopyCreators.remove(recordId);
        if (creatorId != null) {
            ServerPlayer creator = server.getPlayerList().getPlayer(creatorId);
            if (creator != null) {
                creator.sendSystemMessage(Component.translatable(backup
                                ? "message.instantworldmirror.persistent.backup_created"
                                : "message.instantworldmirror.persistent.saved", record.name())
                        .withStyle(ChatFormatting.GREEN));
            }
        }

        InstantWorldMirror.LOGGER.info("Persistent mirror {} saved in slot {}", record.id(), record.dimensionIndex());
    }

    public static void recoverUnreadyPersistentMirrors(MinecraftServer server) {
        PersistentMirrorData data = PersistentMirrorData.get(server);
        List<PersistentMirrorRecord> unreadyRecords = data.removeUnreadyRecords();
        if (unreadyRecords.isEmpty()) {
            return;
        }

        for (PersistentMirrorRecord record : unreadyRecords) {
            pendingCopyCreators.remove(record.id());
            boolean backup = pendingBackupSources.remove(record.id()) != null;
            UUID sourceSessionId = pendingCopySourceSessions.remove(record.id());
            if (sourceSessionId == null && !backup) {
                sourceSessionId = record.sourceSessionId();
            }
            if (sourceSessionId != null) {
                MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSessionId, server);
            }
            WorldCopyService.cancelPersistentCopyTask(record.dimensionIndex());

            ServerLevel persistentLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
            if (persistentLevel != null) {
                WorldCopyService.cleanupPersistentMirrorWorld(persistentLevel, record.sourcePosition(), record.kind());
            }

            InstantWorldMirror.LOGGER.warn(
                    "Removed incomplete persistent mirror {} from slot {} after server restart or interrupted copy",
                    record.id(), record.dimensionIndex());
        }
    }

    public static boolean enterPersistentMirror(ServerPlayer player, UUID recordId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        Optional<PersistentMirrorRecord> recordOpt = PersistentMirrorData.get(server).getRecord(recordId);
        if (recordOpt.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.not_found"), false);
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        if (!record.ready()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.copying"), false);
            return false;
        }

        if (!MirrorConfig.canAccessMirrorKind(player, record.kind())) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.mirror_kind_restricted",
                            Component.translatable(record.kind().translationKey())),
                    false);
            return false;
        }

        if (!hasMatchingPermanenceMirror(player, record.kind())) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.permanence_required"), false);
            return false;
        }

        if (!canEnterRecord(player, record)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.cannot_enter"), false);
            return false;
        }

        if (MirrorWorldManager.hasActiveSession(player.getUUID()) || isInPersistentMirror(player)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.leave_current_first"), false);
            return false;
        }

        ServerLevel targetLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.dimension_not_loaded"), false);
            return false;
        }

        MirrorWorldManager.preparePlayerForMirrorEntry(player, record.kind(), true, record.terrainMode());
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
        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.entered", record.name()), true);
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
        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.left"), true);
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

        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.spawn_teleported"), true);
        return true;
    }

    public static void handlePersistentMirrorDeath(ServerPlayer player) {
        playerToPersistentMirror.remove(player.getUUID());
    }

    public static void handleExternalExit(ServerPlayer player) {
        playerToPersistentMirror.remove(player.getUUID());
        MirrorWorldManager.restorePlayerForMirrorExit(player);
        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.external_exit"), false);
    }

    public static boolean deleteRecord(ServerPlayer player, UUID recordId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> recordOpt = data.getRecord(recordId);
        if (recordOpt.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.not_found"), false);
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        if (!canManageRecord(player, record)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.cannot_delete"), false);
            return false;
        }
        if (pendingBackupSources.containsValue(record.id())) {
            player.displayClientMessage(Component.translatable(
                    "message.instantworldmirror.persistent.backup_source_in_use"), false);
            return false;
        }

        ServerLevel persistentLevel = server.getLevel(ModDimensions.getPersistentMirrorWorld(record.dimensionIndex()));
        pendingCopyCreators.remove(record.id());
        boolean backup = pendingBackupSources.remove(record.id()) != null;
        UUID sourceSessionId = pendingCopySourceSessions.remove(record.id());
        if (sourceSessionId == null && !backup) {
            sourceSessionId = record.sourceSessionId();
        }
        if (!record.ready() && sourceSessionId != null) {
            MirrorWorldManager.releaseTemporarySourceAfterPersistentSave(sourceSessionId, server);
        }
        WorldCopyService.cancelPersistentCopyTask(record.dimensionIndex());
        if (persistentLevel != null) {
            for (ServerPlayer other : persistentLevel.players().toArray(ServerPlayer[]::new)) {
                leavePersistentMirror(other);
            }
            WorldCopyService.cleanupPersistentMirrorWorld(persistentLevel, record.sourcePosition(), record.kind());
        }

        data.removeRecord(record.id());
        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.deleted", record.name()), false);
        return true;
    }

    public static boolean renameRecord(ServerPlayer player, UUID recordId, String requestedName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        PersistentMirrorData data = PersistentMirrorData.get(server);
        Optional<PersistentMirrorRecord> recordOpt = data.getRecord(recordId);
        if (recordOpt.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.not_found"), false);
            return false;
        }

        PersistentMirrorRecord record = recordOpt.get();
        if (!canManageRecord(player, record)) {
            player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.cannot_rename"), false);
            return false;
        }

        String name = sanitizeName(requestedName, record.name());
        record.setName(name);
        data.setDirty();
        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.renamed", name), false);
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

        player.displayClientMessage(Component.translatable("message.instantworldmirror.persistent.left"), true);
        return true;
    }

    private static boolean canManageRecord(ServerPlayer player, PersistentMirrorRecord record) {
        return canEnterRecord(player, record);
    }

    public static Collection<PersistentMirrorRecord> getAccessibleRecords(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return java.util.List.of();
        }

        return PersistentMirrorData.get(server).records().stream()
                .filter(record -> canEnterRecord(player, record))
                .toList();
    }

    public static Optional<PersistentMirrorRecord> resolveRecordSelector(ServerPlayer player, String selector) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }

        return PersistentMirrorData.get(server).getRecordBySelector(selector, record -> canEnterRecord(player, record));
    }

    private static boolean hasSavedPersistentRecord(ServerPlayer player, UUID sourceSessionId) {
        MinecraftServer server = player.getServer();
        return server != null && PersistentMirrorData.get(server).getRecordBySourceSession(sourceSessionId).isPresent();
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
        return labelFor(kind) + " " + index;
    }

    private static String labelFor(MirrorKind kind) {
        return kind.defaultName();
    }

    private static Component labelComponentFor(MirrorKind kind) {
        return Component.translatable(kind.translationKey());
    }
}
