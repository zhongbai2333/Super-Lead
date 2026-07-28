package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SuperLeadSavedDataTest {
    @Test
    void aeTopologyGenerationTracksOnlyLogicalTopology() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection ae = connection("00000000-0000-0000-0000-000000000032",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.AE_NETWORK);
        data.add(ae);
        long added = data.aeTopologyGeneration();

        data.update(ae.id(), old -> old.withPower(5).withPhysicsPreset("soft"), true);
        assertEquals(added, data.aeTopologyGeneration());
        data.update(ae.id(), old -> old.withTier(1), true);
        assertEquals(added + 1, data.aeTopologyGeneration());
        data.update(ae.id(), old -> old.withTier(2), true);
        assertEquals(added + 1, data.aeTopologyGeneration());
        data.update(ae.id(), old -> new LeadConnection(old.id(),
                new LeadAnchor(old.from().pos(), old.from().face(), new net.minecraft.world.phys.Vec3(0.2, 64.2, 0.2)),
                old.to(), old.kind(), old.power(), old.tier(), old.extractAnchor(), old.lengthUnits(),
                old.attachments(), old.physicsPreset(), old.manualPhysicsPreset(), old.adventureOwner()), true);
        assertEquals(added + 1, data.aeTopologyGeneration());
        data.update(ae.id(), old -> new LeadConnection(old.id(),
                new LeadAnchor(old.from().pos(), Direction.NORTH), old.to(), old.kind(), old.power(), old.tier(),
                old.extractAnchor(), old.lengthUnits(), old.attachments(), old.physicsPreset(),
                old.manualPhysicsPreset(), old.adventureOwner()), true);
        assertEquals(added + 2, data.aeTopologyGeneration());
        data.update(ae.id(), old -> old.withKind(LeadKind.NORMAL), true);
        assertEquals(added + 3, data.aeTopologyGeneration());
    }

        @Test
        void directReplacementCountsAeTopologyOnce() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                LeadConnection normal = connection("00000000-0000-0000-0000-000000000033",
                                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                data.add(normal);
                data.add(normal.withKind(LeadKind.AE_NETWORK));
                assertEquals(1L, data.aeTopologyGeneration());
                data.add(normal.withKind(LeadKind.AE_NETWORK).withPower(7));
                assertEquals(1L, data.aeTopologyGeneration());
                data.add(normal);
                assertEquals(2L, data.aeTopologyGeneration());
        }

    @Test
    void addTracksOwnedReferencedAndDirtyChunks() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000001",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.ITEM);

        data.add(connection);

        Set<Long> expectedChunks = Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(1, 0),
                SuperLeadSavedData.chunkKey(2, 0));
        assertEquals(expectedChunks, data.chunksForConnection(connection.id()));
        assertEquals(expectedChunks, data.allChunkKeys());
        assertEquals(expectedChunks, data.consumeDirtyChunkKeys());
        assertTrue(data.consumeDirtyChunkKeys().isEmpty());
    }

        @Test
        void dirtyChunkCanBeRequeuedForNextTickRetry() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                long chunkKey = SuperLeadSavedData.chunkKey(3, -2);

                data.markChunkDirty(chunkKey);
                assertTrue(data.hasDirtyChunks());
                assertEquals(Set.of(chunkKey), data.consumeDirtyChunkKeys());
                assertFalse(data.hasDirtyChunks());

                data.markChunkDirty(chunkKey);
                assertEquals(Set.of(chunkKey), data.consumeDirtyChunkKeys());
        }

        @Test
        void syncStreamRevisionOrdersSnapshotsAndUnloadTombstones() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                long firstSnapshotRevision = data.nextSyncRevision();
                long unloadRevision = data.nextSyncRevision();
                long rewatchRevision = data.nextSyncRevision();

                assertTrue(firstSnapshotRevision < unloadRevision);
                assertTrue(unloadRevision < rewatchRevision);
        }

    @Test
    void updateMarksOldAndNewCoveredChunksDirty() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000002",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);
        data.add(connection);
        data.consumeDirtyChunkKeys();

        data.update(connection.id(), old -> new LeadConnection(old.id(), old.from(),
                new LeadAnchor(new BlockPos(-32, 64, 0), Direction.UP), old.kind(), old.power(), old.tier(),
                old.extractAnchor(), old.lengthUnits(), old.attachments(), old.physicsPreset(),
                old.manualPhysicsPreset(), old.adventureOwner()), true);

        Set<Long> expectedDirty = Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(1, 0),
                SuperLeadSavedData.chunkKey(2, 0),
                SuperLeadSavedData.chunkKey(-1, 0),
                SuperLeadSavedData.chunkKey(-2, 0));
        assertEquals(expectedDirty, data.consumeDirtyChunkKeys());
        assertEquals(Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(-1, 0),
                SuperLeadSavedData.chunkKey(-2, 0)), data.chunksForConnection(connection.id()));
    }

    @Test
    void kindIndexUpdatesWhenConnectionKindChanges() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000003",
                new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), LeadKind.NORMAL);
        data.add(connection);

        data.update(connection.id(), old -> old.withKind(LeadKind.FLUID), true);

        assertTrue(data.connectionsOfKindFast(LeadKind.NORMAL).isEmpty());
        assertEquals(1, data.connectionsOfKindFast(LeadKind.FLUID).size());
    }

    @Test
    void lengthUnitsAreClamped() {
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000004",
                new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), LeadKind.NORMAL);

        assertEquals(LeadConnection.MIN_LENGTH_UNITS, connection.withLengthUnits(0).lengthUnits());
        assertEquals(LeadConnection.MAX_LENGTH_UNITS, connection.withLengthUnits(99).lengthUnits());
    }

    @Test
    void clientChunkMirrorKeepsOneConnectionForMultiChunkRope() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000005",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);

        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 1L, List.of(connection));
        ConnectionDelta secondReference = LeadClientConnectionCache.replaceChunk(
                key, 1L, epoch, 1L, List.of(connection));

        assertEquals(1, LeadClientConnectionCache.connections(key).size());
        assertTrue(secondReference.isEmpty());
    }

    @Test
    void staleSnapshotForSameChunkIsIgnored() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadConnection original = connection("00000000-0000-0000-0000-000000000009",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.REDSTONE);
        LeadConnection updated = original.withPower(15);

        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 10L, List.of(updated));
        ConnectionDelta stale = LeadClientConnectionCache.replaceChunk(
                key, 0L, epoch, 9L, List.of(original));

        assertTrue(stale.isEmpty());
        assertEquals(List.of(updated), LeadClientConnectionCache.connections(key));
    }

    @Test
    void staleSnapshotFromAnotherChunkCannotRollBackLongRope() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadConnection original = connection("00000000-0000-0000-0000-000000000010",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.REDSTONE);
        LeadConnection updated = original.withPower(15);

        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 10L, List.of(updated));
        ConnectionDelta staleOtherChunk = LeadClientConnectionCache.replaceChunk(
                key, 1L, epoch, 9L, List.of(original));

        assertTrue(staleOtherChunk.isEmpty());
        assertEquals(List.of(updated), LeadClientConnectionCache.connections(key));
        assertTrue(LeadClientConnectionCache.unloadChunk(key, 0L, epoch, 11L).isEmpty());
        assertEquals(List.of(updated), LeadClientConnectionCache.connections(key));
    }

    @Test
    void newerSnapshotFromAnotherChunkUpdatesOnlyThatRope() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadConnection target = connection("00000000-0000-0000-0000-000000000011",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.REDSTONE);
        LeadConnection unrelated = connection("00000000-0000-0000-0000-000000000012",
                new BlockPos(1, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 10L, List.of(target, unrelated));

        ConnectionDelta delta = LeadClientConnectionCache.replaceChunk(
                key, 1L, epoch, 11L, List.of(target.withPower(15)));

        assertEquals(Set.of(target.id()), delta.updatedIds());
        assertTrue(delta.addedIds().isEmpty());
        assertTrue(delta.removedIds().isEmpty());
        assertTrue(LeadClientConnectionCache.connections(key).contains(unrelated));
    }

    @Test
    void unloadingLastReferenceReturnsRemovedDelta() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000013",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 1L, List.of(connection));

        ConnectionDelta delta = LeadClientConnectionCache.unloadChunk(key, 0L, epoch, 2L);

        assertEquals(Set.of(connection.id()), delta.removedIds());
        assertTrue(LeadClientConnectionCache.connections(key).isEmpty());
    }

    @Test
    void staleUnloadCannotRemoveNewerChunkSnapshot() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000029",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 10L, List.of(connection));

        ConnectionDelta stale = LeadClientConnectionCache.unloadChunk(key, 0L, epoch, 9L);

        assertTrue(stale.isEmpty());
        assertEquals(List.of(connection), LeadClientConnectionCache.connections(key));
    }

    @Test
    void unloadTombstoneRejectsDelayedSnapshotAndAllowsNewWatch() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000030",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);
        LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 10L, List.of(connection));
        LeadClientConnectionCache.unloadChunk(key, 0L, epoch, 11L);

        ConnectionDelta delayed = LeadClientConnectionCache.replaceChunk(
                key, 0L, epoch, 10L, List.of(connection));
        ConnectionDelta rewatch = LeadClientConnectionCache.replaceChunk(
                key, 0L, epoch, 12L, List.of(connection));

        assertTrue(delayed.isEmpty());
        assertEquals(Set.of(connection.id()), rewatch.addedIds());
        assertEquals(List.of(connection), LeadClientConnectionCache.connections(key));
    }

    @Test
    void unloadBeforeAnySnapshotStillCreatesTombstone() {
        NetworkKey key = new NetworkKey(null, true);
        UUID epoch = UUID.randomUUID();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000031",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.beginSyncEpoch(key, epoch);

        LeadClientConnectionCache.unloadChunk(key, 0L, epoch, 5L);
        ConnectionDelta delayed = LeadClientConnectionCache.replaceChunk(
                key, 0L, epoch, 4L, List.of(connection));

        assertTrue(delayed.isEmpty());
        assertTrue(LeadClientConnectionCache.connections(key).isEmpty());
    }

    @Test
    void announcedEpochRejectsOldSessionAndAcceptsLowerNewRevision() {
        NetworkKey key = new NetworkKey(null, true);
        UUID oldEpoch = UUID.randomUUID();
        UUID newEpoch = UUID.randomUUID();
        LeadConnection oldConnection = connection("00000000-0000-0000-0000-000000000014",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadConnection newConnection = connection("00000000-0000-0000-0000-000000000015",
                new BlockPos(0, 64, 1), new BlockPos(8, 64, 1), LeadKind.ITEM);
        LeadClientConnectionCache.beginSyncEpoch(key, oldEpoch);
        LeadClientConnectionCache.replaceChunk(key, 0L, oldEpoch, 100L, List.of(oldConnection));

        LeadClientConnectionCache.beginSyncEpoch(key, newEpoch);
        ConnectionDelta rejected = LeadClientConnectionCache.replaceChunk(
                key, 0L, oldEpoch, 101L, List.of(oldConnection));
        ConnectionDelta accepted = LeadClientConnectionCache.replaceChunk(
                key, 0L, newEpoch, 1L, List.of(newConnection));

        assertTrue(rejected.isEmpty());
        assertEquals(Set.of(newConnection.id()), accepted.addedIds());
        assertEquals(List.of(newConnection), LeadClientConnectionCache.connections(key));
    }

        @Test
        void repeatedClearForSameEpochStillResetsClientMirror() {
                NetworkKey key = new NetworkKey(null, true);
                UUID epoch = UUID.randomUUID();
                LeadConnection connection = connection("00000000-0000-0000-0000-000000000016",
                                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                LeadClientConnectionCache.beginSyncEpoch(key, epoch);
                LeadClientConnectionCache.replaceChunk(key, 0L, epoch, 1L, List.of(connection));

                LeadClientConnectionCache.beginSyncEpoch(key, epoch);

                assertTrue(LeadClientConnectionCache.connections(key).isEmpty());
        }

            @Test
            void validationRequiresBothEndpointChunksWithoutLoadingThem() {
                LeadConnection connection = connection("00000000-0000-0000-0000-000000000017",
                        new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);

                assertTrue(SuperLeadNetwork.validationChunksLoaded(connection,
                        pos -> (pos.getX() >> 4) == 0 || (pos.getX() >> 4) == 2));
                assertTrue(!SuperLeadNetwork.validationChunksLoaded(connection,
                        pos -> (pos.getX() >> 4) == 0));
            }

            @Test
            void validationIncludesOutsideFaceChunkAtBoundary() {
                LeadConnection connection = new LeadConnection(
                        UUID.fromString("00000000-0000-0000-0000-000000000018"),
                        new LeadAnchor(new BlockPos(15, 64, 0), Direction.EAST),
                        new LeadAnchor(new BlockPos(8, 64, 0), Direction.UP), LeadKind.NORMAL,
                        0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(), LeadConnection.NO_PHYSICS_PRESET,
                        LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_ADVENTURE_OWNER);

                assertTrue(!SuperLeadNetwork.validationChunksLoaded(connection,
                        pos -> (pos.getX() >> 4) == 0));
                assertTrue(SuperLeadNetwork.validationChunksLoaded(connection,
                        pos -> (pos.getX() >> 4) == 0 || (pos.getX() >> 4) == 1));
            }

        @Test
        void validationStopsAtFirstUnavailablePosition() {
                LeadConnection connection = connection("00000000-0000-0000-0000-000000000019",
                                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);
                List<BlockPos> checked = new ArrayList<>();

                boolean loaded = SuperLeadNetwork.validationChunksLoaded(connection, pos -> {
                        checked.add(pos);
                        return false;
                });

                assertFalse(loaded);
                assertEquals(List.of(connection.from().pos()), checked);
        }

            @Test
            void validationQueueHonorsBudgetAndRotatesFairly() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                LeadConnection first = connection("00000000-0000-0000-0000-000000000020",
                        new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                LeadConnection second = connection("00000000-0000-0000-0000-000000000021",
                        new BlockPos(1, 64, 0), new BlockPos(9, 64, 0), LeadKind.NORMAL);
                LeadConnection third = connection("00000000-0000-0000-0000-000000000022",
                        new BlockPos(2, 64, 0), new BlockPos(10, 64, 0), LeadKind.NORMAL);
                data.add(first);
                data.add(second);
                data.add(third);

                assertEquals(List.of(first, second), data.pollValidationBatch(2));
                assertEquals(List.of(third, first), data.pollValidationBatch(2));
                assertTrue(data.pollValidationBatch(0).isEmpty());
            }

            @Test
            void removedConnectionsAreSkippedByValidationQueue() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                LeadConnection removed = connection("00000000-0000-0000-0000-000000000023",
                        new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                LeadConnection retained = connection("00000000-0000-0000-0000-000000000024",
                        new BlockPos(1, 64, 0), new BlockPos(9, 64, 0), LeadKind.NORMAL);
                data.add(removed);
                data.add(retained);
                data.removeIf(connection -> connection.id().equals(removed.id()));

                assertEquals(List.of(retained), data.pollValidationBatch(2));
            }

        @Test
        void removeAndReaddSameIdRequeuesLatestConnectionOnce() {
                SuperLeadSavedData data = new SuperLeadSavedData();
                LeadConnection original = connection("00000000-0000-0000-0000-000000000026",
                                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                LeadConnection replacement = new LeadConnection(original.id(), original.from(),
                                new LeadAnchor(new BlockPos(24, 64, 0), Direction.UP), LeadKind.ITEM,
                                0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(), LeadConnection.NO_PHYSICS_PRESET,
                                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_ADVENTURE_OWNER);
                data.add(original);
                data.removeIf(connection -> connection.id().equals(original.id()));
                data.add(replacement);

                assertEquals(List.of(replacement), data.pollValidationBatch(2));
                assertEquals(List.of(replacement), data.pollValidationBatch(2));
        }

        @Test
        void attachmentLimitRejectsAdditionalEntriesWithoutChangingConnection() {
                LeadConnection connection = connection("00000000-0000-0000-0000-000000000027",
                                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                RopeAttachment attachment = new RopeAttachment(UUID.randomUUID(), 0.5D,
                        net.minecraft.world.item.ItemStack.EMPTY, false, 1);
                List<RopeAttachment> attachments = new ArrayList<>(LeadConnection.MAX_ATTACHMENTS);
                for (int i = 0; i < LeadConnection.MAX_ATTACHMENTS; i++) {
                        attachments.add(attachment);
                }
                LeadConnection full = connection.withAttachments(attachments);

                assertEquals(full, full.addAttachment(attachment));
        }

        @Test
        void attachmentLimitTruncatesOversizedLegacyData() {
                LeadConnection connection = connection("00000000-0000-0000-0000-000000000028",
                                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
                RopeAttachment attachment = new RopeAttachment(UUID.randomUUID(), 0.5D,
                                net.minecraft.world.item.ItemStack.EMPTY, false, 1);
                List<RopeAttachment> attachments = new ArrayList<>(LeadConnection.MAX_ATTACHMENTS + 1);
                for (int i = 0; i <= LeadConnection.MAX_ATTACHMENTS; i++) {
                        attachments.add(attachment);
                }

                assertEquals(LeadConnection.MAX_ATTACHMENTS,
                                connection.withAttachments(attachments).attachments().size());
        }

            @Test
            void blockChangeFilterMatchesOnlyAnchorsAndOutsideFaces() {
                LeadConnection connection = new LeadConnection(
                        UUID.fromString("00000000-0000-0000-0000-000000000025"),
                        new LeadAnchor(new BlockPos(15, 64, 0), Direction.EAST),
                        new LeadAnchor(new BlockPos(8, 64, 0), Direction.UP), LeadKind.NORMAL,
                        0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(), LeadConnection.NO_PHYSICS_PRESET,
                        LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_ADVENTURE_OWNER);

                assertTrue(SuperLeadNetwork.blockChangeCanAffectConnection(connection.from().pos(), connection));
                assertTrue(SuperLeadNetwork.blockChangeCanAffectConnection(new BlockPos(16, 64, 0), connection));
                assertFalse(SuperLeadNetwork.blockChangeCanAffectConnection(new BlockPos(14, 64, 0), connection));
            }

        @Test
        void periodicValidationBudgetSpreadsOnePassAcrossTwentyTicks() {
                assertEquals(0, SuperLeadNetwork.periodicValidationBudget(0));
                assertEquals(1, SuperLeadNetwork.periodicValidationBudget(1));
                assertEquals(5, SuperLeadNetwork.periodicValidationBudget(100));
                assertEquals(64, SuperLeadNetwork.periodicValidationBudget(1280));
                assertEquals(64, SuperLeadNetwork.periodicValidationBudget(10_000));
        }

    @Test
    void clientFullSnapshotDeduplicatesMultiChunkRope() {
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000006",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);

        LeadClientConnectionCache.replaceAll(key, List.of(connection, connection));

        assertEquals(List.of(connection), LeadClientConnectionCache.connections(key));
    }

    @Test
    void clientRevisionChangesOnlyWhenCanonicalConnectionsChange() {
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000007",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.replaceAll(key, List.of());
        long emptyRevision = LeadClientConnectionCache.revision(key);

        LeadClientConnectionCache.replaceAll(key, List.of(connection));
        long populatedRevision = LeadClientConnectionCache.revision(key);
        LeadClientConnectionCache.replaceAll(key, List.of(connection, connection));

        assertTrue(populatedRevision > emptyRevision);
        assertEquals(populatedRevision, LeadClientConnectionCache.revision(key));
    }

    @Test
    void clearingClientMirrorReleasesConnectionsAndRevision() {
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000008",
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), LeadKind.NORMAL);
        LeadClientConnectionCache.replaceAll(key, List.of(connection));

        LeadClientConnectionCache.clearAll();

        assertTrue(LeadClientConnectionCache.connections(key).isEmpty());
        assertEquals(0L, LeadClientConnectionCache.revision(key));
        assertEquals(0L, LeadClientConnectionCache.endpointLayoutRevision(key));
    }

    private static LeadConnection connection(String id, BlockPos from, BlockPos to, LeadKind kind) {
        return new LeadConnection(UUID.fromString(id), new LeadAnchor(from, Direction.UP),
                new LeadAnchor(to, Direction.UP), kind, 0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(),
                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_PHYSICS_PRESET,
                LeadConnection.NO_ADVENTURE_OWNER);
    }

}
