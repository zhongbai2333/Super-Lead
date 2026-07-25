package com.zhongbai233.super_lead.lead;

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
    private static final Map<NetworkKey, UUID> SYNC_EPOCHS = new HashMap<>();
    private static final Map<NetworkKey, Map<Long, Long>> CHUNK_REVISIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, Long>> CONNECTION_REVISIONS = new HashMap<>();
    private static final Map<NetworkKey, Long> REVISIONS = new HashMap<>();
    private static final Map<NetworkKey, Long> ENDPOINT_LAYOUT_REVISIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, EndpointLayoutIdentity>> ENDPOINT_LAYOUT_IDENTITIES = new HashMap<>();

    private LeadClientConnectionCache() {
    }

    static List<LeadConnection> connections(Level level) {
        return connections(NetworkKey.of(level));
    }

    static List<LeadConnection> connections(NetworkKey key) {
        return CONNECTIONS.getOrDefault(key, List.of());
    }

    static long revision(Level level) {
        return revision(NetworkKey.of(level));
    }

    static long revision(NetworkKey key) {
        return REVISIONS.getOrDefault(key, 0L);
    }

    static long endpointLayoutRevision(Level level) {
        return endpointLayoutRevision(NetworkKey.of(level));
    }

    static long endpointLayoutRevision(NetworkKey key) {
        return ENDPOINT_LAYOUT_REVISIONS.getOrDefault(key, 0L);
    }

    static void clearAll() {
        CONNECTIONS.clear();
        CONNECTIONS_BY_ID.clear();
        CHUNK_CONNECTIONS.clear();
        CONNECTION_REFCOUNTS.clear();
        SYNC_EPOCHS.clear();
        CHUNK_REVISIONS.clear();
        CONNECTION_REVISIONS.clear();
        REVISIONS.clear();
        ENDPOINT_LAYOUT_REVISIONS.clear();
        ENDPOINT_LAYOUT_IDENTITIES.clear();
    }

    static Optional<LeadConnection> find(Level level, UUID id) {
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.get(NetworkKey.of(level));
        return byId == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    static void replaceAll(Level level, List<LeadConnection> connections) {
        replaceAll(NetworkKey.of(level), connections);
    }

    static void replaceAll(NetworkKey key, List<LeadConnection> connections) {
        Map<UUID, LeadConnection> byId = new LinkedHashMap<>();
        for (LeadConnection connection : connections) {
            byId.put(connection.id(), connection);
        }
        CONNECTIONS_BY_ID.put(key, byId);
        // Full snapshots can contain the same long rope once for every watched
        // chunk it intersects. Keep the public list canonical just like the
        // chunk-delta path; otherwise static rope geometry overwrites by connection
        // UUID while baked attachments are appended once per duplicate source.
        rebuildConnectionList(key);
        CHUNK_CONNECTIONS.remove(key);
        CONNECTION_REFCOUNTS.remove(key);
        CHUNK_REVISIONS.remove(key);
        CONNECTION_REVISIONS.remove(key);
    }

    static void beginSyncEpoch(Level level, UUID epoch) {
        beginSyncEpoch(NetworkKey.of(level), epoch);
    }

    static void beginSyncEpoch(NetworkKey key, UUID epoch) {
        SYNC_EPOCHS.put(key, epoch);
        CONNECTIONS.remove(key);
        CONNECTIONS_BY_ID.remove(key);
        CHUNK_CONNECTIONS.remove(key);
        CONNECTION_REFCOUNTS.remove(key);
        CHUNK_REVISIONS.remove(key);
        CONNECTION_REVISIONS.remove(key);
        ENDPOINT_LAYOUT_IDENTITIES.remove(key);
        REVISIONS.put(key, REVISIONS.getOrDefault(key, 0L) + 1L);
        ENDPOINT_LAYOUT_REVISIONS.put(key, ENDPOINT_LAYOUT_REVISIONS.getOrDefault(key, 0L) + 1L);
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

    static ConnectionDelta replaceChunk(Level level, ChunkPos chunk, UUID epoch, long revision,
            List<LeadConnection> connections) {
        return replaceChunk(NetworkKey.of(level), chunk, epoch, revision, connections);
    }

    static ConnectionDelta replaceChunk(NetworkKey key, ChunkPos chunk, UUID epoch, long revision,
            List<LeadConnection> connections) {
        return replaceChunk(key, SuperLeadSavedData.chunkKey(chunk), epoch, revision, connections);
    }

    static ConnectionDelta replaceChunk(NetworkKey key, long chunkKey, UUID epoch, long revision,
            List<LeadConnection> connections) {
        if (!epoch.equals(SYNC_EPOCHS.get(key)) || revision < 0L) {
            return ConnectionDelta.EMPTY;
        }
        Map<Long, Long> chunkRevisions = CHUNK_REVISIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Long previousChunkRevision = chunkRevisions.get(chunkKey);
        if (previousChunkRevision != null && revision <= previousChunkRevision.longValue()) {
            return ConnectionDelta.EMPTY;
        }

        Map<Long, Set<UUID>> byChunk = CHUNK_CONNECTIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, Integer> refCounts = CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        Map<UUID, Long> connectionRevisions = CONNECTION_REVISIONS.computeIfAbsent(key, ignored -> new HashMap<>());

        Set<UUID> oldIds = byChunk.getOrDefault(chunkKey, Set.of());
        LinkedHashSet<UUID> newIds = new LinkedHashSet<>();
        for (LeadConnection connection : connections) {
            newIds.add(connection.id());
        }
        LinkedHashSet<UUID> affectedIds = new LinkedHashSet<>(oldIds);
        affectedIds.addAll(newIds);
        Map<UUID, LeadConnection> before = valuesForIds(byId, affectedIds);

        newIds.clear();
        for (LeadConnection connection : connections) {
            if (!newIds.add(connection.id()))
                continue;
            UUID id = connection.id();
            if (!oldIds.contains(id)) {
                refCounts.put(id, refCounts.getOrDefault(id, 0) + 1);
            }
            Long connectionRevision = connectionRevisions.get(id);
            if (!byId.containsKey(id) || connectionRevision == null || revision > connectionRevision.longValue()) {
                byId.put(id, connection);
                connectionRevisions.put(id, revision);
            }
        }
        for (UUID id : oldIds) {
            if (!newIds.contains(id)) {
                decrementRef(byId, refCounts, connectionRevisions, id);
            }
        }
        if (!newIds.isEmpty()) {
            byChunk.put(chunkKey, newIds);
        } else {
            byChunk.remove(chunkKey);
        }
        chunkRevisions.put(chunkKey, revision);
        pruneUnreferenced(key);
        rebuildConnectionList(key);
        return connectionDelta(before, byId, affectedIds);
    }

    static ConnectionDelta unloadChunk(Level level, ChunkPos chunk, UUID epoch, long revision) {
        return unloadChunk(NetworkKey.of(level), SuperLeadSavedData.chunkKey(chunk), epoch, revision);
    }

    static ConnectionDelta unloadChunk(NetworkKey key, long chunkKey, UUID epoch, long revision) {
        if (!epoch.equals(SYNC_EPOCHS.get(key)) || revision < 0L) {
            return ConnectionDelta.EMPTY;
        }
        Map<Long, Long> chunkRevisions = CHUNK_REVISIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Long previousChunkRevision = chunkRevisions.get(chunkKey);
        if (previousChunkRevision != null && revision <= previousChunkRevision.longValue()) {
            return ConnectionDelta.EMPTY;
        }
        // Keep the unload revision as a tombstone. A delayed snapshot at or below
        // this sequence must not resurrect an unwatched chunk.
        chunkRevisions.put(chunkKey, revision);
        Map<Long, Set<UUID>> byChunk = CHUNK_CONNECTIONS.get(key);
        if (byChunk == null) {
            return ConnectionDelta.EMPTY;
        }
        Set<UUID> oldIds = byChunk.remove(chunkKey);
        if (oldIds == null || oldIds.isEmpty()) {
            return ConnectionDelta.EMPTY;
        }
        Map<UUID, Integer> refCounts = CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        Map<UUID, Long> connectionRevisions = CONNECTION_REVISIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> before = valuesForIds(byId, oldIds);
        for (UUID id : oldIds) {
            decrementRef(byId, refCounts, connectionRevisions, id);
        }
        pruneUnreferenced(key);
        rebuildConnectionList(key);
        return connectionDelta(before, byId, oldIds);
    }

    private static Map<UUID, LeadConnection> indexById(NetworkKey key) {
        Map<UUID, LeadConnection> out = new LinkedHashMap<>();
        for (LeadConnection connection : CONNECTIONS.getOrDefault(key, List.of())) {
            out.put(connection.id(), connection);
        }
        return out;
    }

    private static void decrementRef(Map<UUID, LeadConnection> byId, Map<UUID, Integer> refCounts,
            Map<UUID, Long> connectionRevisions, UUID id) {
        int next = refCounts.getOrDefault(id, 0) - 1;
        if (next <= 0) {
            refCounts.remove(id);
            byId.remove(id);
            connectionRevisions.remove(id);
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
        List<LeadConnection> previous = CONNECTIONS.getOrDefault(key, List.of());
        List<LeadConnection> next = byId == null ? List.of() : List.copyOf(byId.values());
        CONNECTIONS.put(key, next);
        if (!previous.equals(next)) {
            REVISIONS.put(key, REVISIONS.getOrDefault(key, 0L) + 1L);
        }
        Map<UUID, EndpointLayoutIdentity> previousLayout = ENDPOINT_LAYOUT_IDENTITIES.getOrDefault(key, Map.of());
        Map<UUID, EndpointLayoutIdentity> nextLayout = endpointLayoutIdentities(next);
        ENDPOINT_LAYOUT_IDENTITIES.put(key, nextLayout);
        if (!previousLayout.equals(nextLayout)) {
            ENDPOINT_LAYOUT_REVISIONS.put(key, ENDPOINT_LAYOUT_REVISIONS.getOrDefault(key, 0L) + 1L);
        }
    }

    private static Map<UUID, LeadConnection> valuesForIds(Map<UUID, LeadConnection> source,
            Iterable<UUID> ids) {
        Map<UUID, LeadConnection> values = new LinkedHashMap<>();
        for (UUID id : ids) {
            LeadConnection connection = source.get(id);
            if (connection != null) {
                values.put(id, connection);
            }
        }
        return values;
    }

    private static ConnectionDelta connectionDelta(Map<UUID, LeadConnection> before,
            Map<UUID, LeadConnection> after, Iterable<UUID> affectedIds) {
        LinkedHashSet<UUID> added = new LinkedHashSet<>();
        LinkedHashSet<UUID> updated = new LinkedHashSet<>();
        LinkedHashSet<UUID> removed = new LinkedHashSet<>();
        for (UUID id : affectedIds) {
            LeadConnection previous = before.get(id);
            LeadConnection current = after.get(id);
            if (previous == null && current != null) {
                added.add(id);
            } else if (previous != null && current == null) {
                removed.add(id);
            } else if (previous != null && !previous.equals(current)) {
                updated.add(id);
            }
        }
        return added.isEmpty() && updated.isEmpty() && removed.isEmpty()
                ? ConnectionDelta.EMPTY
                : new ConnectionDelta(added, updated, removed);
    }

    private static Map<UUID, EndpointLayoutIdentity> endpointLayoutIdentities(List<LeadConnection> connections) {
        if (connections.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EndpointLayoutIdentity> identities = new HashMap<>(connections.size());
        for (LeadConnection connection : connections) {
            identities.put(connection.id(), EndpointLayoutIdentity.of(connection));
        }
        return Map.copyOf(identities);
    }

    private record EndpointLayoutIdentity(UUID id, LeadAnchor from, LeadAnchor to) {
        private static EndpointLayoutIdentity of(LeadConnection connection) {
            return new EndpointLayoutIdentity(connection.id(), connection.from(), connection.to());
        }
    }
}