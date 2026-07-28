package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
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
 * Two identical crossing-rope rigs created in opposite X/Z order.
 *
 * <p>The client solves each rope against a temporarily static neighbour, so a
 * regression can make the first-created rig settle while its reversed twin
 * jitters forever. Both twins must separate and converge with comparable tail
 * motion; the final layering direction itself may differ.
 */
final class RopeStackOrderClientScenario implements BenchClientScenario {
    private static final int SPAN = 6;
    private static final int RIG_GAP = 12;
    private static final int MEASURE_TICKS = 500;
    private static final int TAIL_TICKS = 100;
    private static final int MAX_TICKS_TO_REST = 450;
    private static final int MAX_REST_TICK_DELTA = 100;
    private static final double MIN_SEPARATION = 0.05D;
    private static final double TAIL_AMPLITUDE_LIMIT = 0.012D;
    private static final double MAX_TAIL_AMPLITUDE_DELTA = 0.006D;

    private static final BenchMetricDescriptor ORDER_WORST_TAIL = new BenchMetricDescriptor(
            "super_lead.rope.order_worst_tail_amplitude", "blocks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor ORDER_REST_DELTA = new BenchMetricDescriptor(
            "super_lead.rope.order_rest_tick_delta", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor ORDER_MIN_SEPARATION = new BenchMetricDescriptor(
            "super_lead.rope.order_min_separation", "blocks", MetricDirection.HIGHER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private final RigTrack forward = new RigTrack("x-then-z");
    private final RigTrack reversed = new RigTrack("z-then-x");
    private final StringBuilder trace = new StringBuilder(
            "tick,rig,belly0x,belly0y,belly0z,belly1x,belly1y,belly1z,rest0,rest1\n");

    private volatile boolean rigReady;
    private BlockPos rigBase;
    private BenchClientPose viewPose;
    private int tick;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        BenchClientPose pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(16, 0, 0);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                createCrossingRig(level, base, false, forward);
                createCrossingRig(level, base.offset(0, 0, RIG_GAP), true, reversed);
                rigReady = true;
            } catch (Exception e) {
                serverError.set("stack-order rig setup failed: " + e);
            }
        });
    }

    private void createCrossingRig(ServerLevel level, BlockPos base, boolean reverse, RigTrack track) {
        BlockPos x0 = RopeBenchSupport.fencePillar(level, base, 1, placedBlocks);
        BlockPos x1 = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), 1, placedBlocks);
        BlockPos z0 = RopeBenchSupport.fencePillar(
                level, base.offset(SPAN / 2, 0, -SPAN / 2), 1, placedBlocks);
        BlockPos z1 = RopeBenchSupport.fencePillar(
                level, base.offset(SPAN / 2, 0, SPAN / 2), 1, placedBlocks);
        if (reverse) {
            addConnection(level, z0, z1, track);
            addConnection(level, x0, x1, track);
        } else {
            addConnection(level, x0, x1, track);
            addConnection(level, z0, z1, track);
        }
    }

    private void addConnection(ServerLevel level, BlockPos a, BlockPos b, RigTrack track) {
        LeadConnection connection = RopeBenchSupport.connectFaces(level, a, Direction.UP, b, Direction.UP);
        track.ids.add(connection.id());
        createdConnections.add(connection.id());
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = rigBase.getX() + SPAN * 0.5D;
        double cy = rigBase.getY();
        double cz = rigBase.getZ() + RIG_GAP * 0.5D;
        viewPose = RopeBenchSupport.lookPose(cx - 13.0D, cy + 3.0D, cz + 13.0D, cx, cy, cz);
        holdView(context);
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
        holdView(context);
        tick++;
        meshLifecycle.sample(createdConnections);
        sampleRig(forward);
        sampleRig(reversed);
        if (tick == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-stack-order");
        }
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    private void sampleRig(RigTrack rig) {
        SuperLeadClientEvents.RopeSimBenchProbe a = SuperLeadClientEvents.probeSimForBench(rig.ids.get(0));
        SuperLeadClientEvents.RopeSimBenchProbe b = SuperLeadClientEvents.probeSimForBench(rig.ids.get(1));
        if (a == null || b == null) {
            rig.missingSamples++;
            return;
        }
        boolean bothRest = a.visuallyAtRest() && b.visuallyAtRest();
        if (bothRest && rig.firstBothRestTick < 0) {
            rig.firstBothRestTick = tick;
        }
        rig.lastSeparation = a.bellyDistanceTo(b);
        if (tick > MEASURE_TICKS - TAIL_TICKS) {
            rig.sampleTail(0, a);
            rig.sampleTail(1, b);
        }
        trace.append(tick).append(',').append(rig.label).append(',')
                .append(format(a.bellyX())).append(',').append(format(a.bellyY())).append(',')
                .append(format(a.bellyZ())).append(',').append(format(b.bellyX())).append(',')
                .append(format(b.bellyY())).append(',').append(format(b.bellyZ())).append(',')
                .append(a.visuallyAtRest() ? 1 : 0).append(',')
                .append(b.visuallyAtRest() ? 1 : 0).append('\n');
    }

    @Override
    public void verify(BenchClientContext context) throws Exception {
        java.nio.file.Files.writeString(
                context.resultDirectory().resolve("rope-stack-order-trace.csv"), trace.toString());
        failOnServerError();
        verifyRig(forward);
        verifyRig(reversed);
        long restDelta = Math.abs(forward.firstBothRestTick - reversed.firstBothRestTick);
        double amplitudeDelta = Math.abs(forward.tailAmplitude() - reversed.tailAmplitude());
        if (restDelta > MAX_REST_TICK_DELTA || amplitudeDelta > MAX_TAIL_AMPLITUDE_DELTA) {
            throw new AssertionError("creation order changes convergence quality: "
                    + forward.state() + " " + reversed.state());
        }
        meshLifecycle.requireAllActiveAtLeastOnce(createdConnections, "stack-order");
        context.metrics().record(ORDER_WORST_TAIL,
                Math.max(forward.tailAmplitude(), reversed.tailAmplitude()));
        context.metrics().record(ORDER_REST_DELTA, restDelta);
        context.metrics().record(ORDER_MIN_SEPARATION,
                Math.min(forward.lastSeparation, reversed.lastSeparation));
    }

    private static void verifyRig(RigTrack rig) {
        if (rig.missingSamples > 0) {
            throw new AssertionError(rig.label + " probe disappeared on " + rig.missingSamples + " ticks");
        }
        if (rig.firstBothRestTick < 0 || rig.firstBothRestTick > MAX_TICKS_TO_REST) {
            throw new AssertionError(rig.label + " never came to rest: " + rig.state());
        }
        if (rig.tailAmplitude() > TAIL_AMPLITUDE_LIMIT) {
            throw new AssertionError(rig.label + " keeps moving: " + rig.state());
        }
        if (!(rig.lastSeparation >= MIN_SEPARATION)) {
            throw new AssertionError(rig.label + " sleeps interpenetrating: " + rig.state());
        }
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

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static final class RigTrack {
        private final String label;
        private final List<UUID> ids = new ArrayList<>(2);
        private final double[][] min = {
            { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY },
            { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY }
        };
        private final double[][] max = {
            { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY },
            { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY }
        };
        private long firstBothRestTick = -1;
        private int missingSamples;
        private double lastSeparation = Double.NaN;

        private RigTrack(String label) {
            this.label = label;
        }

        private void sampleTail(int rope, SuperLeadClientEvents.RopeSimBenchProbe probe) {
            min[rope][0] = Math.min(min[rope][0], probe.bellyX());
            min[rope][1] = Math.min(min[rope][1], probe.bellyY());
            min[rope][2] = Math.min(min[rope][2], probe.bellyZ());
            max[rope][0] = Math.max(max[rope][0], probe.bellyX());
            max[rope][1] = Math.max(max[rope][1], probe.bellyY());
            max[rope][2] = Math.max(max[rope][2], probe.bellyZ());
        }

        private double tailAmplitude() {
            if (!Double.isFinite(min[0][0])) {
                return Double.POSITIVE_INFINITY;
            }
            double worst = 0.0D;
            for (int rope = 0; rope < 2; rope++) {
                double amplitude = Math.sqrt(
                        square(max[rope][0] - min[rope][0])
                                + square(max[rope][1] - min[rope][1])
                                + square(max[rope][2] - min[rope][2]));
                worst = Math.max(worst, amplitude);
            }
            return worst;
        }

        private String state() {
            return String.format(java.util.Locale.ROOT,
                    "%s[firstRest=%d tailAmp=%.5f separation=%.5f]",
                    label, firstBothRestTick, tailAmplitude(), lastSeparation);
        }

        private static double square(double value) {
            return value * value;
        }
    }
}