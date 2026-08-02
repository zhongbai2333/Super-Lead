package com.zhongbai233.super_lead.lead.client.debug;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Low-overhead, client-main-thread diagnostics for the most recent rope physics tick. */
public final class RopePhysicsDiagnostics {
    private static final int HISTORY_SIZE = 200;
    private static volatile Snapshot latest = Snapshot.EMPTY;
    private static Mutable current;
    private static Mutable publishedAwaitingRelease;
    private static final PendingAsync pendingAsync = new PendingAsync();
    private static final long[] physicsHistory = new long[HISTORY_SIZE];
    private static final long[] entityHistory = new long[HISTORY_SIZE];
    private static int historyCursor;
    private static int historyCount;

    private RopePhysicsDiagnostics() {
    }

    public static void begin(long tick) {
        publishedAwaitingRelease = null;
        current = new Mutable(tick);
        pendingAsync.drainInto(current);
    }

    public static void recordNeighborBuild(long nanos, int candidates, int narrowPhase,
            int droppedByCap, boolean truncated) {
        Mutable value = current;
        if (value == null)
            return;
        value.neighborNanos += Math.max(0L, nanos);
        value.neighborCandidates = candidates;
        value.neighborNarrowPhase = narrowPhase;
        value.neighborDroppedByCap = droppedByCap;
        value.neighborTruncated = truncated;
    }

    public static void recordPhysicsState(String state) {
        Mutable value = current;
        if (value == null || state == null)
            return;
        Integer previous = value.states.get(state);
        value.states.put(state, previous == null ? 1 : previous + 1);
        if (state.equals("budget") || state.endsWith("-budget"))
            value.budgetSkips++;
        if (state.equals("circuit-breaker") || state.endsWith("-circuit-breaker"))
            value.circuitBreakerSkips++;
    }

    public static void recordSyncSolve(UUID id, int nodes, long nanos) {
        Mutable value = current;
        if (value == null)
            return;
        long safe = Math.max(0L, nanos);
        value.syncSolves++;
        value.syncSolveNanos += safe;
        if (safe > value.slowestSyncNanos) {
            value.slowestSyncNanos = safe;
            value.slowestSyncId = id;
            value.slowestSyncNodes = nodes;
        }
    }

    public static void recordEntityQuery(long nanos, int rawEntities, int acceptedContacts) {
        Mutable value = current;
        if (value == null)
            return;
        value.entityQueries++;
        value.entityQueryNanos += Math.max(0L, nanos);
        value.entityRaw += Math.max(0, rawEntities);
        value.entityContacts += Math.max(0, acceptedContacts);
    }

    public static void recordAsyncPrepare(long nanos, String result) {
        Mutable value = current;
        if (value == null)
            return;
        value.asyncPrepareNanos += Math.max(0L, nanos);
        if ("submitted".equals(result))
            value.asyncSubmitted++;
        else if ("pending".equals(result))
            value.asyncPending++;
        else if ("capacity".equals(result))
            value.asyncCapacity++;
    }

    public static void recordAsyncWorker(long queueWaitNanos, long solveNanos,
            long cancelledRunningNanos, String result) {
        Mutable value = current;
        if (value == null) {
            pendingAsync.add(queueWaitNanos, solveNanos, cancelledRunningNanos, result);
        } else {
            addAsyncResult(value, queueWaitNanos, solveNanos, cancelledRunningNanos, result);
        }
    }

    public static void finishPhysics(long nanos, int entries, int budgetUsed, int budgetMax,
            boolean deadlineExhausted, int asyncRunning, int asyncRetained) {
        Mutable value = current;
        if (value == null)
            return;
        value.physicsNanos = Math.max(0L, nanos);
        value.entries = entries;
        value.budgetUsed = budgetUsed;
        value.budgetMax = budgetMax;
        value.deadlineExhausted = deadlineExhausted;
        value.asyncRunning = asyncRunning;
        value.asyncRetained = asyncRetained;
        // Publish the physics tick immediately. Geometry submission can be absent or
        // irregular, and diagnostics must be able to prove that physics continued
        // independently rather than freezing on the previous render snapshot.
        latest = value.snapshot();
        physicsHistory[historyCursor] = value.physicsNanos;
        entityHistory[historyCursor] = value.entityQueryNanos;
        historyCursor = (historyCursor + 1) % HISTORY_SIZE;
        historyCount = Math.min(HISTORY_SIZE, historyCount + 1);
        publishedAwaitingRelease = value;
        current = null;
    }

    public static void recordAsyncPendingAge(long nanos) {
        Mutable value = current;
        if (value != null) value.asyncOldestPendingNanos = Math.max(0L, nanos);
    }

    public static void recordMaintenance(long nanos, int entries) {
        Mutable value = current;
        if (value != null) {
            value.maintenanceNanos += Math.max(0L, nanos);
            value.maintenanceEntries = Math.max(0, entries);
        }
    }

    public static void recordSolvePass(long nanos) {
        Mutable value = current;
        if (value != null) {
            value.solvePassNanos += Math.max(0L, nanos);
        }
    }

    public static void recordDeferredEntry() {
        Mutable value = current;
        if (value != null) value.deferredEntries++;
    }

    public static void finishRelease(long nanos) {
        Mutable value = publishedAwaitingRelease;
        if (value == null)
            return;
        value.releaseNanos = Math.max(0L, nanos);
        latest = value.snapshot();
        publishedAwaitingRelease = null;
    }

    public static Snapshot snapshot() {
        return latest;
    }

    public static HistorySummary historySummary() {
        return summarize(physicsHistory, entityHistory, historyCount);
    }

    public static void clear() {
        current = null;
        publishedAwaitingRelease = null;
        latest = Snapshot.EMPTY;
        Arrays.fill(physicsHistory, 0L);
        Arrays.fill(entityHistory, 0L);
        historyCursor = 0;
        historyCount = 0;
        pendingAsync.clear();
    }

    private static void addAsyncResult(Mutable value, long queueWaitNanos, long solveNanos,
            long cancelledRunningNanos, String result) {
        value.asyncQueueWaitNanos += Math.max(0L, queueWaitNanos);
        value.asyncSolveNanos += Math.max(0L, solveNanos);
        value.asyncCancelledRunningNanos += Math.max(0L, cancelledRunningNanos);
        if ("completed".equals(result)) value.asyncCompleted++;
        else if ("failed".equals(result)) value.asyncFailed++;
        else if ("stale".equals(result)) value.asyncStaleDiscard++;
        else if ("cancelled".equals(result)) value.asyncCancelled++;
    }

    private static final class PendingAsync {
        long queueWaitNanos;
        long solveNanos;
        long cancelledRunningNanos;
        int completed;
        int failed;
        int stale;
        int cancelled;

        void add(long queueWait, long solve, long cancelledRunning, String result) {
            queueWaitNanos += Math.max(0L, queueWait);
            solveNanos += Math.max(0L, solve);
            cancelledRunningNanos += Math.max(0L, cancelledRunning);
            if ("completed".equals(result)) completed++;
            else if ("failed".equals(result)) failed++;
            else if ("stale".equals(result)) stale++;
            else if ("cancelled".equals(result)) cancelled++;
        }

        void drainInto(Mutable value) {
            value.asyncQueueWaitNanos += queueWaitNanos;
            value.asyncSolveNanos += solveNanos;
            value.asyncCancelledRunningNanos += cancelledRunningNanos;
            value.asyncCompleted += completed;
            value.asyncFailed += failed;
            value.asyncStaleDiscard += stale;
            value.asyncCancelled += cancelled;
            clear();
        }

        void clear() {
            queueWaitNanos = solveNanos = cancelledRunningNanos = 0L;
            completed = failed = stale = cancelled = 0;
        }
    }

    static HistorySummary summarize(long[] physics, long[] entity, int count) {
        int safeCount = Math.max(0, Math.min(count, Math.min(physics.length, entity.length)));
        if (safeCount == 0)
            return HistorySummary.EMPTY;
        long[] physicsCopy = Arrays.copyOf(physics, safeCount);
        long[] entityCopy = Arrays.copyOf(entity, safeCount);
        Arrays.sort(physicsCopy);
        Arrays.sort(entityCopy);
        long physicsSum = 0L;
        long entitySum = 0L;
        for (int i = 0; i < safeCount; i++) {
            physicsSum += physicsCopy[i];
            entitySum += entityCopy[i];
        }
        int p95Index = Math.min(safeCount - 1, Math.max(0, (int) Math.ceil(safeCount * 0.95D) - 1));
        return new HistorySummary(safeCount,
                physicsSum / (double) safeCount, physicsCopy[p95Index], physicsCopy[safeCount - 1],
                entitySum / (double) safeCount, entityCopy[p95Index], entityCopy[safeCount - 1]);
    }

    private static final class Mutable {
        final long tick;
        final Map<String, Integer> states = new LinkedHashMap<>();
        long physicsNanos;
        long neighborNanos;
        long syncSolveNanos;
        long slowestSyncNanos;
        long entityQueryNanos;
        long asyncQueueWaitNanos;
        long asyncSolveNanos;
        long asyncCancelledRunningNanos;
        long asyncOldestPendingNanos;
        long maintenanceNanos;
        long solvePassNanos;
        long releaseNanos;
        long asyncPrepareNanos;
        UUID slowestSyncId;
        int entries;
        int neighborCandidates;
        int neighborNarrowPhase;
        int neighborDroppedByCap;
        int syncSolves;
        int slowestSyncNodes;
        int entityQueries;
        int entityRaw;
        int entityContacts;
        int asyncSubmitted;
        int asyncPending;
        int asyncCapacity;
        int asyncRunning;
        int asyncRetained;
        int asyncCompleted;
        int asyncFailed;
        int asyncStaleDiscard;
        int asyncCancelled;
        int maintenanceEntries;
        int deferredEntries;
        int budgetUsed;
        int budgetMax;
        int budgetSkips;
        int circuitBreakerSkips;
        boolean neighborTruncated;
        boolean deadlineExhausted;

        Mutable(long tick) {
            this.tick = tick;
        }

        Snapshot snapshot() {
            return new Snapshot(tick, physicsNanos, neighborNanos, syncSolveNanos, slowestSyncNanos,
                    entityQueryNanos, asyncPrepareNanos, releaseNanos, slowestSyncId, entries,
                    neighborCandidates, neighborNarrowPhase, neighborDroppedByCap, syncSolves, slowestSyncNodes,
                    entityQueries, entityRaw, entityContacts, asyncSubmitted, asyncPending,
                    asyncCapacity, asyncRunning, asyncRetained, asyncCompleted, asyncFailed,
                    asyncStaleDiscard, asyncCancelled, budgetUsed, budgetMax,
                    budgetSkips, circuitBreakerSkips, neighborTruncated, deadlineExhausted,
                    Map.copyOf(states), asyncQueueWaitNanos, asyncSolveNanos,
                    asyncCancelledRunningNanos, asyncOldestPendingNanos,
                    maintenanceNanos, solvePassNanos, maintenanceEntries, deferredEntries);
        }
    }

    public record Snapshot(
            long tick,
            long physicsNanos,
            long neighborNanos,
            long syncSolveNanos,
            long slowestSyncNanos,
            long entityQueryNanos,
            long asyncPrepareNanos,
            long releaseNanos,
            UUID slowestSyncId,
            int entries,
            int neighborCandidates,
            int neighborNarrowPhase,
            int neighborDroppedByCap,
            int syncSolves,
            int slowestSyncNodes,
            int entityQueries,
            int entityRaw,
            int entityContacts,
            int asyncSubmitted,
            int asyncPending,
            int asyncCapacity,
            int asyncRunning,
            int asyncRetained,
            int asyncCompleted,
            int asyncFailed,
            int asyncStaleDiscard,
            int asyncCancelled,
            int budgetUsed,
            int budgetMax,
            int budgetSkips,
            int circuitBreakerSkips,
            boolean neighborTruncated,
            boolean deadlineExhausted,
            Map<String, Integer> states,
            long asyncQueueWaitNanos,
            long asyncSolveNanos,
            long asyncCancelledRunningNanos,
            long asyncOldestPendingNanos,
            long maintenanceNanos,
            long solvePassNanos,
            int maintenanceEntries,
            int deferredEntries) {
        static final Snapshot EMPTY = new Snapshot(Long.MIN_VALUE, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                null,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                false, false, Map.of(), 0L, 0L, 0L, 0L, 0L, 0L, 0, 0);

        public boolean available() {
            return tick != Long.MIN_VALUE;
        }

        public double physicsMs() {
            return millis(physicsNanos);
        }

        public double neighborMs() {
            return millis(neighborNanos);
        }

        public double syncSolveMs() {
            return millis(syncSolveNanos);
        }

        public double entityQueryMs() {
            return millis(entityQueryNanos);
        }

        public double asyncPrepareMs() {
            return millis(asyncPrepareNanos);
        }


        public double asyncQueueWaitMs() { return millis(asyncQueueWaitNanos); }
        public double asyncSolveMs() { return millis(asyncSolveNanos); }
        public double asyncCancelledRunningMs() { return millis(asyncCancelledRunningNanos); }
        public double asyncOldestPendingMs() { return millis(asyncOldestPendingNanos); }
        public double maintenanceMs() { return millis(maintenanceNanos); }
        public double solvePassMs() { return millis(solvePassNanos); }

        public double releaseMs() {
            return millis(releaseNanos);
        }

        public String slowestSyncSummary() {
            if (slowestSyncId == null)
                return "none";
            return String.format(Locale.ROOT, "%s %.3fms nodes=%d",
                    slowestSyncId, millis(slowestSyncNanos), slowestSyncNodes);
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0D;
        }
    }

    public record HistorySummary(
            int samples,
            double physicsAverageNanos,
            long physicsP95Nanos,
            long physicsMaxNanos,
            double entityAverageNanos,
            long entityP95Nanos,
            long entityMaxNanos) {
        static final HistorySummary EMPTY = new HistorySummary(0, 0.0D, 0L, 0L, 0.0D, 0L, 0L);

        public double physicsAverageMs() {
            return physicsAverageNanos / 1_000_000.0D;
        }

        public double physicsP95Ms() {
            return physicsP95Nanos / 1_000_000.0D;
        }

        public double physicsMaxMs() {
            return physicsMaxNanos / 1_000_000.0D;
        }

        public double entityAverageMs() {
            return entityAverageNanos / 1_000_000.0D;
        }

        public double entityP95Ms() {
            return entityP95Nanos / 1_000_000.0D;
        }

        public double entityMaxMs() {
            return entityMaxNanos / 1_000_000.0D;
        }
    }
}
