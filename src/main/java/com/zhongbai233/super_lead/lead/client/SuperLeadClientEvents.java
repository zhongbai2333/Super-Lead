package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.AddRopeAttachment;
import com.zhongbai233.super_lead.lead.ClientRopeContactReport;
import com.zhongbai233.super_lead.lead.ItemPulse;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadConnectionAction;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.RemoveRopeAttachment;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.ToggleRopeAttachmentForm;
import com.zhongbai233.super_lead.lead.UseConnectionAction;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import com.zhongbai233.super_lead.lead.client.debug.RopeDebugStats;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeJob;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.render.RopeContactsClient;
import com.zhongbai233.super_lead.lead.client.render.RopeVisibility;
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
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class SuperLeadClientEvents {
    private static final double PICK_RADIUS = 0.30D;
    /** Looser radius for picking a rope to attach a decoration to. The aim usually lands a bit
     *  off the rope (the strand is thin); a larger radius means the player can put a lantern
     *  on the rope without having to laser-aim at the strand. */
    private static final double ATTACH_PICK_RADIUS = 0.50D;
    /** Beyond this nearest-rope-span distance (blocks) the rope is dropped entirely — no physics, no render. */
    private static final double MAX_RENDER_DISTANCE = 96.0D;
    private static final double MAX_RENDER_DISTANCE_SQR = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    /** Beyond this nearest-rope-span distance (blocks) physics is frozen but the rope still renders from
     *  its last known node positions. Cheap because step() is skipped entirely. */
    private static final double PHYSICS_LOD_DISTANCE = 48.0D;
    private static final double PHYSICS_LOD_DISTANCE_SQR = PHYSICS_LOD_DISTANCE * PHYSICS_LOD_DISTANCE;
    private static final double FRUSTUM_BOUNDS_MARGIN = 1.0D;
    private static final double PARTICLE_DISTANCE = 32.0D;
    private static final double PARTICLE_DISTANCE_SQR = PARTICLE_DISTANCE * PARTICLE_DISTANCE;
    private static final double PARTICLE_FADE_START = 16.0D;
    private static final double NEIGHBOR_GRID_SIZE = 4.0D;
    private static final double NEIGHBOR_BOUNDS_MARGIN = 0.08D;
    private static final double NEIGHBOR_CONTACT_DISTANCE = 0.14D;
    private static final int GRID_KEY_BIAS = 1 << 20;
    /** Inflated rope-bound margin used to query nearby entities for the entity-pushes-rope pass. */
    private static final double ENTITY_QUERY_MARGIN = 1.0D;
    private static final float[] EMPTY_PULSES = new float[0];
    private static final List<RopeForceField> EMPTY_FORCE_FIELDS = List.of();

    private static final Map<UUID, RopeSimulation> SIMS = new HashMap<>();
    private static RopeSimulation previewSim;
    private static LeadAnchor previewAnchor;
    private static long lastRepelTick = Long.MIN_VALUE;
    private static volatile LeadConnection HOVERED_CONNECTION;
    private static volatile Vec3 HOVERED_HIT_POINT;
    private static volatile double HOVERED_HIT_T = 0.5D;
    // Connection IDs whose sims were physics-stepped (not updateVisualLeash) last frame.
    // updateVisualLeash resets quietTicks=0 every frame, so LOD-zone sims (48-96 blocks)
    // can never satisfy isQuiescent(). Filtering them out here makes tickMaintain treat
    // them as null-sim → anchor-bake path, so chunk mesh stays populated for far ropes.
    private static volatile Set<UUID> physicsActiveSimIds = Set.of();

    static {
        StaticRopeChunkRegistry.get().setSimLookup(
                id -> physicsActiveSimIds.contains(id) ? SIMS.get(id) : null);
    }

    public static LeadConnection hoveredConnection() {
        return HOVERED_CONNECTION;
    }

    /**
     * Send a server-bound UseConnectionAction packet for the currently hovered connection.
     * Called on the client when the player right-clicks with an action item AND the client's
     * own ghost-preview pick produced a target the action can act on.
     */
    public static boolean trySendUseConnectionAction(net.minecraft.world.InteractionHand hand,
            LeadConnectionAction action) {
        LeadConnection hovered = HOVERED_CONNECTION;
        Vec3 hitPoint = HOVERED_HIT_POINT;
        double hitT = HOVERED_HIT_T;
        if (hovered == null || !action.canTarget(hovered)) {
            return false;
        }
        if (hitPoint == null || !Double.isFinite(hitT)) {
            return false;
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new UseConnectionAction(
                        hovered.id(),
                        action.ordinal(),
                        hand == net.minecraft.world.InteractionHand.OFF_HAND,
                        hitPoint,
                        hitT));
        return true;
    }

    /** Try sending an AddRopeAttachment packet for the rope under the player's crosshair.
     *  The held item must be a valid attachment item (lantern, sign, …); the caller is expected
     *  to guard that. Returns true when a packet was sent. */
    public static boolean trySendAddRopeAttachment(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        net.minecraft.world.item.ItemStack stack = mc.player.getItemInHand(hand);
        if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) return false;
        AttachPick pick = pickRopeAttachPoint(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) return false;
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new AddRopeAttachment(pick.connectionId, pick.t,
                        hand == net.minecraft.world.InteractionHand.OFF_HAND));
        return true;
    }

    /** Try sending a RemoveRopeAttachment packet for an existing attachment under the crosshair.
     *  Returns true when a packet was sent. */
    public static boolean trySendRemoveRopeAttachment() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) return false;
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new RemoveRopeAttachment(pick.connectionId, pick.attachmentId));
        return true;
    }

    /** Try sending a ToggleRopeAttachmentForm packet for the attachment under the crosshair.
     *  Only fires when the targeted attachment is a BlockItem (item-only stacks have no
     *  alternate shape to toggle to). */
    public static boolean trySendToggleRopeAttachmentForm() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) return false;
        // Find the attachment to verify it's a block item — toggling an item-only stack would
        // produce no visible change.
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) continue;
            for (com.zhongbai233.super_lead.lead.RopeAttachment a : connection.attachments()) {
                if (a.id().equals(pick.attachmentId)) {
                    if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(a.stack())) return false;
                    break;
                }
            }
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new ToggleRopeAttachmentForm(pick.connectionId, pick.attachmentId));
        return true;
    }

    /** Picks the rope nearest to the player's crosshair (capped only by the global lead reach,
     *  NOT by whatever block the player happens to be aiming at — otherwise right-clicking on
     *  the anchor block would only ever attach to the head segment) and reports the arc-length
     *  parameter at the hit. */
    private static AttachPick pickRopeAttachPoint(Minecraft mc, float partialTick) {
        Player player = mc.player;
        if (player == null) return null;
        ClientLevel level = mc.level;
        if (level == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        var fwd = camera.forwardVector();
        double dirX = fwd.x(), dirY = fwd.y(), dirZ = fwd.z();
        double inv = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= inv; dirY *= inv; dirZ *= inv;
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        double bestDistSqr = ATTACH_PICK_RADIUS * ATTACH_PICK_RADIUS;
        UUID bestId = null;
        double bestT = 0.5D;

        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            // Only plain rope and redstone-upgraded rope accept decorations.
            com.zhongbai233.super_lead.lead.LeadKind k = connection.kind();
            if (k != com.zhongbai233.super_lead.lead.LeadKind.NORMAL
                    && k != com.zhongbai233.super_lead.lead.LeadKind.REDSTONE) continue;
            RopeSimulation sim = SIMS.get(connection.id());
            if (sim == null) continue;
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D) continue;
            int nodeCount = sim.nodeCount();
            for (int i = 0; i < nodeCount - 1; i++) {
                double ax = sim.renderX(i), ay = sim.renderY(i), az = sim.renderZ(i);
                double bx = sim.renderX(i + 1), by = sim.renderY(i + 1), bz = sim.renderZ(i + 1);
                for (int sample = 0; sample <= 4; sample++) {
                    double frac = sample / 4.0D;
                    double px = ax + (bx - ax) * frac;
                    double py = ay + (by - ay) * frac;
                    double pz = az + (bz - az) * frac;
                    // Only sagging-into-empty-space portions of the rope are decoratable —
                    // segments resting on a block (e.g. tight rope along a wall) are not.
                    net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(px, py - 0.35D, pz);
                    if (!level.getBlockState(below).isAir()) continue;
                    double d = distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        bestId = connection.id();
                        double l0 = sim.renderLength(i);
                        double l1 = sim.renderLength(i + 1);
                        double arc = l0 + (l1 - l0) * frac;
                        bestT = Math.max(0.02D, Math.min(0.98D, arc / total));
                    }
                }
            }
        }
        return bestId == null ? null : new AttachPick(bestId, bestT);
    }

    /** Picks the existing attachment closest to the player's crosshair. */
    private static AttachmentPick pickAttachment(Minecraft mc, float partialTick) {
        Player player = mc.player;
        if (player == null) return null;
        ClientLevel level = mc.level;
        if (level == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        var fwd = camera.forwardVector();
        double dirX = fwd.x(), dirY = fwd.y(), dirZ = fwd.z();
        double inv = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= inv; dirY *= inv; dirZ *= inv;
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            maxDistance = Math.min(maxDistance, hit.getLocation().distanceTo(cameraPos) + 0.6D);
        }
        double bestDistSqr = 0.6D * 0.6D;
        UUID bestConnId = null;
        UUID bestAttachId = null;

        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            if (connection.attachments().isEmpty()) continue;
            RopeSimulation sim = SIMS.get(connection.id());
            if (sim == null) continue;
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D) continue;
            int nodeCount = sim.nodeCount();
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                double target = attachment.t() * total;
                int seg = 0;
                for (int i = 0; i < nodeCount - 1; i++) {
                    if (target <= sim.renderLength(i + 1)) { seg = i; break; }
                    seg = i;
                }
                double l0 = sim.renderLength(seg);
                double l1 = sim.renderLength(seg + 1);
                double span = l1 - l0;
                double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
                double px = sim.renderX(seg) + (sim.renderX(seg + 1) - sim.renderX(seg)) * frac;
                double py = sim.renderY(seg) + (sim.renderY(seg + 1) - sim.renderY(seg)) * frac;
                double pz = sim.renderZ(seg) + (sim.renderZ(seg + 1) - sim.renderZ(seg)) * frac;
                // The hanging item visually drops below the rope; check both the attachment
                // anchor and the displayed body.
                double d1 = distancePointToRaySqr(px, py, pz,
                        cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                double d2 = distancePointToRaySqr(px, py - 0.42D, pz,
                        cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                double d = Math.min(d1, d2);
                if (d < bestDistSqr) {
                    bestDistSqr = d;
                    bestConnId = connection.id();
                    bestAttachId = attachment.id();
                }
            }
        }
        return bestConnId == null ? null : new AttachmentPick(bestConnId, bestAttachId);
    }

    private record AttachPick(UUID connectionId, double t) {}
    private record AttachmentPick(UUID connectionId, UUID attachmentId) {}

    private static LeadConnection findById(List<RenderEntry> entries, UUID id) {
        for (RenderEntry entry : entries) {
            if (entry.connection().id().equals(id)) {
                return entry.connection();
            }
        }
        return null;
    }

    private SuperLeadClientEvents() {}

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            SIMS.clear();
            previewSim = null;
            previewAnchor = null;
            HOVERED_CONNECTION = null;
            HOVERED_HIT_POINT = null;
            HOVERED_HIT_T = 0.5D;
            ItemFlowAnimator.clearAll();
            RopeDebugStats.clear();
            return;
        }

        SuperLeadNetwork.pruneInvalid(level);
        long tick = level.getGameTime();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        Frustum frustum = camera.getCullFrustum();
        RopeVisibility.beginFrame(cameraPos);

        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        StaticRopeChunkRegistry staticRopes = StaticRopeChunkRegistry.get();
        Set<UUID> active = new HashSet<>(Math.max(16, connections.size() * 2));
        List<RenderEntry> simEntries = new ArrayList<>(connections.size());
        List<RenderEntry> renderEntries = new ArrayList<>(connections.size());
        // First pass: keep sims for ropes within render distance even when they are outside the
        // camera frustum. Frustum culling must only skip draw submission; if it also drops or
        // freezes the sim, turning around recreates a straight rope for one frame before gravity
        // pulls it down again.
        for (LeadConnection connection : connections) {
            Vec3 a = connection.from().attachmentPoint(level);
            Vec3 b = connection.to().attachmentPoint(level);
            double lodDistSqr = ropeDistanceSqr(a, b, cameraPos);
            if (lodDistSqr > MAX_RENDER_DISTANCE_SQR) continue;
            if (lodDistSqr > PHYSICS_LOD_DISTANCE_SQR
                    && staticRopes.isClaimed(connection.id())
                    && !staticRopes.shouldDynamicLinger(connection.id(), tick)) {
                continue;
            }
            active.add(connection.id());
            RopeTuning tuning = RopeTuning.forConnection(connection);
            RopeSimulation sim = SIMS.get(connection.id());
            boolean rebuiltSim = false;
            if (sim == null || !sim.matchesLength(a, b, tuning)) {
                sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), true, tuning);
                sim.resetCatenary(a, b, initialSagFactor(tuning));
                SIMS.put(connection.id(), sim);
                rebuiltSim = true;
            } else {
                sim.setTuning(tuning);
            }
            AABB physicsBounds = physicsBounds(a, b, sim);
            AABB bounds = physicsBounds.inflate(FRUSTUM_BOUNDS_MARGIN);
            RenderEntry entry = new RenderEntry(connection, sim, a, b, lodDistSqr, bounds, physicsBounds);
            simEntries.add(entry);
            if (rebuiltSim) {
                sim.beginSegmentVisibility(0);
            } else if (!RopeVisibility.shouldRender(level, minecraft.player, frustum, cameraPos, bounds, sim, partialTick)) {
                continue;
            }
            renderEntries.add(entry);
        }
        // Second pass: drive each sim with its neighbours so rope-rope constraints participate
        // in the unified solver iteration (no more separate repel + settle phase). Ropes whose
        // nearest rope-span point is past PHYSICS_LOD_DISTANCE skip stepping altogether and just keep rendering
        // their last positions — still visible, cheap as a static catenary.
        if (tick != lastRepelTick) {
            lastRepelTick = tick;
            Map<RopeSimulation, List<RopeSimulation>> neighborsBySim = buildNeighborMap(simEntries);
            for (RenderEntry entry : simEntries) {
                applyServerState(entry.sim(), entry.connection().id(), tick);
                if (!entry.sim().physicsEnabled() || entry.lodDistSqr() > PHYSICS_LOD_DISTANCE_SQR) {
                    // Physics off for this rope (global/local/zone tuning) or far LOD: keep a cheap sagged visual state.
                    entry.sim().updateVisualLeash(entry.a(), entry.b(), tick, 0.45F);
                    continue;
                }
                List<AABB> entityBoxes = collectEntityBoxes(level, entry.sim(), entry.a(), entry.b());
                entry.sim().step(level, entry.a(), entry.b(), tick,
                        neighborsBySim.getOrDefault(entry.sim(), List.of()), EMPTY_FORCE_FIELDS, entityBoxes);
                maybeReportPlayerContact(minecraft.player, entry.connection(), entry.sim(), entry.a(), entry.b());
            }
        }
        // Update physics-active IDs for the next frame's tickMaintain / onConnectionsReplaced lookup.
        // Must run after the stepping loop so LOD transitions within this frame are captured.
        {
            Set<UUID> next = new HashSet<>(simEntries.size());
            for (RenderEntry e : simEntries) {
                if (e.sim().physicsEnabled() && e.lodDistSqr() <= PHYSICS_LOD_DISTANCE_SQR) {
                    next.add(e.connection().id());
                }
            }
            physicsActiveSimIds = Set.copyOf(next);
        }
        // Maintain static chunk meshes after the current physics tick so a newly disturbed
        // rope is evicted before this frame renders. Far LOD ropes still pass as null-sim via
        // physicsActiveSimIds and use the anchor-bake fallback instead of being stuck forever
        // with updateVisualLeash resetting quietTicks.
        staticRopes.tickMaintain(level,
                id -> physicsActiveSimIds.contains(id) ? SIMS.get(id) : null);

        ConnectionHighlight highlight = pickHighlightedConnection(minecraft, renderEntries, partialTick, cameraPos);

        List<RopeJob> ropeJobs = new ArrayList<>(renderEntries.size());
        for (RenderEntry entry : renderEntries) {
            if (staticRopes.isClaimed(entry.connection().id())
                    && !staticRopes.shouldDynamicLinger(entry.connection().id(), tick)) {
                continue;
            }
            Vec3 a = entry.a();
            Vec3 b = entry.b();
            RopeSimulation sim = entry.sim();

            BlockPos lightA = BlockPos.containing(a);
            BlockPos lightB = BlockPos.containing(b);
            int blockA = level.getBrightness(LightLayer.BLOCK, lightA);
            int blockB = level.getBrightness(LightLayer.BLOCK, lightB);
            int skyA = level.getBrightness(LightLayer.SKY, lightA);
            int skyB = level.getBrightness(LightLayer.SKY, lightB);
            int highlightColor = highlight != null && entry.connection().id().equals(highlight.id())
                    ? highlight.color()
                    : LeashBuilder.NO_HIGHLIGHT;
            float[] pulses = computeItemPulses(entry.connection(), tick, partialTick);
            int extractEnd = (entry.connection().kind() == LeadKind.ITEM || entry.connection().kind() == LeadKind.FLUID)
                    ? entry.connection().extractAnchor()
                    : 0;
            ropeJobs.add(LeashBuilder.collect(sim, blockA, blockB, skyA, skyB, highlightColor,
                    entry.connection().kind(), entry.connection().powered(), entry.connection().tier(),
                    pulses, extractEnd));
            spawnRedstoneParticles(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
            spawnEnergyParticles(level, sim, partialTick, entry.connection(), entry.lodDistSqr());
            if (!entry.connection().attachments().isEmpty()) {
                RopeAttachmentRenderer.submitAll(event.getSubmitNodeCollector(), cameraPos, level, sim,
                        entry.connection().attachments(),
                        entry.connection().kind() == LeadKind.REDSTONE && entry.connection().powered(),
                        partialTick);
            }
        }
        LeashBuilder.flush(event.getSubmitNodeCollector(), cameraPos, partialTick, ropeJobs);
        publishDebugStats(connections, simEntries, renderEntries, staticRopes, ropeJobs);
        SIMS.keySet().retainAll(active);
        ItemFlowAnimator.retainAll(active);

        HOVERED_CONNECTION = highlight == null ? null : findById(renderEntries, highlight.id());
        HOVERED_HIT_POINT = highlight == null ? null : highlight.hitPoint();
        HOVERED_HIT_T = highlight == null ? 0.5D : highlight.hitT();

        // Attachment placement preview: while holding a non-empty item (and NOT sneaking,
        // since sneak is reserved for upgrade / removal / cut), draw a translucent outlined
        // ghost of the held item where a right-click would place it.
        Player previewPlayer = minecraft.player;
        if (previewPlayer != null && !previewPlayer.isShiftKeyDown()) {
            net.minecraft.world.item.ItemStack mainHand = previewPlayer.getMainHandItem();
            net.minecraft.world.item.ItemStack offHand = previewPlayer.getOffhandItem();
            net.minecraft.world.item.ItemStack ghostStack = !mainHand.isEmpty() ? mainHand
                    : (!offHand.isEmpty() ? offHand : net.minecraft.world.item.ItemStack.EMPTY);
            if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(ghostStack)) {
                AttachPick ghostPick = pickRopeAttachPoint(minecraft, partialTick);
                if (ghostPick != null) {
                    RopeSimulation ghostSim = SIMS.get(ghostPick.connectionId);
                    if (ghostSim != null) {
                        RopeAttachmentRenderer.submitGhost(event.getSubmitNodeCollector(),
                                cameraPos, level, ghostSim, ghostStack, ghostPick.t, partialTick);
                    }
                }
            }
        }

        Player player = minecraft.player;
        if (player != null) {
            renderPreview(event, level, cameraPos, partialTick, tick, player);
        } else {
            previewSim = null;
            previewAnchor = null;
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
        RopeDebugStats.chunkMeshIneligible = staticRopes.ineligibleCount();
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

    private static void renderPreview(SubmitCustomGeometryEvent event, ClientLevel level, Vec3 cameraPos, float partialTick, long tick, Player player) {
        LeadAnchor anchor = SuperLeadNetwork.pendingAnchor(player).orElse(null);
        if (anchor == null) {
            previewSim = null;
            previewAnchor = null;
            return;
        }
        Vec3 a = anchor.attachmentPoint(level);
        Vec3 b = player.getRopeHoldPosition(partialTick);
        if (a.distanceTo(b) > SuperLeadNetwork.MAX_LEASH_DISTANCE) {
            return;
        }
        RopeTuning tuning = RopeTuning.forMidpoint(a, b);
        if (previewSim == null || !anchor.equals(previewAnchor) || !previewSim.matchesLength(a, b, tuning)) {
            previewSim = new RopeSimulation(a, b, 0L, false, tuning);
            previewAnchor = anchor;
        } else {
            previewSim.setTuning(tuning);
        }
        previewSim.stepUpTo(level, a, b, tick);

        BlockPos lightA = BlockPos.containing(a);
        BlockPos lightB = BlockPos.containing(b);
        LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(LeadKind.NORMAL);
        int blockA = level.getBrightness(LightLayer.BLOCK, lightA);
        int blockB = level.getBrightness(LightLayer.BLOCK, lightB);
        int skyA = level.getBrightness(LightLayer.SKY, lightA);
        int skyB = level.getBrightness(LightLayer.SKY, lightB);
        LeashBuilder.submit(event.getSubmitNodeCollector(), cameraPos, previewSim, partialTick,
                blockA, blockB, skyA, skyB, false, kind, false);
    }

    private static float[] computeItemPulses(LeadConnection connection, long currentTick, float partialTick) {
        if (connection.kind() != LeadKind.ITEM && connection.kind() != LeadKind.FLUID) {
            return EMPTY_PULSES;
        }
        Iterable<ItemPulse> active = ItemFlowAnimator.activePulses(connection.id(), currentTick, partialTick);
        java.util.ArrayList<Float> list = new java.util.ArrayList<>(4);
        for (ItemPulse p : active) {
            float age = (currentTick - p.startTick()) + partialTick;
            float t = age / Math.max(1, p.durationTicks());
            if (t < 0F || t > 1F) continue;
            float pos = p.reverse() ? 1F - t : t;
            list.add(pos);
        }
        if (list.isEmpty()) return EMPTY_PULSES;
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static void spawnRedstoneParticles(ClientLevel level, RopeSimulation sim, float partialTick, LeadConnection connection, double lodDistSqr) {
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.REDSTONE || !connection.powered()
                || particleScale <= 0.0D || random.nextFloat() > 0.035F * particleScale) {
            return;
        }

        sim.prepareRender(partialTick);
        int segment = random.nextInt(Math.max(1, sim.nodeCount() - 1));
        double t = random.nextDouble();
        double px = sim.renderX(segment) + (sim.renderX(segment + 1) - sim.renderX(segment)) * t;
        double py = sim.renderY(segment) + (sim.renderY(segment + 1) - sim.renderY(segment)) * t;
        double pz = sim.renderZ(segment) + (sim.renderZ(segment + 1) - sim.renderZ(segment)) * t;
        double jitter = 0.035D;
        level.addParticle(
                DustParticleOptions.REDSTONE,
                px + (random.nextDouble() - 0.5D) * jitter,
                py + (random.nextDouble() - 0.5D) * jitter,
                pz + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    private static final DustParticleOptions ENERGY_DUST =
            new DustParticleOptions(0xFFEE55, 1.0F);

    private static void spawnEnergyParticles(ClientLevel level, RopeSimulation sim, float partialTick, LeadConnection connection, double lodDistSqr) {
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.ENERGY || !connection.powered() || particleScale <= 0.0D) {
            return;
        }
        // Higher tier = denser sparks.
        float density = (float) ((0.04F + Math.min(0.18F, connection.tier() * 0.04F)) * particleScale);
        if (random.nextFloat() > density) {
            return;
        }

        sim.prepareRender(partialTick);
        int segment = random.nextInt(Math.max(1, sim.nodeCount() - 1));
        double t = random.nextDouble();
        double px = sim.renderX(segment) + (sim.renderX(segment + 1) - sim.renderX(segment)) * t;
        double py = sim.renderY(segment) + (sim.renderY(segment + 1) - sim.renderY(segment)) * t;
        double pz = sim.renderZ(segment) + (sim.renderZ(segment + 1) - sim.renderZ(segment)) * t;
        double jitter = 0.045D;
        level.addParticle(
                ENERGY_DUST,
                px + (random.nextDouble() - 0.5D) * jitter,
                py + (random.nextDouble() - 0.5D) * jitter,
                pz + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    private static double particleDistanceScale(double lodDistSqr) {
        if (lodDistSqr > PARTICLE_DISTANCE_SQR) return 0.0D;
        double distance = Math.sqrt(lodDistSqr);
        if (distance <= PARTICLE_FADE_START) return 1.0D;
        return Math.max(0.0D, 1.0D - (distance - PARTICLE_FADE_START) / (PARTICLE_DISTANCE - PARTICLE_FADE_START));
    }

    private static void applyServerState(RopeSimulation sim, UUID connectionId, long tick) {
        RopeContactsClient.Contact contact = RopeContactsClient.get(connectionId);
        if (contact == null) {
            sim.clearExternalContact();
        } else {
            sim.setExternalContact(tick, contact.t(), contact.dx(), contact.dy(), contact.dz());
        }

        sim.clearServerNodes();
    }

    private static void maybeReportPlayerContact(Player player, LeadConnection connection,
            RopeSimulation sim, Vec3 a, Vec3 b) {
        if (player == null || player.isSpectator()) return;
        if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE) return;

        if (connection.physicsPreset().isBlank()) return;
        if (!PhysicsZonesClient.hasPreset(connection.physicsPreset())) return;
        Map<String, String> overrides = PhysicsZonesClient.overridesForPreset(connection.physicsPreset());
        if (!resolveBool(overrides, ClientTuning.CONTACT_PUSHBACK)) return;

        double radius = resolveDouble(overrides, ClientTuning.CONTACT_RADIUS);
        if (radius <= 0.0D) return;
        RopeSimulation.ContactSample contact = sim.findPlayerContact(player.getBoundingBox(), radius);
        if (contact == null || contact.depth() <= 1.0e-4D) return;

        double nx = contact.normalX();
        double nz = contact.normalZ();
        double nLen = Math.sqrt(nx * nx + nz * nz);
        if (nLen < 1.0e-5D) return;
        nx /= nLen;
        nz /= nLen;
        Vec3 input = playerMoveIntent(player);

        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new ClientRopeContactReport(
                        connection.id(),
                        (float) clamp01(contact.t()),
                        (float) contact.x(),
                        (float) contact.y(),
                        (float) contact.z(),
                        (float) nx,
                        (float) nz,
                        (float) input.x,
                        (float) input.z,
                        (float) contact.depth()));
    }

    private static Vec3 playerMoveIntent(Player player) {
        if (!(player instanceof LocalPlayer localPlayer) || localPlayer.input == null) {
            return Vec3.ZERO;
        }
        var moveVector = localPlayer.input.getMoveVector();
        double inputX = moveVector.x;
        double inputZ = moveVector.y;
        double inputLen = Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLen < 1.0e-4D) return Vec3.ZERO;

        inputX /= inputLen;
        inputZ /= inputLen;
        double yaw = Math.toRadians(localPlayer.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = inputX * cos - inputZ * sin;
        double worldZ = inputZ * cos + inputX * sin;
        return new Vec3(worldX, 0.0D, worldZ);
    }

    private static boolean resolveBool(Map<String, String> overrides, com.zhongbai233.super_lead.tuning.TuningKey<Boolean> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                Boolean parsed = key.type.parse(raw);
                if (key.type.validate(parsed)) return parsed;
            } catch (RuntimeException ignored) {
            }
        }
        return key.defaultValue;
    }

    private static double resolveDouble(Map<String, String> overrides, com.zhongbai233.super_lead.tuning.TuningKey<Double> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                Double parsed = key.type.parse(raw);
                if (key.type.validate(parsed)) return parsed;
            } catch (RuntimeException ignored) {
            }
        }
        return key.defaultValue;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private static double initialSagFactor(RopeTuning tuning) {
        return Math.abs(tuning.gravity()) < 1.0e-9D ? 0.0D : 0.035D;
    }

    private static ConnectionHighlight pickHighlightedConnection(Minecraft minecraft, List<RenderEntry> entries, float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        // Rope upgrades are gated behind sneak (except shears, which always work). Mirror that
        // here so the player only sees the highlight when the action would actually fire.
        LeadConnectionAction action = LeadConnectionAction.fromHeldItems(player).orElse(null);
        if (action == null) {
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
        // Clamp pick range to whatever the player is actually aiming at (block / entity).
        // Without this, a rope that lies along the view direction many blocks past the targeted
        // block still gets picked, which makes hopper / chest etc. unable to be placed normally.
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        net.minecraft.world.phys.HitResult hitResult = minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            // Allow a small slack so a rope just in front of the targeted block still picks.
            maxDistance = Math.min(maxDistance, hitDist + PICK_RADIUS);
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
                    double distance = distancePointToRaySqr(px, py, pz,
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
        return bestId == null ? null : new ConnectionHighlight(bestId, action.previewColor(),
                bestHitPoint, bestHitT);
    }

    private static double distancePointToRaySqr(
            double px, double py, double pz,
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double maxDistance) {
        double offX = px - ox;
        double offY = py - oy;
        double offZ = pz - oz;
        double along = offX * dx + offY * dy + offZ * dz;
        if (along < 0.0D || along > maxDistance) {
            return Double.POSITIVE_INFINITY;
        }
        double cx = ox + dx * along;
        double cy = oy + dy * along;
        double cz = oz + dz * along;
        double rx = px - cx;
        double ry = py - cy;
        double rz = pz - cz;
        return rx * rx + ry * ry + rz * rz;
    }

    private record RenderEntry(LeadConnection connection, RopeSimulation sim, Vec3 a, Vec3 b,
            double lodDistSqr, AABB bounds, AABB physicsBounds) {}

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
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            RenderEntry entry = entries.get(i);
            if (!entry.sim().physicsEnabled()) continue;
            if (entry.lodDistSqr() > PHYSICS_LOD_DISTANCE_SQR) continue;
            addToNeighborBuckets(buckets, i, entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN));
        }

        Map<RopeSimulation, List<RopeSimulation>> out = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            RenderEntry entry = entries.get(i);
            if (!entry.sim().physicsEnabled()) continue;
            if (entry.lodDistSqr() > PHYSICS_LOD_DISTANCE_SQR) continue;
            AABB query = entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN);
            HashSet<RopeSimulation> set = new HashSet<>();
            HashSet<Integer> seen = new HashSet<>();
            int minX = cell(query.minX);
            int maxX = cell(query.maxX);
            int minY = cell(query.minY);
            int maxY = cell(query.maxY);
            int minZ = cell(query.minZ);
            int maxZ = cell(query.maxZ);
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cy = minY; cy <= maxY; cy++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        List<Integer> candidates = buckets.get(cellKey(cx, cy, cz));
                        if (candidates == null) continue;
                        for (int idx : candidates) {
                            if (idx == i || !seen.add(idx)) continue;
                            RenderEntry other = entries.get(idx);
                            if (query.intersects(other.physicsBounds())
                                    && entry.sim().mightContact(other.sim(), NEIGHBOR_CONTACT_DISTANCE)) {
                                set.add(other.sim());
                            }
                        }
                    }
                }
            }
            if (!set.isEmpty()) {
                out.put(entry.sim(), new ArrayList<>(set));
            }
        }
        return out;
    }

    private static void addToNeighborBuckets(Map<Long, List<Integer>> buckets, int index, AABB bounds) {
        int minX = cell(bounds.minX);
        int maxX = cell(bounds.maxX);
        int minY = cell(bounds.minY);
        int maxY = cell(bounds.maxY);
        int minZ = cell(bounds.minZ);
        int maxZ = cell(bounds.maxZ);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    buckets.computeIfAbsent(cellKey(cx, cy, cz), key -> new ArrayList<>()).add(index);
                }
            }
        }
    }

    private static int cell(double value) {
        return (int) Math.floor(value / NEIGHBOR_GRID_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        long px = (x + GRID_KEY_BIAS) & 0x1FFFFFL;
        long py = (y + GRID_KEY_BIAS) & 0x1FFFFFL;
        long pz = (z + GRID_KEY_BIAS) & 0x1FFFFFL;
        return (px << 42) | (py << 21) | pz;
    }

    /** Snapshot bounding boxes of entities currently overlapping the rope's swept volume. The
     *  rope endpoints are typically attached to entities (player / leashed mob); those are
     *  filtered out so the rope doesn't shove its own anchors. */
    private static List<AABB> collectEntityBoxes(ClientLevel level, RopeSimulation sim, Vec3 a, Vec3 b) {
        AABB ropeBounds = sim.currentBounds().inflate(ENTITY_QUERY_MARGIN);
        List<Entity> raw = level.getEntities((Entity) null, ropeBounds, e -> !e.isSpectator() && e.isPickable());
        if (raw.isEmpty()) return List.of();
        List<AABB> out = new ArrayList<>(raw.size());
        for (Entity entity : raw) {
            AABB box = entity.getBoundingBox();
            // Skip anchors: any entity whose AABB contains a rope endpoint would otherwise fight
            // the pin and thrash the segments next to that endpoint.
            if (containsPoint(box, a) || containsPoint(box, b)) continue;
            out.add(box);
        }
        return out;
    }

    private static boolean containsPoint(AABB box, Vec3 p) {
        return p.x >= box.minX && p.x <= box.maxX
                && p.y >= box.minY && p.y <= box.maxY
                && p.z >= box.minZ && p.z <= box.maxZ;
    }

    private record ConnectionHighlight(UUID id, int color, Vec3 hitPoint, double hitT) {}
}
