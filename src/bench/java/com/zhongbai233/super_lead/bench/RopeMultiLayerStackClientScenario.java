package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Three ropes between the same two pillars (UP / NORTH / SOUTH anchor faces) —
 * a forced coincident pile. The layering response must sort them into a stable
 * vertical stack: everyone rests, every pair stays separated by at least most
 * of a rope diameter, and nobody keeps crawling.
 */
final class RopeMultiLayerStackClientScenario implements BenchClientScenario {

    private static final int SPAN = 6;
    private static final int MEASURE_TICKS = 600;
    private static final int TAIL_TICKS = 100;
    private static final int MAX_TICKS_TO_REST = 500;
    private static final double MIN_PAIR_SEPARATION = 0.045D;
    private static final double TAIL_AMPLITUDE_LIMIT = 0.02D;

    private static final BenchMetricDescriptor LAYER_MIN_SEPARATION = new BenchMetricDescriptor(
            "super_lead.rope.layer_min_separation", "blocks", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor LAYER_TICKS_TO_REST = new BenchMetricDescriptor(
            "super_lead.rope.layer_ticks_to_rest", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor LAYER_TAIL_AMPLITUDE = new BenchMetricDescriptor(
            "super_lead.rope.layer_tail_amplitude", "blocks", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor LAYER_MESH_ACCEPTED_TICKS = new BenchMetricDescriptor(
            "super_lead.rope.layer_mesh_accepted_ticks", "ticks", MetricDirection.HIGHER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean rigReady;

    private final StringBuilder trace = new StringBuilder("tick,belly0,belly1,belly2,rest0,rest1,rest2\n");
    private BlockPos rigBase;
    private int tick;
    private long allRestTick = -1;
    private final List<double[]> tailBellies = new ArrayList<>();
    private final int[] meshAcceptedTicks = new int[3];

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        var pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(-16, 0, 0);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos a = RopeBenchSupport.fencePillar(level, base, 1, placedBlocks);
                BlockPos b = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), 1, placedBlocks);
                // Same anchors, same face: three genuinely coincident ropes. Distinct
                // faces looked like a pile but hung 0.5-1.0 blocks apart in Z and
                // never actually touched, so the separation assertion measured a
                // geometric coincidence instead of the contact solver.
                for (int i = 0; i < 3; i++) {
                    LeadConnection connection = RopeBenchSupport.connectFaces(level,
                            a, Direction.UP, b, Direction.UP);
                    createdConnections.add(connection.id());
                }
                rigReady = true;
            } catch (Exception e) {
                serverError.set("multi-layer rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        // Park the observer first: sims only exist near the player.
        double cx = rigBase.getX() + SPAN * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx, rigBase.getY() + 1.0D, rigBase.getZ() + 9.0D,
                cx, rigBase.getY(), rigBase.getZ()));
        context.automation().setHudHidden(true);
        if (!rigReady || !context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        for (UUID id : createdConnections) {
            if (SuperLeadClientEvents.probeSimForBench(id) == null) {
                return BenchClientStepResult.CONTINUE;
            }
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(createdConnections);
        double[] bellies = new double[createdConnections.size()];
        boolean allRest = true;
        StringBuilder row = new StringBuilder().append(tick);
        for (int i = 0; i < createdConnections.size(); i++) {
            SuperLeadClientEvents.RopeSimBenchProbe probe =
                    SuperLeadClientEvents.probeSimForBench(createdConnections.get(i));
            if (probe == null) {
                throw new IllegalStateException("layer " + i + " sim disappeared at tick " + tick);
            }
            if (!Double.isFinite(probe.bellyY())) {
                throw new AssertionError("layer " + i + " went non-finite at tick " + tick + "\n" + tail());
            }
            bellies[i] = probe.bellyY();
            allRest &= probe.visuallyAtRest();
            if (probe.meshAccepted()) {
                meshAcceptedTicks[i]++;
            }
            row.append(String.format(java.util.Locale.ROOT, ",%.5f", probe.bellyY()));
        }
        for (int i = 0; i < createdConnections.size(); i++) {
            SuperLeadClientEvents.RopeSimBenchProbe probe =
                    SuperLeadClientEvents.probeSimForBench(createdConnections.get(i));
            row.append(',').append(probe.visuallyAtRest() ? 1 : 0);
        }
        trace.append(row).append('\n');
        if (allRest && allRestTick < 0) {
            allRestTick = tick;
        }
        if (tick > MEASURE_TICKS - TAIL_TICKS) {
            tailBellies.add(bellies);
        }
        if (tick == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-multi-layer");
        }
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) throws Exception {
        java.nio.file.Files.writeString(
                context.resultDirectory().resolve("rope-multi-layer-trace.csv"), trace.toString());
        failOnServerError();
        if (allRestTick < 0 || allRestTick > MAX_TICKS_TO_REST) {
            throw new AssertionError("multi-layer pile never came to rest: allRestTick="
                    + allRestTick + "\n" + tail());
        }
        double amplitude = 0.0D;
        for (int layer = 0; layer < createdConnections.size(); layer++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] bellies : tailBellies) {
                min = Math.min(min, bellies[layer]);
                max = Math.max(max, bellies[layer]);
            }
            amplitude = Math.max(amplitude, max - min);
        }
        if (amplitude > TAIL_AMPLITUDE_LIMIT) {
            throw new AssertionError("pile keeps moving at rest: amplitude=" + amplitude + "\n" + tail());
        }
        // Separation is 3D: coincident-anchor ropes may legitimately settle side
        // by side (their real-world configuration), so a Y-only metric reads a
        // correct rest state as interpenetration.
        double minSeparation = Double.POSITIVE_INFINITY;
        for (int i = 0; i < createdConnections.size(); i++) {
            for (int j = i + 1; j < createdConnections.size(); j++) {
                SuperLeadClientEvents.RopeSimBenchProbe pi =
                        SuperLeadClientEvents.probeSimForBench(createdConnections.get(i));
                SuperLeadClientEvents.RopeSimBenchProbe pj =
                        SuperLeadClientEvents.probeSimForBench(createdConnections.get(j));
                if (pi != null && pj != null) {
                    minSeparation = Math.min(minSeparation, pi.bellyDistanceTo(pj));
                }
            }
        }
        if (minSeparation < MIN_PAIR_SEPARATION) {
            throw new AssertionError("layers sleep inside each other: minSeparation="
                    + minSeparation + "\n" + tail());
        }
        int leastMeshTicks = Integer.MAX_VALUE;
        for (int i = 0; i < meshAcceptedTicks.length; i++) {
            leastMeshTicks = Math.min(leastMeshTicks, meshAcceptedTicks[i]);
            if (meshAcceptedTicks[i] < 20) {
                throw new AssertionError("layer " + i
                        + " never completed multi-rope chunk-mesh handoff: meshTicks="
                        + meshAcceptedTicks[i] + "\n" + tail());
            }
        }
        meshLifecycle.requireAllActiveAtLeastOnce(createdConnections, "multi-layer");
        context.metrics().record(LAYER_TICKS_TO_REST, allRestTick);
        context.metrics().record(LAYER_TAIL_AMPLITUDE, amplitude);
        context.metrics().record(LAYER_MIN_SEPARATION, minSeparation);
        context.metrics().record(LAYER_MESH_ACCEPTED_TICKS, leastMeshTicks);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<UUID> ids = Set.copyOf(createdConnections);
        List<BlockPos> blocks = List.copyOf(placedBlocks);
        server.execute(() -> RopeBenchSupport.teardown(server.overworld(), ids, blocks));
    }

    private void failOnServerError() {
        String error = serverError.get();
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    private String tail() {
        String all = trace.toString();
        int from = Math.max(0, all.length() - 2000);
        return all.substring(from);
    }
}
