package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
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
    private static final int CHUNK_MESH_STACK_QUIET_TICKS = 40;
    private static final int CHUNK_MESH_CLAIM_LINGER_TICKS = 3;
    // Section compilation can be observed just before its GPU buffer becomes visible.
    // Keep the source dynamic shape for one logical tick so entering a mesh cannot
    // produce an empty frame. The bake comes from the same simulation snapshot, so
    // this bounded overlap is visually coincident rather than a stale second rope.
    private static final int CHUNK_MESH_ACCEPTED_OVERLAP_TICKS = 1;
    private static final int CHUNK_MESH_DYNAMIC_HOLD_MIN_TICKS = 3;
    private static final int CHUNK_MESH_WIND_COOLDOWN_TICKS = 40;
    private static final int CHUNK_MESH_RETIRE_GRACE_TICKS = 0;
    private static final int CHUNK_MESH_RETIRE_TIMEOUT_TICKS = 40;
    // Sparse high-LOD physics only publishes every few ticks. One low-motion sample
    // is enough to enter the mesh; hard motion, wind and explicit holds still veto it.
    // Once claimed, the same hard-motion threshold is used at every LOD. Requiring a
    // claimed near rope to remain fully settled made the claim disappear while its
    // section was still compiling, then immediately reappear on the next quiet sample.
    private static final int HIGH_LOD_ENTRY_DEBOUNCE_STEPS = 3;
    private static final double HIGH_LOD_HARD_EXIT_MOTION_SQR = 5.0e-4D;
    private static final int URGENT_DIRTY_SECTIONS_PER_TICK = 12;
    private static final int NEW_MESH_SECTIONS_PER_TICK = 2;
    private static final int UNOBSERVED_BUILD_RETRY_TICKS = 20;
    private static final int WATCHDOG_INTERVAL_TICKS = 20;
    private static final int CLAIM_EXPANSION_DEBOUNCE_TICKS = 3;
    private static final int CLAIM_EXPANSION_MAX_DELAY_TICKS = 8;

    public static StaticRopeChunkRegistry get() {
        return INSTANCE;
    }

    private volatile Map<Long, List<RopeSectionSnapshot>> bySection = Map.of();
    private volatile Map<UUID, RopeSectionSnapshot> byConnection = Map.of();
    private volatile List<RopeAttachmentRenderer.BakedAttachment> bakedAttachments = List.of();
    private volatile Set<UUID> claimed = Set.of();
    private volatile Map<UUID, Long> claimTick = Map.of();
    private final Map<UUID, Long> acceptedTick = new HashMap<>();
    private Set<Long> meshedSections = Set.of();
    private Set<UUID> acceptedConnections = Set.of();
    private Set<UUID> claimedFromSim = Set.of();
    private final Map<UUID, Set<Long>> connectionSections = new HashMap<>();
    private final Set<Long> pendingDirtySections = new LinkedHashSet<>();
    private final Set<Long> pendingUrgentDirtySections = new LinkedHashSet<>();
    private final Map<Long, Long> lastDirtySubmitTick = new HashMap<>();
    private final Set<Long> sectionsAwaitingMesh = new LinkedHashSet<>();
    private final Set<Long> publishedWatchdogSections = new LinkedHashSet<>();
    private final Map<Long, Long> sectionGeneration = new HashMap<>();
    private final Map<Long, Long> compiledGeneration = new HashMap<>();
    private final Map<UUID, RetiringMesh> retiringMeshes = new HashMap<>();
    private final Map<UUID, List<RopeAttachmentRenderer.BakedAttachment>> retiringAttachments = new HashMap<>();
    private final Map<UUID, Long> dynamicHoldUntil = new HashMap<>();
    private final Map<UUID, ExitDebounce> highLodEntryDebounce = new HashMap<>();
    /**
     * Claimed ropes whose baked light may have changed. Requests are coalesced until
     * the next maintenance pass so several nearby dynamic lights cause one rebuild.
     */
    private final Set<UUID> pendingLightRebakes = new HashSet<>();
    private final Set<UUID> highLodConnections = new HashSet<>();
    private final Set<UUID> desiredScratch = new HashSet<>();
    private final Set<UUID> desiredFromSimScratch = new HashSet<>();
    private long lastMaintenanceTick = Long.MIN_VALUE;
    private long lastWatchdogProbeTick = Long.MIN_VALUE;
    private boolean connectionSyncDirty;
    private Set<UUID> pendingExpansionClaims = Set.of();
    private Set<UUID> pendingExpansionFromSim = Set.of();
    private long pendingExpansionFirstTick = Long.MIN_VALUE;
    private long pendingExpansionSinceTick = Long.MIN_VALUE;
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
    private volatile int debugDirtyQueue;
    private volatile int debugDirtyFlushedLastTick;

    private StaticRopeChunkRegistry() {
    }

    public List<RopeSectionSnapshot> snapshotsFor(long sectionPosLong) {
        return bySection.getOrDefault(sectionPosLong, Collections.emptyList());
    }

    public synchronized SectionBuild captureSectionBuild(long sectionPosLong) {
        return new SectionBuild(
                bySection.getOrDefault(sectionPosLong, Collections.emptyList()),
                sectionGeneration.getOrDefault(sectionPosLong, 0L));
    }

    public synchronized void markSectionBuildObserved(long sectionPosLong, long generation, long currentTick) {
        long previous = compiledGeneration.getOrDefault(sectionPosLong, Long.MIN_VALUE);
        if (generation < previous)
            return;
        compiledGeneration.put(sectionPosLong, generation);
        boolean currentGeneration = generation == sectionGeneration.getOrDefault(sectionPosLong, 0L);
        if (currentGeneration) {
            lastDirtySubmitTick.remove(sectionPosLong);
            markSectionMeshAccepted(sectionPosLong, currentTick);
        }
        for (Map.Entry<UUID, RetiringMesh> entry : retiringMeshes.entrySet()) {
            RetiringMesh retiring = entry.getValue();
            if (retiring.completedTick() == Long.MIN_VALUE
                    && generationsReached(retiring.targetGeneration(), compiledGeneration)) {
                entry.setValue(new RetiringMesh(
                        retiring.targetGeneration(), retiring.startedTick(), currentTick));
            }
        }
    }

    public Set<Long> publishedSectionKeys() {
        return bySection.keySet();
    }

    /**
     * Returns a bounded rotating sample of published sections. This preserves the
     * empty-section recovery watchdog without walking every static rope section on
     * every client tick.
     */
    public synchronized Set<Long> watchdogPublishedSectionKeys(long currentTick) {
        if (publishedWatchdogSections.isEmpty()
                || !watchdogProbeDue(lastWatchdogProbeTick, currentTick, WATCHDOG_INTERVAL_TICKS)) {
            return Set.of();
        }
        lastWatchdogProbeTick = currentTick;
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        var it = publishedWatchdogSections.iterator();
        while (it.hasNext() && out.size() < URGENT_DIRTY_SECTIONS_PER_TICK) {
            long key = it.next();
            it.remove();
            if (bySection.containsKey(key)) {
                out.add(key);
            }
        }
        publishedWatchdogSections.addAll(out);
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    public synchronized void queueDueUnmeshedRetries(long currentTick) {
        Set<Long> queued = queueDueUnmeshedRetries(
                sectionsAwaitingMesh, bySection.keySet(), meshedSections,
                pendingDirtySections, pendingUrgentDirtySections, lastDirtySubmitTick,
                currentTick, UNOBSERVED_BUILD_RETRY_TICKS, NEW_MESH_SECTIONS_PER_TICK);
        pendingUrgentDirtySections.addAll(queued);
        debugDirtyQueue = pendingDirtySections.size() + pendingUrgentDirtySections.size();
    }

    private synchronized void markSectionMeshAccepted(long sectionPosLong, long currentTick) {
        if (!bySection.containsKey(sectionPosLong) || meshedSections.contains(sectionPosLong))
            return;
        HashSet<Long> next = new HashSet<>(meshedSections);
        next.add(sectionPosLong);
        meshedSections = Set.copyOf(next);
        HashSet<UUID> nextAcceptedConnections = acceptedConnectionsForSections(connectionSections, meshedSections);
        for (UUID id : nextAcceptedConnections) {
            if (!acceptedConnections.contains(id)) {
                acceptedTick.put(id, currentTick);
            }
        }
        acceptedConnections = nextAcceptedConnections.isEmpty() ? Set.of() : Set.copyOf(nextAcceptedConnections);
        sectionsAwaitingMesh.remove(sectionPosLong);
    }

    public synchronized boolean isMeshAccepted(UUID connectionId) {
        Set<Long> sections = connectionSections.get(connectionId);
        return sections != null && !sections.isEmpty() && meshedSections.containsAll(sections);
    }

    /**
     * True only while the previous vanilla section buffer is still the visual
     * fallback. Once its clear generation is observed, dynamic rendering can resume
     * immediately even if maintenance has not removed the retirement record yet.
     */
    public synchronized boolean retirementNeedsStaticFallback(UUID connectionId) {
        RetiringMesh retiring = connectionId == null ? null : retiringMeshes.get(connectionId);
        return retiring != null && retirementNeedsStaticFallback(retiring.completedTick());
    }

    static boolean retirementNeedsStaticFallback(long completedTick) {
        return completedTick == Long.MIN_VALUE;
    }

    public List<RopeAttachmentRenderer.BakedAttachment> bakedAttachmentsForRender(long currentTick) {
        if (bakedAttachments.isEmpty() && retiringAttachments.isEmpty()) {
            return List.of();
        }
        ArrayList<RopeAttachmentRenderer.BakedAttachment> candidates = new ArrayList<>(bakedAttachments);
        synchronized (this) {
            for (Map.Entry<UUID, List<RopeAttachmentRenderer.BakedAttachment>> entry : retiringAttachments.entrySet()) {
                if (retirementNeedsStaticFallback(entry.getKey())) {
                    candidates.addAll(entry.getValue());
                }
            }
        }
        return selectBakedAttachmentsForRender(candidates,
                attachment -> !isMeshAccepted(attachment.connectionId())
                        && !retirementNeedsStaticFallback(attachment.connectionId()));
    }

    static List<RopeAttachmentRenderer.BakedAttachment> selectBakedAttachmentsForRender(
            List<RopeAttachmentRenderer.BakedAttachment> attachments,
            java.util.function.Predicate<RopeAttachmentRenderer.BakedAttachment> excluded) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        ArrayList<RopeAttachmentRenderer.BakedAttachment> selected = new ArrayList<>(attachments.size());
        HashSet<UUID> seenAttachmentIds = new HashSet<>(attachments.size());
        for (RopeAttachmentRenderer.BakedAttachment attachment : attachments) {
            if (attachment == null || excluded.test(attachment)
                    || !seenAttachmentIds.add(attachment.attachmentId())) {
                continue;
            }
            selected.add(attachment);
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        if (selected.size() == attachments.size()) {
            return attachments;
        }
        return List.copyOf(selected);
    }

    public RopeSectionSnapshot snapshotForRender(UUID connectionId, long currentTick) {
        if (connectionId == null || shouldDynamicLinger(connectionId, currentTick)) {
            return null;
        }
        return byConnection.get(connectionId);
    }

    public List<RopeSectionSnapshot> snapshotsForRender(UUID connectionId, long currentTick) {
        if (connectionId == null || shouldDynamicLinger(connectionId, currentTick)) {
            return List.of();
        }
        Set<Long> sections = connectionSections.get(connectionId);
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        ArrayList<RopeSectionSnapshot> out = new ArrayList<>();
        for (long section : sections) {
            List<RopeSectionSnapshot> snapshots = bySection.get(section);
            if (snapshots == null)
                continue;
            for (RopeSectionSnapshot snapshot : snapshots) {
                if (connectionId.equals(snapshot.connectionId)) {
                    out.add(snapshot);
                }
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    public synchronized boolean shouldDynamicLinger(UUID connectionId, long currentTick) {
        if (isMeshAccepted(connectionId)) {
            return handoffNeedsDynamicOverlap(
                    true, Long.MIN_VALUE, acceptedTick.getOrDefault(connectionId, Long.MIN_VALUE), currentTick);
        }
        Long t = claimTick.get(connectionId);
        if (t == null)
            return false;
        return handoffNeedsDynamicOverlap(false, t, Long.MIN_VALUE, currentTick);
    }

    static boolean handoffNeedsDynamicOverlap(
            boolean accepted, long claimedAt, long acceptedAt, long currentTick) {
        long startedAt = accepted ? acceptedAt : claimedAt;
        int duration = accepted ? CHUNK_MESH_ACCEPTED_OVERLAP_TICKS : CHUNK_MESH_CLAIM_LINGER_TICKS;
        return startedAt != Long.MIN_VALUE
                && currentTick >= startedAt
                && currentTick - startedAt < duration;
    }

    public boolean isActive() {
        return !claimed.isEmpty();
    }

    public int claimedCount() {
        return claimed.size();
    }

    public int acceptedConnectionCount() {
        return acceptedConnections.size();
    }

    public int acceptedSectionCount() {
        return meshedSections.size();
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

    public int dirtyQueueCount() {
        return debugDirtyQueue;
    }

    public int dirtyFlushedLastTick() {
        return debugDirtyFlushedLastTick;
    }

    public synchronized void clear() {
        bySection = Map.of();
        byConnection = Map.of();
        bakedAttachments = List.of();
        claimed = Set.of();
        claimTick = Map.of();
        acceptedTick.clear();
        meshedSections = Set.of();
        acceptedConnections = Set.of();
        claimedFromSim = Set.of();
        connectionSections.clear();
        bakedWithMissingAnchors.clear();
        dynamicHoldUntil.clear();
        highLodEntryDebounce.clear();
        pendingLightRebakes.clear();
        highLodConnections.clear();
        desiredScratch.clear();
        desiredFromSimScratch.clear();
        lastMaintenanceTick = Long.MIN_VALUE;
        lastWatchdogProbeTick = Long.MIN_VALUE;
        sectionsAwaitingMesh.clear();
        publishedWatchdogSections.clear();
        pendingDirtySections.clear();
        pendingUrgentDirtySections.clear();
        lastDirtySubmitTick.clear();
        sectionGeneration.clear();
        compiledGeneration.clear();
        retiringMeshes.clear();
        retiringAttachments.clear();
        connectionSyncDirty = false;
        clearPendingExpansion();
        stressSources = List.of();
        realSources = List.of();
        clearDebugCounts();
    }

    public synchronized void flushPendingDirtySections() {
        if (pendingDirtySections.isEmpty() && pendingUrgentDirtySections.isEmpty()) {
            debugDirtyFlushedLastTick = 0;
            debugDirtyQueue = 0;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            debugDirtyFlushedLastTick = 0;
            debugDirtyQueue = pendingDirtySections.size() + pendingUrgentDirtySections.size();
            return;
        }
        Set<Long> dirty = drainDirtyBatch(
                pendingUrgentDirtySections, pendingDirtySections,
                URGENT_DIRTY_SECTIONS_PER_TICK, NEW_MESH_SECTIONS_PER_TICK);
        submitDirtySectionsNow(mc, dirty);
        long currentTick = mc.level.getGameTime();
        for (long key : dirty) {
            lastDirtySubmitTick.put(key, currentTick);
        }
        debugDirtyFlushedLastTick = dirty.size();
        debugDirtyQueue = pendingDirtySections.size() + pendingUrgentDirtySections.size();
    }

    static Set<Long> drainDirtyBatch(
            Set<Long> urgent, Set<Long> normal, int totalBudget, int normalBudget) {
        int safeTotalBudget = Math.max(0, totalBudget);
        LinkedHashSet<Long> batch = new LinkedHashSet<>();
        var urgentIt = urgent.iterator();
        while (urgentIt.hasNext() && batch.size() < safeTotalBudget) {
            batch.add(urgentIt.next());
            urgentIt.remove();
        }
        int remainingNormal = Math.min(Math.max(0, normalBudget), safeTotalBudget - batch.size());
        var normalIt = normal.iterator();
        while (normalIt.hasNext() && remainingNormal-- > 0) {
            batch.add(normalIt.next());
            normalIt.remove();
        }
        return batch;
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
        if (realSources.equals(connections))
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
        invalidateConnections(level, Set.of(connectionId));
    }

    public synchronized void invalidateConnections(Level level, Iterable<UUID> connectionIds) {
        if (level == null || !level.isClientSide() || connectionIds == null)
            return;

        Set<UUID> removing = new HashSet<>();
        for (UUID id : connectionIds) {
            if (id != null && (claimed.contains(id) || connectionSections.containsKey(id)
                    || byConnection.containsKey(id))) {
                removing.add(id);
            }
        }
        if (removing.isEmpty())
            return;

        Set<UUID> retiring = new HashSet<>();
        for (UUID id : removing) {
            Set<Long> sections = connectionSections.getOrDefault(id, Set.of());
            if (!Collections.disjoint(sections, meshedSections)) {
                retiring.add(id);
            }
        }

        Set<Long> dirty = new HashSet<>();
        for (UUID id : removing) {
            dirty.addAll(connectionSections.getOrDefault(id, Set.of()));
        }
        // Recover conservatively from an incomplete connectionSections index without
        // dirtying every published section.
        if (dirty.isEmpty()) {
            for (Map.Entry<Long, List<RopeSectionSnapshot>> entry : bySection.entrySet()) {
                for (RopeSectionSnapshot snapshot : entry.getValue()) {
                    if (removing.contains(snapshot.connectionId)) {
                        dirty.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        Map<Long, List<RopeSectionSnapshot>> nextBySection = new HashMap<>(bySection);
        Map<UUID, RopeSectionSnapshot> nextByConnection = new HashMap<>(byConnection);
        removing.forEach(nextByConnection::remove);
        for (long section : dirty) {
            List<RopeSectionSnapshot> previous = bySection.get(section);
            if (previous == null)
                continue;
            nextBySection.remove(section);
            List<RopeSectionSnapshot> nextList = new ArrayList<>(previous.size());
            for (RopeSectionSnapshot snapshot : previous) {
                if (!removing.contains(snapshot.connectionId))
                    nextList.add(snapshot);
            }
            if (!nextList.isEmpty())
                nextBySection.put(section, List.copyOf(nextList));
        }

        Set<UUID> nextClaimed = new HashSet<>(claimed);
        nextClaimed.removeAll(removing);
        Set<UUID> nextClaimedFromSim = new HashSet<>(claimedFromSim);
        nextClaimedFromSim.removeAll(removing);
        Map<UUID, Long> nextClaimTick = new HashMap<>(claimTick);
        removing.forEach(nextClaimTick::remove);
        removing.forEach(acceptedTick::remove);
        HashSet<Long> nextMeshedSections = new HashSet<>(meshedSections);
        nextMeshedSections.retainAll(nextBySection.keySet());
        nextMeshedSections.removeAll(dirty);
        Map<UUID, Set<Long>> nextConnectionSections = new HashMap<>(connectionSections);
        removing.forEach(nextConnectionSections::remove);
        HashSet<UUID> nextAcceptedConnections = acceptedConnectionsForSections(
                nextConnectionSections, nextMeshedSections);

        bySection = Map.copyOf(nextBySection);
        publishedWatchdogSections.retainAll(nextBySection.keySet());
        publishedWatchdogSections.addAll(nextBySection.keySet());
        byConnection = nextByConnection.isEmpty() ? Map.of() : Map.copyOf(nextByConnection);
        if (!bakedAttachments.isEmpty()) {
            ArrayList<RopeAttachmentRenderer.BakedAttachment> nextAttachments = new ArrayList<>(
                    bakedAttachments.size());
            Map<UUID, List<RopeAttachmentRenderer.BakedAttachment>> retiringCopies = new HashMap<>();
            for (RopeAttachmentRenderer.BakedAttachment attachment : bakedAttachments) {
                if (!removing.contains(attachment.connectionId())) {
                    nextAttachments.add(attachment);
                } else if (retiring.contains(attachment.connectionId())) {
                    retiringCopies.computeIfAbsent(attachment.connectionId(), ignored -> new ArrayList<>())
                            .add(attachment);
                }
            }
            bakedAttachments = nextAttachments.isEmpty() ? List.of() : List.copyOf(nextAttachments);
            for (Map.Entry<UUID, List<RopeAttachmentRenderer.BakedAttachment>> entry : retiringCopies.entrySet()) {
                retiringAttachments.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        claimed = Set.copyOf(nextClaimed);
        claimedFromSim = Set.copyOf(nextClaimedFromSim);
        claimTick = Map.copyOf(nextClaimTick);
        meshedSections = nextMeshedSections.isEmpty() ? Set.of() : Set.copyOf(nextMeshedSections);
        acceptedConnections = nextAcceptedConnections.isEmpty() ? Set.of() : Set.copyOf(nextAcceptedConnections);
        acceptedTick.keySet().retainAll(acceptedConnections);
        connectionSections.clear();
        connectionSections.putAll(nextConnectionSections);
        highLodEntryDebounce.keySet().removeAll(removing);
        pendingLightRebakes.removeAll(removing);
        bakedWithMissingAnchors.removeAll(removing);
        sectionsAwaitingMesh.removeIf(section -> !nextBySection.containsKey(section));
        for (long section : dirty) {
            if (nextBySection.containsKey(section)) {
                sectionsAwaitingMesh.add(section);
            }
        }
        markSectionsDirty(dirty, true);
        if (!retiring.isEmpty() && !dirty.isEmpty()) {
            Map<Long, Long> targets = new HashMap<>();
            for (long section : dirty) {
                targets.put(section, sectionGeneration.getOrDefault(section, 0L));
            }
            Map<Long, Long> immutableTargets = Map.copyOf(targets);
            for (UUID id : retiring) {
                retiringMeshes.put(id, new RetiringMesh(
                        immutableTargets, level.getGameTime(), Long.MIN_VALUE));
            }
        }
    }

    public synchronized void holdDynamic(Level level, UUID connectionId, long untilTick) {
        if (level == null || !level.isClientSide() || connectionId == null)
            return;
        holdDynamic(level, Set.of(connectionId), untilTick);
    }

    public synchronized void holdDynamic(Level level, Iterable<UUID> connectionIds, long untilTick) {
        if (level == null || !level.isClientSide() || connectionIds == null)
            return;
        Set<UUID> ids = new HashSet<>();
        for (UUID id : connectionIds) {
            if (id != null)
                ids.add(id);
        }
        if (ids.isEmpty())
            return;
        highLodEntryDebounce.keySet().removeAll(ids);
        long now = level.getGameTime();
        long effectiveUntil = Math.max(untilTick, now + CHUNK_MESH_DYNAMIC_HOLD_MIN_TICKS);
        Set<UUID> newlyHeldPublished = new HashSet<>();
        for (UUID id : ids) {
            Long previous = dynamicHoldUntil.get(id);
            if (previous == null || previous < effectiveUntil) {
                dynamicHoldUntil.put(id, effectiveUntil);
            }
            if (previous == null && (claimed.contains(id) || connectionSections.containsKey(id)
                    || byConnection.containsKey(id))) {
                newlyHeldPublished.add(id);
            }
        }
        if (!newlyHeldPublished.isEmpty()) {
            invalidateConnections(level, newlyHeldPublished);
        }
    }

    public synchronized Set<UUID> invalidateNearBlock(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null || byConnection.isEmpty())
            return Set.of();
        AABB changed = new AABB(pos).inflate(2.0D);
        Set<UUID> affected = new HashSet<>();
        for (Map.Entry<UUID, RopeSectionSnapshot> entry : byConnection.entrySet()) {
            if (snapshotIntersects(entry.getValue(), changed)) {
                affected.add(entry.getKey());
            }
        }
        long until = level.getGameTime() + 8L;
        holdDynamic(level, affected, until);
        return affected.isEmpty() ? Set.of() : Set.copyOf(affected);
    }

    /**
     * Requests a light-only refresh for claimed ropes within the supplied light
     * influence radius. The current mesh remains active until a rebuilt snapshot
     * actually differs, avoiding an unnecessary mesh-to-dynamic handoff.
     */
    public synchronized void requestLightRebuildNear(
            ClientLevel level, Iterable<BlockPos> lightPositions, double radius) {
        if (level == null || lightPositions == null || byConnection.isEmpty())
            return;
        double safeRadius = Math.max(0.0D, radius);
        for (BlockPos pos : lightPositions) {
            if (pos == null)
                continue;
            AABB affected = new AABB(pos).inflate(safeRadius);
            for (Map.Entry<UUID, RopeSectionSnapshot> entry : byConnection.entrySet()) {
                if (snapshotIntersects(entry.getValue(), affected)) {
                    pendingLightRebakes.add(entry.getKey());
                }
            }
        }
    }

    private static boolean snapshotIntersects(RopeSectionSnapshot snapshot, AABB box) {
        for (int i = 0; i < snapshot.nodeCount; i++) {
            if (box.contains(snapshot.x[i], snapshot.y[i], snapshot.z[i])) {
                return true;
            }
            if (i + 1 < snapshot.nodeCount && segmentBoundsIntersect(snapshot, i, box)) {
                return true;
            }
        }
        return false;
    }

    static boolean segmentBoundsIntersect(RopeSectionSnapshot snapshot, int segment, AABB box) {
        if (snapshot == null || box == null || segment < 0 || segment + 1 >= snapshot.nodeCount)
            return false;
        return segmentBoundsIntersect(
                snapshot.x[segment], snapshot.y[segment], snapshot.z[segment],
                snapshot.x[segment + 1], snapshot.y[segment + 1], snapshot.z[segment + 1], box);
    }

    static boolean segmentBoundsIntersect(
            double ax, double ay, double az, double bx, double by, double bz, AABB box) {
        if (box == null)
            return false;
        double minX = Math.min(ax, bx);
        double minY = Math.min(ay, by);
        double minZ = Math.min(az, bz);
        double maxX = Math.max(ax, bx);
        double maxY = Math.max(ay, by);
        double maxZ = Math.max(az, bz);
        return maxX >= box.minX && minX <= box.maxX
                && maxY >= box.minY && minY <= box.maxY
                && maxZ >= box.minZ && minZ <= box.maxZ;
    }

    public synchronized void rebuildFromCache(Level level) {
        if (level == null || !level.isClientSide())
            return;
        rebuild(level);
    }

    public synchronized void tickMaintain(Level level, Function<UUID, RopeSimulation> simLookup,
            Map<UUID, Double> lodDistanceByConnection) {
        if (level == null || !level.isClientSide() || simLookup == null)
            return;
        long now = level.getGameTime();
        if (now == lastMaintenanceTick)
            return;
        lastMaintenanceTick = now;
        updateHighLodConnections(lodDistanceByConnection);
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
        Set<UUID> desired = desiredScratch;
        Set<UUID> desiredFromSim = desiredFromSimScratch;
        desired.clear();
        desiredFromSim.clear();
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
                    if (!isMeshEligible(c.id(), sim, now)) {
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
        if (!pendingLightRebakes.isEmpty()
            && !connectionSyncDirty
            && !forceRebakeForMissingAnchors
            && desired.equals(claimed) && desiredFromSim.equals(claimedFromSim)) {
            refreshConnectionLights(level, simLookup, pendingLightRebakes, now);
            return;
        }
        if (!connectionSyncDirty
                && !forceRebakeForMissingAnchors
            && pendingLightRebakes.isEmpty()
                && desired.equals(claimed) && desiredFromSim.equals(claimedFromSim)) {
            clearPendingExpansion();
            return;
        }
        if (!connectionSyncDirty
                && !forceRebakeForMissingAnchors
                && pendingLightRebakes.isEmpty()
                && shouldDeferClaimExpansion(claimed, claimedFromSim, desired, desiredFromSim)
                && deferClaimExpansion(desired, desiredFromSim, now)) {
            return;
        }
        clearPendingExpansion();
        rebuildInternal(level, simLookup);
    }

    static boolean shouldDeferClaimExpansion(Set<UUID> claimed, Set<UUID> claimedFromSim,
            Set<UUID> desired, Set<UUID> desiredFromSim) {
        if (claimed == null || claimedFromSim == null || desired == null || desiredFromSim == null) {
            return false;
        }
        boolean changed = !desired.equals(claimed) || !desiredFromSim.equals(claimedFromSim);
        return changed && desired.containsAll(claimed) && desiredFromSim.containsAll(claimedFromSim);
    }

    private boolean deferClaimExpansion(Set<UUID> desired, Set<UUID> desiredFromSim, long currentTick) {
        if (pendingExpansionFirstTick == Long.MIN_VALUE || currentTick < pendingExpansionFirstTick) {
            pendingExpansionFirstTick = currentTick;
        }
        if (!desired.equals(pendingExpansionClaims) || !desiredFromSim.equals(pendingExpansionFromSim)
                || pendingExpansionSinceTick == Long.MIN_VALUE || currentTick < pendingExpansionSinceTick) {
            pendingExpansionClaims = Set.copyOf(desired);
            pendingExpansionFromSim = Set.copyOf(desiredFromSim);
            pendingExpansionSinceTick = currentTick;
        }
        return continueClaimExpansionDebounce(
                pendingExpansionFirstTick, pendingExpansionSinceTick, currentTick,
                CLAIM_EXPANSION_DEBOUNCE_TICKS, CLAIM_EXPANSION_MAX_DELAY_TICKS);
    }

    static boolean continueClaimExpansionDebounce(long firstTick, long changedTick, long currentTick,
            int quietTicks, int maxDelayTicks) {
        return firstTick != Long.MIN_VALUE && changedTick != Long.MIN_VALUE
                && currentTick >= firstTick && currentTick >= changedTick
                && currentTick - firstTick < Math.max(1, maxDelayTicks)
                && currentTick - changedTick < Math.max(1, quietTicks);
    }

    private void clearPendingExpansion() {
        pendingExpansionClaims = Set.of();
        pendingExpansionFromSim = Set.of();
        pendingExpansionFirstTick = Long.MIN_VALUE;
        pendingExpansionSinceTick = Long.MIN_VALUE;
    }

    private void refreshConnectionLights(Level level, Function<UUID, RopeSimulation> simLookup,
            Set<UUID> requested, long now) {
        if (requested.isEmpty())
            return;
        Map<UUID, LeadConnection> sourcesById = new HashMap<>();
        for (LeadConnection connection : realSources) {
            if (requested.contains(connection.id())) {
                sourcesById.put(connection.id(), connection);
            }
        }

        Map<UUID, RopeStaticGeometryResult> replacements = new HashMap<>();
        Set<UUID> completed = new HashSet<>();
        Set<Long> touchedSections = new HashSet<>();
        for (UUID id : requested) {
            if (!claimed.contains(id)) {
                completed.add(id);
                continue;
            }
            LeadConnection connection = sourcesById.get(id);
            if (connection == null || isDynamicallyHeld(id, now)) {
                completed.add(id);
                continue;
            }
                RopeStaticGeometryResult result = RopeStaticGeometry.relight(
                    currentPublishedGeometry(id), level, connection);
            if (!hasGeometry(result)) {
                continue;
            }
            replacements.put(id, result);
            touchedSections.addAll(connectionSections.getOrDefault(id, Set.of()));
            touchedSections.addAll(result.sectionKeys);
            completed.add(id);
        }
        if (replacements.isEmpty()) {
            pendingLightRebakes.removeAll(completed);
            return;
        }

        Map<Long, List<RopeSectionSnapshot>> nextBySection = new HashMap<>(bySection);
        Set<Long> changedSections = new HashSet<>();
        for (long section : touchedSections) {
            List<RopeSectionSnapshot> next = new ArrayList<>();
            List<RopeSectionSnapshot> previous = bySection.getOrDefault(section, List.of());
            Set<UUID> inserted = new HashSet<>();
            for (RopeSectionSnapshot snapshot : previous) {
                RopeStaticGeometryResult replacement = replacements.get(snapshot.connectionId);
                if (replacement == null) {
                    next.add(snapshot);
                } else if (inserted.add(snapshot.connectionId)) {
                    List<RopeSectionSnapshot> snapshots = replacement.snapshotsBySection.get(section);
                    if (snapshots != null) {
                        next.addAll(snapshots);
                    }
                }
            }
            // A replacement may newly span this section. Append it in real-source
            // order so repeated refreshes remain deterministic.
            for (LeadConnection connection : realSources) {
                RopeStaticGeometryResult replacement = replacements.get(connection.id());
                if (replacement != null && inserted.add(connection.id())) {
                    List<RopeSectionSnapshot> snapshots = replacement.snapshotsBySection.get(section);
                    if (snapshots != null) {
                        next.addAll(snapshots);
                    }
                }
            }
            if (next.isEmpty()) {
                nextBySection.remove(section);
                if (!previous.isEmpty())
                    changedSections.add(section);
            } else if (!sameSectionSnapshots(previous, next)) {
                nextBySection.put(section, List.copyOf(next));
                changedSections.add(section);
            }
        }

        Map<UUID, RopeSectionSnapshot> nextByConnection = new HashMap<>(byConnection);
        for (Map.Entry<UUID, RopeStaticGeometryResult> replacement : replacements.entrySet()) {
            nextByConnection.put(replacement.getKey(), replacement.getValue().snapshot);
            connectionSections.put(replacement.getKey(), replacement.getValue().sectionKeys);
        }
        HashSet<Long> nextMeshedSections = new HashSet<>(meshedSections);
        nextMeshedSections.retainAll(nextBySection.keySet());
        nextMeshedSections.removeAll(changedSections);
        bySection = Map.copyOf(nextBySection);
        publishedWatchdogSections.retainAll(nextBySection.keySet());
        publishedWatchdogSections.addAll(nextBySection.keySet());
        byConnection = Map.copyOf(nextByConnection);
        meshedSections = nextMeshedSections.isEmpty() ? Set.of() : Set.copyOf(nextMeshedSections);
        HashSet<UUID> nextAccepted = acceptedConnectionsForSections(connectionSections, meshedSections);
        acceptedConnections = nextAccepted.isEmpty() ? Set.of() : Set.copyOf(nextAccepted);
        acceptedTick.keySet().retainAll(acceptedConnections);
        sectionsAwaitingMesh.addAll(changedSections);
        pendingLightRebakes.removeAll(completed);
        markSectionsDirty(changedSections, true);
    }

    private RopeStaticGeometryResult currentPublishedGeometry(UUID connectionId) {
        Set<Long> sections = connectionSections.get(connectionId);
        if (sections == null || sections.isEmpty()) {
            return RopeStaticGeometryResult.EMPTY;
        }
        Map<Long, List<RopeSectionSnapshot>> snapshots = new HashMap<>();
        for (long section : sections) {
            List<RopeSectionSnapshot> sectionSnapshots = bySection.get(section);
            if (sectionSnapshots == null) {
                continue;
            }
            ArrayList<RopeSectionSnapshot> matching = new ArrayList<>();
            for (RopeSectionSnapshot snapshot : sectionSnapshots) {
                if (connectionId.equals(snapshot.connectionId)) {
                    matching.add(snapshot);
                }
            }
            if (!matching.isEmpty()) {
                snapshots.put(section, List.copyOf(matching));
            }
        }
        return snapshots.isEmpty()
                ? RopeStaticGeometryResult.EMPTY
                : new RopeStaticGeometryResult(snapshots, snapshots.keySet());
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
                && dynamicHoldUntil.isEmpty() && retiringMeshes.isEmpty() && !connectionSyncDirty)
            return;
        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        bySection = Map.of();
        publishedWatchdogSections.clear();
        byConnection = Map.of();
        bakedAttachments = List.of();
        claimed = Set.of();
        claimTick = Map.of();
        acceptedTick.clear();
        meshedSections = Set.of();
        acceptedConnections = Set.of();
        claimedFromSim = Set.of();
        connectionSections.clear();
        dynamicHoldUntil.clear();
        retiringMeshes.clear();
        retiringAttachments.clear();
        sectionGeneration.clear();
        compiledGeneration.clear();
        highLodEntryDebounce.clear();
        highLodConnections.clear();
        connectionSyncDirty = false;
        markSectionsDirty(toDirty, true);
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
        return isMeshEligible(connection.id(), sim, level.getGameTime())
            ? RopeStaticGeometry.buildFromSim(connection, sim, level)
            : null;
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

        Set<Long> previouslyMeshedSections = meshedSections;
        Set<Long> toDirty = changedSectionKeys(bySection, publishedBySection);
        HashSet<Long> nextMeshedSections = new HashSet<>(meshedSections);
        nextMeshedSections.retainAll(publishedBySection.keySet());
        nextMeshedSections.removeAll(toDirty);
        HashSet<UUID> nextAcceptedConnections = acceptedConnectionsForSections(next.connectionSections,
            nextMeshedSections);

        Map<UUID, Long> nextClaimTick = copyClaimTicks(next.claimed, now);

        bySection = Map.copyOf(publishedBySection);
        publishedWatchdogSections.retainAll(publishedBySection.keySet());
        publishedWatchdogSections.addAll(publishedBySection.keySet());
        byConnection = next.byConnection.isEmpty() ? Map.of() : Map.copyOf(next.byConnection);
        bakedAttachments = next.bakedAttachments.isEmpty() ? List.of() : List.copyOf(next.bakedAttachments);
        claimed = Set.copyOf(next.claimed);
        claimTick = Map.copyOf(nextClaimTick);
        meshedSections = nextMeshedSections.isEmpty() ? Set.of() : Set.copyOf(nextMeshedSections);
        acceptedConnections = nextAcceptedConnections.isEmpty() ? Set.of() : Set.copyOf(nextAcceptedConnections);
        acceptedTick.keySet().retainAll(acceptedConnections);
        claimedFromSim = Set.copyOf(next.claimedFromSim);
        highLodEntryDebounce.keySet().removeAll(next.claimed);
        sectionsAwaitingMesh.clear();
        sectionsAwaitingMesh.addAll(toDirty);
        connectionSections.clear();
        connectionSections.putAll(next.connectionSections);
        bakedWithMissingAnchors.retainAll(next.claimed);
        pendingLightRebakes.clear();
        connectionSyncDirty = false;

        HashSet<Long> urgentDirty = new HashSet<>(toDirty);
        urgentDirty.retainAll(previouslyMeshedSections);
        markSectionsDirty(urgentDirty, true);
        if (urgentDirty.size() != toDirty.size()) {
            HashSet<Long> newDirty = new HashSet<>(toDirty);
            newDirty.removeAll(urgentDirty);
            markSectionsDirty(newDirty, false);
        }
    }

    private static HashSet<UUID> acceptedConnectionsForSections(
            Map<UUID, Set<Long>> sectionsByConnection,
            Set<Long> acceptedSections) {
        HashSet<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, Set<Long>> entry : sectionsByConnection.entrySet()) {
            Set<Long> required = entry.getValue();
            if (!required.isEmpty() && acceptedSections.containsAll(required)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private static Set<Long> changedSectionKeys(Map<Long, List<RopeSectionSnapshot>> previous,
            Map<Long, List<RopeSectionSnapshot>> next) {
        HashSet<Long> changed = new HashSet<>();
        for (Map.Entry<Long, List<RopeSectionSnapshot>> entry : previous.entrySet()) {
            List<RopeSectionSnapshot> nextSnapshots = next.get(entry.getKey());
            if (nextSnapshots == null || !sameSectionSnapshots(entry.getValue(), nextSnapshots)) {
                changed.add(entry.getKey());
            }
        }
        for (Map.Entry<Long, List<RopeSectionSnapshot>> entry : next.entrySet()) {
            if (!previous.containsKey(entry.getKey())) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private static boolean sameSectionSnapshots(List<RopeSectionSnapshot> a, List<RopeSectionSnapshot> b) {
        if (a.size() != b.size())
            return false;
        for (int i = 0; i < a.size(); i++) {
            RopeSectionSnapshot left = a.get(i);
            RopeSectionSnapshot right = b.get(i);
            if (!sameSnapshot(left, right)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSnapshot(RopeSectionSnapshot a, RopeSectionSnapshot b) {
        return a.connectionId.equals(b.connectionId)
                && a.nodeCount == b.nodeCount
                && a.extractEnd == b.extractEnd
                && a.segmentStart == b.segmentStart
                && a.segmentEndExclusive == b.segmentEndExclusive
                && Arrays.equals(a.x, b.x)
                && Arrays.equals(a.y, b.y)
                && Arrays.equals(a.z, b.z)
                && Arrays.equals(a.sourceX, b.sourceX)
                && Arrays.equals(a.sourceY, b.sourceY)
                && Arrays.equals(a.sourceZ, b.sourceZ)
                && Arrays.equals(a.sx, b.sx)
                && Arrays.equals(a.sy, b.sy)
                && Arrays.equals(a.sz, b.sz)
                && Arrays.equals(a.ux, b.ux)
                && Arrays.equals(a.uy, b.uy)
                && Arrays.equals(a.uz, b.uz)
                && Arrays.equals(a.nodeLight, b.nodeLight)
                && Arrays.equals(a.segmentColorARGB, b.segmentColorARGB)
                && Arrays.equals(a.nodeThicknessScale, b.nodeThicknessScale)
                && a.attachmentLines.equals(b.attachmentLines);
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
        if (sim.ropeStackQuietTicks() >= CHUNK_MESH_STACK_QUIET_TICKS)
            return true;
        return false;
    }

    private boolean isQuiescent(UUID connectionId, RopeSimulation sim, long currentTick) {
        if (isWindCoolingDown(sim.lastWindActiveTick(), currentTick)) {
            return false;
        }
        return isQuiescent(sim);
    }

    private boolean isMeshEligible(UUID connectionId, RopeSimulation sim, long currentTick) {
        // A rope inside its configured wind-physics range must remain dynamic for the
        // whole wind cycle, including calm gaps. Baking during each gap and retiring
        // on the next gust repeatedly rebuilds chunk sections and produces visible
        // handoff stalls.
        if (sim.hasEnabledWindPhysics()) {
            highLodEntryDebounce.remove(connectionId);
            return false;
        }
        if (isQuiescent(connectionId, sim, currentTick)) {
            highLodEntryDebounce.remove(connectionId);
            return true;
        }
        if (isWindCoolingDown(sim.lastWindActiveTick(), currentTick)) {
            highLodEntryDebounce.remove(connectionId);
            return false;
        }
        if (!claimed.contains(connectionId)) {
            return isHighLodEntryStable(connectionId, sim);
        }
        highLodEntryDebounce.remove(connectionId);
        if (!shouldRetainClaim(sim.maxNodeMotionSqr())) {
            return false;
        }
        return true;
    }

    static boolean shouldRetainClaim(double motionSqr) {
        return motionSqr < HIGH_LOD_HARD_EXIT_MOTION_SQR;
    }

    static boolean isWindCoolingDown(long lastWindActiveTick, long currentTick) {
        return lastWindActiveTick != Long.MIN_VALUE
                && currentTick >= lastWindActiveTick
                && currentTick - lastWindActiveTick <= CHUNK_MESH_WIND_COOLDOWN_TICKS;
    }

    private boolean isHighLodEntryStable(UUID connectionId, RopeSimulation sim) {
        if (!isHighLod(connectionId)) {
            highLodEntryDebounce.remove(connectionId);
            return false;
        }
        ExitDebounce previous = highLodEntryDebounce.get(connectionId);
        ExitDebounce next = advanceExitDebounce(previous, sim.lastSteppedTick(), sim.maxNodeMotionSqr());
        if (next == null) {
            highLodEntryDebounce.remove(connectionId);
            return false;
        }
        highLodEntryDebounce.put(connectionId, next);
        return next.nonQuietSteps() >= HIGH_LOD_ENTRY_DEBOUNCE_STEPS;
    }

    private boolean isHighLod(UUID connectionId) {
        return highLodConnections.contains(connectionId);
    }

    private void updateHighLodConnections(Map<UUID, Double> lodDistanceByConnection) {
        highLodConnections.clear();
        if (lodDistanceByConnection == null || lodDistanceByConnection.isEmpty()) {
            return;
        }
        double threshold = ClientTuning.LOD_STRIDE4_DISTANCE.get();
        double thresholdSqr = threshold * threshold;
        for (Map.Entry<UUID, Double> entry : lodDistanceByConnection.entrySet()) {
            Double distanceSqr = entry.getValue();
            if (distanceSqr != null && distanceSqr > thresholdSqr) {
                highLodConnections.add(entry.getKey());
            }
        }
    }

    static ExitDebounce advanceExitDebounce(ExitDebounce previous, long steppedTick, double motionSqr) {
        if (motionSqr >= HIGH_LOD_HARD_EXIT_MOTION_SQR) {
            return null;
        }
        if (steppedTick == Long.MIN_VALUE) {
            return previous;
        }
        if (previous != null && previous.lastSteppedTick() == steppedTick) {
            return previous;
        }
        int count = previous == null || steppedTick < previous.lastSteppedTick()
                ? 1
                : previous.nonQuietSteps() + 1;
        return new ExitDebounce(steppedTick, count);
    }

    static record ExitDebounce(long lastSteppedTick, int nonQuietSteps) {
    }

    private boolean isDynamicallyHeld(UUID connectionId, long currentTick) {
        if (retiringMeshes.containsKey(connectionId))
            return true;
        Long until = dynamicHoldUntil.get(connectionId);
        return until != null && currentTick <= until;
    }

    private void pruneDynamicHolds(long currentTick) {
        if (!dynamicHoldUntil.isEmpty()) {
            dynamicHoldUntil.entrySet().removeIf(entry -> currentTick > entry.getValue());
        }
        if (!retiringMeshes.isEmpty()) {
            retiringMeshes.entrySet().removeIf(entry -> retirementReleased(entry.getValue(), currentTick));
        }
        retiringAttachments.keySet().removeIf(id -> !retiringMeshes.containsKey(id));
    }

    private static boolean retirementReleased(RetiringMesh retiring, long currentTick) {
        return currentTick - retiring.startedTick() >= CHUNK_MESH_RETIRE_TIMEOUT_TICKS
                || (retiring.completedTick() != Long.MIN_VALUE
                        && currentTick - retiring.completedTick() >= CHUNK_MESH_RETIRE_GRACE_TICKS);
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

    private void markSectionsDirty(Set<Long> sectionKeys, boolean urgent) {
        if (sectionKeys.isEmpty())
            return;
        for (long section : sectionKeys) {
            sectionGeneration.put(section, sectionGeneration.getOrDefault(section, 0L) + 1L);
        }
        if (urgent) {
            pendingDirtySections.removeAll(sectionKeys);
            pendingUrgentDirtySections.addAll(sectionKeys);
        } else {
            for (long key : sectionKeys) {
                if (!pendingUrgentDirtySections.contains(key)) {
                    pendingDirtySections.add(key);
                }
            }
        }
        debugDirtyQueue = pendingDirtySections.size() + pendingUrgentDirtySections.size();
    }

    private static void submitDirtySectionsNow(Minecraft mc, Set<Long> sectionKeys) {
        if (sectionKeys.isEmpty() || mc.levelRenderer == null)
            return;
        try {
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
        } catch (NullPointerException ignored) {
            // During world shutdown LevelRenderer may still be non-null while its
            // internal ViewArea has already been released. Dirtying sections is only a
            // rebuild hint, so it is safe to drop it at teardown instead of crashing.
        }
    }

    private void clearDebugCounts() {
        debugEligible = 0;
        debugWaitingQuiet = 0;
        debugReadyFromSim = 0;
        debugReadyAnchorBake = 0;
        debugDirtyQueue = 0;
        debugDirtyFlushedLastTick = 0;
    }

    static boolean buildRetryDue(long submittedAt, long currentTick, int retryTicks) {
        return submittedAt == Long.MIN_VALUE
                || currentTick < submittedAt
                || currentTick - submittedAt >= Math.max(1, retryTicks);
    }

    static boolean watchdogProbeDue(long lastProbeTick, long currentTick, int intervalTicks) {
        return lastProbeTick == Long.MIN_VALUE
                || currentTick < lastProbeTick
                || currentTick - lastProbeTick >= Math.max(1, intervalTicks);
    }

    static Set<Long> queueDueUnmeshedRetries(
            Set<Long> awaiting, Set<Long> published, Set<Long> meshed,
            Set<Long> pendingNormal, Set<Long> pendingUrgent,
            Map<Long, Long> lastSubmittedAt, long currentTick, int retryTicks, int budget) {
        if (awaiting.isEmpty()) {
            return Set.of();
        }
        awaiting.removeIf(key -> !published.contains(key) || meshed.contains(key));
        LinkedHashSet<Long> queued = new LinkedHashSet<>();
        for (long key : awaiting) {
            if (queued.size() >= Math.max(0, budget)) {
                break;
            }
            if (pendingNormal.contains(key) || pendingUrgent.contains(key)) {
                continue;
            }
            long submittedAt = lastSubmittedAt.getOrDefault(key, Long.MIN_VALUE);
            if (buildRetryDue(submittedAt, currentTick, retryTicks)) {
                queued.add(key);
            }
        }
        return queued.isEmpty() ? Set.of() : Set.copyOf(queued);
    }

    static boolean generationsReached(Map<Long, Long> targets, Map<Long, Long> compiled) {
        for (Map.Entry<Long, Long> target : targets.entrySet()) {
            if (compiled.getOrDefault(target.getKey(), Long.MIN_VALUE) < target.getValue())
                return false;
        }
        return true;
    }

    public record SectionBuild(List<RopeSectionSnapshot> snapshots, long generation) {
    }

    private record RetiringMesh(Map<Long, Long> targetGeneration, long startedTick, long completedTick) {
    }
}
