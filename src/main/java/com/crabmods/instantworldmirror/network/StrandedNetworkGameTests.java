package com.crabmods.instantworldmirror.network;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(InstantWorldMirror.MODID)
@PrefixGameTestTemplate(false)
public final class StrandedNetworkGameTests {
    private static final String TEMPLATE = "mirror_lifecycle_empty";

    private StrandedNetworkGameTests() {
    }

    @GameTest(template = TEMPLATE, batch = "stranded_packet_round_trip", timeoutTicks = 40)
    public static void strandedMirrorPacketsPreserveEveryClientServerAction(GameTestHelper helper) {
        BlockPos target = new BlockPos(12, 64, -9);
        UUID snapshotId = UUID.randomUUID();

        StrandedCapturePromptPacket prompt = roundTrip(
                StrandedCapturePromptPacket.STREAM_CODEC, new StrandedCapturePromptPacket(target));
        helper.assertTrue(prompt.targetPos().equals(target),
                "Capture prompt packet must preserve the clicked block position");

        CreateStrandedSnapshotPacket create = roundTrip(
                CreateStrandedSnapshotPacket.STREAM_CODEC,
                new CreateStrandedSnapshotPacket(target, "Packet house"));
        helper.assertTrue(create.targetPos().equals(target) && "Packet house".equals(create.name()),
                "Create packet must preserve the target and player-entered snapshot name");

        OpenStrandedSnapshotPacket open = roundTrip(
                OpenStrandedSnapshotPacket.STREAM_CODEC,
                new OpenStrandedSnapshotPacket(target, snapshotId));
        helper.assertTrue(open.targetPos().equals(target) && open.snapshotId().equals(snapshotId),
                "Open packet must preserve the selected snapshot identity");

        OpenStrandedSnapshotResultPacket openResult = roundTrip(
                OpenStrandedSnapshotResultPacket.STREAM_CODEC,
                new OpenStrandedSnapshotResultPacket(snapshotId, true));
        helper.assertTrue(openResult.snapshotId().equals(snapshotId) && openResult.opened(),
                "Open result packet must acknowledge the selected snapshot and server outcome");

        DeleteStrandedSnapshotPacket delete = roundTrip(
                DeleteStrandedSnapshotPacket.STREAM_CODEC,
                new DeleteStrandedSnapshotPacket(target, snapshotId));
        helper.assertTrue(delete.targetPos().equals(target) && delete.snapshotId().equals(snapshotId),
                "Delete packet must preserve the confirmed snapshot identity");

        BackupStrandedSnapshotPacket backupSnapshot = roundTrip(
                BackupStrandedSnapshotPacket.STREAM_CODEC,
                new BackupStrandedSnapshotPacket(target, snapshotId));
        helper.assertTrue(backupSnapshot.targetPos().equals(target)
                        && backupSnapshot.snapshotId().equals(snapshotId),
                "World-slice backup packet must preserve the selected record identity");

        BackupPersistentMirrorPacket backupPersistent = roundTrip(
                BackupPersistentMirrorPacket.STREAM_CODEC,
                new BackupPersistentMirrorPacket("slot_3"));
        helper.assertTrue("slot_3".equals(backupPersistent.selector()),
                "Persistent backup packet must preserve the selected record selector");

        helper.assertTrue(roundTrip(
                        OpenStrandedSnapshotMenuPacket.STREAM_CODEC,
                        new OpenStrandedSnapshotMenuPacket()) != null,
                "Long-term menu must preserve the cross-save tab request");
        helper.assertTrue(roundTrip(
                        OpenPersistentMirrorLibraryPacket.STREAM_CODEC,
                        new OpenPersistentMirrorLibraryPacket()) != null,
                "Long-term menu must preserve the persistent-world tab request");

        List<StrandedSnapshotMenuPacket.Entry> entries = List.of(
                new StrandedSnapshotMenuPacket.Entry(snapshotId, "Available", 10, 123L, true, true),
                new StrandedSnapshotMenuPacket.Entry(UUID.randomUUID(), "Old version", 2, 456L, false, true));
        StrandedSnapshotMenuPacket menu = roundTrip(
                StrandedSnapshotMenuPacket.STREAM_CODEC,
                new StrandedSnapshotMenuPacket(target, entries));
        helper.assertTrue(menu.targetPos().equals(target)
                        && menu.entries().equals(entries)
                        && menu.entries().get(0).available()
                        && !menu.entries().get(1).available()
                        && menu.entries().stream().allMatch(StrandedSnapshotMenuPacket.Entry::backupAvailable),
                "Snapshot menu packet must preserve separate open and backup availability states");

        helper.succeed();
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, packet);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
