package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Authoritative per-dimension rope store on the server.
 *
 * <p>
 * Ropes are indexed both by UUID and by chunk coverage. The chunk buckets are
 * the persistence format and also feed chunk-scoped sync packets, so long ropes
 * can be sent to every client watching any covered chunk without duplicating
 * the
 * canonical in-memory connection object.
 */
public final class SuperLeadSavedData extends SavedData {
    private static final Codec<RopeChunkBucket> BUCKET_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("chunk").forGetter(RopeChunkBucket::chunkKey),
            LeadConnection.CODEC.listOf().optionalFieldOf("owned", List.of()).forGetter(RopeChunkBucket::owned),
            UUIDUtil.CODEC.listOf().optionalFieldOf("refs", List.of()).forGetter(RopeChunkBucket::refs))
            .apply(instance, SuperLeadSavedData::ropeChunkBucket));

    public static final Codec<SuperLeadSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BUCKET_CODEC.listOf().optionalFieldOf("chunks", List.of()).forGetter(SuperLeadSavedData::bucketRecords))
            .apply(instance, SuperLeadSavedData::new));

    public static final SavedDataType<SuperLeadSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "lead_connections"),
            SuperLeadSavedData::new,
            CODEC);

    private static RopeChunkBucket ropeChunkBucket(Long chunkKey, List<LeadConnection> owned, List<UUID> refs) {
        return new RopeChunkBucket(java.util.Objects.requireNonNull(chunkKey, "chunkKey").longValue(), owned, refs);
    }

    private final Map<UUID, StoredRope> byId = new LinkedHashMap<>();
    private final EnumMap<LeadKind, LinkedHashMap<UUID, LeadConnection>> byKind = new EnumMap<>(LeadKind.class);
    private final Map<Long, List<LeadConnection>> ownedByChunk = new HashMap<>();
    private final Map<Long, Set<UUID>> refsByChunk = new HashMap<>();
    private final Set<Long> dirtyChunkKeys = new LinkedHashSet<>();

    public SuperLeadSavedData() {
    }

    public SuperLeadSavedData(List<RopeChunkBucket> buckets) {
        for (RopeChunkBucket bucket : buckets) {
            long ownerChunk = bucket.chunkKey();
            for (LeadConnection connection : bucket.owned()) {
                StoredRope stored = byId.get(connection.id());
                if (stored == null) {
                    stored = new StoredRope(connection, ownerChunk);
                    byId.put(connection.id(), stored);
                } else {
                    unindexByKind(stored.connection);
                }
                stored.connection = connection;
                stored.ownerChunk = ownerChunk;
                stored.coveredChunks.add(ownerChunk);
                indexByKind(connection);
                ownedByChunk.computeIfAbsent(ownerChunk, key -> new ArrayList<>()).add(connection);
            }
        }
        for (RopeChunkBucket bucket : buckets) {
            long chunk = bucket.chunkKey();
            for (UUID id : bucket.refs()) {
                StoredRope stored = byId.get(id);
                if (stored == null)
                    continue;
                stored.coveredChunks.add(chunk);
                if (chunk != stored.ownerChunk) {
                    refsByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(id);
                }
            }
        }
    }

    public static SuperLeadSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public static long chunkKey(ChunkPos chunk) {
        return chunkKey(chunk.x(), chunk.z());
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static ChunkPos chunkFromKey(long key) {
        return new ChunkPos((int) (key >> 32), (int) key);
    }

    public List<LeadConnection> connections() {
        ArrayList<LeadConnection> out = new ArrayList<>(byId.size());
        for (StoredRope stored : byId.values()) {
            out.add(stored.connection);
        }
        return Collections.unmodifiableList(out);
    }

    public List<LeadConnection> connectionsOfKind(LeadKind kind) {
        LinkedHashMap<UUID, LeadConnection> indexed = byKind.get(kind);
        return indexed == null || indexed.isEmpty() ? List.of() : List.copyOf(indexed.values());
    }

    public List<LeadConnection> connectionsForChunk(ChunkPos chunk) {
        long key = chunkKey(chunk);
        LinkedHashMap<UUID, LeadConnection> out = new LinkedHashMap<>();
        for (LeadConnection connection : ownedByChunk.getOrDefault(key, List.of())) {
            out.put(connection.id(), connection);
        }
        for (UUID id : refsByChunk.getOrDefault(key, Set.of())) {
            StoredRope stored = byId.get(id);
            if (stored != null) {
                out.put(id, stored.connection);
            }
        }
        return List.copyOf(out.values());
    }

    public Set<Long> chunksForConnection(UUID id) {
        StoredRope stored = byId.get(id);
        return stored == null ? Set.of() : Set.copyOf(stored.coveredChunks);
    }

    public Set<Long> allChunkKeys() {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        out.addAll(ownedByChunk.keySet());
        out.addAll(refsByChunk.keySet());
        return out;
    }

    public Set<Long> consumeDirtyChunkKeys() {
        if (dirtyChunkKeys.isEmpty())
            return Set.of();
        LinkedHashSet<Long> out = new LinkedHashSet<>(dirtyChunkKeys);
        dirtyChunkKeys.clear();
        return out;
    }

    public void add(LeadConnection connection) {
        put(connection, true);
    }

    public boolean removeIf(Predicate<LeadConnection> predicate) {
        ArrayList<UUID> removeIds = new ArrayList<>();
        for (StoredRope stored : byId.values()) {
            if (predicate.test(stored.connection)) {
                removeIds.add(stored.connection.id());
            }
        }
        if (removeIds.isEmpty()) {
            return false;
        }
        for (UUID id : removeIds) {
            remove(id);
        }
        setDirty();
        return true;
    }

    public Optional<LeadConnection> find(UUID id) {
        StoredRope stored = byId.get(id);
        return stored == null ? Optional.empty() : Optional.of(stored.connection);
    }

    public boolean update(UUID id, UnaryOperator<LeadConnection> updater, boolean markDirty) {
        StoredRope stored = byId.get(id);
        if (stored == null) {
            return false;
        }
        LeadConnection oldConnection = stored.connection;
        LeadConnection newConnection = updater.apply(oldConnection);
        if (newConnection.equals(oldConnection)) {
            return false;
        }
        long newOwner = ownerChunkKey(newConnection);
        LinkedHashSet<Long> newCovered = coveredChunkKeys(newConnection);
        newCovered.add(newOwner);
        if (newConnection.id().equals(id)
                && stored.ownerChunk == newOwner
                && stored.coveredChunks.equals(newCovered)) {
            replaceStoredConnection(stored, oldConnection, newConnection);
            markDirtyChunks(stored.coveredChunks);
            if (markDirty) {
                setDirty();
            }
            return true;
        }
        remove(id);
        put(newConnection, false);
        if (markDirty) {
            setDirty();
        }
        return true;
    }

    private void put(LeadConnection connection, boolean markDirty) {
        remove(connection.id());
        long owner = ownerChunkKey(connection);
        LinkedHashSet<Long> covered = coveredChunkKeys(connection);
        covered.add(owner);

        StoredRope stored = new StoredRope(connection, owner);
        stored.coveredChunks.addAll(covered);
        byId.put(connection.id(), stored);
        indexByKind(connection);

        List<LeadConnection> owned = ownedByChunk.computeIfAbsent(owner, key -> new ArrayList<>());
        owned.removeIf(c -> c.id().equals(connection.id()));
        owned.add(connection);

        for (long chunk : covered) {
            if (chunk == owner)
                continue;
            refsByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(connection.id());
        }
        markDirtyChunks(covered);
        if (markDirty) {
            setDirty();
        }
    }

    private void remove(UUID id) {
        StoredRope stored = byId.remove(id);
        if (stored == null) {
            return;
        }
        unindexByKind(stored.connection);
        List<LeadConnection> owned = ownedByChunk.get(stored.ownerChunk);
        if (owned != null) {
            owned.removeIf(c -> c.id().equals(id));
            if (owned.isEmpty()) {
                ownedByChunk.remove(stored.ownerChunk);
            }
        }
        for (long chunk : stored.coveredChunks) {
            Set<UUID> refs = refsByChunk.get(chunk);
            if (refs != null) {
                refs.remove(id);
                if (refs.isEmpty()) {
                    refsByChunk.remove(chunk);
                }
            }
        }
        markDirtyChunks(stored.coveredChunks);
    }

    private void markDirtyChunks(Set<Long> chunks) {
        dirtyChunkKeys.addAll(chunks);
    }

    private void indexByKind(LeadConnection connection) {
        byKind.computeIfAbsent(connection.kind(), key -> new LinkedHashMap<>())
                .put(connection.id(), connection);
    }

    private void unindexByKind(LeadConnection connection) {
        LinkedHashMap<UUID, LeadConnection> indexed = byKind.get(connection.kind());
        if (indexed == null) {
            return;
        }
        indexed.remove(connection.id());
        if (indexed.isEmpty()) {
            byKind.remove(connection.kind());
        }
    }

    private void replaceStoredConnection(StoredRope stored, LeadConnection oldConnection,
            LeadConnection newConnection) {
        stored.connection = newConnection;
        replaceOwnedConnection(stored.ownerChunk, newConnection);
        if (oldConnection.kind() != newConnection.kind()) {
            unindexByKind(oldConnection);
            indexByKind(newConnection);
        } else {
            byKind.computeIfAbsent(newConnection.kind(), key -> new LinkedHashMap<>())
                    .put(newConnection.id(), newConnection);
        }
    }

    private void replaceOwnedConnection(long ownerChunk, LeadConnection connection) {
        List<LeadConnection> owned = ownedByChunk.get(ownerChunk);
        if (owned == null) {
            ownedByChunk.computeIfAbsent(ownerChunk, key -> new ArrayList<>()).add(connection);
            return;
        }
        for (int i = 0; i < owned.size(); i++) {
            if (owned.get(i).id().equals(connection.id())) {
                owned.set(i, connection);
                return;
            }
        }
        owned.add(connection);
    }

    private List<RopeChunkBucket> bucketRecords() {
        ArrayList<Long> keys = new ArrayList<>(allChunkKeys());
        Collections.sort(keys);
        ArrayList<RopeChunkBucket> out = new ArrayList<>(keys.size());
        for (long key : keys) {
            List<LeadConnection> owned = ownedByChunk.getOrDefault(key, List.of());
            Set<UUID> refs = refsByChunk.getOrDefault(key, Set.of());
            out.add(new RopeChunkBucket(key, owned, List.copyOf(refs)));
        }
        return out;
    }

    private static long ownerChunkKey(LeadConnection connection) {
        return chunkKey(connection.from().pos().getX() >> 4, connection.from().pos().getZ() >> 4);
    }

    private static LinkedHashSet<Long> coveredChunkKeys(LeadConnection connection) {
        int x0 = connection.from().pos().getX() >> 4;
        int z0 = connection.from().pos().getZ() >> 4;
        int x1 = connection.to().pos().getX() >> 4;
        int z1 = connection.to().pos().getZ() >> 4;

        LinkedHashSet<Long> out = new LinkedHashSet<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            out.add(chunkKey(x, z));
            if (x == x1 && z == z1) {
                break;
            }
            int e2 = err * 2;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return out;
    }

    private record RopeChunkBucket(long chunkKey, List<LeadConnection> owned, List<UUID> refs) {
        private RopeChunkBucket {
            owned = List.copyOf(owned);
            refs = List.copyOf(refs);
        }
    }

    private static final class StoredRope {
        private LeadConnection connection;
        private long ownerChunk;
        private final Set<Long> coveredChunks = new LinkedHashSet<>();

        private StoredRope(LeadConnection connection, long ownerChunk) {
            this.connection = connection;
            this.ownerChunk = ownerChunk;
        }
    }
}
