package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

public final class StaticRopeChunkRegistry {

    private static final StaticRopeChunkRegistry INSTANCE = new StaticRopeChunkRegistry();

    private static final double CHUNK_MESH_QUIET_MOTION_SQR = 4.0e-5D; // ~0.0063 block/tick
    private static final int CHUNK_MESH_QUIET_TICKS = 20;
    private static final int CHUNK_MESH_LINGER_TICKS = 3;

    public static StaticRopeChunkRegistry get() {
        return INSTANCE;
    }

    private volatile Map<Long, List<RopeSectionSnapshot>> bySection = Map.of();
    private volatile Set<UUID> claimed = Set.of();
    private volatile Map<UUID, Long> claimTick = Map.of();
    private Set<UUID> claimedFromSim = Set.of();
    private final Map<UUID, Set<Long>> connectionSections = new HashMap<>();

    private List<LeadConnection> realSources = List.of();
    private List<StressSource> stressSources = List.of();
    /** Optional accessor injected by client startup so that connection-sync paths (which only
     *  carry the connection list) can still rebuild the chunk-mesh cache using actual rope
     *  simulation state. Without this, sync events would re-bake every rope from its anchors
     *  via {@code RopeStaticGeometry.build}, briefly snapping the world's ropes back to their
     *  no-physics catenary shape. */
    private volatile Function<UUID, RopeSimulation> simLookup = null;

    private StaticRopeChunkRegistry() {}

    public List<RopeSectionSnapshot> snapshotsFor(long sectionPosLong) {
        return bySection.getOrDefault(sectionPosLong, Collections.emptyList());
    }

    public Set<Long> publishedSectionKeys() {
        return bySection.keySet();
    }

    public boolean isClaimed(UUID connectionId) {
        return claimed.contains(connectionId);
    }

    public boolean shouldDynamicLinger(UUID connectionId, long currentTick) {
        Long t = claimTick.get(connectionId);
        if (t == null) return false;
        return currentTick - t < CHUNK_MESH_LINGER_TICKS;
    }

    public boolean isActive() {
        return !claimed.isEmpty();
    }

    public int claimedCount() {
        return claimed.size();
    }

    public int sectionCount() {
        return bySection.size();
    }

    public int sectionSnapshotsTotal() {
        int sum = 0;
        for (List<RopeSectionSnapshot> list : bySection.values()) sum += list.size();
        return sum;
    }

    public int claimedNodesTotal() {
        int sum = 0;
        for (Map.Entry<UUID, Set<Long>> e : connectionSections.entrySet()) {
            Set<Long> keys = e.getValue();
            if (keys.isEmpty()) continue;
            long anyKey = keys.iterator().next();
            List<RopeSectionSnapshot> list = bySection.get(anyKey);
            if (list == null) continue;
            for (RopeSectionSnapshot s : list) {
                if (s.connectionId.equals(e.getKey())) { sum += s.nodeCount; break; }
            }
        }
        return sum;
    }

    public synchronized void clear() {
        if (bySection.isEmpty() && claimed.isEmpty() && stressSources.isEmpty()) return;
        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        bySection = Map.of();
        claimed = Set.of();
        claimTick = Map.of();
        claimedFromSim = Set.of();
        connectionSections.clear();
        stressSources = List.of();
        markSectionsDirty(toDirty);
    }

    public synchronized void clearStressSources(Level level) {
        if (stressSources.isEmpty()) return;
        stressSources = List.of();
        rebuild(level);
    }

    public synchronized void onConnectionsReplaced(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide()) return;
        realSources = List.copyOf(connections);
        // Use the injected sim lookup when available so existing ropes keep their physics
        // shape across connection-sync events (otherwise everything baked through
        // RopeStaticGeometry.build would briefly flash to its no-physics catenary form).
        rebuildInternal(level, simLookup);
    }

    /**
     * Inject a sim lookup so connection-sync paths can rebuild the chunk-mesh cache against
     * live {@link RopeSimulation} state. Call once at client startup with {@code SIMS::get}.
     */
    public synchronized void setSimLookup(Function<UUID, RopeSimulation> lookup) {
        this.simLookup = lookup;
    }

    public synchronized void publishStressSources(Level level, List<StressSource> sources) {
        if (level == null || !level.isClientSide()) return;
        stressSources = List.copyOf(sources);
        rebuild(level);
    }

    public synchronized void invalidateAll(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide()) return;
        realSources = List.copyOf(connections);
        rebuild(level);
    }

    public synchronized void invalidateConnection(Level level, UUID connectionId) {
        if (level == null || !level.isClientSide() || connectionId == null) return;
        if (!claimed.contains(connectionId) && !connectionSections.containsKey(connectionId)) return;

        Set<Long> dirty = new HashSet<>(connectionSections.getOrDefault(connectionId, Set.of()));
        if (dirty.isEmpty()) dirty.addAll(bySection.keySet());

        Map<Long, List<RopeSectionSnapshot>> nextBySection = new HashMap<>(bySection.size());
        for (Map.Entry<Long, List<RopeSectionSnapshot>> entry : bySection.entrySet()) {
            List<RopeSectionSnapshot> nextList = new ArrayList<>(entry.getValue().size());
            for (RopeSectionSnapshot snapshot : entry.getValue()) {
                if (!snapshot.connectionId.equals(connectionId)) nextList.add(snapshot);
            }
            if (!nextList.isEmpty()) nextBySection.put(entry.getKey(), List.copyOf(nextList));
        }

        Set<UUID> nextClaimed = new HashSet<>(claimed);
        nextClaimed.remove(connectionId);
        Set<UUID> nextClaimedFromSim = new HashSet<>(claimedFromSim);
        nextClaimedFromSim.remove(connectionId);
        Map<UUID, Long> nextClaimTick = new HashMap<>(claimTick);
        nextClaimTick.remove(connectionId);

        bySection = Map.copyOf(nextBySection);
        claimed = Set.copyOf(nextClaimed);
        claimedFromSim = Set.copyOf(nextClaimedFromSim);
        claimTick = Map.copyOf(nextClaimTick);
        connectionSections.remove(connectionId);
        markSectionsDirty(dirty);
    }

    public synchronized void rebuildFromCache(Level level) {
        if (level == null || !level.isClientSide()) return;
        rebuild(level);
    }

    public synchronized void tickMaintain(Level level, Function<UUID, RopeSimulation> simLookup) {
        if (level == null || !level.isClientSide() || simLookup == null) return;
        boolean enabled = ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();
        Set<UUID> desired = new HashSet<>();
        Set<UUID> desiredFromSim = new HashSet<>();
        if (enabled) {
            for (LeadConnection c : realSources) {
                if (!isEligible(c)) continue;
                RopeSimulation sim = simLookup.apply(c.id());
                if (sim != null) {
                    if (!isQuiescent(sim)) continue;
                    desired.add(c.id());
                    desiredFromSim.add(c.id());
                } else {
                    desired.add(c.id());
                }
            }
            for (StressSource s : stressSources) {
                desired.add(s.id());
            }
        }
        if (desired.equals(claimed) && desiredFromSim.equals(claimedFromSim)) return;
        rebuildInternal(level, simLookup);
    }

    private void rebuild(Level level) {
        rebuildInternal(level, null);
    }

    private void rebuildInternal(Level level, Function<UUID, RopeSimulation> simLookup) {
        if (level == null) {
            if (bySection.isEmpty()) return;
            Set<Long> toDirty = new HashSet<>(bySection.keySet());
            bySection = Map.of();
            claimed = Set.of();
            claimTick = Map.of();
            claimedFromSim = Set.of();
            connectionSections.clear();
            markSectionsDirty(toDirty);
            return;
        }
        boolean enabled = ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();

        Map<Long, List<RopeSectionSnapshot>> nextBySection = new HashMap<>();
        Set<UUID> nextClaimed = new HashSet<>();
        Set<UUID> nextClaimedFromSim = new HashSet<>();
        Map<UUID, Set<Long>> nextConnSections = new HashMap<>();

        if (enabled) {
            for (LeadConnection c : realSources) {
                if (!isEligible(c)) continue;
                RopeSimulation sim = simLookup == null ? null : simLookup.apply(c.id());
                RopeStaticGeometryResult r;
                if (sim != null) {
                    if (!isQuiescent(sim)) continue;
                    r = RopeStaticGeometry.buildFromSim(c, sim, level);
                    if (r.snapshot != null && !r.sectionKeys.isEmpty()) {
                        nextClaimedFromSim.add(c.id());
                    }
                } else {
                    r = RopeStaticGeometry.build(c, level);
                }
                if (r.snapshot == null || r.sectionKeys.isEmpty()) continue;
                nextClaimed.add(c.id());
                nextConnSections.put(c.id(), r.sectionKeys);
                addSnapshots(nextBySection, r);
            }
            for (StressSource s : stressSources) {
                RopeStaticGeometryResult r = RopeStaticGeometry.build(s.id(), s.a(), s.b(), level);
                if (r.snapshot == null || r.sectionKeys.isEmpty()) continue;
                nextClaimed.add(s.id());
                nextConnSections.put(s.id(), r.sectionKeys);
                addSnapshots(nextBySection, r);
            }
        }

        Map<Long, List<RopeSectionSnapshot>> publishedBySection = new HashMap<>(nextBySection.size());
        for (Map.Entry<Long, List<RopeSectionSnapshot>> e : nextBySection.entrySet()) {
            publishedBySection.put(e.getKey(), List.copyOf(e.getValue()));
        }

        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        toDirty.addAll(publishedBySection.keySet());

        long now = level.getGameTime();
        java.util.Map<UUID, Long> nextClaimTick = new java.util.HashMap<>(nextClaimed.size());
        for (UUID id : nextClaimed) {
            Long prev = claimTick.get(id);
            nextClaimTick.put(id, prev != null ? prev : now);
        }

        bySection = Map.copyOf(publishedBySection);
        claimed = Set.copyOf(nextClaimed);
        claimTick = Map.copyOf(nextClaimTick);
        claimedFromSim = Set.copyOf(nextClaimedFromSim);
        connectionSections.clear();
        connectionSections.putAll(nextConnSections);

        markSectionsDirty(toDirty);
    }

    private static void addSnapshots(Map<Long, List<RopeSectionSnapshot>> target, RopeStaticGeometryResult result) {
        for (Map.Entry<Long, List<RopeSectionSnapshot>> e : result.snapshotsBySection.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>(2)).addAll(e.getValue());
        }
    }

    
    private static boolean isEligible(LeadConnection c) {
        if (!c.attachments().isEmpty()) return false;
        if (c.powered()) return false;
        if (c.extractAnchor() != 0) return false;
        return true;
    }

    private static boolean isQuiescent(RopeSimulation sim) {
        if (sim.isSettled() && sim.quietTicks() >= CHUNK_MESH_QUIET_TICKS) return true;
        if (sim.quietTicks() >= CHUNK_MESH_QUIET_TICKS
                && sim.maxNodeMotionSqr() < CHUNK_MESH_QUIET_MOTION_SQR) return true;
        return false;
    }

    private static void markSectionsDirty(Set<Long> sectionKeys) {
        if (sectionKeys.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        var level = mc.level;
        if (level != null) {
            var emptySet = level.getChunkSource().getLoadedEmptySections();
            for (long key : sectionKeys) {
                if (emptySet.remove(key)) {
                    mc.levelRenderer.onSectionBecomingNonEmpty(key);
                }
            }
        }
        for (long key : sectionKeys) {
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            mc.levelRenderer.setSectionDirty(sx, sy, sz);
        }
    }
}
