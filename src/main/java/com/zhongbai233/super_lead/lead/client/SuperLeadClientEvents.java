package com.zhongbai233.super_lead.lead.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.ClientRopeContactReport;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadConnectionAction;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadItems;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.ZiplineController;
import com.zhongbai233.super_lead.lead.client.chunk.RopeSectionSnapshot;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import com.zhongbai233.super_lead.lead.client.debug.RopeDebugLabels;
import com.zhongbai233.super_lead.lead.client.debug.RopeDebugStats;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeJob;
import com.zhongbai233.super_lead.lead.client.render.AttachmentSwingClient;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.render.RopeContactsClient;
import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import com.zhongbai233.super_lead.lead.client.render.RopeVisibility;
import com.zhongbai233.super_lead.lead.client.render.ZiplineClientState;
import com.zhongbai233.super_lead.lead.client.sim.RopeEntityContact;
import com.zhongbai233.super_lead.lead.client.sim.RopeForceField;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

/**
 * Client-side event hub for rope interaction, simulation submission, particles,
 * and debug publication.
 *
 * <p>
 * This is the next major split candidate after {@code SuperLeadNetwork}: new
 * code should prefer smaller helpers for picking, packet sending, particles,
 * and
 * contact reporting instead of growing this event subscriber further.
 */
@EventBusSubscriber(modid = Super_lead.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class SuperLeadClientEvents {
    private static final Logger LOG = LogUtils.getLogger();
    private static final double PICK_RADIUS = 0.30D;
    /** Max blocks beyond player reach to scan when no vanilla block hit exists. */
    private static final double PICK_REACH_SLACK = 2.0D;
    private static final int ATTACHMENT_HIGHLIGHT_COLOR = LeashBuilder.DEFAULT_HIGHLIGHT;
    private static final int ATTACHMENT_REMOVAL_HIGHLIGHT_COLOR = 0x66FF6040;
    private static final int ZIPLINE_HIGHLIGHT_COLOR = 0x883FCBFF;
    private static final int PRESET_BINDER_HIGHLIGHT_COLOR = 0x88DD55FF;
    private static final int PERCH_BOOST_HIGHLIGHT_COLOR = 0x8855FF88;
    /** Physics and render LOD distances are user-tunable via {@link ClientTuning}. */
    private static final double BLOCKS_PER_CHUNK = 16.0D;
    private static final double FRUSTUM_BOUNDS_MARGIN = 1.0D;
    private static final double NEIGHBOR_GRID_SIZE = 4.0D;
    private static final double NEIGHBOR_BOUNDS_MARGIN = 0.08D;
    private static final double NEIGHBOR_CONTACT_DISTANCE = 0.14D;
    /** Hard caps that keep crowded rope buckets from degrading toward O(N^2*S^2). */
    private static final int MAX_NEIGHBORS_PER_ROPE = 6;
    private static final int MAX_NEIGHBOR_CANDIDATES_PER_FRAME = 4096;
    private static final int MAX_NEIGHBOR_NARROW_PHASE_PER_FRAME = 1024;
    /** Main-thread rope solving may consume at most this much of one client tick. */
    private static final long MAX_MAIN_THREAD_PHYSICS_NANOS = 4_000_000L;
    /** Sparse terrain-only maintenance outside the full-physics LOD radius. */
    private static final int TERRAIN_LOD_STEP_INTERVAL = 4;
    private static final double LOCAL_ROPE_JUMP_SPEED = 0.42D;
    private static final double LOCAL_CONTACT_SIDE_HARD_DEPTH_FRACTION = 0.65D;
    private static final double LOCAL_CONTACT_EXIT_INPUT_DOT = 0.05D;
    /**
     * Ropes with a large one-tick node displacement are visually sensitive to async
     * publish latency: a worker result that lands one render frame later can look like
     * a tiny rollback during high-speed rebound. Keep only these bursty ropes on the
     * main thread; slow/quiescent ropes still use async where safe.
     */
    private static final double ASYNC_HIGH_MOTION_BLOCKS_PER_TICK = 0.10D;
    private static final double ASYNC_HIGH_MOTION_SQR = ASYNC_HIGH_MOTION_BLOCKS_PER_TICK
            * ASYNC_HIGH_MOTION_BLOCKS_PER_TICK;
    /**
     * Inflated rope-bound margin used to query nearby entities for the
     * entity-pushes-rope pass.
     */
    private static final double ENTITY_QUERY_MARGIN = 1.0D;
    private static final Map<UUID, RopeSimulation> SIMS = new HashMap<>();
    private static final Set<UUID> FRAME_ACTIVE = new HashSet<>();
    private static final Map<UUID, Double> FRAME_LOD_DISTANCE = new HashMap<>();
    private static final List<RenderEntry> FRAME_SIM_ENTRIES = new ArrayList<>();
    private static final List<RenderEntry> FRAME_STATIC_COLLISION_ENTRIES = new ArrayList<>();
    private static final List<RenderEntry> FRAME_RENDER_ENTRIES = new ArrayList<>();
    private static final Set<UUID> FRAME_STATIC_SIM_IDS = new HashSet<>();
    private static final Set<UUID> FRAME_PARROT_WEIGHTED_ROPES = new HashSet<>();
    private static final Map<UUID, Long> FRAME_PHYSICS_NANOS = new HashMap<>();
    private static final Map<UUID, String> FRAME_PHYSICS_STATE = new HashMap<>();
    private static final Map<UUID, Long> LAST_DYNAMIC_STEP_TICK = new HashMap<>();
    private static final Set<UUID> FRAME_ACTIVE_ATTACHMENT_IDS = new HashSet<>();
    private static final LodRefinementTracker LOD_REFINEMENT = new LodRefinementTracker();
    private static final Map<UUID, AsyncPhysicsJob> ASYNC_PHYSICS_JOBS = new ConcurrentHashMap<>();
    private static final List<RopeJob> FRAME_ROPE_JOBS = new ArrayList<>();
    private static final int ASYNC_PHYSICS_THREADS = Math.max(
            1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final int MAX_ASYNC_PHYSICS_JOBS = Math.max(4, ASYNC_PHYSICS_THREADS * 4);
    private static final ExecutorService ASYNC_PHYSICS_EXECUTOR = Executors.newFixedThreadPool(
            ASYNC_PHYSICS_THREADS,
            new RopePhysicsThreadFactory());
    private static RopeSimulation previewSim;
    private static LeadAnchor previewAnchor;
    private static long lastRepelTick = Long.MIN_VALUE;
    private static long lastDebugStatsTick = Long.MIN_VALUE;
    // Connection IDs whose sims are valid static-mesh sources. Full-physics and
    // sparse terrain-LOD sims are both included, so static baking inherits their
    // collision-safe shape instead of rebuilding an unchecked anchor catenary.
    private static volatile Set<UUID> maintainableSimIds = Set.of();

    static RopeSimulation simulation(UUID id) {
        return SIMS.get(id);
    }

    /** Wakes static-mesh simulations whose nearby client terrain changed. */
    public static void wakeForTerrainChange(Iterable<UUID> connectionIds) {
        if (connectionIds == null)
            return;
        for (UUID id : connectionIds) {
            if (id == null)
                continue;
            cancelAsyncPhysics(id);
            RopeSimulation sim = SIMS.get(id);
            if (sim != null) {
                sim.wakeForTerrainChange();
            }
            LAST_DYNAMIC_STEP_TICK.remove(id);
        }
    }

    public static Map<UUID, RopeTuning> captureTunings(List<LeadConnection> connections) {
        Map<UUID, RopeTuning> out = new HashMap<>();
        if (connections != null) {
            for (LeadConnection connection : connections) {
                out.put(connection.id(), RopeTuning.forConnection(connection));
            }
        }
        return out;
    }

    public static void disturbChangedTunings(
            Level level, List<LeadConnection> connections, Map<UUID, RopeTuning> previousTunings) {
        if (level == null || connections == null || previousTunings == null)
            return;
        Set<UUID> changed = new HashSet<>();
        for (LeadConnection connection : connections) {
            RopeTuning previous = previousTunings.get(connection.id());
            RopeTuning current = RopeTuning.forConnection(connection);
            if (previous != null && !previous.equals(current)) {
                changed.add(connection.id());
            }
        }
        disturbConnections(level, changed, level.getGameTime() + 8L);
    }

    public static void disturbConnections(Level level, Iterable<UUID> connectionIds, long untilTick) {
        if (level == null || connectionIds == null)
            return;
        Set<UUID> ids = new HashSet<>();
        for (UUID id : connectionIds) {
            if (id != null)
                ids.add(id);
        }
        if (ids.isEmpty())
            return;
        StaticRopeChunkRegistry.get().holdDynamic(level, ids, untilTick);
        for (UUID id : ids) {
            cancelAsyncPhysics(id);
            RopeSimulation sim = SIMS.get(id);
            if (sim != null) {
                sim.wakeForExternalChange();
            }
            LAST_DYNAMIC_STEP_TICK.remove(id);
        }
    }

    public static LeadConnection hoveredConnection() {
        return ClientRopeInteractions.hoveredConnection();
    }

    /**
     * Send a server-bound UseConnectionAction packet for the currently hovered
     * connection.
     * Called on the client when the player right-clicks with an action item AND the
     * client's
     * own ghost-preview pick produced a target the action can act on.
     */
    public static boolean trySendUseConnectionAction(net.minecraft.world.InteractionHand hand,
            LeadConnectionAction action) {
        return ClientRopeInteractions.trySendUseConnectionAction(hand, action);
    }

    /** Try starting a zipline ride on the sync-zone rope under the crosshair. */
    public static boolean trySendStartZipline(net.minecraft.world.InteractionHand hand) {
        return ClientRopeInteractions.trySendStartZipline(hand);
    }

    /**
     * Try sending an AddRopeAttachment packet for the rope under the player's
     * crosshair.
     * The held item must be a valid attachment item (lantern, sign, …); the caller
     * is expected
     * to guard that. Returns true when a packet was sent.
     */
    public static boolean trySendAddRopeAttachment(net.minecraft.world.InteractionHand hand) {
        return ClientRopeInteractions.trySendAddRopeAttachment(hand);
    }

    /**
     * Try sending a RemoveRopeAttachment packet for an existing attachment under
     * the crosshair.
     * Returns true when a packet was sent.
     */
    public static boolean trySendRemoveRopeAttachment() {
        return ClientRopeInteractions.trySendRemoveRopeAttachment();
    }

    /**
     * Try sending a ToggleRopeAttachmentForm packet for the attachment under the
     * crosshair.
     * Only fires when the targeted attachment is a BlockItem (item-only stacks have
     * no
     * alternate shape to toggle to).
     */
    public static boolean trySendToggleRopeAttachmentForm() {
        return ClientRopeInteractions.trySendToggleRopeAttachmentForm();
    }

    public static boolean trySendConfigureRopeAttachmentDisplay() {
        return ClientRopeInteractions.trySendConfigureRopeAttachmentDisplay();
    }

    /** Open the vanilla sign editor for a sign stored as a rope attachment. */
    public static boolean tryOpenRopeAttachmentSignEditor() {
        return ClientRopeInteractions.tryOpenRopeAttachmentSignEditor();
    }

    /** Ask the server to open an AE2 ME Terminal mounted as a rope attachment. */
    public static boolean tryOpenRopeAttachmentAeTerminal() {
        return ClientRopeInteractions.tryOpenRopeAttachmentAeTerminal();
    }

    public static boolean trySendSignAttachmentDye(net.minecraft.world.item.DyeColor color) {
        return ClientRopeInteractions.trySendSignAttachmentDye(color);
    }

    public static boolean trySendSignAttachmentGlow() {
        return ClientRopeInteractions.trySendSignAttachmentGlow();
    }

    private static LeadConnection findById(List<RenderEntry> entries, UUID id) {
        for (RenderEntry entry : entries) {
            if (entry.connection().id().equals(id)) {
                return entry.connection();
            }
        }
        return null;
    }

    private SuperLeadClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clearClientState();
    }

    @SubscribeEvent
    public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clearClientState();
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clearClientState();
            return;
        }

        SuperLeadNetwork.pruneInvalid(level);
        long tick = level.getGameTime();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        Frustum frustum = camera.getCullFrustum();
        RopeVisibility.beginFrame(cameraPos);
        if (RopeDebugLabels.enabled()) {
            RopeDebugLabels.beginFrame();
        }

        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        Map<UUID, LeadEndpointLayout.Endpoints> endpointsByConnection = LeadEndpointLayout
            .endpointsByConnection(level, connections);
        StaticRopeChunkRegistry staticRopes = StaticRopeChunkRegistry.get();
        Set<UUID> active = FRAME_ACTIVE;
        Map<UUID, Double> lodDistanceByConnection = FRAME_LOD_DISTANCE;
        List<RenderEntry> simEntries = FRAME_SIM_ENTRIES;
        List<RenderEntry> staticCollisionEntries = FRAME_STATIC_COLLISION_ENTRIES;
        List<RenderEntry> renderEntries = FRAME_RENDER_ENTRIES;
        Set<UUID> staticSimIds = FRAME_STATIC_SIM_IDS;
        Set<UUID> parrotWeightedRopes = FRAME_PARROT_WEIGHTED_ROPES;
        Map<UUID, Long> physicsNanosByConnection = debugPhysicsNanosMap();
        Map<UUID, String> physicsStateByConnection = debugPhysicsStateMap();
        active.clear();
        lodDistanceByConnection.clear();
        simEntries.clear();
        staticCollisionEntries.clear();
        renderEntries.clear();
        staticSimIds.clear();
        parrotWeightedRopes.clear();
        publishCompletedAsyncPhysics(active, physicsNanosByConnection, physicsStateByConnection);
        // First pass: keep sims for ropes within render distance even when they are
        // outside the
        // camera frustum. Frustum culling must only skip draw submission; if it also
        // drops or
        // freezes the sim, turning around recreates a straight rope for one frame
        // before gravity
        // pulls it down again.
        for (LeadConnection connection : connections) {
            addConnectionEntry(level, minecraft, staticRopes, endpointsByConnection, active,
                    lodDistanceByConnection, simEntries, staticSimIds, renderEntries,
                    staticCollisionEntries, connection, cameraPos, frustum, partialTick, tick);
        }
        // Second pass: drive each sim with its neighbours so rope-rope constraints
        // participate
        // in the unified solver iteration (no more separate repel + settle phase).
        // Ropes past lod.physicsDistance skip expensive neighbour/entity interaction,
        // but still receive sparse terrain-only maintenance before static handoff.
        if (tick != lastRepelTick) {
            lastRepelTick = tick;
                    stepFrameSimulations(level, minecraft.player, simEntries, staticCollisionEntries,
                        parrotWeightedRopes, active,
                    physicsNanosByConnection, physicsStateByConnection, tick);
        }
        // Update sim IDs that tickMaintain may use as a geometry source. Static-mesh
        // claimed sims stay maintainable for shape preservation, but are no longer in
        // simEntries and therefore do not physics-step.
        // Must run after the stepping loop so LOD transitions within this frame are
        // captured.
        updateMaintainableSimIds(simEntries, staticSimIds);
        releaseDynamicActiveStaticRopes(level, renderEntries, staticRopes, tick);
        // Maintain static chunk meshes after the current physics tick so a newly
        // disturbed rope is evicted before this frame renders. Sparse terrain-LOD
        // ropes remain valid sim sources for collision-safe chunk mesh baking.
        staticRopes.tickMaintain(level,
            id -> maintainableSimIds.contains(id) ? SIMS.get(id) : null,
            lodDistanceByConnection);
        staticRopes.invalidateConnections(level, parrotWeightedRopes);
        List<RopeAttachmentRenderer.BakedAttachment> bakedStaticAttachments = staticRopes
                .bakedAttachmentsForRender(tick);
        // LOD: drop baked attachments for ropes past the physics distance so far
        // ropes don't waste GPU on small decoration items nobody can see.
        bakedStaticAttachments = filterBakedStaticAttachments(
                bakedStaticAttachments, lodDistanceByConnection, null);
        RopeDynamicLights.update(level, cameraPos, connections,
                id -> staticRopes.isMeshAccepted(id) && !staticRopes.shouldDynamicLinger(id, tick)
                        ? null
                        : SIMS.get(id),
                bakedStaticAttachments,
                partialTick);

        ConnectionHighlight highlight = pickHighlightedConnection(minecraft, renderEntries, partialTick, cameraPos);
        UUID highlightedConnectionId = highlight == null ? null : highlight.id();
        bakedStaticAttachments = filterBakedStaticAttachments(
                bakedStaticAttachments, lodDistanceByConnection, highlightedConnectionId);

        List<RopeJob> ropeJobs = FRAME_ROPE_JOBS;
        ropeJobs.clear();
        for (RenderEntry entry : renderEntries) {
            submitRenderEntry(event, level, staticRopes, physicsNanosByConnection, physicsStateByConnection,
                    ropeJobs, entry, cameraPos, partialTick, tick, highlight, highlightedConnectionId);
        }
        tickStaticAttachmentSwings(level, bakedStaticAttachments, lodDistanceByConnection, tick);
        RopeAttachmentRenderer.submitBakedAll(event.getSubmitNodeCollector(), cameraPos, level, bakedStaticAttachments,
            partialTick);
        LeashBuilder.flush(event.getSubmitNodeCollector(), cameraPos, partialTick, ropeJobs);
        ZiplineClientState.submitVisuals(event.getSubmitNodeCollector(), cameraPos, level, partialTick,
                id -> SIMS.get(id));
        if (tick != lastDebugStatsTick) {
            lastDebugStatsTick = tick;
            publishDebugStats(connections, simEntries, renderEntries, staticRopes, ropeJobs);
        }
        RopeDebugLabels.publishFrame();
        SIMS.keySet().retainAll(active);
        LAST_DYNAMIC_STEP_TICK.keySet().retainAll(active);
        LOD_REFINEMENT.retainConnections(connections);
        retainAsyncPhysicsJobs(active);
        ItemFlowAnimator.retainAll(active);
        retainAttachmentSwingStates(connections, bakedStaticAttachments, tick);

        ClientRopeInteractions.setHovered(
                highlight == null ? null : findById(renderEntries, highlight.id()),
                highlight == null ? null : highlight.hitPoint(),
                highlight == null ? 0.5D : highlight.hitT());

        // Attachment removal preview: while holding shears + shift and aiming at an
        // existing attachment, draw a translucent ghost to confirm the target.
        submitAttachmentRemovalPreview(event, level, connections, cameraPos, partialTick);

        // Attachment placement preview: while holding a non-empty item (and NOT
        // sneaking,
        // since sneak is reserved for upgrade / removal / cut), draw a translucent
        // outlined
        // ghost of the held item where a right-click would place it.
        submitAttachmentPlacementPreview(event, level, minecraft, cameraPos, partialTick);

        Player player = minecraft.player;
        if (player != null) {
            renderPreview(event, level, cameraPos, partialTick, tick, player);
        } else {
            previewSim = null;
            previewAnchor = null;
        }
    }

    private static Map<UUID, Long> debugPhysicsNanosMap() {
        FRAME_PHYSICS_NANOS.clear();
        return FRAME_PHYSICS_NANOS;
    }

    private static Map<UUID, String> debugPhysicsStateMap() {
        FRAME_PHYSICS_STATE.clear();
        return FRAME_PHYSICS_STATE;
    }

    private static void clearClientState() {
        cancelAllAsyncPhysics();
        SIMS.clear();
        LAST_DYNAMIC_STEP_TICK.clear();
        maintainableSimIds = Set.of();
        lastRepelTick = Long.MIN_VALUE;
        lastDebugStatsTick = Long.MIN_VALUE;
        FRAME_ACTIVE.clear();
        FRAME_LOD_DISTANCE.clear();
        FRAME_SIM_ENTRIES.clear();
        FRAME_STATIC_COLLISION_ENTRIES.clear();
        FRAME_RENDER_ENTRIES.clear();
        FRAME_STATIC_SIM_IDS.clear();
        FRAME_PARROT_WEIGHTED_ROPES.clear();
        FRAME_PHYSICS_NANOS.clear();
        FRAME_PHYSICS_STATE.clear();
        previewSim = null;
        previewAnchor = null;
        ClientRopeInteractions.clearHovered();
        ItemFlowAnimator.clearAll();
        RopeDynamicLights.clear();
        RopeDebugLabels.clear();
        RopeDebugStats.clear();
        ZiplineClientState.clear();
        AttachmentSwingClient.clear();
        RopeTuning.clearCache();
        LOD_REFINEMENT.clear();
    }

    private static void stepFrameSimulations(ClientLevel level, Player player,
            List<RenderEntry> simEntries, List<RenderEntry> staticCollisionEntries,
            Set<UUID> parrotWeightedRopes, Set<UUID> active,
            Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection, long tick) {
        NeighborMapResult neighborResult = buildNeighborMap(simEntries, staticCollisionEntries, tick);
        RopeDebugStats.neighborCandidates = neighborResult.candidates();
        RopeDebugStats.neighborNarrowPhase = neighborResult.narrowPhase();
        RopeDebugStats.neighborBuildTruncated = neighborResult.truncated();

        if (!neighborResult.staticContacts().isEmpty()) {
            disturbConnections(level, neighborResult.staticContacts(), tick + 8L);
        }

        PhysicsBudget physicsBudget = new PhysicsBudget(
                ClientTuning.DYNAMIC_PHYSICS_BUDGET.get(), MAX_MAIN_THREAD_PHYSICS_NANOS);
        for (RenderEntry entry : simEntries) {
            stepConnectionEntry(level, player, neighborResult.neighbors(), parrotWeightedRopes, active,
                    physicsNanosByConnection, physicsStateByConnection, entry, tick, physicsBudget);
        }
    }

    private static void addConnectionEntry(ClientLevel level, Minecraft minecraft,
            StaticRopeChunkRegistry staticRopes, Map<UUID, LeadEndpointLayout.Endpoints> endpointsByConnection, Set<UUID> active,
            Map<UUID, Double> lodDistanceByConnection, List<RenderEntry> simEntries, Set<UUID> staticSimIds,
            List<RenderEntry> renderEntries, List<RenderEntry> staticCollisionEntries,
            LeadConnection connection, Vec3 cameraPos, Frustum frustum, float partialTick, long tick) {
        LeadEndpointLayout.Endpoints endpoints = endpointsByConnection.get(connection.id());
        if (endpoints == null) {
            endpoints = LeadEndpointLayout.endpoints(level, connection, List.of(connection));
        }
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double lodDistSqr = ropeDistanceSqr(a, b, cameraPos);
        lodDistanceByConnection.put(connection.id(), lodDistSqr);
        if (skipConnectionEntry(staticRopes, connection, lodDistSqr, tick)) {
            return;
        }
        if (!active.add(connection.id())) {
            return;
        }

        UUID connectionId = connection.id();
        boolean acceptedMesh = staticRopes.isMeshAccepted(connectionId);
        RopeSimulation existingSim = SIMS.get(connectionId);
        // UUID alone cannot validate a cached shape: endpoint, preset and chunk
        // lifecycles can change while the connection ID stays the same. A mesh with
        // no live sim must therefore be revalidated when it enters fine LOD.
        boolean unknownMeshShape = acceptedMesh && existingSim == null;
        RopeSectionSnapshot refinementSnapshot = null;
        boolean startRefinement = shouldStartLodRefinement(
                LOD_REFINEMENT.hasLowDetailShape(connectionId) || unknownMeshShape,
                LOD_REFINEMENT.isRefining(connectionId),
                lodDistSqr, fullDetailTopologyDistanceSqr());
        if (startRefinement) {
            cancelAsyncPhysics(connectionId);
            LOD_REFINEMENT.begin(connectionId);
            if (acceptedMesh) {
                refinementSnapshot = staticRopes.snapshotForRender(connectionId, tick);
            }
            // Remove the cached mesh before choosing simEntries. holdDynamic also
            // prevents tickMaintain from immediately baking the same low-detail shape.
            staticRopes.holdDynamic(level, connectionId, tick + 8L);
        }
        SimLookup lookup = getOrCreateSimulation(connection, a, b, lodDistSqr);
        if (usesCoarseTopology(lodDistSqr)) {
            LOD_REFINEMENT.markLowDetail(connectionId);
        }
        if (startRefinement) {
            if (lookup.rebuilt() && refinementSnapshot != null) {
                lookup.sim().restorePolylineForRefinement(
                    refinementSnapshot.sourceX, refinementSnapshot.sourceY, refinementSnapshot.sourceZ, a, b);
            } else {
                lookup.sim().wakeForRefinement();
            }
        }
        AABB physicsBounds = physicsBounds(a, b, lookup.sim());
        AABB bounds = physicsBounds.inflate(FRUSTUM_BOUNDS_MARGIN);
        RenderEntry entry = new RenderEntry(connection, lookup.sim(), a, b, lodDistSqr, bounds, physicsBounds);
        boolean staticMeshActive = staticRopes.isMeshAccepted(connection.id())
                && !staticRopes.shouldDynamicLinger(connection.id(), tick);
        if (!staticMeshActive) {
            simEntries.add(entry);
        } else {
            staticSimIds.add(connection.id());
            if (participatesInPhysics(entry)) {
                staticCollisionEntries.add(entry);
            }
        }
        if (lookup.rebuilt()) {
            lookup.sim().beginSegmentVisibility(0);
        } else if (!RopeVisibility.shouldRender(level, minecraft.player, frustum, cameraPos, bounds, lookup.sim(),
                partialTick)) {
            return;
        }
        renderEntries.add(entry);
    }

    static boolean shouldStartLodRefinement(
            boolean hasLowDetailShape, boolean alreadyRefining,
            double lodDistSqr, double fullDetailDistSqr) {
        // dynamicTopologyTuning uses a strict '<' boundary. Keep the handoff strict as
        // well so refinement cannot start with the coarse topology at the exact edge.
        return hasLowDetailShape && !alreadyRefining && lodDistSqr < fullDetailDistSqr;
    }

    private static double fullDetailTopologyDistanceSqr() {
        double distance = Math.min(
                physicsLodDistance(),
                Math.max(0.0D, ClientTuning.DYNAMIC_COARSE_TOPOLOGY_DISTANCE.get()));
        return distance * distance;
    }

    private static boolean skipConnectionEntry(StaticRopeChunkRegistry staticRopes, LeadConnection connection,
            double lodDistSqr, long tick) {
        return lodDistSqr > maxRenderDistanceSqr()
            || (ClientTuning.MODE_RENDER3D.get()
                && lodDistSqr <= ribbonLodDistanceSqr()
                && lodDistSqr > physicsLodDistanceSqr()
                && staticRopes.isMeshAccepted(connection.id())
                && !staticRopes.shouldDynamicLinger(connection.id(), tick));
    }

    private static SimLookup getOrCreateSimulation(LeadConnection connection, Vec3 a, Vec3 b, double lodDistSqr) {
        RopeTuning tuning = dynamicTopologyTuning(RopeTuning.forConnection(connection), lodDistSqr);
        RopeSimulation sim = SIMS.get(connection.id());
        boolean rebuilt = false;
        boolean tuningChanged = sim != null && tuningRequiresRebuild(sim.tuning(), tuning);
        boolean topologyProfileChanged = sim != null && !sim.matchesTopologyProfile(tuning);
        boolean topologyMismatch = sim != null
                && (topologyProfileChanged || !sim.matchesLength(a, b, tuning));
        if (sim == null || topologyMismatch || tuningChanged) {
            RopeSimulation previous = sim;
            if (previous != null) {
                cancelAsyncPhysics(connection.id());
            }
            sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), tuning);
            if (previous != null) {
                sim.resampleShapeForTopologyChange(previous, a, b);
            }
            SIMS.put(connection.id(), sim);
            rebuilt = true;
        } else {
            sim.setTuning(tuning);
        }
        sim.setUseCollisionProxy(connectionUsesCollisionProxy(connection));
        sim.setWindPhysicsEnabled(windPhysicsEnabledFor(tuning, lodDistSqr));
        return new SimLookup(sim, rebuilt);
    }

    static boolean tuningRequiresRebuild(RopeTuning current, RopeTuning next) {
        return current != next && (current == null || next == null || !current.equals(next));
    }

    private static RopeTuning dynamicTopologyTuning(RopeTuning tuning, double lodDistSqr) {
        if (!usesCoarseTopology(lodDistSqr)) {
            return tuning;
        }
        double scale = lodDistSqr >= ribbonLodDistanceSqr() ? 2.0D : 1.5D;
        double segmentLength = Math.min(0.90D, Math.max(tuning.segmentLength(), tuning.segmentLength() * scale));
        int segmentMax = Math.max(tuning.minSegments(), (int) Math.ceil(tuning.segmentMax() / scale));
        return tuning.withTopology(segmentLength, segmentMax);
    }

    static boolean usesCoarseTopology(double lodDistSqr) {
        double coarse = Math.max(0.0D, ClientTuning.DYNAMIC_COARSE_TOPOLOGY_DISTANCE.get());
        return lodDistSqr >= coarse * coarse;
    }

    private static boolean connectionUsesCollisionProxy(LeadConnection connection) {
        if (connection.physicsPreset().isBlank()) {
            return false;
        }
        Map<String, String> overrides = PhysicsZonesClient.overridesForPreset(connection.physicsPreset());
        return resolveBool(overrides, ClientTuning.CONTACT_PUSHBACK)
                || resolveBool(overrides, ClientTuning.CONTACT_TRIP_ENABLED);
    }

    private static void stepConnectionEntry(ClientLevel level, Player player,
            Map<RopeSimulation, List<RopeSimulation>> neighborsBySim, Set<UUID> parrotWeightedRopes,
            Set<UUID> active,
            Map<UUID, Long> physicsNanosByConnection, Map<UUID, String> physicsStateByConnection, RenderEntry entry,
            long tick, PhysicsBudget physicsBudget) {
        UUID id = entry.connection().id();
        applyServerState(entry.sim(), id, tick);
        entry.sim().setWindPhysicsEnabled(windPhysicsEnabledFor(entry.sim().tuning(), entry.lodDistSqr()));
        if (!entry.sim().physicsEnabled()) {
            updateInactivePhysicsEntry(player, physicsNanosByConnection, physicsStateByConnection, entry, tick);
            return;
        }
        if (entry.lodDistSqr() > physicsLodDistanceSqr()) {
            maintainTerrainLodEntry(level, physicsNanosByConnection, physicsStateByConnection,
                    entry, tick, physicsBudget);
            return;
        }

        List<RopeForceField> forceFields = RopePerchClientForces.forConnection(level, entry.connection(),
            entry.sim());
        if (!forceFields.isEmpty()) {
            parrotWeightedRopes.add(id);
        }

        StepDecision decision = stepDecision(entry, tick, physicsBudget, !forceFields.isEmpty());
        if (!decision.shouldStep()) {
            recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id,
                    0L, decision.state());
            maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
            return;
        }

        List<RopeEntityContact> entityContacts = collectEntityContacts(level, entry.sim(), entry.a(),
                entry.b());
        List<RopeSimulation> neighbors = neighborsBySim.getOrDefault(entry.sim(), List.of());
        if (canStepAsync(entry, neighbors, entityContacts)) {
            if (submitAsyncPhysics(level, entry, forceFields, entityContacts, tick, active)) {
                LAST_DYNAMIC_STEP_TICK.put(id, tick);
                recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id, 0L, "async");
                maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
                return;
            }
            recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id, 0L, "pending");
            maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
            return;
        }

        cancelAsyncPhysics(id);
        long stepStart = RopeDebugLabels.enabled() ? System.nanoTime() : 0L;
        boolean stepped = entry.sim().step(level, entry.a(), entry.b(), tick,
                neighbors, forceFields, entityContacts);
        LAST_DYNAMIC_STEP_TICK.put(id, tick);
        long stepNanos = RopeDebugLabels.enabled() ? System.nanoTime() - stepStart : 0L;
        recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id,
                stepNanos, stepped ? decision.state() : "idle");
        maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
    }

    private static boolean canStepAsync(RenderEntry entry, List<RopeSimulation> neighbors,
            List<RopeEntityContact> entityContacts) {
        LeadConnection hovered = ClientRopeInteractions.hoveredConnection();
        // Keep hard entity contacts synchronous. They are sampled from the current
        // frame's entity poses and can strongly push rope nodes; publishing a worker's
        // delayed result while the player keeps intersecting the rope causes visible
        // fight/jitter. Snapshot-only force fields (parrot weight, etc.) may still go
        // async, but player/entity collision stays on the main step.
        return neighbors.isEmpty()
                && entityContacts.isEmpty()
                && entry.sim().maxNodeMotionSqr() < ASYNC_HIGH_MOTION_SQR
                && !hasSynchronousDynamicReason(entry)
                && (hovered == null || !entry.connection().id().equals(hovered.id()));
    }

    private static boolean submitAsyncPhysics(ClientLevel level, RenderEntry entry,
            List<RopeForceField> forceFields, List<RopeEntityContact> entityContacts, long tick, Set<UUID> active) {
        UUID id = entry.connection().id();
        AsyncPhysicsJob existing = ASYNC_PHYSICS_JOBS.get(id);
        if (existing != null && existing.running() && !existing.done()) {
            return false;
        }
        // Executors.newFixedThreadPool uses an unbounded queue. Refuse excess work
        // before allocating/copying another simulation so a dense scene cannot build
        // an arbitrarily large delayed-work and memory backlog.
        if ((existing == null || !existing.running()) && runningAsyncPhysicsJobs() >= MAX_ASYNC_PHYSICS_JOBS) {
            return false;
        }
        RopeSimulation worker;
        if (existing != null && canReuseAsyncWorker(existing, entry.a(), entry.b(), entry.sim().tuning())) {
            worker = existing.worker();
        } else {
            worker = new RopeSimulation(entry.a(), entry.b(), id.getLeastSignificantBits(), entry.sim().tuning());
        }
        if (worker.nodeCount() == entry.sim().nodeCount()) {
            worker.copyMutableStateFrom(entry.sim());
        } else {
            worker.resampleMutableStateFrom(entry.sim(), entry.a(), entry.b());
        }
        worker.preparePhysicsParallel(level, entry.a(), entry.b(), tick);
        Future<?> future = ASYNC_PHYSICS_EXECUTOR.submit(() -> {
            RopeSimulation.beginParallelPhase();
            try {
                worker.step(level, entry.a(), entry.b(), tick, List.of(), forceFields, entityContacts);
            } finally {
                RopeSimulation.endParallelPhase();
            }
        });
        ASYNC_PHYSICS_JOBS.put(id, new AsyncPhysicsJob(entry.sim(), worker, future, tick));
        active.add(id);
        return true;
    }

    private static int runningAsyncPhysicsJobs() {
        int count = 0;
        for (AsyncPhysicsJob job : ASYNC_PHYSICS_JOBS.values()) {
            if (job.running() && !job.done()) {
                count++;
            }
        }
        return count;
    }

    private static boolean canReuseAsyncWorker(AsyncPhysicsJob job, Vec3 a, Vec3 b, RopeTuning tuning) {
        return job != null && !job.running() && job.worker().matchesTopology(a, b, tuning);
    }

    private static void publishCompletedAsyncPhysics(Set<UUID> active, Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection) {
        for (var it = ASYNC_PHYSICS_JOBS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, AsyncPhysicsJob> e = it.next();
            UUID id = e.getKey();
            AsyncPhysicsJob job = e.getValue();
            if (!job.running() || !job.done()) {
                continue;
            }
            RopeSimulation sim = SIMS.get(id);
            if (sim != null) {
                try {
                    job.future.get();
                    // A completed worker belongs to the exact live simulation that
                    // submitted it. LOD topology/refinement may replace that owner while
                    // this task is running; publishing such a stale result would restore
                    // coarse point caches and settled/block-hash state into the fine sim.
                    if (sim != job.owner) {
                        it.remove();
                        continue;
                    }
                    if (sim.nodeCount() == job.worker.nodeCount()) {
                        sim.copyMutableStateFrom(job.worker);
                    } else {
                        sim.resampleMutableStateFrom(job.worker,
                                new Vec3(job.worker.currentX(0), job.worker.currentY(0), job.worker.currentZ(0)),
                                new Vec3(job.worker.currentX(job.worker.nodeCount() - 1),
                                        job.worker.currentY(job.worker.nodeCount() - 1),
                                        job.worker.currentZ(job.worker.nodeCount() - 1)));
                    }
                    recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id, 0L,
                            "async-done");
                } catch (Exception ex) {
                    LOG.warn("Async rope physics failed for {}", id, ex);
                }
            }
            if (sim == job.owner) {
                ASYNC_PHYSICS_JOBS.put(id, job.idle());
            } else {
                it.remove();
            }
        }
    }

    private static void cancelAsyncPhysics(UUID id) {
        AsyncPhysicsJob job = ASYNC_PHYSICS_JOBS.remove(id);
        if (job != null && job.running() && !job.done()) {
            job.future.cancel(false);
        }
    }

    private static void retainAsyncPhysicsJobs(Set<UUID> active) {
        for (var it = ASYNC_PHYSICS_JOBS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, AsyncPhysicsJob> e = it.next();
            if (!active.contains(e.getKey())) {
                AsyncPhysicsJob job = e.getValue();
                if (job.running() && !job.done()) {
                    job.future.cancel(false);
                }
                it.remove();
            }
        }
    }

    private static void cancelAllAsyncPhysics() {
        for (AsyncPhysicsJob job : ASYNC_PHYSICS_JOBS.values()) {
            if (job.running() && !job.done()) {
                job.future.cancel(false);
            }
        }
        ASYNC_PHYSICS_JOBS.clear();
    }

    private static StepDecision stepDecision(RenderEntry entry, long tick, PhysicsBudget physicsBudget,
            boolean forceActive) {
        if (forceActive || hasSynchronousDynamicReason(entry)) {
            return physicsBudget.tryForceConsume()
                    ? new StepDecision(true, forceActive ? "force" : "sync!")
                    : new StepDecision(false, "circuit-breaker");
        }
        if (isHighMotion(entry)) {
            return physicsBudget.tryForceConsume()
                    ? new StepDecision(true, "fast")
                    : new StepDecision(false, "circuit-breaker");
        }
        if (isCloseDynamic(entry)) {
            return physicsBudget.tryForceConsume()
                    ? new StepDecision(true, "20tps")
                    : new StepDecision(false, "circuit-breaker");
        }
        boolean windActive = entry.sim().hasActiveWind(entry.a(), entry.b(), tick);
        int interval = dynamicStepInterval(entry.lodDistSqr(), entry.sim().tuning().maxTickDelta());
        if (!windActive && entry.sim().isSettled() && entry.sim().quietTicks() >= entry.sim().tuning().settleThresholdTicks()) {
            interval = Math.max(interval, ClientTuning.DYNAMIC_SETTLED_STEP_INTERVAL.get());
        }
        long last = LAST_DYNAMIC_STEP_TICK.getOrDefault(entry.connection().id(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && tick - last < interval) {
            return new StepDecision(false, windActive ? "wind-skip/" + interval : "skip/" + interval);
        }
        if (!physicsBudget.tryConsume()) {
            return new StepDecision(false, windActive ? "wind-budget" : "budget");
        }
        if (windActive) {
            return new StepDecision(true, interval <= 1 ? "wind" : "wind/" + interval);
        }
        return new StepDecision(true, interval <= 1 ? "20tps" : (20 / interval) + "tps");
    }

    private static boolean hasSynchronousDynamicReason(RenderEntry entry) {
        return entry.sim().hasExternalContact(net.minecraft.client.Minecraft.getInstance().level.getGameTime())
                || ZiplineClientState.hasRiderOn(entry.connection().id());
    }

    private static boolean isCloseDynamic(RenderEntry entry) {
        return entry.lodDistSqr() <= 8.0D * 8.0D;
    }

    private static boolean isHighMotion(RenderEntry entry) {
        return entry.sim().maxNodeMotionSqr() >= ASYNC_HIGH_MOTION_SQR;
    }

    private static int dynamicStepInterval(double lodDistSqr, int maxTickDelta) {
        int maxSafeInterval = Math.max(1, maxTickDelta);
        double d4 = ClientTuning.DYNAMIC_STEP_INTERVAL4_DISTANCE.get();
        if (lodDistSqr >= d4 * d4) {
            return Math.min(4, maxSafeInterval);
        }
        double d2 = ClientTuning.DYNAMIC_STEP_INTERVAL2_DISTANCE.get();
        if (lodDistSqr >= d2 * d2) {
            return Math.min(2, maxSafeInterval);
        }
        return 1;
    }

    private static void updateInactivePhysicsEntry(Player player, Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection, RenderEntry entry, long tick) {
        // Physics is explicitly disabled for this rope: keep a cheap cosmetic sagged
        // state. Distance-based LOD uses maintainTerrainLodEntry instead.
        entry.sim().updateVisualLeash(entry.a(), entry.b(), tick, 0.45F);
        if (!entry.sim().physicsEnabled() && tripEnabled(entry.connection())) {
            maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
        }
        recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, entry.connection().id(),
            0L, "off");
    }

    private static void maintainTerrainLodEntry(ClientLevel level,
            Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection,
            RenderEntry entry, long tick, PhysicsBudget physicsBudget) {
        UUID id = entry.connection().id();
        LOD_REFINEMENT.markLowDetail(id);
        long last = LAST_DYNAMIC_STEP_TICK.getOrDefault(id, Long.MIN_VALUE);
        if (!shouldStepTerrainLod(last, tick)) {
            recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id, 0L, "terrain-hold");
            return;
        }
        if (!physicsBudget.tryConsume()) {
            recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id, 0L, "terrain-budget");
            return;
        }

        cancelAsyncPhysics(id);
        // A sparse LOD update must resolve one current terrain frame, not repay every
        // skipped simulation tick. Repaying that debt would multiply the very work this
        // LOD path is intended to avoid and can pull a resting rope through terrain.
        entry.sim().prepareSparseTerrainStep(tick);
        long stepStart = RopeDebugLabels.enabled() ? System.nanoTime() : 0L;
        boolean stepped = entry.sim().step(level, entry.a(), entry.b(), tick,
                List.of(), List.of(), List.of());
        LAST_DYNAMIC_STEP_TICK.put(id, tick);
        long stepNanos = RopeDebugLabels.enabled() ? System.nanoTime() - stepStart : 0L;
        recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, id,
                stepNanos, stepped ? "terrain-lod" : "terrain-idle");
    }

    static boolean shouldStepTerrainLod(long lastStepTick, long currentTick) {
        return lastStepTick == Long.MIN_VALUE
                || currentTick - lastStepTick >= TERRAIN_LOD_STEP_INTERVAL;
    }

    private static void updateMaintainableSimIds(List<RenderEntry> simEntries, Set<UUID> staticSimIds) {
        Set<UUID> next = new HashSet<>(simEntries.size() + staticSimIds.size());
        for (RenderEntry entry : simEntries) {
            if (entry.sim().physicsEnabled()) {
                next.add(entry.connection().id());
            }
        }
        next.addAll(staticSimIds);
        maintainableSimIds = Set.copyOf(next);
    }

    private static void releaseDynamicActiveStaticRopes(ClientLevel level, List<RenderEntry> renderEntries,
            StaticRopeChunkRegistry staticRopes, long tick) {
        for (RenderEntry entry : renderEntries) {
            UUID id = entry.connection().id();
            RopeSimulation sim = entry.sim();
            applyServerState(sim, id, tick);
            if (LOD_REFINEMENT.isRefining(id)) {
                if (!sim.isSettled()) {
                    staticRopes.holdDynamic(level, id, tick + 8L);
                    continue;
                }
                // This shape has completed at least one full-physics settle cycle and
                // may now be cached again without preserving low-detail penetration.
                LOD_REFINEMENT.complete(id);
            }
            if (hasSynchronousDynamicReason(entry)) {
                staticRopes.holdDynamic(level, id, tick + 8L);
                continue;
            }
            if (!RopePerchClientForces.forConnection(level, entry.connection(), sim).isEmpty()) {
                staticRopes.holdDynamic(level, id, tick + 8L);
                continue;
            }
            if (hasActualEntityContact(level, sim, entry.a(), entry.b())) {
                staticRopes.holdDynamic(level, id, tick + 8L);
                continue;
            }
            sim.setWindPhysicsEnabled(windPhysicsEnabledFor(sim.tuning(), entry.lodDistSqr()));
            if (sim.hasActiveWind(entry.a(), entry.b(), tick)) {
                staticRopes.holdDynamic(level, id, tick + 8L);
            }
        }
    }

    private static boolean hasActualEntityContact(ClientLevel level, RopeSimulation sim, Vec3 a, Vec3 b) {
        List<RopeEntityContact> candidates = collectEntityContacts(level, sim, a, b);
        if (candidates.isEmpty()) {
            return false;
        }
        double radius = Math.max(0.02D, sim.tuning().ropeRadius() + sim.tuning().collisionEps());
        for (RopeEntityContact candidate : candidates) {
            double topPadding = candidate.player() ? 0.18D : 0.0D;
            RopeSimulation.ContactSample contact = sim.findPlayerContact(candidate.box(), radius, topPadding);
            if (contact != null && contact.depth() > 1.0e-4D) {
                return true;
            }
        }
        return false;
    }

    private static void submitRenderEntry(SubmitCustomGeometryEvent event, ClientLevel level,
            StaticRopeChunkRegistry staticRopes, Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection, List<RopeJob> ropeJobs, RenderEntry entry, Vec3 cameraPos,
            float partialTick, long tick, ConnectionHighlight highlight, UUID highlightedConnectionId) {
        UUID connectionId = entry.connection().id();
        boolean chunkMeshActive = shouldUseStaticChunkMeshRender(
            staticRopes.isMeshAccepted(connectionId),
            staticRopes.shouldDynamicLinger(connectionId, tick));
        if (chunkMeshActive
                && (highlightedConnectionId == null || !connectionId.equals(highlightedConnectionId))) {
            submitStaticRenderEntry(event, level, staticRopes, physicsNanosByConnection, physicsStateByConnection,
                    entry, cameraPos, partialTick, tick, connectionId);
            return;
        }
        submitDynamicRenderEntry(event, level, physicsNanosByConnection, physicsStateByConnection, ropeJobs, entry,
                cameraPos, partialTick, tick, highlight, connectionId, chunkMeshActive);
    }

    private static void submitStaticRenderEntry(SubmitCustomGeometryEvent event, ClientLevel level,
            StaticRopeChunkRegistry staticRopes,
            Map<UUID, Long> physicsNanosByConnection, Map<UUID, String> physicsStateByConnection, RenderEntry entry,
            Vec3 cameraPos, float partialTick, long tick, UUID connectionId) {
        recordRopeLabel(entry, partialTick, true, physicsNanosByConnection, physicsStateByConnection);
        RopeSectionSnapshot snapshot = staticRopes.snapshotForRender(connectionId, tick);
        if (snapshot != null) {
            ClientRopeParticles.spawnRedstone(level, snapshot, entry.connection(), entry.lodDistSqr());
            ClientRopeParticles.spawnEnergy(level, snapshot, entry.connection(), entry.lodDistSqr());
        }
    }

    private static void submitDynamicRenderEntry(SubmitCustomGeometryEvent event, ClientLevel level,
            Map<UUID, Long> physicsNanosByConnection, Map<UUID, String> physicsStateByConnection,
            List<RopeJob> ropeJobs, RenderEntry entry, Vec3 cameraPos, float partialTick, long tick,
            ConnectionHighlight highlight, UUID connectionId, boolean chunkMeshActive) {
        Vec3 a = entry.a();
        Vec3 b = entry.b();
        RopeSimulation sim = entry.sim();
        BlockPos lightA = BlockPos.containing(a);
        BlockPos lightB = BlockPos.containing(b);
        int blockA = RopeDynamicLights.boostBlockLight(lightA, level.getBrightness(LightLayer.BLOCK, lightA));
        int blockB = RopeDynamicLights.boostBlockLight(lightB, level.getBrightness(LightLayer.BLOCK, lightB));
        int skyA = level.getBrightness(LightLayer.SKY, lightA);
        int skyB = level.getBrightness(LightLayer.SKY, lightB);
        int highlightColor = highlight != null && connectionId.equals(highlight.id())
                ? highlight.color()
                : LeashBuilder.NO_HIGHLIGHT;
        float[] pulses = entry.lodDistSqr() <= pulseRenderDistanceSqr()
                ? ClientRopeParticles.computeItemPulses(entry.connection(), tick, partialTick)
                : null;
        int extractEnd = extractEnd(entry.connection());
        recordRopeLabel(entry, partialTick, false, physicsNanosByConnection, physicsStateByConnection);
        ropeJobs.add(LeashBuilder.collect(sim, blockA, blockB, skyA, skyB, highlightColor,
                entry.connection().kind(), entry.connection().powered(), entry.connection().tier(),
                pulses, extractEnd, chunkMeshActive));
        ClientRopeParticles.spawnRedstone(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
        ClientRopeParticles.spawnEnergy(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
        if (!entry.connection().attachments().isEmpty()
                && entry.lodDistSqr() <= physicsLodDistanceSqr()) {
            tickDynamicAttachmentSwings(level, sim, entry.connection().attachments(), partialTick, entry.lodDistSqr(), tick);
            RopeAttachmentRenderer.submitAll(event.getSubmitNodeCollector(), cameraPos, level, sim,
                    entry.connection().attachments(),
                    entry.connection().kind() == LeadKind.REDSTONE && entry.connection().powered(),
                    partialTick);
        }
    }

    static boolean shouldUseStaticChunkMeshRender(boolean meshAccepted, boolean dynamicLinger) {
        return shouldUseStaticChunkMeshRender(ClientTuning.MODE_RENDER3D.get(), meshAccepted, dynamicLinger);
    }

    static boolean shouldUseStaticChunkMeshRender(boolean render3d, boolean meshAccepted, boolean dynamicLinger) {
        return render3d
                && meshAccepted
                && !dynamicLinger;
    }

    private static void tickDynamicAttachmentSwings(ClientLevel level, RopeSimulation sim,
            List<com.zhongbai233.super_lead.lead.RopeAttachment> attachments, float partialTick,
            double lodDistSqr, long tick) {
        double total = sim.prepareRender(partialTick);
        if (total <= 1.0e-6D) {
            return;
        }
        int nodeCount = sim.nodeCount();
        for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : attachments) {
            double target = attachment.t() * total;
            int seg = locateRenderSegment(sim, nodeCount, target);
            double l0 = sim.renderLength(seg);
            double l1 = sim.renderLength(seg + 1);
            double frac = l1 > l0 + 1.0e-6D ? (target - l0) / (l1 - l0) : 0.0D;
            double ax = sim.renderX(seg), ay = sim.renderY(seg), az = sim.renderZ(seg);
            double bx = sim.renderX(seg + 1), by = sim.renderY(seg + 1), bz = sim.renderZ(seg + 1);
            double px = ax + (bx - ax) * frac;
            double py = ay + (by - ay) * frac;
            double pz = az + (bz - az) * frac;
            double tx = bx - ax, ty = by - ay, tz = bz - az;
            double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tLen <= 1.0e-6D) {
                AttachmentSwingClient.tickPassive(attachment.id(), tick);
                continue;
            }
            tx /= tLen;
            ty /= tLen;
            tz /= tLen;
            double sx = -tz;
            double sy = 0.0D;
            double sz = tx;
            double sLen = Math.sqrt(sx * sx + sz * sz);
            if (sLen <= 1.0e-6D) {
                sx = 1.0D;
                sy = 0.0D;
                sz = 0.0D;
            } else {
                sx /= sLen;
                sz /= sLen;
            }
            double cx = px;
            double cy = py - 0.28D;
            double cz = pz;
            AttachmentSwingClient.tickDynamicWithSupport(level, attachment.id(), cx, cy, cz, px, py, pz,
                    tx, ty, tz, sx, sy, sz, lodDistSqr, tick);
        }
    }

    private static int locateRenderSegment(RopeSimulation sim, int nodeCount, double target) {
        if (target <= 0.0D) {
            return 0;
        }
        double eps = 1.0e-9D;
        for (int i = 0; i < nodeCount - 1; i++) {
            if (target < sim.renderLength(i + 1) - eps) {
                return i;
            }
        }
        return Math.max(0, nodeCount - 2);
    }

    private static void retainAttachmentSwingStates(List<LeadConnection> connections,
            List<RopeAttachmentRenderer.BakedAttachment> bakedStaticAttachments, long tick) {
        Set<UUID> activeAttachmentIds = FRAME_ACTIVE_ATTACHMENT_IDS;
        activeAttachmentIds.clear();
        for (LeadConnection connection : connections) {
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                activeAttachmentIds.add(attachment.id());
            }
        }
        for (RopeAttachmentRenderer.BakedAttachment attachment : bakedStaticAttachments) {
            activeAttachmentIds.add(attachment.attachmentId());
            AttachmentSwingClient.tickPassive(attachment.attachmentId(), tick);
        }
        AttachmentSwingClient.retainAttachments(activeAttachmentIds, tick);
    }

    private static void tickStaticAttachmentSwings(ClientLevel level,
            List<RopeAttachmentRenderer.BakedAttachment> attachments,
            Map<UUID, Double> lodDistanceByConnection, long tick) {
        if (attachments.isEmpty()) {
            return;
        }
        for (RopeAttachmentRenderer.BakedAttachment attachment : attachments) {
            Double lod = lodDistanceByConnection.get(attachment.connectionId());
            double lodDistSqr = lod == null ? Double.POSITIVE_INFINITY : lod.doubleValue();
            double tx = attachment.bx() - attachment.ax();
            double ty = attachment.by() - attachment.ay();
            double tz = attachment.bz() - attachment.az();
            double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tLen <= 1.0e-6D) {
                AttachmentSwingClient.tickPassive(attachment.attachmentId(), tick);
                continue;
            }
            tx /= tLen;
            ty /= tLen;
            tz /= tLen;
            double sx = -tz;
            double sy = 0.0D;
            double sz = tx;
            double sLen = Math.sqrt(sx * sx + sz * sz);
            if (sLen <= 1.0e-6D) {
                sx = 1.0D;
                sz = 0.0D;
            } else {
                sx /= sLen;
                sz /= sLen;
            }
            AttachmentSwingClient.tickDynamic(level, attachment.attachmentId(), attachment.lightX(), attachment.lightY(),
                    attachment.lightZ(), tx, ty, tz, sx, sy, sz, lodDistSqr, tick);
        }
    }

    private static int extractEnd(LeadConnection connection) {
        return connection.kind() == LeadKind.ITEM
                || connection.kind() == LeadKind.FLUID
                || connection.kind() == LeadKind.PRESSURIZED
                || connection.kind() == LeadKind.ENERGY
                        ? connection.extractAnchor()
                        : 0;
    }

    private static double pulseRenderDistanceSqr() {
        double d = ClientTuning.DYNAMIC_PULSE_DISTANCE.get();
        return d * d;
    }

    private static final class PhysicsBudget {
        private final int max;
        private final long deadlineNanos;
        private int used;

        private PhysicsBudget(int max, long maxNanos) {
            this.max = Math.max(0, max);
            this.deadlineNanos = System.nanoTime() + Math.max(0L, maxNanos);
        }

        boolean tryConsume() {
            if (used >= max || timeExhausted()) {
                return false;
            }
            used++;
            return true;
        }

        boolean tryForceConsume() {
            if (timeExhausted()) {
                return false;
            }
            used++;
            return true;
        }

        private boolean timeExhausted() {
            return System.nanoTime() >= deadlineNanos;
        }
    }

    private record StepDecision(boolean shouldStep, String state) {
    }

    private static void submitAttachmentRemovalPreview(SubmitCustomGeometryEvent event, ClientLevel level,
            List<LeadConnection> connections, Vec3 cameraPos, float partialTick) {
        UUID removalConnId = ClientRopeInteractions.removalPreviewConnectionId();
        UUID removalAttachId = ClientRopeInteractions.removalPreviewAttachmentId();
        if (removalConnId == null || removalAttachId == null) {
            return;
        }
        RopeSimulation removalSim = SIMS.get(removalConnId);
        if (removalSim == null) {
            return;
        }
        for (LeadConnection connection : connections) {
            if (connection.id().equals(removalConnId)) {
                submitMatchingRemovalGhost(event, level, cameraPos, partialTick, removalSim, connection,
                        removalAttachId);
                return;
            }
        }
    }

    private static void submitMatchingRemovalGhost(SubmitCustomGeometryEvent event, ClientLevel level, Vec3 cameraPos,
            float partialTick, RopeSimulation removalSim, LeadConnection connection, UUID removalAttachId) {
        for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
            if (attachment.id().equals(removalAttachId)) {
                RopeAttachmentRenderer.submitGhost(event.getSubmitNodeCollector(),
                        cameraPos, level, removalSim, attachment.stack(), attachment.t(), partialTick);
                return;
            }
        }
    }

    private static void submitAttachmentPlacementPreview(SubmitCustomGeometryEvent event, ClientLevel level,
            Minecraft minecraft, Vec3 cameraPos, float partialTick) {
        Player previewPlayer = minecraft.player;
        net.minecraft.world.item.ItemStack ghostStack = ClientRopeInteractions.attachmentStackForPreview(previewPlayer);
        if (ghostStack.isEmpty()) {
            return;
        }
        ClientRopeInteractions.AttachPick ghostPick = ClientRopeInteractions.pickRopeAttachPoint(minecraft,
                partialTick);
        if (ghostPick == null) {
            return;
        }
        RopeSimulation ghostSim = SIMS.get(ghostPick.connectionId());
        if (ghostSim != null) {
            RopeAttachmentRenderer.submitGhost(event.getSubmitNodeCollector(),
                    cameraPos, level, ghostSim, ghostStack, ghostPick.t(), partialTick);
        }
    }

    private static void publishDebugStats(List<LeadConnection> connections,
            List<RenderEntry> simEntries,
            List<RenderEntry> renderEntries,
            StaticRopeChunkRegistry staticRopes,
            List<RopeJob> ropeJobs) {
        RopeDebugStats.totalConnections = connections.size();
        RopeDebugStats.simEntries = simEntries.size();
        RopeDebugStats.renderEntries = renderEntries.size();
        RopeDebugStats.chunkMeshClaimed = staticRopes.claimedCount();
        RopeDebugStats.chunkMeshAcceptedConnections = staticRopes.acceptedConnectionCount();
        RopeDebugStats.dynamicJobs = ropeJobs.size();
        RopeDebugStats.simCount = SIMS.size();
        RopeDebugStats.chunkMeshSections = staticRopes.sectionCount();
        RopeDebugStats.chunkMeshAcceptedSections = staticRopes.acceptedSectionCount();
        RopeDebugStats.chunkMeshSnapshots = staticRopes.sectionSnapshotsTotal();
        int attachmentsTotal = 0;
        for (LeadConnection connection : connections) {
            attachmentsTotal += connection.attachments().size();
        }
        RopeDebugStats.attachmentsTotal = attachmentsTotal;
        RopeDebugStats.chunkMeshEligible = staticRopes.eligibleCount();
        RopeDebugStats.chunkMeshWaitingQuiet = staticRopes.waitingQuietCount();
        RopeDebugStats.chunkMeshReadyFromSim = staticRopes.readyFromSimCount();
        RopeDebugStats.chunkMeshReadyAnchorBake = staticRopes.readyAnchorBakeCount();
        RopeDebugStats.chunkMeshClaimedFromSim = staticRopes.claimedFromSimCount();
        RopeDebugStats.chunkMeshClaimedAnchorBake = staticRopes.claimedAnchorBakeCount();
        RopeDebugStats.chunkMeshMissingAnchors = staticRopes.missingAnchorBakeCount();
        RopeDebugStats.visibilityFarRetests = RopeVisibility.debugFarRetests();
        RopeDebugStats.visibilityFarDeferred = RopeVisibility.debugFarDeferred();
        RopeDebugStats.meshDirtyQueue = staticRopes.dirtyQueueCount();
        RopeDebugStats.meshDirtyFlushed = staticRopes.dirtyFlushedLastTick();
        int dynamicNodesTotal = 0;
        for (RopeJob job : ropeJobs) {
            dynamicNodesTotal += job.sim.nodeCount();
        }
        RopeDebugStats.dynamicNodesTotal = dynamicNodesTotal;
        RopeDebugStats.chunkMeshNodesTotal = staticRopes.claimedNodesTotal();
        RopeDebugStats.totalRenderNodes = RopeDebugStats.dynamicNodesTotal + RopeDebugStats.chunkMeshNodesTotal;
    }

    private static void recordPhysicsState(Map<UUID, Long> nanosByConnection,
            Map<UUID, String> stateByConnection, UUID id, long nanos, String state) {
        if (!RopeDebugLabels.enabled()) {
            return;
        }
        nanosByConnection.put(id, nanos);
        stateByConnection.put(id, state);
    }

    private static boolean windPhysicsEnabledFor(RopeTuning tuning, double lodDistSqr) {
        if (tuning == null || !tuning.windEnabled() || tuning.windPhysicsDistance() <= 0.0D) {
            return false;
        }
        double distance = Math.min(tuning.windPhysicsDistance(), physicsLodDistance());
        return lodDistSqr <= distance * distance;
    }

    private static void recordRopeLabel(RenderEntry entry, float partialTick, boolean chunkMeshActive,
            Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection) {
        if (!RopeDebugLabels.enabled()) {
            return;
        }
        RopeSimulation sim = entry.sim();
        Vec3 point = ropeLabelPoint(sim, partialTick);
        if (point == null) {
            return;
        }
        UUID id = entry.connection().id();
        long nanos = physicsNanosByConnection.getOrDefault(id, 0L);
        double physicsMs = nanos <= 0L ? 0.0D : nanos / 1_000_000.0D;
        String state = physicsStateByConnection.getOrDefault(id, chunkMeshActive ? "mesh" : "cached");
        RopeDebugLabels.record(id, point, sim.nodeCount(), renderLodLevel(entry.lodDistSqr()),
                renderPressureScore(sim, entry.lodDistSqr(), chunkMeshActive),
                physicsMs, state, chunkMeshActive, entry.lodDistSqr());
    }

    private static Vec3 ropeLabelPoint(RopeSimulation sim, float partialTick) {
        if (sim == null || sim.nodeCount() <= 0) {
            return null;
        }
        sim.prepareRender(partialTick);
        int i = sim.nodeCount() / 2;
        return new Vec3(sim.renderX(i), sim.renderY(i) + 0.35D, sim.renderZ(i));
    }

    private static int renderPressureScore(RopeSimulation sim, double lodDistSqr, boolean chunkMeshActive) {
        int nodes = sim.nodeCount();
        if (chunkMeshActive) {
            return Math.max(1, 2 + nodes / 8);
        }
        double ribbonDistance = ClientTuning.LOD_RIBBON_DISTANCE.get();
        double stride4Distance = ClientTuning.LOD_STRIDE4_DISTANCE.get();
        double stride2Distance = ClientTuning.LOD_STRIDE2_DISTANCE.get();
        int stride = lodDistSqr >= stride4Distance * stride4Distance ? 4
                : lodDistSqr >= stride2Distance * stride2Distance ? 2
                        : 1;
        boolean ribbon = lodDistSqr >= ribbonDistance * ribbonDistance;
        int visibleNodes = Math.max(2, (nodes + stride - 1) / stride);
        return visibleNodes * (ribbon ? 2 : 5);
    }

    static int renderLodLevel(double lodDistSqr) {
        if (!ClientTuning.MODE_RENDER3D.get()) {
            return 3;
        }
        return renderLodLevel(lodDistSqr,
                ClientTuning.LOD_STRIDE2_DISTANCE.get(),
                ClientTuning.LOD_STRIDE4_DISTANCE.get(),
                ClientTuning.LOD_RIBBON_DISTANCE.get());
    }

    static int renderLodLevel(double lodDistSqr, double stride2Distance,
            double stride4Distance, double ribbonDistance) {
        if (lodDistSqr > ribbonDistance * ribbonDistance) {
            return 3;
        }
        if (lodDistSqr > stride4Distance * stride4Distance) {
            return 2;
        }
        return lodDistSqr > stride2Distance * stride2Distance ? 1 : 0;
    }

    private static double ribbonLodDistanceSqr() {
        double d = ClientTuning.LOD_RIBBON_DISTANCE.get();
        return d * d;
    }

    private static void renderPreview(SubmitCustomGeometryEvent event, ClientLevel level, Vec3 cameraPos,
            float partialTick, long tick, Player player) {
        if (!SuperLeadNetwork.canModifyRopes(player)) {
            previewSim = null;
            previewAnchor = null;
            return;
        }
        LeadAnchor anchor = SuperLeadNetwork.pendingAnchor(player).orElse(null);
        if (anchor == null) {
            previewSim = null;
            previewAnchor = null;
            return;
        }
        Vec3 a = anchor.attachmentPoint(level);
        Vec3 b = player.getRopeHoldPosition(partialTick);
        int lengthUnits = SuperLeadNetwork.pendingLengthUnits(player);
        if (a.distanceTo(b) > SuperLeadNetwork.maxLeashDistanceForUnits(lengthUnits)) {
            return;
        }
        RopeTuning tuning = RopeTuning.forMidpoint(a, b);
        // While one endpoint is in the player's hand, keep the preview rope's
        // topology stable and let the solver stretch/compress the existing segments.
        // Rebuilding on every segment-count threshold crossing wipes the current
        // physical state and causes visible popping/flicker during dragging.
        if (previewSim == null || !anchor.equals(previewAnchor)) {
            previewSim = new RopeSimulation(a, b, 0L, tuning);
            previewAnchor = anchor;
        } else {
            previewSim.setTuning(tuning);
        }
        previewSim.stepUpTo(level, a, b, tick);

        BlockPos lightA = BlockPos.containing(a);
        BlockPos lightB = BlockPos.containing(b);
        LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(LeadKind.NORMAL);
        int blockA = RopeDynamicLights.boostBlockLight(lightA, level.getBrightness(LightLayer.BLOCK, lightA));
        int blockB = RopeDynamicLights.boostBlockLight(lightB, level.getBrightness(LightLayer.BLOCK, lightB));
        int skyA = level.getBrightness(LightLayer.SKY, lightA);
        int skyB = level.getBrightness(LightLayer.SKY, lightB);
        LeashBuilder.submit(event.getSubmitNodeCollector(), cameraPos, previewSim, partialTick,
                blockA, blockB, skyA, skyB, false, kind, false);
    }

    private static List<RopeAttachmentRenderer.BakedAttachment> filterBakedStaticAttachments(
            List<RopeAttachmentRenderer.BakedAttachment> attachments,
            Map<UUID, Double> lodDistanceByConnection,
            UUID highlightedConnectionId) {
        if (attachments.isEmpty()) {
            return attachments;
        }
        List<RopeAttachmentRenderer.BakedAttachment> filtered = null;
        for (int i = 0; i < attachments.size(); i++) {
            RopeAttachmentRenderer.BakedAttachment attachment = attachments.get(i);
            Double lodDistSqr = lodDistanceByConnection.get(attachment.connectionId());
            boolean drop = lodDistSqr != null && lodDistSqr > physicsLodDistanceSqr();
            if (!drop && highlightedConnectionId != null
                    && attachment.connectionId().equals(highlightedConnectionId)) {
                drop = true;
            }
            if (drop) {
                if (filtered == null) {
                    filtered = new ArrayList<>(attachments.size());
                    for (int j = 0; j < i; j++) {
                        filtered.add(attachments.get(j));
                    }
                }
            } else if (filtered != null) {
                filtered.add(attachment);
            }
        }
        return filtered == null ? attachments : filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    private static void applyServerState(RopeSimulation sim, UUID connectionId, long tick) {
        RopeContactsClient.Contact contact = RopeContactsClient.get(connectionId);
        if (ZiplineClientState.hasRiderOn(connectionId)) {
            // Do not apply a client-only fake load while ziplining. The rider position is
            // server-authoritative, so bending only the local rendered rope makes the rope
            // appear below/away from the player (especially after repeated ticks).
            sim.clearExternalContact();
        } else if (contact == null) {
            sim.clearExternalContact();
        } else {
            sim.setExternalContact(tick, contact.t(), contact.dx(), contact.dy(), contact.dz());
        }

    }

    private static void maybeReportPlayerContact(Player player, LeadConnection connection,
            RopeSimulation sim, Vec3 a, Vec3 b, long tick) {
        if (player == null || player.isSpectator())
            return;
        if (ZiplineClientState.isZiplining(player.getId()))
            return;
        if (connection.physicsPreset().isBlank())
            return;
        if (!PhysicsZonesClient.hasPreset(connection.physicsPreset()))
            return;
        Map<String, String> overrides = PhysicsZonesClient.overridesForPreset(connection.physicsPreset());
        boolean normalContactKind = connection.kind() == LeadKind.NORMAL || connection.kind() == LeadKind.REDSTONE;
        boolean tripEnabled = resolveBool(overrides, ClientTuning.CONTACT_TRIP_ENABLED);
        boolean pushbackEnabled = normalContactKind && resolveBool(overrides, ClientTuning.CONTACT_PUSHBACK);
        if (!pushbackEnabled && !tripEnabled)
            return;

        double radius = resolveDouble(overrides, ClientTuning.CONTACT_RADIUS);
        if (radius <= 0.0D)
            return;
        double topPadding = resolveDouble(overrides, ClientTuning.CONTACT_TOP_PADDING);
        AABB playerBox = player.getBoundingBox();
        RopeSimulation.ContactSample contact = sim.findPlayerContact(playerBox, radius, topPadding);
        boolean tripProbeContact = false;
        if (contact == null && tripEnabled) {
            contact = findTripProbeContact(sim, playerBox, radius, topPadding);
            tripProbeContact = contact != null;
        }
        if (contact == null || contact.depth() <= 1.0e-4D)
            return;

        double nx = contact.normalX();
        double ny = contact.normalY();
        double nz = contact.normalZ();
        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 1.0e-5D)
            return;
        nx /= nLen;
        ny /= nLen;
        nz /= nLen;
        double tx = contact.tangentX();
        double ty = contact.tangentY();
        double tz = contact.tangentZ();
        double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tLen < 1.0e-5D)
            return;
        tx /= tLen;
        ty /= tLen;
        tz /= tLen;
        double topThreshold = resolveDouble(overrides, ClientTuning.CONTACT_TOP_NORMAL_THRESHOLD);
        boolean jumpDown = isJumpKeyDown(player);
        Vec3 input = playerMoveIntent(player);
        double playerFeetY = player.getY();
        double ropeSurfaceY = contact.y() + radius;
        boolean footSupport = tripProbeContact
                || (ny >= topThreshold)
                || (ropeSurfaceY >= playerFeetY - 0.025D && contact.y() <= playerFeetY + 0.6D);
        boolean tripCandidate = tripEnabled && footSupport;
        if (normalContactKind && pushbackEnabled && player instanceof LocalPlayer localPlayer) {
            // Foot proximity selects the vertical support path. Side contacts keep using
            // the horizontal projected normal.
            applyLocalRigidContact(localPlayer, nx, ny, nz, contact.depth(), radius, jumpDown, footSupport,
                    input.x, input.z);
        }

        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new ClientRopeContactReport(
                        connection.id(),
                        (float) clamp01(contact.t()),
                        (float) contact.x(),
                        (float) contact.y(),
                        (float) contact.z(),
                        (float) nx,
                        (float) ny,
                        (float) nz,
                        (float) tx,
                        (float) ty,
                        (float) tz,
                        (float) input.x,
                        (float) input.z,
                        jumpDown,
                        tripCandidate,
                        (float) contact.depth(),
                        (float) contact.slack()));
    }

    private static boolean tripEnabled(LeadConnection connection) {
        return connection != null
                && !connection.physicsPreset().isBlank()
                && PhysicsZonesClient.hasPreset(connection.physicsPreset())
                && resolveBool(PhysicsZonesClient.overridesForPreset(connection.physicsPreset()),
                        ClientTuning.CONTACT_TRIP_ENABLED);
    }

    private static RopeSimulation.ContactSample findTripProbeContact(RopeSimulation sim, AABB playerBox, double radius,
            double topPadding) {
        double horizontal = Math.max(radius + 0.08D, 0.18D);
        double below = Math.max(radius * 2.5D, 0.30D);
        double above = Math.max(radius * 3.0D, 0.58D);
        AABB footProbe = new AABB(
                playerBox.minX - horizontal,
                playerBox.minY - below,
                playerBox.minZ - horizontal,
                playerBox.maxX + horizontal,
                playerBox.minY + above,
                playerBox.maxZ + horizontal);
        double probeRadius = Math.max(radius, 0.22D);
        return sim.findPlayerContact(footProbe, probeRadius, Math.max(topPadding, 0.26D));
    }

    private static boolean isJumpKeyDown(Player player) {
        if (!(player instanceof LocalPlayer)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options != null && minecraft.options.keyJump.isDown();
    }

    private static Vec3 playerMoveIntent(Player player) {
        if (!(player instanceof LocalPlayer localPlayer) || localPlayer.input == null) {
            return Vec3.ZERO;
        }
        var moveVector = localPlayer.input.getMoveVector();
        double inputX = moveVector.x;
        double inputZ = moveVector.y;
        double inputLen = Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLen < 1.0e-4D)
            return Vec3.ZERO;

        inputX /= inputLen;
        inputZ /= inputLen;
        double yaw = Math.toRadians(localPlayer.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = inputX * cos - inputZ * sin;
        double worldZ = inputZ * cos + inputX * sin;
        return new Vec3(worldX, 0.0D, worldZ);
    }

    /**
     * Client-side mirror of {@code RopeContactTracker.applyRigidContact}. Foot
     * support is vertical-only; side contacts cancel only inward horizontal normal
     * velocity so tangential and vertical motion stay intact.
     */
    private static void applyLocalRigidContact(LocalPlayer player, double nx, double ny, double nz,
            double depth, double radius, boolean jumpDown, boolean footSupport, double inputX, double inputZ) {
        Vec3 v = player.getDeltaMovement();
        if (footSupport) {
            player.setOnGround(true);
            player.resetFallDistance();
            if (jumpDown && v.y < LOCAL_ROPE_JUMP_SPEED) {
                player.setDeltaMovement(v.x, LOCAL_ROPE_JUMP_SPEED, v.z);
                return;
            }
            if (v.y < 0.0D) {
                player.setDeltaMovement(v.x, 0.0D, v.z);
                return;
            }
            return;
        }

        double horizontalLen = Math.sqrt(nx * nx + nz * nz);
        if (horizontalLen < 1.0e-5D || !Double.isFinite(horizontalLen))
            return;
        double hardDepth = radius * LOCAL_CONTACT_SIDE_HARD_DEPTH_FRACTION;
        if (depth < hardDepth)
            return;
        double hx = nx / horizontalLen;
        double hz = nz / horizontalLen;
        double correctionMag = Math.max(0.0D, depth - hardDepth) + 1.0e-3D;
        player.move(MoverType.SELF, new Vec3(hx * correctionMag, 0.0D, hz * correctionMag));
        double vn = v.x * hx + v.z * hz;
        double inputDot = inputX * hx + inputZ * hz;
        if (vn >= 0.0D || inputDot > LOCAL_CONTACT_EXIT_INPUT_DOT)
            return;
        player.setDeltaMovement(v.x - hx * vn, v.y, v.z - hz * vn);
    }

    private static boolean resolveBool(Map<String, String> overrides,
            com.zhongbai233.super_lead.tuning.TuningKey<Boolean> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                Boolean parsed = key.type.parse(raw);
                if (key.type.validate(parsed))
                    return parsed;
            } catch (RuntimeException ignored) {
            }
        }
        return key.defaultValue;
    }

    private static double resolveDouble(Map<String, String> overrides,
            com.zhongbai233.super_lead.tuning.TuningKey<Double> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                Double parsed = key.type.parse(raw);
                if (key.type.validate(parsed))
                    return parsed;
            } catch (RuntimeException ignored) {
            }
        }
        return key.defaultValue;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private static ConnectionHighlight pickHighlightedConnection(Minecraft minecraft, List<RenderEntry> entries,
            float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        ConnectionHighlight ziplineHighlight = pickZiplineHighlight(minecraft, partialTick);
        if (ziplineHighlight != null) {
            return ziplineHighlight;
        }
        if (!SuperLeadNetwork.canModifyRopes(player)) {
            return null;
        }
        ConnectionHighlight removalHighlight = pickAttachmentRemovalHighlight(minecraft, partialTick);
        if (removalHighlight != null) {
            return removalHighlight;
        }
        ConnectionHighlight attachmentHighlight = pickAttachmentPlacementHighlight(minecraft, partialTick);
        if (attachmentHighlight != null) {
            return attachmentHighlight;
        }
        // Perch boost highlight: shift + seeds aiming at a rope
        if (player.isShiftKeyDown()) {
            ConnectionHighlight perchHighlight = pickPerchBoostHighlight(minecraft, entries, partialTick, cameraPos);
            if (perchHighlight != null) {
                return perchHighlight;
            }
        }

        // Rope upgrades are gated behind sneak (except shears, which always work).
        // Mirror that
        // here so the player only sees the highlight when the action would actually
        // fire.
        // However, also show the highlight for upgrade items without sneak so the
        // tier-info overlay (LeadTooltipOverlay) can display current level.
        LeadConnectionAction action = LeadConnectionAction.fromHeldItems(player).orElse(null);
        if (action == null) {
            // No upgrade action — check for preset binder highlight
            if (player.isShiftKeyDown()) {
                return pickPresetBinderHighlight(minecraft, entries, partialTick, cameraPos);
            }
            return null;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        double dirX = forward.x();
        double dirY = forward.y();
        double dirZ = forward.z();
        double invDir = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= invDir;
        dirY *= invDir;
        dirZ *= invDir;
        // Clamp pick range to whatever the player is actually aiming at (block /
        // entity).
        // Without this, a rope that lies along the view direction many blocks past the
        // targeted
        // block still gets picked, which makes hopper / chest etc. unable to be placed
        // normally.
        double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
        net.minecraft.world.phys.HitResult hitResult = minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            // Allow a small slack so a rope just in front of the targeted block still
            // picks.
            maxDistance = Math.min(maxDistance, hitDist + PICK_RADIUS);
        } else if (player != null) {
            // No block/entity hit — cap to player reach so we don't scan ropes 48 blocks
            // away.
            maxDistance = Math.min(maxDistance, player.blockInteractionRange() + PICK_REACH_SLACK);
        }
        double best = PICK_RADIUS * PICK_RADIUS;
        UUID bestId = null;
        Vec3 bestHitPoint = null;
        double bestHitT = 0.5D;

        for (RenderEntry entry : entries) {
            if (!action.canTarget(entry.connection())) {
                continue;
            }
            RopeSimulation sim = entry.sim();
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D) {
                continue;
            }
            for (int i = 0; i < sim.nodeCount() - 1; i++) {
                double ax = sim.renderX(i);
                double ay = sim.renderY(i);
                double az = sim.renderZ(i);
                double bx = sim.renderX(i + 1);
                double by = sim.renderY(i + 1);
                double bz = sim.renderZ(i + 1);
                for (int sample = 0; sample <= 4; sample++) {
                    double t = sample / 4.0D;
                    double px = ax + (bx - ax) * t;
                    double py = ay + (by - ay) * t;
                    double pz = az + (bz - az) * t;
                    double distance = RopePickMath.distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            dirX, dirY, dirZ, maxDistance);
                    if (distance < best) {
                        best = distance;
                        bestId = entry.connection().id();
                        bestHitPoint = new Vec3(px, py, pz);
                        double l0 = sim.renderLength(i);
                        double l1 = sim.renderLength(i + 1);
                        double arc = l0 + (l1 - l0) * t;
                        bestHitT = clamp01(arc / total);
                    }
                }
            }
        }
        return bestId == null ? null
                : new ConnectionHighlight(bestId, action.previewColor(),
                        bestHitPoint, bestHitT);
    }

    private static ConnectionHighlight pickZiplineHighlight(Minecraft minecraft, float partialTick) {
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        if (!ZiplineController.isChain(player.getMainHandItem())
                && !ZiplineController.isChain(player.getOffhandItem())) {
            return null;
        }
        ClientRopeInteractions.AttachPick pick = ClientRopeInteractions.pickZiplinePoint(minecraft, partialTick);
        if (pick == null) {
            return null;
        }
        return new ConnectionHighlight(pick.connectionId(), ZIPLINE_HIGHLIGHT_COLOR,
                pick.point(), pick.t());
    }

    private static ConnectionHighlight pickAttachmentRemovalHighlight(Minecraft minecraft, float partialTick) {
        ClientRopeInteractions.clearRemovalPreview();
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        if (!SuperLeadNetwork.canModifyRopes(player)) {
            return null;
        }
        if (!player.isShiftKeyDown()) {
            return null;
        }
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        if (!main.is(net.minecraft.world.item.Items.SHEARS) && !off.is(net.minecraft.world.item.Items.SHEARS)) {
            return null;
        }
        ClientRopeInteractions.AttachmentPick pick = ClientRopeInteractions.pickAttachmentForRemoval(minecraft,
                partialTick);
        if (pick == null) {
            return null;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(minecraft.level)) {
            if (!connection.id().equals(pick.connectionId()))
                continue;
            for (com.zhongbai233.super_lead.lead.RopeAttachment a : connection.attachments()) {
                if (a.id().equals(pick.attachmentId())) {
                    ClientRopeInteractions.setRemovalPreview(pick.connectionId(), pick.attachmentId());
                    return new ConnectionHighlight(pick.connectionId(), ATTACHMENT_REMOVAL_HIGHLIGHT_COLOR,
                            Vec3.ZERO, a.t());
                }
            }
        }
        return null;
    }

    private static ConnectionHighlight pickAttachmentPlacementHighlight(Minecraft minecraft, float partialTick) {
        Player player = minecraft.player;
        if (ClientRopeInteractions.attachmentStackForPreview(player).isEmpty()) {
            return null;
        }
        ClientRopeInteractions.AttachPick pick = ClientRopeInteractions.pickRopeAttachPoint(minecraft, partialTick);
        if (pick == null) {
            return null;
        }
        return new ConnectionHighlight(pick.connectionId(), ATTACHMENT_HIGHLIGHT_COLOR,
                pick.point(), pick.t());
    }

    /**
     * Highlight the rope under the crosshair when the player holds a bound preset
     * binder and is sneaking, giving visual feedback for the rope toggle action.
     */
    public static boolean trySendBoostRopePerch() {
        return ClientRopeInteractions.trySendBoostRopePerch();
    }

    private static ConnectionHighlight pickPerchBoostHighlight(Minecraft minecraft, List<RenderEntry> entries,
            float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null)
            return null;
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean hasSeeds = isSeedItem(main) || isSeedItem(off);
        if (!hasSeeds)
            return null;

        Camera camera = minecraft.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        double dirX = forward.x();
        double dirY = forward.y();
        double dirZ = forward.z();
        double invDir = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= invDir;
        dirY *= invDir;
        dirZ *= invDir;

        double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
        net.minecraft.world.phys.HitResult hitResult = minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            maxDistance = Math.min(maxDistance, hitDist + PICK_RADIUS);
        } else if (player != null) {
            maxDistance = Math.min(maxDistance, player.blockInteractionRange() + PICK_REACH_SLACK);
        }
        double best = PICK_RADIUS * PICK_RADIUS;
        UUID bestId = null;
        Vec3 bestHitPoint = null;
        double bestHitT = 0.5D;

        for (RenderEntry entry : entries) {
            RopeSimulation sim = entry.sim();
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D)
                continue;
            for (int i = 0; i < sim.nodeCount() - 1; i++) {
                double ax = sim.renderX(i);
                double ay = sim.renderY(i);
                double az = sim.renderZ(i);
                double bx = sim.renderX(i + 1);
                double by = sim.renderY(i + 1);
                double bz = sim.renderZ(i + 1);
                for (int sample = 0; sample <= 4; sample++) {
                    double t = sample / 4.0D;
                    double px = ax + (bx - ax) * t;
                    double py = ay + (by - ay) * t;
                    double pz = az + (bz - az) * t;
                    double distance = RopePickMath.distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            dirX, dirY, dirZ, maxDistance);
                    if (distance < best) {
                        best = distance;
                        bestId = entry.connection().id();
                        bestHitPoint = new Vec3(px, py, pz);
                        bestHitT = i / (double) (sim.nodeCount() - 1) + t / (sim.nodeCount() - 1);
                    }
                }
            }
        }
        if (bestId == null)
            return null;
        return new ConnectionHighlight(bestId, PERCH_BOOST_HIGHLIGHT_COLOR, bestHitPoint, bestHitT);
    }

    private static boolean isSeedItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS)
                || stack.is(net.minecraft.world.item.Items.MELON_SEEDS)
                || stack.is(net.minecraft.world.item.Items.PUMPKIN_SEEDS)
                || stack.is(net.minecraft.world.item.Items.BEETROOT_SEEDS)
                || stack.is(net.minecraft.world.item.Items.TORCHFLOWER_SEEDS)
                || stack.is(net.minecraft.world.item.Items.PITCHER_POD);
    }

    private static ConnectionHighlight pickPresetBinderHighlight(Minecraft minecraft, List<RenderEntry> entries,
            float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null)
            return null;
        if (!SuperLeadItems.isBoundPresetBinder(player.getMainHandItem())
                && !SuperLeadItems.isBoundPresetBinder(player.getOffhandItem())) {
            return null;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        double dirX = forward.x();
        double dirY = forward.y();
        double dirZ = forward.z();
        double invDir = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= invDir;
        dirY *= invDir;
        dirZ *= invDir;

        double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
        net.minecraft.world.phys.HitResult hitResult = minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            maxDistance = Math.min(maxDistance, hitDist + PICK_RADIUS);
        } else if (player != null) {
            maxDistance = Math.min(maxDistance, player.blockInteractionRange() + PICK_REACH_SLACK);
        }
        double best = PICK_RADIUS * PICK_RADIUS;
        UUID bestId = null;
        Vec3 bestHitPoint = null;
        double bestHitT = 0.5D;

        for (RenderEntry entry : entries) {
            RopeSimulation sim = entry.sim();
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D)
                continue;
            for (int i = 0; i < sim.nodeCount() - 1; i++) {
                double ax = sim.renderX(i);
                double ay = sim.renderY(i);
                double az = sim.renderZ(i);
                double bx = sim.renderX(i + 1);
                double by = sim.renderY(i + 1);
                double bz = sim.renderZ(i + 1);
                for (int sample = 0; sample <= 4; sample++) {
                    double t = sample / 4.0D;
                    double px = ax + (bx - ax) * t;
                    double py = ay + (by - ay) * t;
                    double pz = az + (bz - az) * t;
                    double distance = RopePickMath.distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            dirX, dirY, dirZ, maxDistance);
                    if (distance < best) {
                        best = distance;
                        bestId = entry.connection().id();
                        bestHitPoint = new Vec3(px, py, pz);
                        double l0 = sim.renderLength(i);
                        double l1 = sim.renderLength(i + 1);
                        double arc = l0 + (l1 - l0) * t;
                        bestHitT = clamp01(arc / total);
                    }
                }
            }
        }
        return bestId == null ? null
                : new ConnectionHighlight(bestId, PRESET_BINDER_HIGHLIGHT_COLOR, bestHitPoint, bestHitT);
    }

    private record RenderEntry(LeadConnection connection, RopeSimulation sim, Vec3 a, Vec3 b,
            double lodDistSqr, AABB bounds, AABB physicsBounds) {
    }

    private static double ropeDistanceSqr(Vec3 a, Vec3 b, Vec3 camera) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double abz = b.z - a.z;
        double lenSqr = abx * abx + aby * aby + abz * abz;
        double t = 0.0D;
        if (lenSqr > 1.0e-7D) {
            t = ((camera.x - a.x) * abx + (camera.y - a.y) * aby + (camera.z - a.z) * abz) / lenSqr;
            t = Math.max(0.0D, Math.min(1.0D, t));
        }
        double cx = a.x + abx * t - camera.x;
        double cy = a.y + aby * t - camera.y;
        double cz = a.z + abz * t - camera.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private static AABB physicsBounds(Vec3 a, Vec3 b, RopeSimulation sim) {
        AABB endpointBounds = new AABB(
                Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
        return endpointBounds.minmax(sim.currentBounds());
    }

    private static NeighborMapResult buildNeighborMap(
            List<RenderEntry> dynamicEntries, List<RenderEntry> staticEntries, long tick) {
        List<RenderEntry> entries = new ArrayList<>(dynamicEntries.size() + staticEntries.size());
        entries.addAll(dynamicEntries);
        entries.addAll(staticEntries);
        RopeNeighborBuckets buckets = buildNeighborBuckets(entries);
        Map<RopeSimulation, List<RopeSimulation>> out = new HashMap<>();
        Set<UUID> staticContacts = new HashSet<>();
        NeighborBuildBudget budget = new NeighborBuildBudget(
                MAX_NEIGHBOR_CANDIDATES_PER_FRAME, MAX_NEIGHBOR_NARROW_PHASE_PER_FRAME);
        for (int i = 0; i < dynamicEntries.size(); i++) {
            if (budget.exhausted())
                break;
            RenderEntry entry = entries.get(i);
            if (!participatesInPhysics(entry))
                continue;
                collectNeighborPairs(entries, dynamicEntries.size(), buckets, i, entry, out, staticContacts, budget,
                    tick);
        }
        return new NeighborMapResult(
            out.isEmpty() ? Map.of() : out,
            staticContacts.isEmpty() ? Set.of() : Set.copyOf(staticContacts),
            budget.exhausted(), budget.candidates(), budget.narrowPhase());
    }

    private static RopeNeighborBuckets buildNeighborBuckets(List<RenderEntry> entries) {
        RopeNeighborBuckets buckets = new RopeNeighborBuckets(NEIGHBOR_GRID_SIZE);
        for (int i = 0; i < entries.size(); i++) {
            RenderEntry entry = entries.get(i);
            if (participatesInPhysics(entry)) {
                buckets.add(i, entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN));
            }
        }
        return buckets;
    }

    private static boolean participatesInPhysics(RenderEntry entry) {
        return entry.sim().physicsEnabled() && entry.lodDistSqr() <= physicsLodDistanceSqr();
    }

    private static double maxRenderDistanceSqr() {
        double configured = Math.max(0.0D, ClientTuning.RENDER_MAX_DISTANCE.get());
        double clientView = clientRenderDistanceBlocks();
        double distance = clientView > 0.0D ? Math.min(configured, clientView) : configured;
        return distance * distance;
    }

    private static double clientRenderDistanceBlocks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 0.0D;
        }
        return Math.max(0, minecraft.options.renderDistance().get()) * BLOCKS_PER_CHUNK;
    }

    private static double physicsLodDistance() {
        return Math.max(0.0D,
                Math.min(ClientTuning.LOD_PHYSICS_DISTANCE.get(), ClientTuning.RENDER_MAX_DISTANCE.get()));
    }

    private static double physicsLodDistanceSqr() {
        double distance = physicsLodDistance();
        return distance * distance;
    }

    private static void collectNeighborPairs(
            List<RenderEntry> entries, int dynamicCount, RopeNeighborBuckets buckets, int index, RenderEntry entry,
            Map<RopeSimulation, List<RopeSimulation>> neighborsBySim, Set<UUID> staticContacts,
            NeighborBuildBudget budget, long tick) {
        AABB query = entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN);
        HashSet<Integer> seen = new HashSet<>();
        buckets.forEachCandidateWhile(query, candidate -> collectNeighborPairCandidate(
            entries, dynamicCount, index, entry, query, seen, neighborsBySim, staticContacts, budget, candidate,
            tick));
    }

    private static boolean collectNeighborPairCandidate(
            List<RenderEntry> entries,
            int dynamicCount,
            int index,
            RenderEntry entry,
            AABB query,
            HashSet<Integer> seen,
            Map<RopeSimulation, List<RopeSimulation>> neighborsBySim,
            Set<UUID> staticContacts,
            NeighborBuildBudget budget,
            int candidate,
            long tick) {
        if (candidate <= index || !seen.add(candidate))
            return !budget.exhausted();
        if (!budget.tryCandidate())
            return false;
        RenderEntry other = entries.get(candidate);
        if (!participatesInPhysics(other) || !query.intersects(other.physicsBounds())) {
            return true;
        }
        List<RopeSimulation> ownNeighbors = neighborsBySim.computeIfAbsent(entry.sim(), ignored -> new ArrayList<>());
        boolean otherIsStatic = candidate >= dynamicCount;
        List<RopeSimulation> otherNeighbors = otherIsStatic
            ? null
            : neighborsBySim.computeIfAbsent(other.sim(), ignored -> new ArrayList<>());
        if (ownNeighbors.size() >= MAX_NEIGHBORS_PER_ROPE
            || (otherNeighbors != null && otherNeighbors.size() >= MAX_NEIGHBORS_PER_ROPE)) {
            return true;
        }
        if (!budget.tryNarrowPhase())
            return false;
        if (entry.sim().mightContact(other.sim(), NEIGHBOR_CONTACT_DISTANCE)) {
            ownNeighbors.add(other.sim());
            recordReverseNeighborOrStaticWake(
                    otherIsStatic, shouldWakeStaticFromContact(entry.sim(), tick),
                    other.connection().id(), staticContacts, otherNeighbors, entry.sim());
        } else {
            if (ownNeighbors.isEmpty()) {
                neighborsBySim.remove(entry.sim());
            }
            if (otherNeighbors != null && otherNeighbors.isEmpty()) {
                neighborsBySim.remove(other.sim());
            }
        }
        return true;
    }

    static void recordReverseNeighborOrStaticWake(
            boolean otherIsStatic,
            boolean wakeStatic,
            UUID staticConnectionId,
            Set<UUID> staticContacts,
            List<RopeSimulation> otherNeighbors,
            RopeSimulation dynamicSim) {
        if (otherIsStatic) {
            if (wakeStatic) {
                staticContacts.add(staticConnectionId);
            }
            return;
        }
        if (otherNeighbors == null) {
            throw new IllegalArgumentException("dynamic neighbor list must not be null");
        }
        otherNeighbors.add(dynamicSim);
    }

    static boolean shouldWakeStaticFromContact(RopeSimulation dynamicSim, long tick) {
        if (dynamicSim == null)
            return false;
        if (dynamicSim.maxNodeMotionSqr() >= 4.0e-5D || dynamicSim.hasExternalContact(tick))
            return true;
        long lastWind = dynamicSim.lastWindActiveTick();
        return lastWind != Long.MIN_VALUE && tick - lastWind <= 1L;
    }

    private record NeighborMapResult(
            Map<RopeSimulation, List<RopeSimulation>> neighbors,
            Set<UUID> staticContacts,
            boolean truncated,
            int candidates,
            int narrowPhase) {
    }

    private static final class NeighborBuildBudget {
        private final int maxCandidates;
        private final int maxNarrowPhase;
        private int candidates;
        private int narrowPhase;

        private NeighborBuildBudget(int maxCandidates, int maxNarrowPhase) {
            this.maxCandidates = Math.max(0, maxCandidates);
            this.maxNarrowPhase = Math.max(0, maxNarrowPhase);
        }

        boolean tryCandidate() {
            if (candidates >= maxCandidates)
                return false;
            candidates++;
            return true;
        }

        boolean tryNarrowPhase() {
            if (narrowPhase >= maxNarrowPhase)
                return false;
            narrowPhase++;
            return true;
        }

        boolean exhausted() {
            return candidates >= maxCandidates || narrowPhase >= maxNarrowPhase;
        }

        int candidates() {
            return candidates;
        }

        int narrowPhase() {
            return narrowPhase;
        }
    }

    /**
     * Snapshot bounding boxes of entities currently overlapping the rope's swept
     * volume. The
     * rope endpoints are typically attached to entities (player / leashed mob);
     * those are
     * filtered out so the rope doesn't shove its own anchors.
     */
    private static List<RopeEntityContact> collectEntityContacts(ClientLevel level, RopeSimulation sim, Vec3 a,
            Vec3 b) {
        AABB ropeBounds = sim.currentBounds().inflate(ENTITY_QUERY_MARGIN);
        List<Entity> raw = level.getEntities((Entity) null, ropeBounds, e -> !e.isSpectator() && e.isPickable());
        if (raw.isEmpty())
            return List.of();
        List<RopeEntityContact> out = new ArrayList<>(raw.size());
        for (Entity entity : raw) {
            if (ZiplineClientState.isZiplining(entity.getId())) {
                continue;
            }
            if (entity instanceof net.minecraft.world.entity.animal.parrot.Parrot) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            // Skip anchors: any entity whose AABB contains a rope endpoint would otherwise
            // fight
            // the pin and thrash the segments next to that endpoint.
            if (containsPoint(box, a) || containsPoint(box, b))
                continue;
            out.add(new RopeEntityContact(box, entity.getDeltaMovement(), entity instanceof Player));
        }
        return out;
    }

    private static boolean containsPoint(AABB box, Vec3 p) {
        return p.x >= box.minX && p.x <= box.maxX
                && p.y >= box.minY && p.y <= box.maxY
                && p.z >= box.minZ && p.z <= box.maxZ;
    }

    private record ConnectionHighlight(UUID id, int color, Vec3 hitPoint, double hitT) {
    }

    private record SimLookup(RopeSimulation sim, boolean rebuilt) {
    }

    private record AsyncPhysicsJob(RopeSimulation owner, RopeSimulation worker, Future<?> future, long tick) {
        boolean running() {
            return future != null;
        }

        boolean done() {
            return future == null || future.isDone();
        }

        AsyncPhysicsJob idle() {
            return new AsyncPhysicsJob(owner, worker, null, tick);
        }
    }

    private static final class LodRefinementTracker {
        private final Set<UUID> lowDetailShapes = new HashSet<>();
        private final Set<UUID> refiningShapes = new HashSet<>();

        boolean hasLowDetailShape(UUID id) {
            return lowDetailShapes.contains(id);
        }

        boolean isRefining(UUID id) {
            return refiningShapes.contains(id);
        }

        void markLowDetail(UUID id) {
            lowDetailShapes.add(id);
        }

        void begin(UUID id) {
            refiningShapes.add(id);
        }

        void complete(UUID id) {
            refiningShapes.remove(id);
            lowDetailShapes.remove(id);
        }

        void retainConnections(List<LeadConnection> connections) {
            if (lowDetailShapes.isEmpty() && refiningShapes.isEmpty()) {
                return;
            }
            HashSet<UUID> live = new HashSet<>(connections.size());
            for (LeadConnection connection : connections) {
                live.add(connection.id());
            }
            lowDetailShapes.retainAll(live);
            refiningShapes.retainAll(live);
        }

        void clear() {
            lowDetailShapes.clear();
            refiningShapes.clear();
        }
    }

    private static final class RopePhysicsThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "super-lead-rope-physics-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}
