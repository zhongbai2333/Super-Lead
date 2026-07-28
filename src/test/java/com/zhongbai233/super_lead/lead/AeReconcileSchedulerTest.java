package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AeReconcileSchedulerTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void failureUsesCappedBackoffAndDirtyRetry() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        scheduler.observe(1, List.of(new AeReconcileScheduler.Connection(ID, Set.of(10L))), 0);
        int jitter = (ID.hashCode() & 0x7fffffff) % 8;
        long tick = 0;
        for (int i = 0; i < AeReconcileScheduler.BACKOFF_TICKS.length; i++) {
            scheduler.failure(ID, tick);
            assertEquals(i + 1, scheduler.attempt(ID));
            tick += AeReconcileScheduler.BACKOFF_TICKS[i] + jitter;
            assertTrue(scheduler.due(tick, false).contains(ID));
        }
        scheduler.failure(ID, tick);
        assertEquals(AeReconcileScheduler.BACKOFF_TICKS.length, scheduler.attempt(ID));
    }

    @Test
    void successClearsRetryAndDirty() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        scheduler.observe(1, List.of(new AeReconcileScheduler.Connection(ID, Set.of(10L))), 0);
        scheduler.failure(ID, 0);
        scheduler.success(ID, 1);
        assertTrue(scheduler.due(1000, false).isEmpty());
        scheduler.markDirty(ID, 20);
        assertEquals(List.of(ID), scheduler.due(20, false));
    }

    @Test
    void userEnsureCannotBypassFailureBackoff() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        scheduler.observe(1, List.of(new AeReconcileScheduler.Connection(ID, Set.of(10L))), 0);
        assertTrue(scheduler.allowUserEnsure(ID, 0));
        scheduler.failure(ID, 0);
        assertTrue(!scheduler.allowUserEnsure(ID, 1));
        assertTrue(scheduler.allowUserEnsure(ID, AeReconcileScheduler.BACKOFF_TICKS[0]
                + ((ID.hashCode() & 0x7fffffff) % 8)));
    }

    @Test
    void generationAndChunkChangesScheduleOnlyRelevantIds() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        scheduler.observe(1, List.of(
                new AeReconcileScheduler.Connection(ID, Set.of(10L)),
                new AeReconcileScheduler.Connection(other, Set.of(20L))), 0);
        scheduler.success(ID, 0);
        scheduler.success(other, 0);
        scheduler.markDirtyChunk(10L, 5);
        assertEquals(List.of(ID), scheduler.due(5, false));
        scheduler.observe(1, List.of(
                new AeReconcileScheduler.Connection(ID, Set.of(10L)),
                new AeReconcileScheduler.Connection(other, Set.of(20L))), 6);
        assertEquals(List.of(ID), scheduler.due(6, false));
        scheduler.observe(2, List.of(new AeReconcileScheduler.Connection(ID, Set.of(10L))), 7);
        assertTrue(scheduler.due(7, false).contains(ID));
        assertTrue(!scheduler.due(107, false).contains(other));
    }

    @Test
    void clearRemovesDimensionState() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        scheduler.observe(1, List.of(new AeReconcileScheduler.Connection(ID, Set.of(10L))), 0);
        scheduler.clear();
        assertTrue(scheduler.due(0, false).isEmpty());
        assertEquals(Long.MIN_VALUE, scheduler.observedGeneration());
    }

    @Test
    void healthChecksRotateOnlySuccessfulConnections() {
        AeReconcileScheduler scheduler = new AeReconcileScheduler();
        UUID failed = UUID.fromString("00000000-0000-0000-0000-000000000002");
        scheduler.observe(1, List.of(
                new AeReconcileScheduler.Connection(ID, Set.of(10L)),
                new AeReconcileScheduler.Connection(failed, Set.of(20L))), 0);
        scheduler.success(ID, 0);
        scheduler.failure(failed, 0);
        assertEquals(List.of(ID), scheduler.due(1, true));
    }
}