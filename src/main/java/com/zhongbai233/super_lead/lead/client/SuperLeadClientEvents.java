package com.zhongbai233.super_lead.lead.client;

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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

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
    private static final double PICK_RADIUS = 0.30D;
    /** Max blocks beyond player reach to scan when no vanilla block hit exists. */
    private static final double PICK_REACH_SLACK = 2.0D;
    private static final int ATTACHMENT_HIGHLIGHT_COLOR = LeashBuilder.DEFAULT_HIGHLIGHT;
    private static final int ATTACHMENT_REMOVAL_HIGHLIGHT_COLOR = 0x66FF6040;
    private static final int ZIPLINE_HIGHLIGHT_COLOR = 0x883FCBFF;
    private static final int PRESET_BINDER_HIGHLIGHT_COLOR = 0x88DD55FF;
    /**
     * Beyond this nearest-rope-span distance (blocks) the rope is dropped entirely:
     * no physics, no render.
     */
    private static final double MAX_RENDER_DISTANCE = 96.0D;
    private static final double MAX_RENDER_DISTANCE_SQR = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    /**
     * Beyond this nearest-rope-span distance (blocks) physics is frozen but the
     * rope still renders from
     * its last known node positions. Cheap because step() is skipped entirely.
     */
    private static final double PHYSICS_LOD_DISTANCE = 48.0D;
    private static final double PHYSICS_LOD_DISTANCE_SQR = PHYSICS_LOD_DISTANCE * PHYSICS_LOD_DISTANCE;
    private static final double FRUSTUM_BOUNDS_MARGIN = 1.0D;
    private static final double NEIGHBOR_GRID_SIZE = 4.0D;
    private static final double NEIGHBOR_BOUNDS_MARGIN = 0.08D;
    private static final double NEIGHBOR_CONTACT_DISTANCE = 0.14D;
    private static final double LOCAL_ROPE_JUMP_SPEED = 0.42D;
    private static final double LOCAL_CONTACT_SIDE_HARD_DEPTH_FRACTION = 0.65D;
    private static final double LOCAL_CONTACT_EXIT_INPUT_DOT = 0.05D;
    /**
     * Inflated rope-bound margin used to query nearby entities for the
     * entity-pushes-rope pass.
     */
    private static final double ENTITY_QUERY_MARGIN = 1.0D;
    private static final Map<UUID, RopeSimulation> SIMS = new HashMap<>();
    private static RopeSimulation previewSim;
    private static LeadAnchor previewAnchor;
    private static long lastRepelTick = Long.MIN_VALUE;
    // Connection IDs whose sims were physics-stepped (not updateVisualLeash) last
    // frame.
    // updateVisualLeash resets quietTicks=0 every frame, so LOD-zone sims (48-96
    // blocks)
    // can never satisfy isQuiescent(). Filtering them out here makes tickMaintain
    // treat
    // them as null-sim anchor-bake candidates, so chunk mesh stays populated for
    // far
    // ropes.
    private static volatile Set<UUID> physicsActiveSimIds = Set.of();

    static RopeSimulation simulation(UUID id) {
        return SIMS.get(id);
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
        StaticRopeChunkRegistry staticRopes = StaticRopeChunkRegistry.get();
        Set<UUID> active = new HashSet<>(Math.max(16, connections.size() * 2));
        Map<UUID, Double> lodDistanceByConnection = new HashMap<>(connections.size());
        List<RenderEntry> simEntries = new ArrayList<>(connections.size());
        List<RenderEntry> renderEntries = new ArrayList<>(connections.size());
        Set<UUID> parrotWeightedRopes = new HashSet<>();
        Map<UUID, Long> physicsNanosByConnection = new HashMap<>(connections.size());
        Map<UUID, String> physicsStateByConnection = new HashMap<>(connections.size());
        // First pass: keep sims for ropes within render distance even when they are
        // outside the
        // camera frustum. Frustum culling must only skip draw submission; if it also
        // drops or
        // freezes the sim, turning around recreates a straight rope for one frame
        // before gravity
        // pulls it down again.
        for (LeadConnection connection : connections) {
            addConnectionEntry(level, minecraft, staticRopes, connections, active, lodDistanceByConnection, simEntries,
                    renderEntries, connection, cameraPos, frustum, partialTick, tick);
        }
        // Second pass: drive each sim with its neighbours so rope-rope constraints
        // participate
        // in the unified solver iteration (no more separate repel + settle phase).
        // Ropes whose
        // nearest rope-span point is past PHYSICS_LOD_DISTANCE skip stepping altogether
        // and just keep rendering
        // their last positions — still visible, cheap as a static catenary.
        if (tick != lastRepelTick) {
            lastRepelTick = tick;
            Map<RopeSimulation, List<RopeSimulation>> neighborsBySim = buildNeighborMap(simEntries);
            for (RenderEntry entry : simEntries) {
                stepConnectionEntry(level, minecraft.player, neighborsBySim, parrotWeightedRopes,
                        physicsNanosByConnection, physicsStateByConnection, entry, tick);
            }
        }
        // Update physics-active IDs for the next frame's tickMaintain /
        // onConnectionsReplaced lookup.
        // Must run after the stepping loop so LOD transitions within this frame are
        // captured.
        updatePhysicsActiveSimIds(simEntries);
        // Maintain static chunk meshes after the current physics tick so a newly
        // disturbed
        // rope is evicted before this frame renders. Far LOD ropes still pass as
        // null-sim via
        // physicsActiveSimIds and use the anchor-bake fallback instead of being stuck
        // forever
        // with updateVisualLeash resetting quietTicks.
        staticRopes.tickMaintain(level,
                id -> physicsActiveSimIds.contains(id) ? SIMS.get(id) : null);
        for (UUID id : parrotWeightedRopes) {
            staticRopes.invalidateConnection(level, id);
        }
        List<RopeAttachmentRenderer.BakedAttachment> bakedStaticAttachments = staticRopes
                .bakedAttachmentsForRender(tick);
        // LOD: drop baked attachments for ropes past the physics distance so far
        // ropes don't waste GPU on small decoration items nobody can see.
        bakedStaticAttachments = filterBakedStaticAttachments(
                bakedStaticAttachments, lodDistanceByConnection, null);
        RopeDynamicLights.update(level, cameraPos, connections,
                id -> staticRopes.isClaimed(id) && !staticRopes.shouldDynamicLinger(id, tick)
                        ? null
                        : SIMS.get(id),
                bakedStaticAttachments,
                partialTick);

        ConnectionHighlight highlight = pickHighlightedConnection(minecraft, renderEntries, partialTick, cameraPos);
        UUID highlightedConnectionId = highlight == null ? null : highlight.id();
        bakedStaticAttachments = filterBakedStaticAttachments(
                bakedStaticAttachments, lodDistanceByConnection, highlightedConnectionId);

        List<RopeJob> ropeJobs = new ArrayList<>(renderEntries.size());
        for (RenderEntry entry : renderEntries) {
            submitRenderEntry(event, level, staticRopes, physicsNanosByConnection, physicsStateByConnection,
                    ropeJobs, entry, cameraPos, partialTick, tick, highlight, highlightedConnectionId);
        }
        RopeAttachmentRenderer.submitBakedAll(event.getSubmitNodeCollector(), cameraPos, level, bakedStaticAttachments);
        LeashBuilder.flush(event.getSubmitNodeCollector(), cameraPos, partialTick, ropeJobs);
        ZiplineClientState.submitVisuals(event.getSubmitNodeCollector(), cameraPos, level, partialTick,
                id -> SIMS.get(id));
        publishDebugStats(connections, simEntries, renderEntries, staticRopes, ropeJobs);
        RopeDebugLabels.publishFrame();
        SIMS.keySet().retainAll(active);
        ItemFlowAnimator.retainAll(active);

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

    private static void clearClientState() {
        SIMS.clear();
        previewSim = null;
        previewAnchor = null;
        ClientRopeInteractions.clearHovered();
        ItemFlowAnimator.clearAll();
        RopeDynamicLights.clear();
        RopeDebugLabels.clear();
        RopeDebugStats.clear();
        ZiplineClientState.clear();
    }

    private static void addConnectionEntry(ClientLevel level, Minecraft minecraft,
            StaticRopeChunkRegistry staticRopes, List<LeadConnection> connections, Set<UUID> active,
            Map<UUID, Double> lodDistanceByConnection, List<RenderEntry> simEntries, List<RenderEntry> renderEntries,
            LeadConnection connection, Vec3 cameraPos, Frustum frustum, float partialTick, long tick) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double lodDistSqr = ropeDistanceSqr(a, b, cameraPos);
        lodDistanceByConnection.put(connection.id(), lodDistSqr);
        if (skipConnectionEntry(staticRopes, connection, lodDistSqr, tick)) {
            return;
        }
        active.add(connection.id());

        SimLookup lookup = getOrCreateSimulation(connection, a, b, lodDistSqr);
        AABB physicsBounds = physicsBounds(a, b, lookup.sim());
        AABB bounds = physicsBounds.inflate(FRUSTUM_BOUNDS_MARGIN);
        RenderEntry entry = new RenderEntry(connection, lookup.sim(), a, b, lodDistSqr, bounds, physicsBounds);
        simEntries.add(entry);
        if (lookup.rebuilt()) {
            lookup.sim().beginSegmentVisibility(0);
        } else if (!RopeVisibility.shouldRender(level, minecraft.player, frustum, cameraPos, bounds, lookup.sim(),
                partialTick)) {
            return;
        }
        renderEntries.add(entry);
    }

    private static boolean skipConnectionEntry(StaticRopeChunkRegistry staticRopes, LeadConnection connection,
            double lodDistSqr, long tick) {
        return lodDistSqr > MAX_RENDER_DISTANCE_SQR
                || (lodDistSqr > PHYSICS_LOD_DISTANCE_SQR
                        && staticRopes.isClaimed(connection.id())
                        && !staticRopes.shouldDynamicLinger(connection.id(), tick));
    }

    private static SimLookup getOrCreateSimulation(LeadConnection connection, Vec3 a, Vec3 b, double lodDistSqr) {
        RopeTuning tuning = RopeTuning.forConnection(connection);
        RopeSimulation sim = SIMS.get(connection.id());
        boolean rebuilt = false;
        if (sim == null || !sim.matchesLength(a, b, tuning)) {
            sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), tuning);
            SIMS.put(connection.id(), sim);
            rebuilt = true;
        } else {
            sim.setTuning(tuning);
        }
        sim.setUseCollisionProxy(connectionUsesCollisionProxy(connection));
        sim.setWindPhysicsEnabled(windPhysicsEnabledFor(tuning, lodDistSqr));
        return new SimLookup(sim, rebuilt);
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
            Map<UUID, Long> physicsNanosByConnection, Map<UUID, String> physicsStateByConnection, RenderEntry entry,
            long tick) {
        applyServerState(entry.sim(), entry.connection().id(), tick);
        entry.sim().setWindPhysicsEnabled(windPhysicsEnabledFor(entry.sim().tuning(), entry.lodDistSqr()));
        if (!entry.sim().physicsEnabled() || entry.lodDistSqr() > PHYSICS_LOD_DISTANCE_SQR) {
            updateInactivePhysicsEntry(player, physicsNanosByConnection, physicsStateByConnection, entry, tick);
            return;
        }

        List<RopeForceField> forceFields = RopePerchClientForces.forConnection(level, entry.connection(),
                entry.sim());
        if (!forceFields.isEmpty()) {
            parrotWeightedRopes.add(entry.connection().id());
        }
        List<RopeEntityContact> entityContacts = collectEntityContacts(level, entry.sim(), entry.a(),
                entry.b());
        long stepStart = RopeDebugLabels.enabled() ? System.nanoTime() : 0L;
        boolean stepped = entry.sim().step(level, entry.a(), entry.b(), tick,
                neighborsBySim.getOrDefault(entry.sim(), List.of()), forceFields, entityContacts);
        long stepNanos = RopeDebugLabels.enabled() ? System.nanoTime() - stepStart : 0L;
        recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, entry.connection().id(),
                stepNanos, stepped ? "20tps" : "idle");
        maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
    }

    private static void updateInactivePhysicsEntry(Player player, Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection, RenderEntry entry, long tick) {
        // Physics off for this rope (global/local/zone tuning) or far LOD: keep a cheap
        // sagged visual state.
        entry.sim().updateVisualLeash(entry.a(), entry.b(), tick, 0.45F);
        if (!entry.sim().physicsEnabled() && tripEnabled(entry.connection())) {
            maybeReportPlayerContact(player, entry.connection(), entry.sim(), entry.a(), entry.b(), tick);
        }
        recordPhysicsState(physicsNanosByConnection, physicsStateByConnection, entry.connection().id(),
                0L, entry.sim().physicsEnabled() ? "lod" : "off");
    }

    private static void updatePhysicsActiveSimIds(List<RenderEntry> simEntries) {
        Set<UUID> next = new HashSet<>(simEntries.size());
        for (RenderEntry entry : simEntries) {
            if (entry.sim().physicsEnabled() && entry.lodDistSqr() <= PHYSICS_LOD_DISTANCE_SQR) {
                next.add(entry.connection().id());
            }
        }
        physicsActiveSimIds = Set.copyOf(next);
    }

    private static void submitRenderEntry(SubmitCustomGeometryEvent event, ClientLevel level,
            StaticRopeChunkRegistry staticRopes, Map<UUID, Long> physicsNanosByConnection,
            Map<UUID, String> physicsStateByConnection, List<RopeJob> ropeJobs, RenderEntry entry, Vec3 cameraPos,
            float partialTick, long tick, ConnectionHighlight highlight, UUID highlightedConnectionId) {
        UUID connectionId = entry.connection().id();
        boolean chunkMeshActive = staticRopes.isMeshAccepted(connectionId)
                && !staticRopes.shouldDynamicLinger(connectionId, tick);
        if (chunkMeshActive
                && (highlightedConnectionId == null || !connectionId.equals(highlightedConnectionId))) {
            submitStaticRenderEntry(level, staticRopes, physicsNanosByConnection, physicsStateByConnection, entry,
                    partialTick, tick, connectionId);
            return;
        }
        submitDynamicRenderEntry(event, level, physicsNanosByConnection, physicsStateByConnection, ropeJobs, entry,
                cameraPos, partialTick, tick, highlight, connectionId, chunkMeshActive);
    }

    private static void submitStaticRenderEntry(ClientLevel level, StaticRopeChunkRegistry staticRopes,
            Map<UUID, Long> physicsNanosByConnection, Map<UUID, String> physicsStateByConnection, RenderEntry entry,
            float partialTick, long tick, UUID connectionId) {
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
        float[] pulses = ClientRopeParticles.computeItemPulses(entry.connection(), tick, partialTick);
        int extractEnd = extractEnd(entry.connection());
        recordRopeLabel(entry, partialTick, false, physicsNanosByConnection, physicsStateByConnection);
        ropeJobs.add(LeashBuilder.collect(sim, blockA, blockB, skyA, skyB, highlightColor,
                entry.connection().kind(), entry.connection().powered(), entry.connection().tier(),
                pulses, extractEnd, chunkMeshActive));
        ClientRopeParticles.spawnRedstone(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
        ClientRopeParticles.spawnEnergy(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
        if (!entry.connection().attachments().isEmpty()
                && entry.lodDistSqr() <= PHYSICS_LOD_DISTANCE_SQR) {
            RopeAttachmentRenderer.submitAll(event.getSubmitNodeCollector(), cameraPos, level, sim,
                    entry.connection().attachments(),
                    entry.connection().kind() == LeadKind.REDSTONE && entry.connection().powered(),
                    partialTick);
        }
    }

    private static int extractEnd(LeadConnection connection) {
        return connection.kind() == LeadKind.ITEM
                || connection.kind() == LeadKind.FLUID
                || connection.kind() == LeadKind.PRESSURIZED
                        ? connection.extractAnchor()
                        : 0;
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
        RopeDebugStats.dynamicJobs = ropeJobs.size();
        RopeDebugStats.simCount = SIMS.size();
        RopeDebugStats.chunkMeshSections = staticRopes.sectionCount();
        RopeDebugStats.chunkMeshSnapshots = staticRopes.sectionSnapshotsTotal();
        RopeDebugStats.attachmentsTotal = connections.stream().mapToInt(c -> c.attachments().size()).sum();
        RopeDebugStats.chunkMeshEligible = staticRopes.eligibleCount();
        RopeDebugStats.chunkMeshWaitingQuiet = staticRopes.waitingQuietCount();
        RopeDebugStats.chunkMeshReadyFromSim = staticRopes.readyFromSimCount();
        RopeDebugStats.chunkMeshReadyAnchorBake = staticRopes.readyAnchorBakeCount();
        RopeDebugStats.chunkMeshClaimedFromSim = staticRopes.claimedFromSimCount();
        RopeDebugStats.chunkMeshClaimedAnchorBake = staticRopes.claimedAnchorBakeCount();
        RopeDebugStats.chunkMeshMissingAnchors = staticRopes.missingAnchorBakeCount();
        RopeDebugStats.dynamicNodesTotal = ropeJobs.stream().mapToInt(job -> job.sim.nodeCount()).sum();
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
        double distance = Math.min(tuning.windPhysicsDistance(), PHYSICS_LOD_DISTANCE);
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
        RopeDebugLabels.record(id, point, sim.nodeCount(),
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
            boolean drop = lodDistSqr != null && lodDistSqr > PHYSICS_LOD_DISTANCE_SQR;
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
        // Rope upgrades are gated behind sneak (except shears, which always work).
        // Mirror that
        // here so the player only sees the highlight when the action would actually
        // fire.
        LeadConnectionAction action = LeadConnectionAction.fromHeldItems(player).orElse(null);
        if (action == null) {
            // No upgrade action — check for preset binder highlight
            if (player.isShiftKeyDown()) {
                return pickPresetBinderHighlight(minecraft, entries, partialTick, cameraPos);
            }
            return null;
        }
        boolean isShears = player.getMainHandItem().is(net.minecraft.world.item.Items.SHEARS)
                || player.getOffhandItem().is(net.minecraft.world.item.Items.SHEARS);
        if (!isShears && !player.isShiftKeyDown()) {
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

    private static Map<RopeSimulation, List<RopeSimulation>> buildNeighborMap(List<RenderEntry> entries) {
        RopeNeighborBuckets buckets = buildNeighborBuckets(entries);
        Map<RopeSimulation, List<RopeSimulation>> out = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            RenderEntry entry = entries.get(i);
            if (!participatesInPhysics(entry))
                continue;
            List<RopeSimulation> neighbors = collectNeighborsForEntry(entries, buckets, i, entry);
            if (!neighbors.isEmpty()) {
                out.put(entry.sim(), neighbors);
            }
        }
        return out;
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
        return entry.sim().physicsEnabled() && entry.lodDistSqr() <= PHYSICS_LOD_DISTANCE_SQR;
    }

    private static List<RopeSimulation> collectNeighborsForEntry(
            List<RenderEntry> entries, RopeNeighborBuckets buckets, int index, RenderEntry entry) {
        AABB query = entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN);
        HashSet<RopeSimulation> neighbors = new HashSet<>();
        HashSet<Integer> seen = new HashSet<>();
        buckets.forEachCandidate(query, candidate -> collectNeighborCandidate(
                entries, index, entry, query, seen, neighbors, candidate));
        return neighbors.isEmpty() ? List.of() : new ArrayList<>(neighbors);
    }

    private static void collectNeighborCandidate(
            List<RenderEntry> entries,
            int index,
            RenderEntry entry,
            AABB query,
            HashSet<Integer> seen,
            HashSet<RopeSimulation> neighbors,
            int candidate) {
        if (candidate == index || !seen.add(candidate))
            return;
        RenderEntry other = entries.get(candidate);
        if (query.intersects(other.physicsBounds())
                && entry.sim().mightContact(other.sim(), NEIGHBOR_CONTACT_DISTANCE)) {
            neighbors.add(other.sim());
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

}
