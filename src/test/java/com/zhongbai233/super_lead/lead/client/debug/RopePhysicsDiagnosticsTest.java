package com.zhongbai233.super_lead.lead.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RopePhysicsDiagnosticsTest {
    @AfterEach
    void clear() {
        RopePhysicsDiagnostics.clear();
    }

    @Test
    void publishesCompletePhysicsSnapshot() {
        UUID slow = UUID.fromString("00000000-0000-0000-0000-000000000123");
        RopePhysicsDiagnostics.begin(100L);
        RopePhysicsDiagnostics.recordNeighborBuild(1_000_000L, 12, 3, 2, false);
        RopePhysicsDiagnostics.recordPhysicsState("budget");
        RopePhysicsDiagnostics.recordPhysicsState("async");
        RopePhysicsDiagnostics.recordSyncSolve(slow, 64, 2_000_000L);
        RopePhysicsDiagnostics.recordEntityQuery(500_000L, 4, 2);
        RopePhysicsDiagnostics.recordAsyncPrepare(250_000L, "capacity");
        RopePhysicsDiagnostics.finishPhysics(4_500_000L, 7, 4, 48, true, 2, 6);
        RopePhysicsDiagnostics.finishRelease(300_000L);

        RopePhysicsDiagnostics.Snapshot snapshot = RopePhysicsDiagnostics.snapshot();
        assertTrue(snapshot.available());
        assertEquals(100L, snapshot.tick());
        assertEquals(4.5D, snapshot.physicsMs(), 1.0e-9D);
        assertEquals(1, snapshot.budgetSkips());
        assertEquals(1, snapshot.asyncCapacity());
        assertEquals(slow, snapshot.slowestSyncId());
        assertEquals(2, snapshot.entityContacts());
        assertEquals(2, snapshot.neighborDroppedByCap());
        assertTrue(snapshot.deadlineExhausted());
    }

    @Test
    void publishesPhysicsWithoutGeometryReleaseCallback() {
        RopePhysicsDiagnostics.begin(101L);
        RopePhysicsDiagnostics.recordDeferredEntry();
        RopePhysicsDiagnostics.finishPhysics(2_500_000L, 3, 2, 8, false, 1, 1);

        RopePhysicsDiagnostics.Snapshot snapshot = RopePhysicsDiagnostics.snapshot();
        assertTrue(snapshot.available());
        assertEquals(101L, snapshot.tick());
        assertEquals(2.5D, snapshot.physicsMs(), 1.0e-9D);
        assertEquals(1, snapshot.deferredEntries());
        assertEquals(0.0D, snapshot.releaseMs(), 1.0e-9D);
        assertEquals(1, RopePhysicsDiagnostics.historySummary().samples());
    }

    @Test
    void releaseTimingUpdatesSnapshotWithoutPublishingHistoryTwice() {
        RopePhysicsDiagnostics.begin(102L);
        RopePhysicsDiagnostics.finishPhysics(2_000_000L, 1, 1, 8, false, 0, 0);
        assertEquals(1, RopePhysicsDiagnostics.historySummary().samples());

        RopePhysicsDiagnostics.finishRelease(400_000L);

        assertEquals(0.4D, RopePhysicsDiagnostics.snapshot().releaseMs(), 1.0e-9D);
        assertEquals(1, RopePhysicsDiagnostics.historySummary().samples());
    }

    @Test
    void asyncCompletionAfterPhysicsPublishFlowsIntoNextTick() {
        RopePhysicsDiagnostics.begin(103L);
        RopePhysicsDiagnostics.finishPhysics(1_000_000L, 1, 1, 8, false, 1, 1);

        RopePhysicsDiagnostics.recordAsyncWorker(200_000L, 900_000L, 0L, "completed");
        RopePhysicsDiagnostics.begin(104L);
        RopePhysicsDiagnostics.finishPhysics(1_500_000L, 1, 1, 8, false, 0, 1);

        RopePhysicsDiagnostics.Snapshot snapshot = RopePhysicsDiagnostics.snapshot();
        assertEquals(104L, snapshot.tick());
        assertEquals(1, snapshot.asyncCompleted());
        assertEquals(0.2D, snapshot.asyncQueueWaitMs(), 1.0e-9D);
        assertEquals(0.9D, snapshot.asyncSolveMs(), 1.0e-9D);
    }

    @Test
    void clearRemovesStaleSnapshot() {
        RopePhysicsDiagnostics.begin(1L);
        RopePhysicsDiagnostics.finishPhysics(1L, 0, 0, 0, false, 0, 0);
        RopePhysicsDiagnostics.finishRelease(1L);
        RopePhysicsDiagnostics.clear();

        assertFalse(RopePhysicsDiagnostics.snapshot().available());
    }

    @Test
    void rollingSummaryReportsAverageP95AndMax() {
        var summary = RopePhysicsDiagnostics.summarize(
                new long[] { 1_000_000L, 2_000_000L, 3_000_000L, 10_000_000L },
                new long[] { 100_000L, 200_000L, 300_000L, 1_000_000L }, 4);

        assertEquals(4.0D, summary.physicsAverageMs(), 1.0e-9D);
        assertEquals(10.0D, summary.physicsP95Ms(), 1.0e-9D);
        assertEquals(10.0D, summary.physicsMaxMs(), 1.0e-9D);
        assertEquals(0.4D, summary.entityAverageMs(), 1.0e-9D);
    }

    @Test
    void asyncCompletionBeforeBeginIsMergedIntoNextSnapshot() {
        RopePhysicsDiagnostics.recordAsyncWorker(100_000L, 800_000L, 300_000L, "cancelled");
        RopePhysicsDiagnostics.recordAsyncWorker(200_000L, 900_000L, 0L, "stale");

        RopePhysicsDiagnostics.begin(20L);
        RopePhysicsDiagnostics.finishPhysics(1L, 0, 0, 0, false, 0, 0);
        RopePhysicsDiagnostics.finishRelease(1L);

        RopePhysicsDiagnostics.Snapshot snapshot = RopePhysicsDiagnostics.snapshot();
        assertEquals(1, snapshot.asyncCancelled());
        assertEquals(1, snapshot.asyncStaleDiscard());
        assertEquals(0.3D, snapshot.asyncQueueWaitMs(), 1.0e-9D);
        assertEquals(1.7D, snapshot.asyncSolveMs(), 1.0e-9D);
        assertEquals(0.3D, snapshot.asyncCancelledRunningMs(), 1.0e-9D);
    }

    @Test
    void everyDeferredPathCanIncrementSnapshotCount() {
        RopePhysicsDiagnostics.begin(30L);
        RopePhysicsDiagnostics.recordDeferredEntry();
        RopePhysicsDiagnostics.recordDeferredEntry();
        RopePhysicsDiagnostics.recordSolvePass(100L);
        RopePhysicsDiagnostics.finishPhysics(1L, 2, 0, 2, true, 0, 0);
        RopePhysicsDiagnostics.finishRelease(1L);

        assertEquals(2, RopePhysicsDiagnostics.snapshot().deferredEntries());
    }
}