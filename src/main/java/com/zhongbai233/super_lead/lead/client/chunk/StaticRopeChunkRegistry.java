package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Client registry for chunk-section static rope meshes.
 *
 * <p>
 * Dynamic ropes are rendered every frame, but settled ropes can be baked into
 * section-scoped geometry. This registry decides when a rope is eligible,
 * tracks
 * which sections own the baked result, and marks vanilla sections dirty when
 * the
 * static mesh view changes.
 */
public final class StaticRopeChunkRegistry {

    private static final StaticRopeChunkRegistry INSTANCE = new StaticRopeChunkRegistry();

    private static final double CHUNK_MESH_QUIET_MOTION_SQR = 4.0e-5D; // ~0.0063 block/tick
    private static final int CHUNK_MESH_FALLBACK_QUIET_TICKS = 3;
    private static final int CHUNK_MESH_LINGER_TICKS = 3;
    private static final int CHUNK_MESH_DYNAMIC_HOLD_MIN_TICKS = 3;

    public static StaticRopeChunkRegistry get() {
        return INSTANCE;
    }

    private volatile Map<Long, List<RopeSectionSnapshot>> bySection = Map.of();
    private volatile Map<UUID, RopeSectionSnapshot> byConnection = Map.of();
    private volatile List<RopeAttachmentRenderer.BakedAttachment> bakedAttachments = List.of();
    private volatile Set<UUID> claimed = Set.of();
    private volatile Map<UUID, Long> claimTick = Map.of();
    private Set<Long> meshedSections = Set.of();
    private Set<UUID> claimedFromSim = Set.of();
    private final Map<UUID, Set<Long>> connectionSections = new HashMap<>();
    private final Set<Long> pendingDirtySections = new HashSet<>();
    private final Map<UUID, Long> dynamicHoldUntil = new HashMap<>();
    private boolean connectionSyncDirty;
    /**
     * Static bakes made before anchor chunks load use default block shapes; retry
     * them once both anchor chunks arrive.
     */
    private final Set<UUID> bakedWithMissingAnchors = new HashSet<>();

    private List<LeadConnection> realSources = List.of();
    private List<StressSource> stressSources = List.of();

    private volatile int debugEligible;
    private volatile int debugWaitingQuiet;
    private volatile int debugReadyFromSim;
    private volatile int debugReadyAnchorBake;

    private StaticRopeChunkRegistry() {
    }

    public List<RopeSectionSnapshot> snapshotsFor(long sectionPosLong) {
        return bySection.getOrDefault(sectionPosLong, Collections.emptyList());
    }

    public Set<Long> publishedSectionKeys() {
        return bySection.keySet();
    }

    public synchronized Set<Long> unmeshedPublishedSectionKeys() {
        if (bySection.isEmpty())
            return Set.of();
        HashSet<Long> out = new HashSet<>(bySection.keySet());
        out.removeAll(meshedSections);
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    public synchronized void markSectionMeshAccepted(long sectionPosLong) {
        if (!bySection.containsKey(sectionPosLong) || meshedSections.contains(sectionPosLong))
            return;
        HashSet<Long> next = new HashSet<>(meshedSections);
        next.add(sectionPosLong);
        meshedSections = Set.copyOf(next);
    }

    public synchronized boolean isMeshAccepted(UUID connectionId) {
        Set<Long> sections = connectionSections.get(connectionId);
        return sections != null && !sections.isEmpty() && meshedSections.containsAll(sections);
    }

    public List<RopeAttachmentRenderer.BakedAttachment> bakedAttachmentsForRender(long currentTick) {
        if (bakedAttachments.isEmpty()) {
            return List.of();
        }
        ArrayList<RopeAttachmentRenderer.BakedAttachment> out = new ArrayList<>(bakedAttachments.size());
        for (RopeAttachmentRenderer.BakedAttachment attachment : bakedAttachments) {
            if (!shouldDynamicLinger(attachment.connectionId(), currentTick)) {
                out.add(attachment);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    public RopeSectionSnapshot snapshotForRender(UUID connectionId, long currentTick) {
        if (connectionId == null || shouldDynamicLinger(connectionId, currentTick)) {
            return null;
        }
        return byConnection.get(connectionId);
    }

    public boolean isClaimed(UUID connectionId) {
        return claimed.contains(connectionId);
    }

    public boolean shouldDynamicLinger(UUID connectionId, long currentTick) {
        Long t = claimTick.get(connectionId);
        if (t == null)
            return false;
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
        for (List<RopeSectionSnapshot> list : bySection.values())
            sum += list.size();
        return sum;
    }

    public int claimedNodesTotal() {
        int sum = 0;
        for (Map.Entry<UUID, Set<Long>> e : connectionSections.entrySet()) {
            Set<Long> keys = e.getValue();
            if (keys.isEmpty())
                continue;
            long anyKey = keys.iterator().next();
            List<RopeSectionSnapshot> list = bySection.get(anyKey);
            if (list == null)
                continue;
            for (RopeSectionSnapshot s : list) {
                if (s.connectionId.equals(e.getKey())) {
                    sum += s.nodeCount;
                    break;
                }
            }
        }
        return sum;
    }

    public int eligibleCount() {
        return debugEligible;
    }

    public int waitingQuietCount() {
        return debugWaitingQuiet;
    }

    public int readyFromSimCount() {
        return debugReadyFromSim;
    }

    public int readyAnchorBakeCount() {
        return debugReadyAnchorBake;
    }

    public int claimedFromSimCount() {
        return claimedFromSim.size();
    }

    public int claimedAnchorBakeCount() {
        return Math.max(0, claimed.size() - claimedFromSim.size());
    }

    public int missingAnchorBakeCount() {
        return bakedWithMissingAnchors.size();
    }

    public synchronized void clear() {
        if (bySection.isEmpty() && byConnection.isEmpty() && bakedAttachments.isEmpty()
                && claimed.isEmpty() && stressSources.isEmpty() && dynamicHoldUntil.isEmpty()
                && !connectionSyncDirty)
            return;
        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        bySection = Map.of();
        byConnection = Map.of();
        bakedAttachments = List.of();
        claimed = Set.of();
        claimTick = Map.of();
        meshedSections = Set.of();
        claimedFromSim = Set.of();
        connectionSections.clear();
        bakedWithMissingAnchors.clear();
        dynamicHoldUntil.clear();
        connectionSyncDirty = false;
        stressSources = List.of();
        clearDebugCounts();
        markSectionsDirty(toDirty);
    }

    public synchronized void flushPendingDirtySections() {
        if (pendingDirtySections.isEmpty())
            return;
        Set<Long> dirty = new HashSet<>(pendingDirtySections);
        pendingDirtySections.clear();
        markSectionsDirty(dirty);
    }

    public synchronized void clearStressSources(Level level) {
        if (stressSources.isEmpty())
            return;
        stressSources = List.of();
        rebuild(level);
    }

    public synchronized void onConnectionsReplaced(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide())
            return;
        realSources = List.copyOf(connections);
        connectionSyncDirty = true;
        if (connections.isEmpty()) {
            rebuildInternal(level, null);
        }
    }

    public synchronized void publishStressSources(Level level, List<StressSource> sources) {
        if (level == null || !level.isClientSide())
            return;
        stressSources = List.copyOf(sources);
        rebuild(level);
    }

    public synchronized void invalidateAll(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide())
            return;
        realSources = List.copyOf(connections);
        rebuild(level);
    }

    public synchronized void invalidateConnection(Level level, UUID connectionId) {
        if (level == null || !level.isClientSide() || connectionId == null)
            return;
        if (!claimed.contains(connectionId) && !connectionSections.containsKey(connectionId))
            return;

        Set<Long> dirty = new HashSet<>(connectionSections.getOrDefault(connectionId, Set.of()));
        if (dirty.isEmpty())
            dirty.addAll(bySection.keySet());

        Map<Long, List<RopeSectionSnapshot>> nextBySection = new HashMap<>(bySection.size());
        Map<UUID, RopeSectionSnapshot> nextByConnection = new HashMap<>(byConnection);
        nextByConnection.remove(connectionId);
        for (Map.Entry<Long, List<RopeSectionSnapshot>> entry : bySection.entrySet()) {
            List<RopeSectionSnapshot> nextList = new ArrayList<>(entry.getValue().size());
            for (RopeSectionSnapshot snapshot : entry.getValue()) {
                if (!snapshot.connectionId.equals(connectionId))
                    nextList.add(snapshot);
            }
            if (!nextList.isEmpty())
                nextBySection.put(entry.getKey(), List.copyOf(nextList));
        }

        Set<UUID> nextClaimed = new HashSet<>(claimed);
        nextClaimed.remove(connectionId);
        Set<UUID> nextClaimedFromSim = new HashSet<>(claimedFromSim);
        nextClaimedFromSim.remove(connectionId);
        Map<UUID, Long> nextClaimTick = new HashMap<>(claimTick);
        nextClaimTick.remove(connectionId);
        HashSet<Long> nextMeshedSections = new HashSet<>(meshedSections);
        nextMeshedSections.removeAll(dirty);

        bySection = Map.copyOf(nextBySection);
        byConnection = nextByConnection.isEmpty() ? Map.of() : Map.copyOf(nextByConnection);
        if (!bakedAttachments.isEmpty()) {
            ArrayList<RopeAttachmentRenderer.BakedAttachment> nextAttachments = new ArrayList<>(
                    bakedAttachments.size());
            for (RopeAttachmentRenderer.BakedAttachment attachment : bakedAttachments) {
                if (!attachment.connectionId().equals(connectionId)) {
                    nextAttachments.add(attachment);
                }
            }
            bakedAttachments = nextAttachments.isEmpty() ? List.of() : List.copyOf(nextAttachments);
        }
        claimed = Set.copyOf(nextClaimed);
        claimedFromSim = Set.copyOf(nextClaimedFromSim);
        claimTick = Map.copyOf(nextClaimTick);
        meshedSections = nextMeshedSections.isEmpty() ? Set.of() : Set.copyOf(nextMeshedSections);
        connectionSections.remove(connectionId);
        markSectionsDirty(dirty);
    }

    public synchronized void holdDynamic(Level level, UUID connectionId, long untilTick) {
        if (level == null || !level.isClientSide() || connectionId == null)
            return;
        long now = level.getGameTime();
        long effectiveUntil = Math.max(untilTick, now + CHUNK_MESH_DYNAMIC_HOLD_MIN_TICKS);
        Long previous = dynamicHoldUntil.get(connectionId);
        if (previous == null || previous < effectiveUntil) {
            dynamicHoldUntil.put(connectionId, effectiveUntil);
        }
        invalidateConnection(level, connectionId);
    }

    public synchronized void rebuildFromCache(Level level) {
        if (level == null || !level.isClientSide())
            return;
        rebuild(level);
    }

    public synchronized void tickMaintain(Level level, Function<UUID, RopeSimulation> simLookup) {
        if (level == null || !level.isClientSide() || simLookup == null)
            return;
        long now = level.getGameTime();
        pruneDynamicHolds(now);
        boolean enabled = ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();
        // Retry meshes that were baked before their endpoint chunks arrived.
        boolean forceRebakeForMissingAnchors = false;
        if (enabled && !bakedWithMissingAnchors.isEmpty()) {
            for (LeadConnection c : realSources) {
                if (!bakedWithMissingAnchors.contains(c.id()))
                    continue;
                if (anchorsLoaded(level, c)) {
                    forceRebakeForMissingAnchors = true;
                    break;
                }
            }
        }
        Set<UUID> desired = new HashSet<>();
        Set<UUID> desiredFromSim = new HashSet<>();
        int eligible = 0;
        int waitingQuiet = 0;
        int readyFromSim = 0;
        int readyAnchorBake = 0;
        if (enabled) {
            for (LeadConnection c : realSources) {
                eligible++;
                if (isDynamicallyHeld(c.id(), now)) {
                    waitingQuiet++;
                    continue;
                }
                RopeSimulation sim = simLookup.apply(c.id());
                if (sim != null) {
                    if (!isQuiescent(sim)) {
                        waitingQuiet++;
                        continue;
                    }
                    desired.add(c.id());
                    desiredFromSim.add(c.id());
                    readyFromSim++;
                } else {
                    desired.add(c.id());
                    readyAnchorBake++;
                }
            }
            for (StressSource s : stressSources) {
                desired.add(s.id());
            }
        }
        debugEligible = eligible;
        debugWaitingQuiet = waitingQuiet;
        debugReadyFromSim = readyFromSim;
        debugReadyAnchorBake = readyAnchorBake;
        if (!connectionSyncDirty
                && !forceRebakeForMissingAnchors
                && desired.equals(claimed) && desiredFromSim.equals(claimedFromSim))
            return;
        rebuildInternal(level, simLookup);
    }

    private void rebuild(Level level) {
        rebuildInternal(level, null);
    }

    private void rebuildInternal(Level level, Function<UUID, RopeSimulation> simLookup) {
        if (level == null) {
            clearPublishedState();
            return;
        }
        long now = level.getGameTime();
        pruneDynamicHolds(now);
        RebuildState next = new RebuildState();
        if (staticMeshEnabled()) {
            collectRealSources(level, simLookup, now, next);
            collectStressSources(level, next);
        }
        publishRebuild(next, now);
    }

    private void clearPublishedState() {
        if (bySection.isEmpty() && byConnection.isEmpty() && bakedAttachments.isEmpty()
                && dynamicHoldUntil.isEmpty() && !connectionSyncDirty)
            return;
        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        bySection = Map.of();
        byConnection = Map.of();
        bakedAttachments = List.of();
        claimed = Set.of();
        claimTick = Map.of();
        meshedSections = Set.of();
        claimedFromSim = Set.of();
        connectionSections.clear();
        dynamicHoldUntil.clear();
        connectionSyncDirty = false;
        markSectionsDirty(toDirty);
    }

    private static boolean staticMeshEnabled() {
        return ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();
    }

    private void collectRealSources(
            Level level, Function<UUID, RopeSimulation> simLookup, long now, RebuildState next) {
        for (LeadConnection connection : realSources) {
            if (isDynamicallyHeld(connection.id(), now))
                continue;
            addRealSource(level, simLookup, connection, next);
        }
    }

    private void addRealSource(
            Level level, Function<UUID, RopeSimulation> simLookup, LeadConnection connection, RebuildState next) {
        RopeSimulation sim = simLookup == null ? null : simLookup.apply(connection.id());
        RopeStaticGeometryResult result = buildRealSourceGeometry(level, connection, sim);
        if (!hasGeometry(result))
            return;
        next.addConnection(connection.id(), result);
        if (sim != null) {
            next.claimedFromSim.add(connection.id());
        }
        next.bakedAttachments.addAll(RopeAttachmentRenderer.bakeStatic(
                level, connection, result.snapshot.x, result.snapshot.y, result.snapshot.z));
        updateMissingAnchorBake(level, connection, sim == null);
    }

    private RopeStaticGeometryResult buildRealSourceGeometry(
            Level level, LeadConnection connection, RopeSimulation sim) {
        if (sim == null) {
            return RopeStaticGeometry.build(connection, level, realSources);
        }
        return isQuiescent(sim) ? RopeStaticGeometry.buildFromSim(connection, sim, level) : null;
    }

    private void updateMissingAnchorBake(Level level, LeadConnection connection, boolean bakedFromAnchors) {
        if (bakedFromAnchors && !anchorsLoaded(level, connection)) {
            bakedWithMissingAnchors.add(connection.id());
        } else {
            bakedWithMissingAnchors.remove(connection.id());
        }
    }

    private void collectStressSources(Level level, RebuildState next) {
        for (StressSource source : stressSources) {
            RopeStaticGeometryResult result = RopeStaticGeometry.build(source.id(), source.a(), source.b(), level);
            if (hasGeometry(result)) {
                next.addConnection(source.id(), result);
            }
        }
    }

    private void publishRebuild(RebuildState next, long now) {
        Map<Long, List<RopeSectionSnapshot>> publishedBySection = new HashMap<>(next.bySection.size());
        for (Map.Entry<Long, List<RopeSectionSnapshot>> e : next.bySection.entrySet()) {
            publishedBySection.put(e.getKey(), List.copyOf(e.getValue()));
        }

        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        toDirty.addAll(publishedBySection.keySet());

        Map<UUID, Long> nextClaimTick = copyClaimTicks(next.claimed, now);

        bySection = Map.copyOf(publishedBySection);
        byConnection = next.byConnection.isEmpty() ? Map.of() : Map.copyOf(next.byConnection);
        bakedAttachments = next.bakedAttachments.isEmpty() ? List.of() : List.copyOf(next.bakedAttachments);
        claimed = Set.copyOf(next.claimed);
        claimTick = Map.copyOf(nextClaimTick);
        meshedSections = Set.of();
        claimedFromSim = Set.copyOf(next.claimedFromSim);
        connectionSections.clear();
        connectionSections.putAll(next.connectionSections);
        bakedWithMissingAnchors.retainAll(next.claimed);
        connectionSyncDirty = false;

        markSectionsDirty(toDirty);
    }

    private Map<UUID, Long> copyClaimTicks(Set<UUID> nextClaimed, long now) {
        Map<UUID, Long> nextClaimTick = new HashMap<>(nextClaimed.size());
        for (UUID id : nextClaimed) {
            Long prev = claimTick.get(id);
            nextClaimTick.put(id, prev != null ? prev : now);
        }
        return nextClaimTick;
    }

    private static boolean hasGeometry(RopeStaticGeometryResult result) {
        return result != null && result.snapshot != null && !result.sectionKeys.isEmpty();
    }

    private static void addSnapshots(Map<Long, List<RopeSectionSnapshot>> target, RopeStaticGeometryResult result) {
        for (Map.Entry<Long, List<RopeSectionSnapshot>> e : result.snapshotsBySection.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new ArrayList<>(2)).addAll(e.getValue());
        }
    }

    private static final class RebuildState {
        final Map<Long, List<RopeSectionSnapshot>> bySection = new HashMap<>();
        final Map<UUID, RopeSectionSnapshot> byConnection = new HashMap<>();
        final List<RopeAttachmentRenderer.BakedAttachment> bakedAttachments = new ArrayList<>();
        final Set<UUID> claimed = new HashSet<>();
        final Set<UUID> claimedFromSim = new HashSet<>();
        final Map<UUID, Set<Long>> connectionSections = new HashMap<>();

        void addConnection(UUID id, RopeStaticGeometryResult result) {
            claimed.add(id);
            connectionSections.put(id, result.sectionKeys);
            byConnection.put(id, result.snapshot);
            addSnapshots(bySection, result);
        }
    }

    private static boolean isQuiescent(RopeSimulation sim) {
        if (sim.isSettled())
            return true;
        if (sim.quietTicks() >= CHUNK_MESH_FALLBACK_QUIET_TICKS
                && sim.maxNodeMotionSqr() < CHUNK_MESH_QUIET_MOTION_SQR)
            return true;
        return false;
    }

    private boolean isDynamicallyHeld(UUID connectionId, long currentTick) {
        Long until = dynamicHoldUntil.get(connectionId);
        return until != null && currentTick <= until;
    }

    private void pruneDynamicHolds(long currentTick) {
        if (dynamicHoldUntil.isEmpty())
            return;
        dynamicHoldUntil.entrySet().removeIf(entry -> currentTick > entry.getValue());
    }

    private static boolean anchorsLoaded(Level level, LeadConnection connection) {
        return anchorChunkReady(level, connection.from().pos())
                && anchorChunkReady(level, connection.to().pos());
    }

    private static boolean anchorChunkReady(Level level, BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        return level.getChunk(cx, cz, ChunkStatus.FULL, false) != null;
    }

    private void markSectionsDirty(Set<Long> sectionKeys) {
        if (sectionKeys.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) {
            pendingDirtySections.addAll(sectionKeys);
            return;
        }
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

    private void clearDebugCounts() {
        debugEligible = 0;
        debugWaitingQuiet = 0;
        debugReadyFromSim = 0;
        debugReadyAnchorBake = 0;
    }
}
