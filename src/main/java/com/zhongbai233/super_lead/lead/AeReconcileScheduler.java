package com.zhongbai233.super_lead.lead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure server-side scheduling state; deliberately has no AE2 or Minecraft types. */
final class AeReconcileScheduler {
    static final long[] BACKOFF_TICKS = { 2, 4, 8, 16, 32, 64, 100 };
    private static final int HEALTH_BUDGET = 8;

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<Long, Set<UUID>> idsByChunk = new HashMap<>();
    private long observedGeneration = Long.MIN_VALUE;
    private int healthCursor;

    boolean observe(long generation, Collection<Connection> connections, long tick) {
        Map<UUID, Connection> incoming = new HashMap<>();
        for (Connection connection : connections) {
            incoming.put(connection.id(), connection);
        }
        boolean changed = generation != observedGeneration;
        observedGeneration = generation;
        for (UUID id : new ArrayList<>(states.keySet())) {
            if (!incoming.containsKey(id)) {
                remove(id);
                changed = true;
            }
        }
        for (Connection connection : incoming.values()) {
            State old = states.get(connection.id());
            if (old == null || !old.connection.equals(connection)) {
                if (old != null) {
                    unindex(old.connection);
                }
                State state = new State(connection);
                state.dirty = true;
                state.dueTick = tick;
                states.put(connection.id(), state);
                index(connection);
                changed = true;
            }
        }
        if (changed) {
            for (State state : states.values()) {
                state.dirty = true;
                state.dueTick = Math.min(state.dueTick, tick);
            }
        }
        return changed;
    }

    void markDirty(UUID id, long tick) {
        State state = states.get(id);
        if (state != null) {
            state.dirty = true;
            state.dueTick = tick;
            state.attempt = 0;
        }
    }

    void markDirtyChunk(long chunk, long tick) {
        for (UUID id : idsByChunk.getOrDefault(chunk, Set.of())) {
            markDirty(id, tick);
        }
    }

    List<UUID> due(long tick, boolean healthTick) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (State state : states.values()) {
            if (state.dirty && state.dueTick <= tick) {
                result.add(state.connection.id());
            }
        }
        if (healthTick && !states.isEmpty()) {
            List<UUID> ids = new ArrayList<>();
            for (State state : states.values()) {
                if (!state.dirty) {
                    ids.add(state.connection.id());
                }
            }
            Collections.sort(ids);
            int count = Math.min(HEALTH_BUDGET, ids.size());
            for (int i = 0; i < count; i++) {
                result.add(ids.get((healthCursor + i) % ids.size()));
            }
            if (!ids.isEmpty()) {
                healthCursor = (healthCursor + count) % ids.size();
            }
        }
        return List.copyOf(result);
    }

    void success(UUID id, long tick) {
        State state = states.get(id);
        if (state != null) {
            state.dirty = false;
            state.attempt = 0;
            state.dueTick = Long.MAX_VALUE;
        }
    }

    boolean allowUserEnsure(UUID id, long tick) {
        State state = states.get(id);
        return state == null || (state.dirty && state.dueTick <= tick);
    }

    void failure(UUID id, long tick) {
        State state = states.get(id);
        if (state == null) return;
        int attempt = Math.min(state.attempt, BACKOFF_TICKS.length - 1);
        state.attempt = Math.min(state.attempt + 1, BACKOFF_TICKS.length);
        state.dirty = true;
        state.dueTick = tick + BACKOFF_TICKS[attempt] + jitter(id);
    }

    void clear() {
        states.clear();
        idsByChunk.clear();
        observedGeneration = Long.MIN_VALUE;
        healthCursor = 0;
    }

    int attempt(UUID id) {
        State state = states.get(id);
        return state == null ? 0 : state.attempt;
    }

    long observedGeneration() {
        return observedGeneration;
    }

    private static int jitter(UUID id) {
        return (id.hashCode() & 0x7fffffff) % 8;
    }

    private void remove(UUID id) {
        State state = states.remove(id);
        if (state != null) unindex(state.connection);
    }

    private void index(Connection connection) {
        for (long chunk : connection.chunks()) {
            idsByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(connection.id());
        }
    }

    private void unindex(Connection connection) {
        for (long chunk : connection.chunks()) {
            Set<UUID> ids = idsByChunk.get(chunk);
            if (ids != null && (ids.remove(connection.id()) && ids.isEmpty())) idsByChunk.remove(chunk);
        }
    }

    record Connection(UUID id, Set<Long> chunks) {
        Connection {
            chunks = Set.copyOf(chunks);
        }
    }

    private static final class State {
        private final Connection connection;
        private boolean dirty;
        private int attempt;
        private long dueTick;

        private State(Connection connection) {
            this.connection = connection;
            this.dueTick = Long.MAX_VALUE;
        }
    }
}