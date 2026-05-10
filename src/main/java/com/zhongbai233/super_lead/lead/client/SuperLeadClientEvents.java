package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.AddRopeAttachment;
import com.zhongbai233.super_lead.lead.ItemPulse;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadConnectionAction;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.RemoveRopeAttachment;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.ToggleRopeAttachmentForm;
import com.zhongbai233.super_lead.lead.UseConnectionAction;
import com.zhongbai233.super_lead.lead.client.debug.RopeDebugStats;
import com.zhongbai233.super_lead.lead.client.interaction.AttachPick;
import com.zhongbai233.super_lead.lead.client.interaction.AttachmentPick;
import com.zhongbai233.super_lead.lead.client.interaction.ConnectionHighlight;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RenderEntry;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.render.RopeJob;
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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class SuperLeadClientEvents {
    private static final double PICK_RADIUS = 0.30D;
    /** Looser radius for picking a rope to attach a decoration to. The aim usually lands a bit
     *  off the rope (the strand is thin); a larger radius means the player can put a lantern
     *  on the rope without having to laser-aim at the strand. */
    private static final double ATTACH_PICK_RADIUS = 0.50D;
    /** Beyond this midpoint distance (blocks) physics is frozen but the rope still renders from
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
    private static final int PARALLEL_THRESHOLD = 4;
    private static final int PHYSICS_THREAD_COUNT = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static final java.util.concurrent.atomic.AtomicInteger PHYSICS_THREAD_ID =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ExecutorService PHYSICS_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(PHYSICS_THREAD_COUNT, runnable -> {
                Thread thread = new Thread(runnable,
                        "super-lead-physics-" + PHYSICS_THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private static final Map<UUID, RopeSimulation> SIMS = new HashMap<>();
    private static final Map<UUID, RopeSimulation> WORK_SIMS = new HashMap<>();
    private static PhysicsBatch pendingPhysics;
    static {
        // Let StaticRopeChunkRegistry rebuild against real sim state when connection-sync
        // packets arrive, so existing ropes don't briefly flash to their no-physics catenary
        // shape during interactions (place / cut / upgrade).
        com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry.get()
                .setSimLookup(SIMS::get);
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> PHYSICS_EXECUTOR.shutdownNow(), "super-lead-physics-shutdown"));
    }
    private static RopeSimulation previewSim;
    private static LeadAnchor previewAnchor;
    private static long lastRepelTick = Long.MIN_VALUE;
    private static volatile LeadConnection HOVERED_CONNECTION;

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
        if (hovered == null || !action.canTarget(hovered)) {
            return false;
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new UseConnectionAction(
                        hovered.id(),
                        action.ordinal(),
                        hand == net.minecraft.world.InteractionHand.OFF_HAND));
        return true;
    }

    public static boolean trySendAddRopeAttachment(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        net.minecraft.world.item.ItemStack stack = mc.player.getItemInHand(hand);
        if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) return false;
        // Items must be "tied on" with string in the opposite hand. Creative bypasses.
        if (!mc.player.getAbilities().instabuild) {
            net.minecraft.world.InteractionHand bindHand = hand == net.minecraft.world.InteractionHand.OFF_HAND
                    ? net.minecraft.world.InteractionHand.MAIN_HAND
                    : net.minecraft.world.InteractionHand.OFF_HAND;
            if (!mc.player.getItemInHand(bindHand).is(net.minecraft.world.item.Items.STRING)) return false;
        }
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
        double maxDistance = SuperLeadNetwork.maxLeashDistance();
        double bestDistSqr = ATTACH_PICK_RADIUS * ATTACH_PICK_RADIUS;
        UUID bestId = null;
        double bestT = 0.5D;
        double[] hit = new double[2];

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
                double d = distanceSegmentToRaySqr(ax, ay, az, bx, by, bz,
                        cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance, hit);
                if (d < bestDistSqr) {
                    double frac = hit[0];
                    double px = ax + (bx - ax) * frac;
                    double py = ay + (by - ay) * frac;
                    double pz = az + (bz - az) * frac;
                    net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(px, py - 0.35D, pz);
                    if (!level.getBlockState(below).isAir()) continue;
                    bestDistSqr = d;
                    bestId = connection.id();
                    double l0 = sim.renderLength(i);
                    double l1 = sim.renderLength(i + 1);
                    double arc = l0 + (l1 - l0) * frac;
                    bestT = Math.max(0.02D, Math.min(0.98D, arc / total));
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
        double maxDistance = SuperLeadNetwork.maxLeashDistance();
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

    private static LeadConnection findById(List<RenderEntry> entries, UUID id) {
        for (RenderEntry entry : entries) {
            if (entry.connection().id().equals(id)) {
                return entry.connection();
            }
        }
        return null;
    }

    private SuperLeadClientEvents() {}

    private record AsyncPhysJob(
            UUID id,
            RopeSimulation live,
            RopeSimulation work,
            Vec3 a,
            Vec3 b,
            List<RopeSimulation> nbrs,
            List<AABB> eboxes) {}

    private record PhysicsBatch(
            long tick,
            List<AsyncPhysJob> jobs,
            java.util.concurrent.CompletableFuture<Void> future) {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearClientPhysicsState();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clearClientPhysicsState();
        }
    }

    private static void clearClientPhysicsState() {
        if (pendingPhysics != null) {
            pendingPhysics.future().cancel(true);
            pendingPhysics = null;
        }
        WORK_SIMS.clear();
        lastRepelTick = Long.MIN_VALUE;
    }

    private static void captureFrameStats(List<LeadConnection> connections,
            List<RenderEntry> simEntries,
            List<RenderEntry> renderEntries,
            List<RopeJob> ropeJobs,
            com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry chunkReg) {
        int dynNodes = 0;
        int attachments = 0;
        for (RenderEntry e : renderEntries) {
            if (chunkReg.isClaimed(e.connection().id())) continue;
            dynNodes += e.sim().nodeCount();
            attachments += e.connection().attachments().size();
        }
        int meshNodes = chunkReg.claimedNodesTotal();
        RopeDebugStats.totalConnections = connections.size();
        RopeDebugStats.simEntries = simEntries.size();
        RopeDebugStats.renderEntries = renderEntries.size();
        RopeDebugStats.chunkMeshClaimed = chunkReg.claimedCount();
        RopeDebugStats.dynamicJobs = ropeJobs.size();
        RopeDebugStats.simCount = SIMS.size();
        RopeDebugStats.dynamicNodesTotal = dynNodes;
        RopeDebugStats.chunkMeshNodesTotal = meshNodes;
        RopeDebugStats.totalRenderNodes = dynNodes + meshNodes;
        RopeDebugStats.chunkMeshSections = chunkReg.sectionCount();
        RopeDebugStats.chunkMeshSnapshots = chunkReg.sectionSnapshotsTotal();
        RopeDebugStats.attachmentsTotal = attachments;
        RopeDebugStats.captureLeashBuilderDeltas();
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clearClientPhysicsState();
            PhysicsZonesClient.clear();
            SIMS.clear();
            previewSim = null;
            previewAnchor = null;
            HOVERED_CONNECTION = null;
            ItemFlowAnimator.clearAll();
            RopeDebugStats.clear();
            return;
        }

        ClientTuning.loadOnce();
        SuperLeadNetwork.pruneInvalid(level);
        long tick = level.getGameTime();
        completePendingPhysics();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        Frustum frustum = camera.getCullFrustum();
        RopeVisibility.beginFrame(cameraPos);

        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        double maxRenderDistance = ClientTuning.RENDER_MAX_DISTANCE.get();
        double maxRenderDistanceSqr = maxRenderDistance * maxRenderDistance;
        Set<UUID> active = new HashSet<>(Math.max(16, connections.size() * 2));
        List<RenderEntry> simEntries = new ArrayList<>(connections.size());
        com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry chunkReg =
            com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry.get();
        // First pass: keep sims for ropes within render distance even when they are outside the
        // camera frustum. Frustum culling is delayed until after the tick's physics update so it
        // tests the same partialTick-interpolated nodes that rendering will actually submit.
        for (LeadConnection connection : connections) {
            Vec3 a = connection.from().attachmentPoint(level);
            Vec3 b = connection.to().attachmentPoint(level);
            double midDistSqr = midpointDistanceSqr(a, b, cameraPos);
            if (midDistSqr > maxRenderDistanceSqr) continue;
            active.add(connection.id());
            RopeTuning tuning = RopeTuning.forMidpoint(a, b);
            RopeSimulation sim = SIMS.get(connection.id());
            if (sim == null || !sim.matchesLength(a, b, tuning)) {
                sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), true, tuning);
                sim.resetCatenary(a, b, initialSagFactor(tuning));
                SIMS.put(connection.id(), sim);
            } else {
                if (sim.setTuning(tuning)) {
                    chunkReg.invalidateConnection(level, connection.id());
                }
            }
            AABB physicsBounds = physicsBounds(a, b, sim);
            AABB bounds = physicsBounds.inflate(FRUSTUM_BOUNDS_MARGIN);
            RenderEntry entry = new RenderEntry(connection, sim, a, b, midDistSqr, bounds, physicsBounds);
            simEntries.add(entry);
        }
        // Second pass: drive each sim with its neighbours so rope-rope constraints participate
        // in the unified solver iteration (no more separate repel + settle phase). Ropes whose
        // midpoint is past PHYSICS_LOD_DISTANCE skip stepping altogether and just keep rendering.
        // Parallel solves run one frame behind on worker-owned copies; the main thread publishes
        // completed work at the start of the next frame before any render reads the live sims.
        if (tick != lastRepelTick) {
            lastRepelTick = tick;
            schedulePhysicsTick(level, simEntries, tick);
        }

        List<RenderEntry> renderEntries = new ArrayList<>(simEntries.size());
        for (RenderEntry entry : simEntries) {
            RopeSimulation sim = entry.sim();
            AABB physicsBounds = physicsBounds(entry.a(), entry.b(), sim);
            AABB renderBounds = sim.renderBounds(partialTick).inflate(FRUSTUM_BOUNDS_MARGIN);
            RenderEntry fresh = new RenderEntry(entry.connection(), sim, entry.a(), entry.b(),
                    entry.midDistSqr(), renderBounds, physicsBounds);
            if (!RopeVisibility.shouldRender(level, minecraft.player, frustum, cameraPos,
                    renderBounds, sim, partialTick)) {
                continue;
            }
            renderEntries.add(fresh);
        }

        ConnectionHighlight highlight = pickHighlightedConnection(minecraft, renderEntries, partialTick, cameraPos);

        java.util.List<RopeJob> ropeJobs = new ArrayList<>(renderEntries.size());
        // Per-tick chunk-mesh maintenance: any rope whose sim has settled (re)claims chunk-mesh
        // and gets snapshotted from the physics-derived node positions; any rope being disturbed
        // (sim not settled) is evicted so the dynamic path renders the live shape.
        chunkReg.tickMaintain(level, SIMS::get);
        for (RenderEntry entry : renderEntries) {
            // Keep a short dynamic linger window while the chunk mesh rebuild catches up.
            if (chunkReg.isClaimed(entry.connection().id())
                    && !chunkReg.shouldDynamicLinger(entry.connection().id(), tick)) {
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
            spawnRedstoneParticles(level, sim, partialTick, entry.connection(), entry.midDistSqr());
            spawnEnergyParticles(level, sim, partialTick, entry.connection(), entry.midDistSqr());
            if (!entry.connection().attachments().isEmpty()) {
                RopeAttachmentRenderer.submitAll(event.getSubmitNodeCollector(), cameraPos, level, sim,
                        entry.connection().attachments(),
                        entry.connection().kind() == LeadKind.REDSTONE && entry.connection().powered(),
                        partialTick);
            }
        }
        LeashBuilder.flush(event.getSubmitNodeCollector(), cameraPos, partialTick, ropeJobs);
        SIMS.keySet().retainAll(active);
        WORK_SIMS.keySet().retainAll(active);
        ItemFlowAnimator.retainAll(active);

        HOVERED_CONNECTION = highlight == null ? null : findById(renderEntries, highlight.id());

        captureFrameStats(connections, simEntries, renderEntries, ropeJobs, chunkReg);

        // Attachment placement preview: while holding a non-empty attachable item AND string
        // in the opposite hand ("tied on" mechanic; creative skips the string check), draw a
        // translucent ghost of the held item where a right-click would place it. The rope itself
        // is also tinted via the highlight pipeline (see pickHighlightedConnection).
        Player previewPlayer = minecraft.player;
        if (previewPlayer != null && !previewPlayer.isShiftKeyDown()) {
            net.minecraft.world.item.ItemStack mainHand = previewPlayer.getMainHandItem();
            net.minecraft.world.item.ItemStack offHand = previewPlayer.getOffhandItem();
            net.minecraft.world.item.ItemStack ghostStack;
            boolean creative = previewPlayer.getAbilities().instabuild;
            // Pick the hand whose OPPOSITE hand holds STRING (or any hand in creative).
            if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(mainHand)
                    && (creative || offHand.is(net.minecraft.world.item.Items.STRING))) {
                ghostStack = mainHand;
            } else if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(offHand)
                    && (creative || mainHand.is(net.minecraft.world.item.Items.STRING))) {
                ghostStack = offHand;
            } else {
                ghostStack = net.minecraft.world.item.ItemStack.EMPTY;
            }
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

    private static void renderPreview(SubmitCustomGeometryEvent event, ClientLevel level, Vec3 cameraPos, float partialTick, long tick, Player player) {
        LeadAnchor anchor = SuperLeadNetwork.pendingAnchor(player).orElse(null);
        if (anchor == null) {
            previewSim = null;
            previewAnchor = null;
            return;
        }
        Vec3 a = anchor.attachmentPoint(level);
        Vec3 b = player.getRopeHoldPosition(partialTick);
        if (a.distanceTo(b) > SuperLeadNetwork.maxLeashDistance()) {
            return;
        }
        if (previewSim == null || !anchor.equals(previewAnchor)) {
            previewSim = new RopeSimulation(a, b);
            previewAnchor = anchor;
        }
        previewSim.setTuning(RopeTuning.forMidpoint(a, b));
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
        float[] out = new float[4];
        int count = 0;
        for (ItemPulse p : active) {
            float age = (currentTick - p.startTick()) + partialTick;
            float t = age / Math.max(1, p.durationTicks());
            if (t < 0F || t > 1F) continue;
            if (count == out.length) {
                out = java.util.Arrays.copyOf(out, out.length * 2);
            }
            out[count++] = p.reverse() ? 1F - t : t;
        }
        if (count == 0) return EMPTY_PULSES;
        return count == out.length ? out : java.util.Arrays.copyOf(out, count);
    }

    private static double initialSagFactor(RopeTuning tuning) {
        return Math.abs(tuning.gravity()) < 1.0e-9D ? 0.0D : 0.035D;
    }

    private static void spawnRedstoneParticles(ClientLevel level, RopeSimulation sim, float partialTick, LeadConnection connection, double midDistSqr) {
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(midDistSqr);
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

    private static void spawnEnergyParticles(ClientLevel level, RopeSimulation sim, float partialTick, LeadConnection connection, double midDistSqr) {
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(midDistSqr);
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

    private static double particleDistanceScale(double midDistSqr) {
        if (midDistSqr > PARTICLE_DISTANCE_SQR) return 0.0D;
        double distance = Math.sqrt(midDistSqr);
        if (distance <= PARTICLE_FADE_START) return 1.0D;
        return Math.max(0.0D, 1.0D - (distance - PARTICLE_FADE_START) / (PARTICLE_DISTANCE - PARTICLE_FADE_START));
    }

    private static ConnectionHighlight pickHighlightedConnection(Minecraft minecraft, List<RenderEntry> entries, float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        // Rope upgrades are gated behind sneak (except shears, which always work). Mirror that
        // here so the player only sees the highlight when the action would actually fire.
        LeadConnectionAction action = LeadConnectionAction.fromHeldItems(player).orElse(null);
        // Attachment placement: held attachable + STRING in opposite hand (creative skips check).
        // Reuses the upgrade highlight pipeline so the rope tints under the crosshair when the
        // attach action would succeed.
        AttachAimMode attachMode = null;
        if (action == null) {
            attachMode = pickAttachAimMode(player);
            if (attachMode == null) return null;
        } else {
            boolean isShears = player.getMainHandItem().is(net.minecraft.world.item.Items.SHEARS)
                    || player.getOffhandItem().is(net.minecraft.world.item.Items.SHEARS);
            if (!isShears && !player.isShiftKeyDown()) {
                return null;
            }
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
        double maxDistance = SuperLeadNetwork.maxLeashDistance();
        net.minecraft.world.phys.HitResult hitResult = minecraft.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            // Allow a small slack so a rope just in front of the targeted block still picks.
            maxDistance = Math.min(maxDistance, hitDist + PICK_RADIUS);
        }
        double best = PICK_RADIUS * PICK_RADIUS;
        UUID bestId = null;
        double[] hit = new double[2];

        for (RenderEntry entry : entries) {
            if (action != null && !action.canTarget(entry.connection())) {
                continue;
            }
            if (attachMode != null) {
                LeadKind k = entry.connection().kind();
                if (k != LeadKind.NORMAL && k != LeadKind.REDSTONE) continue;
            }
            RopeSimulation sim = entry.sim();
            sim.prepareRender(partialTick);
            for (int i = 0; i < sim.nodeCount() - 1; i++) {
                double ax = sim.renderX(i);
                double ay = sim.renderY(i);
                double az = sim.renderZ(i);
                double bx = sim.renderX(i + 1);
                double by = sim.renderY(i + 1);
                double bz = sim.renderZ(i + 1);
                double distance = distanceSegmentToRaySqr(ax, ay, az, bx, by, bz,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        dirX, dirY, dirZ, maxDistance, hit);
                if (distance < best) {
                    best = distance;
                    bestId = entry.connection().id();
                }
            }
        }
        if (bestId == null) return null;
        int color = action != null ? action.previewColor() : ATTACH_HIGHLIGHT_COLOR;
        return new ConnectionHighlight(bestId, color);
    }

    private enum AttachAimMode { MAIN, OFF }

    /** Returns the hand carrying the attachable item when the player is properly armed for
     *  attaching (i.e. has STRING in the opposite hand or is in creative). */
    private static AttachAimMode pickAttachAimMode(Player player) {
        if (player.isShiftKeyDown()) return null;
        boolean creative = player.getAbilities().instabuild;
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(main)
                && (creative || off.is(net.minecraft.world.item.Items.STRING))) {
            return AttachAimMode.MAIN;
        }
        if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(off)
                && (creative || main.is(net.minecraft.world.item.Items.STRING))) {
            return AttachAimMode.OFF;
        }
        return null;
    }

    private static final int ATTACH_HIGHLIGHT_COLOR = 0x6666FFAA;

    private static double distanceSegmentToRaySqr(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double maxDistance,
            double[] outSegmentTAlong) {
        double sx = bx - ax;
        double sy = by - ay;
        double sz = bz - az;
        double segLenSqr = sx * sx + sy * sy + sz * sz;
        if (segLenSqr < 1.0e-8D) {
            double d = distancePointToRaySqr(ax, ay, az, ox, oy, oz, dx, dy, dz, maxDistance);
            outSegmentTAlong[0] = 0.0D;
            outSegmentTAlong[1] = 0.0D;
            return d;
        }

        double wx = ax - ox;
        double wy = ay - oy;
        double wz = az - oz;
        double segDotRay = sx * dx + sy * dy + sz * dz;
        double segDotW = sx * wx + sy * wy + sz * wz;
        double rayDotW = dx * wx + dy * wy + dz * wz;
        double denom = segLenSqr - segDotRay * segDotRay;
        double s = denom < 1.0e-8D ? 0.0D : (segDotRay * rayDotW - segDotW) / denom;
        s = Math.max(0.0D, Math.min(1.0D, s));
        double along = sx * s * dx + sy * s * dy + sz * s * dz + rayDotW;
        along = Math.max(0.0D, Math.min(maxDistance, along));
        s = ((ox + dx * along - ax) * sx
                + (oy + dy * along - ay) * sy
                + (oz + dz * along - az) * sz) / segLenSqr;
        s = Math.max(0.0D, Math.min(1.0D, s));
        double px = ax + sx * s;
        double py = ay + sy * s;
        double pz = az + sz * s;
        double rx = px - (ox + dx * along);
        double ry = py - (oy + dy * along);
        double rz = pz - (oz + dz * along);
        outSegmentTAlong[0] = s;
        outSegmentTAlong[1] = along;
        return rx * rx + ry * ry + rz * rz;
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

    private static void completePendingPhysics() {
        PhysicsBatch batch = pendingPhysics;
        if (batch == null || !batch.future().isDone()) {
            return;
        }
        pendingPhysics = null;
        try {
            batch.future().join();
        } catch (java.util.concurrent.CancellationException | java.util.concurrent.CompletionException ignored) {
            return;
        }
        for (AsyncPhysJob job : batch.jobs()) {
            if (SIMS.get(job.id()) != job.live()) {
                continue;
            }
            try {
                job.live().copyMutableStateFrom(job.work());
            } catch (IllegalArgumentException ignored) {
                // The live sim was rebuilt with a different topology while the worker was running.
            }
        }
    }

    private static void schedulePhysicsTick(ClientLevel level, List<RenderEntry> simEntries, long tick) {
        // Refresh server-broadcast contact state on every live sim before the worker snapshot is
        // made; both physics and the off-LOD visual leash consume contactT/dx,dy,dz.
        for (RenderEntry entry : simEntries) {
            refreshBroadcastState(entry.sim(), entry.connection(), tick);
        }

        // Off-LOD and physics-disabled ropes are cheap and visual-only, so keep updating them on
        // the render-owned live simulation even when a previous physics batch is still running.
        for (RenderEntry entry : simEntries) {
            if (!entry.sim().physicsEnabled() || entry.midDistSqr() > PHYSICS_LOD_DISTANCE_SQR) {
                entry.sim().updateVisualLeash(entry.a(), entry.b(), tick, 0.45F);
            }
        }

        if (pendingPhysics != null) {
            return;
        }

        Map<RopeSimulation, List<RopeSimulation>> liveNeighbors = buildNeighborMap(simEntries);
        Map<RopeSimulation, RopeSimulation> workByLive = new HashMap<>();
        List<AsyncPhysJob> jobs = new ArrayList<>(simEntries.size());
        for (RenderEntry entry : simEntries) {
            RopeSimulation live = entry.sim();
            if (!live.physicsEnabled() || entry.midDistSqr() > PHYSICS_LOD_DISTANCE_SQR) {
                continue;
            }

            RopeSimulation work = workSim(entry.connection(), entry.a(), entry.b(), live.tuning());
            work.copyMutableStateFrom(live);
            workByLive.put(live, work);
        }

        for (RenderEntry entry : simEntries) {
            RopeSimulation live = entry.sim();
            RopeSimulation work = workByLive.get(live);
            if (work == null) {
                continue;
            }

            List<RopeSimulation> liveNbrs = liveNeighbors.getOrDefault(live, List.of());
            List<RopeSimulation> workNbrs;
            if (liveNbrs.isEmpty()) {
                workNbrs = List.of();
            } else {
                workNbrs = new ArrayList<>(liveNbrs.size());
                for (RopeSimulation liveNbr : liveNbrs) {
                    RopeSimulation workNbr = workByLive.get(liveNbr);
                    if (workNbr != null) {
                        workNbrs.add(workNbr);
                    }
                }
            }

            List<AABB> entityBoxes = collectEntityBoxes(level, live, entry.a(), entry.b());
            work.preparePhysicsParallel(level, entry.a(), entry.b(), tick);
            jobs.add(new AsyncPhysJob(entry.connection().id(), live, work,
                    entry.a(), entry.b(), workNbrs, entityBoxes));
        }

        if (jobs.isEmpty()) {
            return;
        }
        if (jobs.size() < PARALLEL_THRESHOLD) {
            for (AsyncPhysJob job : jobs) {
                runPhysicsJob(job, level, tick, false);
                job.live().copyMutableStateFrom(job.work());
            }
            return;
        }

        java.util.concurrent.CompletableFuture<?>[] futures =
                new java.util.concurrent.CompletableFuture<?>[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            AsyncPhysJob job = jobs.get(i);
            futures[i] = java.util.concurrent.CompletableFuture.runAsync(
                    () -> runPhysicsJob(job, level, tick, true), PHYSICS_EXECUTOR);
        }
        pendingPhysics = new PhysicsBatch(tick, jobs,
                java.util.concurrent.CompletableFuture.allOf(futures));
    }

    private static RopeSimulation workSim(LeadConnection connection, Vec3 a, Vec3 b, RopeTuning tuning) {
        RopeSimulation sim = WORK_SIMS.get(connection.id());
        if (sim == null || !sim.matchesLength(a, b, tuning)) {
            sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), true, tuning);
            WORK_SIMS.put(connection.id(), sim);
        }
        return sim;
    }

    private static void refreshBroadcastState(RopeSimulation sim, LeadConnection connection, long tick) {
        com.zhongbai233.super_lead.lead.client.render.RopeContactsClient.Contact contact =
                com.zhongbai233.super_lead.lead.client.render.RopeContactsClient.get(connection.id());
        if (contact != null) {
            sim.setExternalContact(tick, contact.t(), contact.dx(), contact.dy(), contact.dz());
        } else {
            sim.clearExternalContact();
        }
        com.zhongbai233.super_lead.lead.client.render.RopeServerNodesClient.Snapshot snap =
                com.zhongbai233.super_lead.lead.client.render.RopeServerNodesClient.get(connection.id());
        if (snap != null) {
            sim.setServerNodes(tick, snap.segments(), snap.interior());
        } else {
            sim.clearServerNodes();
        }
    }

    private static void runPhysicsJob(AsyncPhysJob job, ClientLevel level, long tick, boolean parallelPhase) {
        if (parallelPhase) {
            RopeSimulation.beginParallelPhase();
        }
        try {
            job.work().step(level, job.a(), job.b(), tick, job.nbrs(), EMPTY_FORCE_FIELDS, job.eboxes());
        } finally {
            if (parallelPhase) {
                RopeSimulation.endParallelPhase();
            }
        }
    }

    private static double midpointDistanceSqr(Vec3 a, Vec3 b, Vec3 camera) {
        double mx = (a.x + b.x) * 0.5D - camera.x;
        double my = (a.y + b.y) * 0.5D - camera.y;
        double mz = (a.z + b.z) * 0.5D - camera.z;
        return mx * mx + my * my + mz * mz;
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
            if (entry.midDistSqr() > PHYSICS_LOD_DISTANCE_SQR) continue;
            addToNeighborBuckets(buckets, i, entry.physicsBounds().inflate(NEIGHBOR_BOUNDS_MARGIN));
        }

        Map<RopeSimulation, List<RopeSimulation>> out = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            RenderEntry entry = entries.get(i);
            if (!entry.sim().physicsEnabled()) continue;
            if (entry.midDistSqr() > PHYSICS_LOD_DISTANCE_SQR) continue;
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

}
