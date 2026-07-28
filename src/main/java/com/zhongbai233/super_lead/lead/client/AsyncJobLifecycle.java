package com.zhongbai233.super_lead.lead.client;

/** Thread-safe lifecycle timestamps for one non-interrupting async rope solve. */
final class AsyncJobLifecycle {
    private enum State { QUEUED, RUNNING, FINISHED, CANCELLED_BEFORE_START }

    private final long submittedNanos;
    private State state = State.QUEUED;
    private long startedNanos;
    private long finishedNanos;
    private long cancelRequestedNanos;

    AsyncJobLifecycle(long submittedNanos) {
        this.submittedNanos = submittedNanos;
    }

    synchronized boolean tryStart(long nowNanos) {
        if (state != State.QUEUED) return false;
        startedNanos = nowNanos;
        state = State.RUNNING;
        return true;
    }

    synchronized void finish(long nowNanos) {
        if (state == State.RUNNING) {
            finishedNanos = nowNanos;
            state = State.FINISHED;
        }
    }

    /** Returns true only when this request cancelled work before its runnable started. */
    synchronized boolean requestCancel(long nowNanos) {
        if (cancelRequestedNanos == 0L) cancelRequestedNanos = nowNanos;
        if (state != State.QUEUED) return false;
        finishedNanos = nowNanos;
        state = State.CANCELLED_BEFORE_START;
        return true;
    }

    synchronized boolean inFlight() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    synchronized boolean finished() {
        return state == State.FINISHED || state == State.CANCELLED_BEFORE_START;
    }

    synchronized boolean cancelRequested() {
        return cancelRequestedNanos != 0L;
    }

    synchronized long queueWaitNanos() {
        return startedNanos == 0L ? 0L : Math.max(0L, startedNanos - submittedNanos);
    }

    synchronized long solveNanos() {
        return startedNanos == 0L || finishedNanos == 0L
                ? 0L : Math.max(0L, finishedNanos - startedNanos);
    }

    synchronized long cancelledRunningNanos() {
        if (startedNanos == 0L || finishedNanos == 0L || cancelRequestedNanos == 0L) return 0L;
        return Math.max(0L, finishedNanos - Math.max(startedNanos, cancelRequestedNanos));
    }

    long submittedNanos() {
        return submittedNanos;
    }
}
