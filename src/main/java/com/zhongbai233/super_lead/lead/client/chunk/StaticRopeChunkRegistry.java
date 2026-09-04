package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.mixin.LevelRendererAccessor;
import com.zhongbai233.super_lead.mixin.ViewAreaAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
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

    // Mesh eligibility now reads the simulation's own at-rest state instead of
    // registry-owned motion thresholds. The solver's isVisuallyAtRest() (its
    // settled-tick counter over settleMotionSqr) replaces the old
    // CHUNK_MESH_QUIET_MOTION_SQR entry threshold, the HIGH_LOD hard-exit
    // threshold and the per-LOD entry debounce, all of which could disagree with
    // the solver's own idea of "settled" and flap a rope in and out of its mesh.
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
    /**
     * Reach of a block change for {@link #invalidateNearBlock}. A changed block can
     * alter its own collision shape and, through connections (fences, walls, panes),
     * its direct neighbours' — but it cannot move a rope two full blocks away. The
     * old 2.0-block inflation tore down every mesh in a 5x5x5 column around any
     * placed or broken block, which made ordinary building next to rope stacks cycle
     * their meshes constantly.
     */
    private static final double BLOCK_CHANGE_INVALIDATE_RADIUS = 1.05D;
    // The 1536-rope mesh-churn matrix selected 8: it kept 12-section bursts below
    // the 16.67 ms frame budget while completing the replacement in two ticks.
    private static final int DEFAULT_URGENT_DIRTY_SECTIONS_PER_TICK = 8;
    private static final int DEFAULT_NEW_MESH_SECTIONS_PER_TICK = 2;
    private static final int UNOBSERVED_BUILD_RETRY_TICKS = 20;
    private static final int WATCHDOG_INTERVAL_TICKS = 20;
    private static final int CLAIM_EXPANSION_DEBOUNCE_TICKS = 3;
    private static final int CLAIM_EXPANSION_MAX_DELAY_TICKS = 8;

    public static StaticRopeChunkRegistry get() {
        return INSTANCE;
    }

    /** Per-key publication lets one rope hand off without copying the whole registry. */
    private final Map<Long, List<RopeSectionSnapshot>> bySection = new ConcurrentHashMap<>();
    private final Map<UUID, RopeSectionSnapshot> byConnection = new ConcurrentHashMap<>();
    private volatile List<RopeAttachmentRenderer.BakedAttachment> bakedAttachments = List.of();
    /** Per-connection bake products are immutable; only the index itself mutates. */
    private final Map<UUID, RopeStaticGeometryResult> publishedGeometry = new HashMap<>();
    private final Map<UUID, List<RopeAttachmentRenderer.BakedAttachment>> publishedAttachments = new HashMap<>();
    private final Set<UUID> claimed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> claimTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> acceptedTick = new HashMap<>();
    private final Set<Long> meshedSections = ConcurrentHashMap.newKeySet();
    private final Set<UUID> acceptedConnections = ConcurrentHashMap.newKeySet();
    private final Set<UUID> claimedFromSim = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<Long>> connectionSections = new ConcurrentHashMap<>();
    /** Unclaimed real ropes are the only ones whose settle state needs polling. */
    private final Set<UUID> meshCandidates = new LinkedHashSet<>();
    private final Map<UUID, LeadConnection> realSourcesById = new HashMap<>();
    private final Map<UUID, Integer> sourceOrder = new HashMap<>();
    private final Set<Long> pendingDirtySections = new LinkedHashSet<>();
    private final Set<Long> pendingUrgentDirtySections = new LinkedHashSet<>();
    private final Set<Long> pendingDirectRebuildSections = new LinkedHashSet<>();
    private final Map<Long, Long> lastDirtySubmitTick = new HashMap<>();
    private final Set<Long> sectionsAwaitingMesh = new LinkedHashSet<>();
    private final Set<Long> publishedWatchdogSections = new LinkedHashSet<>();
    private final Map<Long, Long> sectionGeneration = new HashMap<>();
    private final Map<Long, Long> compiledGeneration = new HashMap<>();
    private final Map<UUID, RetiringMesh> retiringMeshes = new HashMap<>();
    private final Map<UUID, List<RopeAttachmentRenderer.BakedAttachment>> retiringAttachments = new HashMap<>();
    private final Map<UUID, Long> dynamicHoldUntil = new HashMap<>();
    private final Map<UUID, DynamicHoldDiagnostics> dynamicHoldDiagnostics = new HashMap<>();
    /**
     * Claimed ropes whose baked light may have changed. Requests are coalesced until
     * the next maintenance pass so several nearby dynamic lights cause one rebuild.
     */
    private final Set<UUID> pendingLightRebakes = new HashSet<>();
    private final Set<UUID> desiredScratch = new HashSet<>();
    private final Set<UUID> desiredFromSimScratch = new HashSet<>();
    private long lastMaintenanceTick = Long.MIN_VALUE;
    private long lastWatchdogProbeTick = Long.MIN_VALUE;
    private Set<UUID> pendingExpansionClaims = Set.of();
    private Set<UUID> pendingExpansionFromSim = Set.of();
    private long pendingExpansionFirstTick = Long.MIN_VALUE;
    private long pendingExpansionSinceTick = Long.MIN_VALUE;
    /**
     * Static bakes made before anchor chunks load use default block shapes; retry
     * them once both anchor chunks arrive.
     */
    private final Set<UUID> bakedWithMissingAnchors = new HashSet<>();

    private int urgentDirtySectionsPerTick = DEFAULT_URGENT_DIRTY_SECTIONS_PER_TICK;
    private int newMeshSectionsPerTick = DEFAULT_NEW_MESH_SECTIONS_PER_TICK;

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
        while (it.hasNext() && out.size() < urgentDirtySectionsPerTick) {
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
                currentTick, UNOBSERVED_BUILD_RETRY_TICKS, newMeshSectionsPerTick);
        pendingUrgentDirtySections.addAll(queued);
        pendingDirectRebuildSections.addAll(queued);
        debugDirtyQueue = pendingDirtySections.size() + pendingUrgentDirtySections.size();
    }

    private synchronized void markSectionMeshAccepted(long sectionPosLong, long currentTick) {
        if (!bySection.containsKey(sectionPosLong) || !meshedSections.add(sectionPosLong))
            return;
        // Only connections present in the completed section can cross the
        // all-required-sections barrier. Already accepted bystanders deliberately
        // stay accepted while a shared section replaces its previous GPU buffer.
        HashSet<UUID> candidates = new HashSet<>();
        for (RopeSectionSnapshot snapshot : bySection.getOrDefault(sectionPosLong, List.of())) {
            candidates.add(snapshot.connectionId);
        }
        for (UUID id : candidates) {
            Set<Long> required = connectionSections.get(id);
            if (required != null && !required.isEmpty()
                    && meshedSections.containsAll(required)
                    && acceptedConnections.add(id)) {
                acceptedTick.put(id, currentTick);
            }
        }
        sectionsAwaitingMesh.remove(sectionPosLong);
    }

    public synchronized boolean isMeshAccepted(UUID connectionId) {
        return connectionId != null && acceptedConnections.contains(connectionId);
    }

    public boolean isClaimed(UUID connectionId) {
        return connectionId != null && claimed.contains(connectionId);
    }

    public synchronized int connectionSectionCount(UUID connectionId) {
        Set<Long> sections = connectionSections.get(connectionId);
        return sections == null ? 0 : sections.size();
    }

    /** Read-only diagnostics for one connection's section-build barrier. */
    public synchronized ConnectionMeshDiagnostics connectionMeshDiagnostics(UUID connectionId) {
        Set<Long> sections = connectionSections.get(connectionId);
        if (sections == null || sections.isEmpty()) {
            return ConnectionMeshDiagnostics.EMPTY;
        }
        int accepted = 0;
        int awaiting = 0;
        int pending = 0;
        long firstMissing = Long.MIN_VALUE;
        for (long section : sections) {
            if (meshedSections.contains(section)) {
                accepted++;
                continue;
            }
            if (firstMissing == Long.MIN_VALUE) {
                firstMissing = section;
            }
            if (sectionsAwaitingMesh.contains(section)) {
                awaiting++;
            }
            if (pendingDirtySections.contains(section) || pendingUrgentDirtySections.contains(section)) {
                pending++;
            }
        }
        return new ConnectionMeshDiagnostics(
                sections.size(), accepted, awaiting, pending,
                firstMissing,
                firstMissing == Long.MIN_VALUE ? Long.MIN_VALUE
                        : sectionGeneration.getOrDefault(firstMissing, Long.MIN_VALUE),
                firstMissing == Long.MIN_VALUE ? Long.MIN_VALUE
                        : compiledGeneration.getOrDefault(firstMissing, Long.MIN_VALUE),
                firstMissing == Long.MIN_VALUE ? Long.MIN_VALUE
                        : lastDirtySubmitTick.getOrDefault(firstMissing, Long.MIN_VALUE));
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

    synchronized void configureSectionBudgetsForBench(int urgent, int normal) {
        if (urgent < 1 || normal < 0 || normal > urgent) {
            throw new IllegalArgumentException("invalid mesh section budgets: urgent="
                    + urgent + " normal=" + normal);
        }
        urgentDirtySectionsPerTick = urgent;
        newMeshSectionsPerTick = normal;
    }

    synchronized void resetSectionBudgetsForBench() {
        urgentDirtySectionsPerTick = DEFAULT_URGENT_DIRTY_SECTIONS_PER_TICK;
        newMeshSectionsPerTick = DEFAULT_NEW_MESH_SECTIONS_PER_TICK;
    }

    public synchronized void clear() {
        bySection.clear();
        byConnection.clear();
        bakedAttachments = List.of();
        publishedGeometry.clear();
        publishedAttachments.clear();
        claimed.clear();
        claimTick.clear();
        acceptedTick.clear();
        meshedSections.clear();
        acceptedConnections.clear();
        claimedFromSim.clear();
        connectionSections.clear();
        meshCandidates.clear();
        realSourcesById.clear();
        sourceOrder.clear();
        bakedWithMissingAnchors.clear();
        dynamicHoldUntil.clear();
        dynamicHoldDiagnostics.clear();
        pendingLightRebakes.clear();
        desiredScratch.clear();
        desiredFromSimScratch.clear();
        lastMaintenanceTick = Long.MIN_VALUE;
        lastWatchdogProbeTick = Long.MIN_VALUE;
        sectionsAwaitingMesh.clear();
        publishedWatchdogSections.clear();
        pendingDirtySections.clear();
        pendingUrgentDirtySections.clear();
        pendingDirectRebuildSections.clear();
        lastDirtySubmitTick.clear();
        sectionGeneration.clear();
        compiledGeneration.clear();
        retiringMeshes.clear();
        retiringAttachments.clear();
        clearPendingExpansion();
        urgentDirtySectionsPerTick = DEFAULT_URGENT_DIRTY_SECTIONS_PER_TICK;
        newMeshSectionsPerTick = DEFAULT_NEW_MESH_SECTIONS_PER_TICK;
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
                urgentDirtySectionsPerTick, newMeshSectionsPerTick);
        submitDirtySectionsNow(mc, dirty);
        HashSet<Long> direct = new HashSet<>(dirty);
        direct.retainAll(pendingDirectRebuildSections);
        pendingDirectRebuildSections.removeAll(dirty);
        submitDirectRebuildsNow(mc, direct);
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
        Set<UUID> removing = new HashSet<>(stressSources.size());
        for (StressSource source : stressSources) {
            removing.add(source.id());
        }
        stressSources = List.of();
        rebuildSourceOrder();
        invalidateConnections(level, removing);
    }

    public synchronized void onConnectionsReplaced(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide())
            return;
        if (realSources.equals(connections))
            return;
        HashSet<UUID> changed = new HashSet<>(realSourcesById.keySet());
        for (LeadConnection connection : connections) {
            LeadConnection previous = realSourcesById.get(connection.id());
            if (connection.equals(previous)) {
                changed.remove(connection.id());
            } else {
                changed.add(connection.id());
            }
        }
        setRealSources(connections);
        invalidateConnections(level, changed);
    }

    public synchronized void onConnectionsChanged(Level level, List<LeadConnection> connections,
            Iterable<UUID> changedIds) {
        if (level == null || !level.isClientSide())
            return;
        Set<UUID> changed = new HashSet<>();
        if (changedIds != null) {
            for (UUID id : changedIds) {
                if (id != null) {
                    changed.add(id);
                }
            }
        }
        setRealSources(connections);
        invalidateConnections(level, changed);
    }

    public synchronized void publishStressSources(Level level, List<StressSource> sources) {
        if (level == null || !level.isClientSide())
            return;
        List<StressSource> nextSources = sources == null || sources.isEmpty()
                ? List.of() : List.copyOf(sources);
        Map<UUID, StressSource> previousById = new HashMap<>();
        for (StressSource source : stressSources) {
            previousById.put(source.id(), source);
        }
        Map<UUID, StressSource> nextById = new HashMap<>();
        Set<UUID> changed = new HashSet<>(previousById.keySet());
        for (StressSource source : nextSources) {
            nextById.put(source.id(), source);
            if (source.equals(previousById.get(source.id()))) {
                changed.remove(source.id());
            } else {
                changed.add(source.id());
            }
        }
        stressSources = nextSources;
        rebuildSourceOrder();
        invalidateConnections(level, changed);

        Map<UUID, GeometryPublication> publications = new HashMap<>();
        for (UUID id : changed) {
            StressSource source = nextById.get(id);
            if (source == null)
                continue;
            RopeStaticGeometryResult result = RopeStaticGeometry.build(source.id(), source.a(), source.b(), level);
            if (hasGeometry(result)) {
                publications.put(id, new GeometryPublication(result, List.of(), false, false));
            }
        }
        publishConnectionGeometries(publications, level.getGameTime());
    }

    public synchronized void invalidateAll(Level level, List<LeadConnection> connections) {
        if (level == null || !level.isClientSide())
            return;
        setRealSources(connections);
        HashSet<UUID> removing = new HashSet<>(claimed);
        invalidateConnections(level, removing);
        meshCandidates.addAll(realSourcesById.keySet());
    }

    private void setRealSources(List<LeadConnection> connections) {
        realSources = connections == null || connections.isEmpty()
                ? List.of() : List.copyOf(connections);
        realSourcesById.clear();
        for (LeadConnection connection : realSources) {
            realSourcesById.put(connection.id(), connection);
        }
        rebuildSourceOrder();
        meshCandidates.retainAll(realSourcesById.keySet());
        for (UUID id : realSourcesById.keySet()) {
            if (!claimed.contains(id)) {
                meshCandidates.add(id);
            }
        }
    }

    private void rebuildSourceOrder() {
        sourceOrder.clear();
        int order = 0;
        for (LeadConnection connection : realSources) {
            sourceOrder.put(connection.id(), order++);
        }
        for (StressSource source : stressSources) {
            sourceOrder.put(source.id(), order++);
        }
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
        Set<Long> dirty = new HashSet<>();
        for (UUID id : removing) {
            Set<Long> sections = connectionSections.getOrDefault(id, Set.of());
            if (!Collections.disjoint(sections, meshedSections)) {
                retiring.add(id);
            }
            dirty.addAll(sections);
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

        boolean attachmentViewChanged = false;
        for (UUID id : removing) {
            connectionSections.remove(id);
            byConnection.remove(id);
            publishedGeometry.remove(id);
            List<RopeAttachmentRenderer.BakedAttachment> removedAttachments = publishedAttachments.remove(id);
            if (removedAttachments != null && !removedAttachments.isEmpty()) {
                attachmentViewChanged = true;
                if (retiring.contains(id)) {
                    retiringAttachments.put(id, removedAttachments);
                }
            }
            claimed.remove(id);
            claimedFromSim.remove(id);
            claimTick.remove(id);
            acceptedConnections.remove(id);
            acceptedTick.remove(id);
            pendingLightRebakes.remove(id);
            bakedWithMissingAnchors.remove(id);
            if (realSourcesById.containsKey(id)) {
                meshCandidates.add(id);
            } else {
                meshCandidates.remove(id);
            }
        }

        for (long section : dirty) {
            List<RopeSectionSnapshot> previous = bySection.get(section);
            if (previous != null) {
                List<RopeSectionSnapshot> next = withoutConnections(previous, removing);
                if (next.isEmpty()) {
                    bySection.remove(section);
                    publishedWatchdogSections.remove(section);
                    sectionsAwaitingMesh.remove(section);
                } else {
                    bySection.put(section, next);
                    publishedWatchdogSections.add(section);
                    sectionsAwaitingMesh.add(section);
                }
            }
            meshedSections.remove(section);
        }
        if (attachmentViewChanged) {
            rebuildBakedAttachmentView();
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

    static List<RopeSectionSnapshot> withoutConnections(
            List<RopeSectionSnapshot> snapshots, Set<UUID> removing) {
        ArrayList<RopeSectionSnapshot> kept = new ArrayList<>(snapshots.size());
        for (RopeSectionSnapshot snapshot : snapshots) {
            if (!removing.contains(snapshot.connectionId)) {
                kept.add(snapshot);
            }
        }
        return kept.isEmpty() ? List.of() : List.copyOf(kept);
    }

    private void rebuildBakedAttachmentView() {
        ArrayList<RopeAttachmentRenderer.BakedAttachment> next = new ArrayList<>();
        for (LeadConnection source : realSources) {
            List<RopeAttachmentRenderer.BakedAttachment> attachments = publishedAttachments.get(source.id());
            if (attachments != null) {
                next.addAll(attachments);
            }
        }
        bakedAttachments = next.isEmpty() ? List.of() : List.copyOf(next);
    }

    public synchronized void holdDynamic(Level level, UUID connectionId, long untilTick) {
        holdDynamic(level, connectionId, untilTick, "unspecified");
    }

    public synchronized void holdDynamic(Level level, UUID connectionId, long untilTick, String reason) {
        if (level == null || !level.isClientSide() || connectionId == null)
            return;
        holdDynamic(level, Set.of(connectionId), untilTick, reason);
    }

    public synchronized void holdDynamic(Level level, Iterable<UUID> connectionIds, long untilTick) {
        holdDynamic(level, connectionIds, untilTick, "unspecified");
    }

    public synchronized void holdDynamic(
            Level level, Iterable<UUID> connectionIds, long untilTick, String reason) {
        if (level == null || !level.isClientSide() || connectionIds == null)
            return;
        Set<UUID> ids = new HashSet<>();
        for (UUID id : connectionIds) {
            if (id != null)
                ids.add(id);
        }
        if (ids.isEmpty())
            return;
        long now = level.getGameTime();
        long effectiveUntil = Math.max(untilTick, now + CHUNK_MESH_DYNAMIC_HOLD_MIN_TICKS);
        String diagnosticReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        Set<UUID> newlyHeldPublished = new HashSet<>();
        for (UUID id : ids) {
            DynamicHoldDiagnostics previousDiagnostics = dynamicHoldDiagnostics.get(id);
            int count = previousDiagnostics == null ? 1 : previousDiagnostics.count() + 1;
            dynamicHoldDiagnostics.put(id,
                    new DynamicHoldDiagnostics(diagnosticReason, now, effectiveUntil, count));
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

    public synchronized DynamicHoldDiagnostics dynamicHoldDiagnostics(UUID connectionId) {
        return dynamicHoldDiagnostics.getOrDefault(connectionId, DynamicHoldDiagnostics.NONE);
    }

    public synchronized Set<UUID> invalidateNearBlock(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null || byConnection.isEmpty())
            return Set.of();
        AABB changed = new AABB(pos).inflate(BLOCK_CHANGE_INVALIDATE_RADIUS);
        Set<UUID> affected = new HashSet<>();
        for (Map.Entry<UUID, RopeSectionSnapshot> entry : byConnection.entrySet()) {
            if (snapshotIntersects(entry.getValue(), changed)) {
                affected.add(entry.getKey());
            }
        }
        long until = level.getGameTime() + 8L;
        holdDynamic(level, affected, until, "terrain-block");
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
        rebuildInternal(level, null);
    }

    public synchronized void tickMaintain(Level level, Function<UUID, RopeSimulation> simLookup) {
        if (level == null || !level.isClientSide() || simLookup == null)
            return;
        long now = level.getGameTime();
        if (now == lastMaintenanceTick)
            return;
        lastMaintenanceTick = now;
        pruneDynamicHolds(now);
        boolean enabled = ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();
        if (!enabled) {
            if (!claimed.isEmpty()) {
                invalidateConnections(level, Set.copyOf(claimed));
            }
            meshCandidates.addAll(realSourcesById.keySet());
            clearPendingExpansion();
            updateCandidateDebugCounts(0, 0);
            return;
        }

        // Accepted and already-claimed ropes no longer need a per-tick eligibility
        // scan. Explicit invalidation puts only the affected IDs back here.
        Set<UUID> ready = desiredScratch;
        Set<UUID> readyFromSim = desiredFromSimScratch;
        ready.clear();
        readyFromSim.clear();
        int waitingQuiet = 0;
        for (UUID id : meshCandidates) {
            LeadConnection connection = realSourcesById.get(id);
            if (connection == null || skipStaticMeshForTransparency(
                    RopeTuning.forConnection(connection), connection)) {
                continue;
            }
            if (isDynamicallyHeld(id, now)) {
                waitingQuiet++;
                continue;
            }
            RopeSimulation sim = simLookup.apply(id);
            if (sim != null && !isMeshEligible(id, sim, now)) {
                waitingQuiet++;
                continue;
            }
            ready.add(id);
            if (sim != null) {
                readyFromSim.add(id);
            }
        }
        updateCandidateDebugCounts(waitingQuiet, readyFromSim.size());

        // Retry only the connections whose original anchor-only bake happened
        // before both endpoint chunks were available.
        HashSet<UUID> missingAnchorRebuilds = new HashSet<>();
        for (UUID id : bakedWithMissingAnchors) {
            LeadConnection connection = realSourcesById.get(id);
            if (connection != null && anchorsLoaded(level, connection)) {
                missingAnchorRebuilds.add(id);
            }
        }
        if (!missingAnchorRebuilds.isEmpty()) {
            rebuildClaimedConnectionsIncrementally(level, simLookup, missingAnchorRebuilds, now);
        }

        if (!pendingLightRebakes.isEmpty()) {
            refreshConnectionLights(level, simLookup, pendingLightRebakes, now);
        }
        if (ready.isEmpty()) {
            clearPendingExpansion();
            return;
        }
        if (deferClaimExpansion(ready, readyFromSim, now)) {
            return;
        }
        clearPendingExpansion();
        addRealConnectionsIncrementally(level, simLookup, ready, readyFromSim, now);
    }

    private void updateCandidateDebugCounts(int waitingQuiet, int newlyReadyFromSim) {
        debugEligible = realSourcesById.size();
        debugWaitingQuiet = waitingQuiet;
        debugReadyFromSim = claimedFromSim.size() + newlyReadyFromSim;
        debugReadyAnchorBake = Math.max(0,
                claimed.size() - claimedFromSim.size() + desiredScratch.size() - newlyReadyFromSim);
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

    private void addRealConnectionsIncrementally(Level level, Function<UUID, RopeSimulation> simLookup,
            Set<UUID> connectionIds, Set<UUID> fromSimIds, long now) {
        Map<UUID, GeometryPublication> publications = new HashMap<>();
        for (UUID id : connectionIds) {
            if (claimed.contains(id)) {
                meshCandidates.remove(id);
                continue;
            }
            LeadConnection connection = realSourcesById.get(id);
            if (connection == null) {
                meshCandidates.remove(id);
                continue;
            }
            RopeSimulation sim = fromSimIds.contains(id) ? simLookup.apply(id) : null;
            RopeStaticGeometryResult result = buildRealSourceGeometry(level, connection, sim);
            if (!hasGeometry(result)) {
                continue;
            }
            List<RopeAttachmentRenderer.BakedAttachment> attachments = RopeAttachmentRenderer.bakeStatic(
                    level, connection, result.snapshot.x, result.snapshot.y, result.snapshot.z);
            publications.put(id, new GeometryPublication(result, attachments, sim != null, false));
            updateMissingAnchorBake(level, connection, sim == null);
        }
        publishConnectionGeometries(publications, now);
    }

    private void rebuildClaimedConnectionsIncrementally(Level level, Function<UUID, RopeSimulation> simLookup,
            Set<UUID> connectionIds, long now) {
        Map<UUID, GeometryPublication> publications = new HashMap<>();
        for (UUID id : connectionIds) {
            if (!claimed.contains(id)) {
                bakedWithMissingAnchors.remove(id);
                continue;
            }
            LeadConnection connection = realSourcesById.get(id);
            if (connection == null) {
                continue;
            }
            RopeSimulation sim = claimedFromSim.contains(id) ? simLookup.apply(id) : null;
            RopeStaticGeometryResult result = buildRealSourceGeometry(level, connection, sim);
            if (!hasGeometry(result)) {
                continue;
            }
            List<RopeAttachmentRenderer.BakedAttachment> attachments = RopeAttachmentRenderer.bakeStatic(
                    level, connection, result.snapshot.x, result.snapshot.y, result.snapshot.z);
            publications.put(id, new GeometryPublication(result, attachments, sim != null, true));
            updateMissingAnchorBake(level, connection, sim == null);
        }
        publishConnectionGeometries(publications, now);
    }

    /**
     * Publishes a connection batch with one copy/sort/compare per touched section.
     * A dense section receiving many ropes therefore remains O(existing + changed)
     * instead of repeatedly copying the growing list for every connection.
     */
    private void publishConnectionGeometries(Map<UUID, GeometryPublication> publications, long now) {
        if (publications.isEmpty()) {
            return;
        }
        Set<UUID> ids = publications.keySet();
        Set<Long> touchedSections = new HashSet<>();
        for (Map.Entry<UUID, GeometryPublication> entry : publications.entrySet()) {
            touchedSections.addAll(connectionSections.getOrDefault(entry.getKey(), Set.of()));
            touchedSections.addAll(entry.getValue().geometry.sectionKeys);
        }

        Set<Long> urgentDirty = new HashSet<>();
        Set<Long> normalDirty = new HashSet<>();
        for (long section : touchedSections) {
            List<RopeSectionSnapshot> previous = bySection.getOrDefault(section, List.of());
            ArrayList<RopeSectionSnapshot> next = new ArrayList<>(previous.size() + publications.size());
            for (RopeSectionSnapshot snapshot : previous) {
                if (!ids.contains(snapshot.connectionId)) {
                    next.add(snapshot);
                }
            }
            for (GeometryPublication publication : publications.values()) {
                next.addAll(publication.geometry.snapshotsBySection.getOrDefault(section, List.of()));
            }
            orderSectionSnapshots(next);
            List<RopeSectionSnapshot> published = next.isEmpty() ? List.of() : List.copyOf(next);
            if (sameSectionSnapshots(previous, published)) {
                continue;
            }
            boolean wasMeshed = meshedSections.remove(section);
            if (published.isEmpty()) {
                bySection.remove(section);
                publishedWatchdogSections.remove(section);
                sectionsAwaitingMesh.remove(section);
            } else {
                bySection.put(section, published);
                publishedWatchdogSections.add(section);
                sectionsAwaitingMesh.add(section);
            }
            if (wasMeshed) {
                urgentDirty.add(section);
            } else {
                normalDirty.add(section);
            }
        }

        boolean attachmentViewChanged = false;
        for (Map.Entry<UUID, GeometryPublication> entry : publications.entrySet()) {
            UUID id = entry.getKey();
            GeometryPublication publication = entry.getValue();
            RopeStaticGeometryResult result = publication.geometry;
            byConnection.put(id, result.snapshot);
            publishedGeometry.put(id, result);
            connectionSections.put(id, Set.copyOf(result.sectionKeys));
            claimed.add(id);
            claimTick.putIfAbsent(id, now);
            if (publication.fromSim) {
                claimedFromSim.add(id);
            } else {
                claimedFromSim.remove(id);
            }
            if (!publication.preserveAcceptance) {
                acceptedConnections.remove(id);
                acceptedTick.remove(id);
            }
            meshCandidates.remove(id);

            List<RopeAttachmentRenderer.BakedAttachment> immutableAttachments =
                    publication.attachments == null || publication.attachments.isEmpty()
                            ? List.of() : List.copyOf(publication.attachments);
            List<RopeAttachmentRenderer.BakedAttachment> previousAttachments = publishedAttachments.get(id);
            if (immutableAttachments.isEmpty()) {
                publishedAttachments.remove(id);
            } else {
                publishedAttachments.put(id, immutableAttachments);
            }
            attachmentViewChanged |= !Objects.equals(previousAttachments, immutableAttachments);
        }
        finishIncrementalPublication(urgentDirty, normalDirty, attachmentViewChanged);
    }

    private void orderSectionSnapshots(List<RopeSectionSnapshot> snapshots) {
        snapshots.sort(Comparator
                .comparingInt((RopeSectionSnapshot snapshot) ->
                        sourceOrder.getOrDefault(snapshot.connectionId, Integer.MAX_VALUE))
                .thenComparing(snapshot -> snapshot.connectionId));
    }

    private void finishIncrementalPublication(Set<Long> urgentDirty, Set<Long> normalDirty,
            boolean attachmentViewChanged) {
        normalDirty.removeAll(urgentDirty);
        markSectionsDirty(urgentDirty, true);
        markSectionsDirty(normalDirty, false);
        if (attachmentViewChanged) {
            rebuildBakedAttachmentView();
        }
    }

    private void refreshConnectionLights(Level level, Function<UUID, RopeSimulation> simLookup,
            Set<UUID> requested, long now) {
        if (requested.isEmpty())
            return;
        Map<UUID, GeometryPublication> publications = new HashMap<>();
        Set<UUID> completed = new HashSet<>();
        for (UUID id : requested) {
            if (!claimed.contains(id)) {
                completed.add(id);
                continue;
            }
            LeadConnection connection = realSourcesById.get(id);
            if (connection == null || isDynamicallyHeld(id, now)) {
                completed.add(id);
                continue;
            }
            RopeStaticGeometryResult result = RopeStaticGeometry.relight(
                    currentPublishedGeometry(id), level, connection);
            if (!hasGeometry(result)) {
                continue;
            }
            publications.put(id, new GeometryPublication(
                    result,
                    publishedAttachments.getOrDefault(id, List.of()),
                    claimedFromSim.contains(id),
                    true));
            completed.add(id);
        }
        publishConnectionGeometries(publications, now);
        pendingLightRebakes.removeAll(completed);
    }

    private RopeStaticGeometryResult currentPublishedGeometry(UUID connectionId) {
        return publishedGeometry.getOrDefault(connectionId, RopeStaticGeometryResult.EMPTY);
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
                && publishedGeometry.isEmpty() && publishedAttachments.isEmpty()
                && dynamicHoldUntil.isEmpty() && retiringMeshes.isEmpty())
            return;
        Set<Long> toDirty = new HashSet<>(bySection.keySet());
        bySection.clear();
        publishedWatchdogSections.clear();
        byConnection.clear();
        bakedAttachments = List.of();
        publishedGeometry.clear();
        publishedAttachments.clear();
        claimed.clear();
        claimTick.clear();
        acceptedTick.clear();
        meshedSections.clear();
        acceptedConnections.clear();
        claimedFromSim.clear();
        connectionSections.clear();
        meshCandidates.clear();
        meshCandidates.addAll(realSourcesById.keySet());
        dynamicHoldUntil.clear();
        dynamicHoldDiagnostics.clear();
        retiringMeshes.clear();
        retiringAttachments.clear();
        sectionGeneration.clear();
        compiledGeneration.clear();
        markSectionsDirty(toDirty, true);
    }

    private static boolean staticMeshEnabled() {
        return ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()
                && ClientTuning.MODE_RENDER3D.get();
    }

    private void collectRealSources(
            Level level, Function<UUID, RopeSimulation> simLookup, long now, RebuildState next) {
        for (LeadConnection connection : realSources) {
            if (skipStaticMeshForTransparency(RopeTuning.forConnection(connection), connection))
                continue;
            if (isDynamicallyHeld(connection.id(), now))
                continue;
            addRealSource(level, simLookup, connection, next);
        }
    }

    private void addRealSource(
            Level level, Function<UUID, RopeSimulation> simLookup, LeadConnection connection, RebuildState next) {
        if (skipStaticMeshForTransparency(RopeTuning.forConnection(connection), connection))
            return;
        RopeSimulation sim = simLookup == null ? null : simLookup.apply(connection.id());
        RopeStaticGeometryResult result = buildRealSourceGeometry(level, connection, sim);
        if (!hasGeometry(result))
            return;
        next.addConnection(connection.id(), result, sim != null);
        List<RopeAttachmentRenderer.BakedAttachment> attachments = RopeAttachmentRenderer.bakeStatic(
                level, connection, result.snapshot.x, result.snapshot.y, result.snapshot.z);
        next.addAttachments(connection.id(), attachments);
        updateMissingAnchorBake(level, connection, sim == null);
    }

    static boolean skipStaticMeshForTransparency(RopeTuning tuning, LeadConnection connection) {
        return tuning != null && connection != null
                && skipStaticMeshForTransparency(
                        tuning.isConfiguredFullyTransparent(connection.kind()),
                        tuning.isFullyTransparent(connection.kind()));
    }

    static boolean skipStaticMeshForTransparency(
            boolean configuredFullyTransparent, boolean currentlyFullyTransparent) {
        return configuredFullyTransparent || currentlyFullyTransparent;
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
                next.addConnection(source.id(), result, false);
            }
        }
    }

    private void publishRebuild(RebuildState next, long now) {
        Map<Long, List<RopeSectionSnapshot>> publishedBySection = new HashMap<>(next.bySection.size());
        for (Map.Entry<Long, List<RopeSectionSnapshot>> e : next.bySection.entrySet()) {
            publishedBySection.put(e.getKey(), List.copyOf(e.getValue()));
        }

        Set<Long> previouslyMeshedSections = Set.copyOf(meshedSections);
        Set<Long> toDirty = changedSectionKeys(bySection, publishedBySection);
        HashSet<Long> nextMeshedSections = new HashSet<>(meshedSections);
        nextMeshedSections.retainAll(publishedBySection.keySet());
        nextMeshedSections.removeAll(toDirty);
        HashSet<UUID> nextAcceptedConnections = acceptedConnectionsForSections(next.connectionSections,
            nextMeshedSections);
        nextAcceptedConnections.addAll(preserveAcceptedConnections(
                acceptedConnections, Set.of(), next.connectionSections, publishedBySection.keySet()));

        Map<UUID, Long> nextClaimTick = copyClaimTicks(next.claimed, now);

        bySection.clear();
        bySection.putAll(publishedBySection);
        publishedWatchdogSections.retainAll(publishedBySection.keySet());
        publishedWatchdogSections.addAll(publishedBySection.keySet());
        byConnection.clear();
        byConnection.putAll(next.byConnection);
        bakedAttachments = next.bakedAttachments.isEmpty() ? List.of() : List.copyOf(next.bakedAttachments);
        publishedGeometry.clear();
        publishedGeometry.putAll(next.geometryByConnection);
        publishedAttachments.clear();
        publishedAttachments.putAll(next.attachmentsByConnection);
        claimed.clear();
        claimed.addAll(next.claimed);
        claimTick.clear();
        claimTick.putAll(nextClaimTick);
        meshedSections.clear();
        meshedSections.addAll(nextMeshedSections);
        acceptedConnections.clear();
        acceptedConnections.addAll(nextAcceptedConnections);
        acceptedTick.keySet().retainAll(acceptedConnections);
        claimedFromSim.clear();
        claimedFromSim.addAll(next.claimedFromSim);
        sectionsAwaitingMesh.clear();
        sectionsAwaitingMesh.addAll(toDirty);
        connectionSections.clear();
        connectionSections.putAll(next.connectionSections);
        meshCandidates.clear();
        for (UUID id : realSourcesById.keySet()) {
            if (!claimed.contains(id)) {
                meshCandidates.add(id);
            }
        }
        bakedWithMissingAnchors.retainAll(next.claimed);
        pendingLightRebakes.clear();

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

    /**
     * Keeps already-visible bystander connections static while a shared section is
     * recompiled. Vanilla continues displaying the previous section buffer until
     * the replacement is uploaded, so dropping every connection that references a
     * dirty section creates a false mesh -> dynamic -> mesh flash. Only connections
     * explicitly invalidated by the caller must leave the accepted set.
     */
    static HashSet<UUID> preserveAcceptedConnections(
            Set<UUID> previouslyAccepted,
            Set<UUID> directlyInvalidated,
            Map<UUID, Set<Long>> nextSectionsByConnection,
            Set<Long> publishedSections) {
        HashSet<UUID> preserved = new HashSet<>();
        if (previouslyAccepted == null || previouslyAccepted.isEmpty()
                || nextSectionsByConnection == null || publishedSections == null) {
            return preserved;
        }
        Set<UUID> direct = directlyInvalidated == null ? Set.of() : directlyInvalidated;
        for (UUID id : previouslyAccepted) {
            if (id == null || direct.contains(id)) {
                continue;
            }
            Set<Long> required = nextSectionsByConnection.get(id);
            if (required != null && !required.isEmpty() && publishedSections.containsAll(required)) {
                preserved.add(id);
            }
        }
        return preserved;
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

    private record GeometryPublication(
            RopeStaticGeometryResult geometry,
            List<RopeAttachmentRenderer.BakedAttachment> attachments,
            boolean fromSim,
            boolean preserveAcceptance) {
    }

    private static final class RebuildState {
        final Map<Long, List<RopeSectionSnapshot>> bySection = new HashMap<>();
        final Map<UUID, RopeSectionSnapshot> byConnection = new HashMap<>();
        final List<RopeAttachmentRenderer.BakedAttachment> bakedAttachments = new ArrayList<>();
        final Map<UUID, RopeStaticGeometryResult> geometryByConnection = new HashMap<>();
        final Map<UUID, List<RopeAttachmentRenderer.BakedAttachment>> attachmentsByConnection = new HashMap<>();
        final Set<UUID> claimed = new HashSet<>();
        final Set<UUID> claimedFromSim = new HashSet<>();
        final Map<UUID, Set<Long>> connectionSections = new HashMap<>();

        void addConnection(UUID id, RopeStaticGeometryResult result, boolean fromSim) {
            claimed.add(id);
            if (fromSim) {
                claimedFromSim.add(id);
            }
            connectionSections.put(id, result.sectionKeys);
            byConnection.put(id, result.snapshot);
            geometryByConnection.put(id, result);
            addSnapshots(bySection, result);
        }

        void addAttachments(UUID id, List<RopeAttachmentRenderer.BakedAttachment> attachments) {
            if (attachments == null || attachments.isEmpty()) {
                return;
            }
            List<RopeAttachmentRenderer.BakedAttachment> immutable = List.copyOf(attachments);
            attachmentsByConnection.put(id, immutable);
            bakedAttachments.addAll(immutable);
        }
    }

    /**
     * Mesh eligibility, symmetric by construction.
     *
     * <p>
     * Entry and exit both read {@link RopeSimulation#isVisuallyAtRest()}, so the
     * solver's settled state (its consecutive-quiet-tick counter) is the single
     * source of truth. That one state replaces the registry's former private entry
     * threshold, hard-exit threshold and per-LOD entry debounce — three constants
     * whose disagreements (and the solver's fourth opinion) let a rope leave its
     * mesh in one tick and take ten to earn it back, turning any periodic nudge
     * into a periodic section rebuild.
     */
    private boolean isMeshEligible(UUID connectionId, RopeSimulation sim, long currentTick) {
        // A rope inside its configured wind-physics range must remain dynamic for the
        // whole wind cycle, including calm gaps. Baking during each gap and retiring
        // on the next gust repeatedly rebuilds chunk sections and produces visible
        // handoff stalls.
        if (sim.hasEnabledWindPhysics()) {
            return false;
        }
        if (isWindCoolingDown(sim.lastWindActiveTick(), currentTick)) {
            return false;
        }
        if (sim.isVisuallyAtRest()) {
            return true;
        }
        // Dense stacks may hover just above the rest threshold for a while even
        // though the pile as a whole is stable. The long stack-quiet observation
        // window covers them.
        return sim.ropeStackQuietTicks() >= CHUNK_MESH_STACK_QUIET_TICKS;
    }

    static boolean isWindCoolingDown(long lastWindActiveTick, long currentTick) {
        return lastWindActiveTick != Long.MIN_VALUE
                && currentTick >= lastWindActiveTick
                && currentTick - lastWindActiveTick <= CHUNK_MESH_WIND_COOLDOWN_TICKS;
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
                    emptySet.remove(key);
                    // ClientChunkCache and LevelRenderer maintain separate empty-section
                    // state. The cache key may already be absent while ViewArea still has
                    // no render section (notably after an older in-flight empty build).
                    // Always wake the renderer before dirtying; making this conditional on
                    // emptySet.remove() can leave external geometry permanently unbuilt.
                    mc.levelRenderer.onSectionBecomingNonEmpty(key);
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

    /**
     * Vanilla normally compiles dirty sections discovered by its occlusion graph.
     * A rope can occupy an isolated all-air section with no terrain path into that
     * graph, so a repeatedly unobserved build needs one direct scheduling attempt.
     * NeoForge's patched compile task still gathers AddSectionGeometryEvent renderers.
     */
    private static void submitDirectRebuildsNow(Minecraft mc, Set<Long> sectionKeys) {
        if (sectionKeys.isEmpty() || mc.levelRenderer == null)
            return;
        var viewArea = ((LevelRendererAccessor) mc.levelRenderer).superLead$getViewArea();
        if (viewArea == null)
            return;
        RenderRegionCache regionCache = new RenderRegionCache();
        ViewAreaAccessor accessor = (ViewAreaAccessor) viewArea;
        for (long key : sectionKeys) {
            var section = accessor.superLead$getRenderSection(key);
            if (section != null && section.getSectionNode() == key) {
                section.rebuildSectionAsync(regionCache);
            }
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

    public record ConnectionMeshDiagnostics(
            int requiredSections, int acceptedSections, int awaitingSections, int pendingDirtySections,
            long firstMissingSection, long targetGeneration, long compiledGeneration, long lastSubmitTick) {
        static final ConnectionMeshDiagnostics EMPTY = new ConnectionMeshDiagnostics(
                0, 0, 0, 0, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    public record DynamicHoldDiagnostics(String reason, long tick, long untilTick, int count) {
        static final DynamicHoldDiagnostics NONE = new DynamicHoldDiagnostics(
                "none", Long.MIN_VALUE, Long.MIN_VALUE, 0);
    }

    private record RetiringMesh(Map<Long, Long> targetGeneration, long startedTick, long completedTick) {
    }
}
