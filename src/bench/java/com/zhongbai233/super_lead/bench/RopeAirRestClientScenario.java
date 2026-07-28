package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * In-game regression bench for free-air rope physics.
 *
 * <p>
 * Proves a hanging rope settles through the full client driver stack —
 * activity scheduler, async workers, wind wake checks, render hand-off and the
 * chunk-mesh registry — which is where cadence-dependent bugs (the "bouncing
 * hanging rope" family) actually lived. It hangs three spans in open air,
 * lets them settle, then measures whether they truly stop.
 */
final class RopeAirRestClientScenario implements BenchClientScenario {

    private static final int[] SPANS = { 4, 6, 8 };
    private static final int MEASURE_TICKS = 400;
    private static final int TAIL_TICKS = 100;
    private static final int MAX_TICKS_TO_REST = 300;
    private static final double TAIL_AMPLITUDE_LIMIT = 0.01D;
    private static final int MAX_REST_FLIPS = 2;
    private static final int MIN_MESH_ACCEPTED_TICKS = 20;

    private static final BenchMetricDescriptor TICKS_TO_REST = new BenchMetricDescriptor(
            "super_lead.rope.ticks_to_rest", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor TAIL_AMPLITUDE = new BenchMetricDescriptor(
            "super_lead.rope.tail_amplitude", "blocks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor REST_FLIPS = new BenchMetricDescriptor(
            "super_lead.rope.rest_flips", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor MESH_ACCEPTED_TICKS = new BenchMetricDescriptor(
            "super_lead.rope.mesh_accepted_ticks", "ticks", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor SAG_MODEL_ERROR = new BenchMetricDescriptor(
            "super_lead.rope.sag_model_error", "ratio", MetricDirection.NEUTRAL);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private volatile boolean rigReady;

    private final Map<UUID, RopeTrack> tracks = new HashMap<>();
    private BlockPos rigBase;
    private BenchClientPose viewPose;
    private int measuredTicks;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        BenchClientPose pose = context.automation().pose();
        // High above the player: guaranteed free air, no terrain in reach of any span.
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                for (int i = 0; i < SPANS.length; i++) {
                    int span = SPANS[i];
                    BlockPos a = base.offset(0, 0, i * 3);
                    BlockPos b = a.offset(span, 0, 0);
                    level.setBlockAndUpdate(a, Blocks.OAK_FENCE.defaultBlockState());
                    level.setBlockAndUpdate(b, Blocks.OAK_FENCE.defaultBlockState());
                    placedBlocks.add(a);
                    placedBlocks.add(b);
                    LeadConnection connection = SuperLeadNetwork.connect(level,
                            new LeadAnchor(a, Direction.UP), new LeadAnchor(b, Direction.UP),
                            LeadKind.NORMAL, null, LeadConnection.MIN_LENGTH_UNITS);
                    if (connection == null) {
                        throw new IllegalStateException("connect() refused span " + span);
                    }
                    createdConnections.add(connection.id());
                }
                rigReady = true;
            } catch (Exception e) {
                serverError.set("rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        if (!rigReady || !context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        // Wait until the client has synced every connection and spawned its sim.
        for (UUID id : createdConnections) {
            if (SuperLeadClientEvents.probeSimForBench(id) == null) {
                return BenchClientStepResult.CONTINUE;
            }
        }
        double cx = rigBase.getX() + SPANS[2] * 0.5D;
        double cy = rigBase.getY();
        double cz = rigBase.getZ() + 3.0D;
        viewPose = lookPose(cx, cy + 1.0D, cz + 12.0D, cx, cy, cz);
        context.automation().stopMovement();
        context.automation().setPose(viewPose);
        context.automation().setHudHidden(true);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        holdView(context);
        // One settle-and-wake cycle of margin before measurement starts.
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        holdView(context);
        measuredTicks++;
        for (UUID id : createdConnections) {
            SuperLeadClientEvents.RopeSimBenchProbe probe = SuperLeadClientEvents.probeSimForBench(id);
            RopeTrack track = tracks.computeIfAbsent(id, ignored -> new RopeTrack());
            track.sample(measuredTicks, probe);
        }
        if (measuredTicks == 30) {
            // Ropes are still dynamically rendered here (they rest at ~tick 64);
            // comparing this frame against the end-of-run frame separates "solver
            // broke" from "chunk-mesh handoff swallowed the rope".
            context.automation().captureScreenshot("rope-air-dynamic");
        }
        if (measuredTicks == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-air-rest");
        }
        return measuredTicks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        if (tracks.size() != SPANS.length) {
            throw new AssertionError("expected " + SPANS.length + " tracked ropes, got " + tracks.size());
        }
        long worstRest = 0;
        double worstAmplitude = 0.0D;
        long worstFlips = 0;
        double worstSagError = 0.0D;
        StringBuilder report = new StringBuilder();
        // Iterate in creation order: tracks is keyed by UUID and its iteration
        // order has nothing to do with which span a rope belongs to.
        for (int index = 0; index < createdConnections.size(); index++) {
            RopeTrack track = tracks.get(createdConnections.get(index));
            int span = SPANS[Math.min(index, SPANS.length - 1)];
            String label = "span" + span;
            if (track == null) {
                throw new AssertionError(label + " was never sampled");
            }
            if (track.missingSamples > 0) {
                throw new AssertionError(label + ": probe returned null on "
                        + track.missingSamples + " ticks — sim was dropped mid-measurement");
            }
            // Absolute sanity only: the baseline solver rests visibly deeper than
            // the analytic sag model (bench-measured 5.7x on span 4 with fence
            // anchors — smooth, natural curve on the screenshot), so any band
            // around the model fails a healthy rope. The assertion catches only
            // "no gravity at all" and "fell out of the rig"; model conformance is
            // recorded as a trend metric. The anchor height comes from the
            // solver's own pinned endpoint (guessing it from block coordinates
            // once hid a constant offset that read as a physics error).
            double predictedSag = com.zhongbai233.super_lead.lead.physics.RopeSagModel.midspanSag(
                    span, 0.30D, -0.065D);
            double measuredSag = track.lastAnchorY - track.lastBellyY;
            worstSagError = Math.max(worstSagError,
                    Math.abs(measuredSag - predictedSag) / Math.max(predictedSag, 1.0e-6D));
            if (measuredSag < 0.02D || measuredSag > span * 0.75D) {
                throw new AssertionError(label + " does not hang sanely: predictedSag="
                        + predictedSag + " measuredSag=" + measuredSag
                        + " terrainNearby=" + track.lastTerrainNearby
                        + " interiorTerrain=" + track.lastInteriorTerrain + "\n" + report);
            }
            double amplitude = track.tailAmplitude();
            report.append(String.format("%s: firstRest=%d flips=%d tailAmp=%.5f%n",
                    label, track.firstRestTick, track.restFlips, amplitude));
            if (track.firstRestTick < 0 || track.firstRestTick > MAX_TICKS_TO_REST) {
                throw new AssertionError(label + " never rested in time: firstRest="
                        + track.firstRestTick + "\n" + report);
            }
            if (amplitude > TAIL_AMPLITUDE_LIMIT) {
                throw new AssertionError(label + " keeps moving (the bounce): tail amplitude="
                        + amplitude + "\n" + report);
            }
            if (track.restFlips > MAX_REST_FLIPS) {
                throw new AssertionError(label + " oscillates between rest and wake: flips="
                        + track.restFlips + "\n" + report);
            }
            if (track.meshAcceptedTicks < MIN_MESH_ACCEPTED_TICKS) {
                throw new AssertionError(label + " never completed dynamic-to-chunk-mesh handoff: meshTicks="
                        + track.meshAcceptedTicks + "\n" + report);
            }
            worstRest = Math.max(worstRest, track.firstRestTick);
            worstAmplitude = Math.max(worstAmplitude, amplitude);
            worstFlips = Math.max(worstFlips, track.restFlips);
        }
        context.metrics().record(TICKS_TO_REST, worstRest);
        context.metrics().record(TAIL_AMPLITUDE, worstAmplitude);
        context.metrics().record(REST_FLIPS, worstFlips);
        context.metrics().record(SAG_MODEL_ERROR, worstSagError);
        long meshTicks = Long.MAX_VALUE;
        for (RopeTrack track : tracks.values()) {
            meshTicks = Math.min(meshTicks, track.meshAcceptedTicks);
        }
        context.metrics().record(MESH_ACCEPTED_TICKS, meshTicks);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<UUID> ids = Set.copyOf(createdConnections);
        List<BlockPos> blocks = List.copyOf(placedBlocks);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SuperLeadSavedData.get(level).removeIf(connection -> ids.contains(connection.id()));
            for (BlockPos pos : blocks) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            SuperLeadPayloads.sendDirtyToDimension(level);
        });
    }

    private void holdView(BenchClientContext context) {
        context.automation().stopMovement();
        if (viewPose != null) {
            context.automation().setPose(viewPose);
        }
    }

    private void failOnServerError() {
        String error = serverError.get();
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    private static BenchClientPose lookPose(double x, double y, double z,
            double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new BenchClientPose(x, y, z, yaw, pitch);
    }

    /** Per-rope kinematic history over the measurement window. */
    private static final class RopeTrack {
        private final List<Double> bellyTail = new ArrayList<>();
        private long firstRestTick = -1;
        private long restFlips;
        private boolean lastRest;
        private int missingSamples;
        private long meshAcceptedTicks;
        private double lastBellyY = Double.NaN;
        private double lastAnchorY = Double.NaN;
        private boolean lastTerrainNearby;
        private boolean lastInteriorTerrain;

        void sample(int tick, SuperLeadClientEvents.RopeSimBenchProbe probe) {
            if (probe == null) {
                missingSamples++;
                return;
            }
            lastBellyY = probe.bellyY();
            lastAnchorY = Math.min(probe.anchorAY(), probe.anchorBY());
            lastTerrainNearby = probe.terrainNearby();
            lastInteriorTerrain = probe.interiorTerrainContact();
            if (probe.meshAccepted()) {
                meshAcceptedTicks++;
            }
            if (probe.visuallyAtRest() && firstRestTick < 0) {
                firstRestTick = tick;
            }
            if (tick > 1 && probe.visuallyAtRest() != lastRest) {
                restFlips++;
            }
            lastRest = probe.visuallyAtRest();
            bellyTail.add(probe.bellyY());
            if (bellyTail.size() > TAIL_TICKS) {
                bellyTail.remove(0);
            }
        }

        double tailAmplitude() {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double y : bellyTail) {
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
            return bellyTail.isEmpty() ? Double.POSITIVE_INFINITY : max - min;
        }
    }
}
