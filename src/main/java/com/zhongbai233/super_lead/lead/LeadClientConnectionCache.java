package com.zhongbai233.super_lead.lead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Client-side mirror of rope connections received from the server.
 *
 * <p>
 * The server owns authoritative rope data through {@link SuperLeadSavedData}.
 * The client receives chunk-scoped snapshots, so a long rope can appear in
 * multiple watched chunks at once. This cache keeps one canonical connection
 * per
 * UUID and a reference count for the chunks currently watching it. When a chunk
 * unload packet arrives, the reference count decides whether the rope should
 * disappear locally or remain visible because another watched chunk still
 * refers
 * to it.
 */
final class LeadClientConnectionCache {
    private static final Map<NetworkKey, List<LeadConnection>> CONNECTIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, LeadConnection>> CONNECTIONS_BY_ID = new HashMap<>();
    private static final Map<NetworkKey, Map<Long, Set<UUID>>> CHUNK_CONNECTIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, Integer>> CONNECTION_REFCOUNTS = new HashMap<>();

    private LeadClientConnectionCache() {
    }

    static List<LeadConnection> connections(Level level) {
        return CONNECTIONS.getOrDefault(NetworkKey.of(level), List.of());
    }

    static Optional<LeadConnection> find(Level level, UUID id) {
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.get(NetworkKey.of(level));
        return byId == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    static void replaceAll(Level level, List<LeadConnection> connections) {
        NetworkKey key = NetworkKey.of(level);
        CONNECTIONS.put(key, new ArrayList<>(connections));
        Map<UUID, LeadConnection> byId = new LinkedHashMap<>();
        for (LeadConnection connection : connections) {
            byId.put(connection.id(), connection);
        }
        CONNECTIONS_BY_ID.put(key, byId);
        CHUNK_CONNECTIONS.remove(key);
        CONNECTION_REFCOUNTS.remove(key);
    }

    static void applyChanges(Level level, List<UUID> removed, List<LeadConnection> upserts) {
        NetworkKey key = NetworkKey.of(level);
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> indexById(key));
        if (!removed.isEmpty()) {
            for (UUID id : removed) {
                byId.remove(id);
            }
        }

        for (LeadConnection upsert : upserts) {
            byId.put(upsert.id(), upsert);
        }
        rebuildConnectionList(key);
    }

    static void replaceChunk(Level level, ChunkPos chunk, List<LeadConnection> connections) {
        NetworkKey key = NetworkKey.of(level);
        long chunkKey = SuperLeadSavedData.chunkKey(chunk);
        Map<Long, Set<UUID>> byChunk = CHUNK_CONNECTIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, Integer> refCounts = CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> new LinkedHashMap<>());

        Set<UUID> oldIds = byChunk.remove(chunkKey);
        if (oldIds != null) {
            for (UUID id : oldIds) {
                decrementRef(byId, refCounts, id);
            }
        }

        LinkedHashSet<UUID> newIds = new LinkedHashSet<>();
        for (LeadConnection connection : connections) {
            if (!newIds.add(connection.id()))
                continue;
            byId.put(connection.id(), connection);
            refCounts.put(connection.id(), refCounts.getOrDefault(connection.id(), 0) + 1);
        }
        if (!newIds.isEmpty()) {
            byChunk.put(chunkKey, newIds);
        }
        pruneUnreferenced(key);
        rebuildConnectionList(key);
    }

    static void unloadChunk(Level level, ChunkPos chunk) {
        NetworkKey key = NetworkKey.of(level);
        Map<Long, Set<UUID>> byChunk = CHUNK_CONNECTIONS.get(key);
        if (byChunk == null) {
            return;
        }
        Set<UUID> oldIds = byChunk.remove(SuperLeadSavedData.chunkKey(chunk));
        if (oldIds == null || oldIds.isEmpty()) {
            return;
        }
        Map<UUID, Integer> refCounts = CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        for (UUID id : oldIds) {
            decrementRef(byId, refCounts, id);
        }
        pruneUnreferenced(key);
        rebuildConnectionList(key);
    }

    private static Map<UUID, LeadConnection> indexById(NetworkKey key) {
        Map<UUID, LeadConnection> out = new LinkedHashMap<>();
        for (LeadConnection connection : CONNECTIONS.getOrDefault(key, List.of())) {
            out.put(connection.id(), connection);
        }
        return out;
    }

    private static void decrementRef(Map<UUID, LeadConnection> byId, Map<UUID, Integer> refCounts, UUID id) {
        int next = refCounts.getOrDefault(id, 0) - 1;
        if (next <= 0) {
            refCounts.remove(id);
            byId.remove(id);
        } else {
            refCounts.put(id, next);
        }
    }

    private static void pruneUnreferenced(NetworkKey key) {
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.get(key);
        Map<UUID, Integer> refCounts = CONNECTION_REFCOUNTS.get(key);
        if (byId == null || refCounts == null) {
            return;
        }
        byId.keySet().removeIf(id -> refCounts.getOrDefault(id, 0) <= 0);
    }

    private static void rebuildConnectionList(NetworkKey key) {
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.get(key);
        CONNECTIONS.put(key, byId == null ? new ArrayList<>() : new ArrayList<>(byId.values()));
    }
}