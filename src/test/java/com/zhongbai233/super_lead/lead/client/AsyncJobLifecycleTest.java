package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AsyncJobLifecycleTest {
    @Test
    void queuedCancellationPreventsWorkerStart() {
        AsyncJobLifecycle lifecycle = new AsyncJobLifecycle(100L);

        assertTrue(lifecycle.requestCancel(120L));
        assertFalse(lifecycle.tryStart(130L));
        assertTrue(lifecycle.finished());
        assertFalse(lifecycle.inFlight());
    }

    @Test
    void runningCancellationWaitsForWorkerFinish() {
        AsyncJobLifecycle lifecycle = new AsyncJobLifecycle(100L);

        assertTrue(lifecycle.tryStart(110L));
        assertFalse(lifecycle.requestCancel(120L));
        assertTrue(lifecycle.inFlight());
        assertFalse(lifecycle.finished());
        lifecycle.finish(170L);

        assertTrue(lifecycle.finished());
        assertEquals(10L, lifecycle.queueWaitNanos());
        assertEquals(60L, lifecycle.solveNanos());
        assertEquals(50L, lifecycle.cancelledRunningNanos());
    }

    @Test
    void lifecycleCanOnlyStartOnce() {
        AsyncJobLifecycle lifecycle = new AsyncJobLifecycle(100L);

        assertTrue(lifecycle.tryStart(105L));
        assertFalse(lifecycle.tryStart(106L));
        lifecycle.finish(120L);
        assertFalse(lifecycle.tryStart(121L));
    }
}
