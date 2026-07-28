package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Long rope that exceeds the one-unit placement range and crosses chunks. */
final class RopeLongSpanClientScenario implements BenchClientScenario {
    private static final int LENGTH_UNITS = 3;
    private static final int MEASURE_TICKS = 500;
    private static final int MAX_REST_TICK = 420;
    private static final int TAIL_TICKS = 100;
    private static final double TAIL_LIMIT = 0.015D;

    private static final BenchMetricDescriptor LONG_NODES = new BenchMetricDescriptor(
            "super_lead.rope.long_nodes", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor LONG_REST = new BenchMetricDescriptor(
            "super_lead.rope.long_ticks_to_rest", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor LONG_TAIL = new BenchMetricDescriptor(
            "super_lead.rope.long_tail_amplitude", "blocks", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> ids = new CopyOnWriteArrayList<>();
    private final List<BlockPos> blocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean ready;
    private BlockPos base;
    private int span;
    private int tick;
    private long firstRest = -1;
    private int nodeCount;
    private double tailMin = Double.POSITIVE_INFINITY;
    private double tailMax = Double.NEGATIVE_INFINITY;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = requireServer(context);
        double oneUnit = SuperLeadNetwork.maxLeashDistanceForUnits(1);
        span = Math.max(5, (int) Math.floor(oneUnit * 2.25D));
        span = Math.min(span, (int) Math.floor(SuperLeadNetwork.maxLeashDistanceForUnits(LENGTH_UNITS) - 1.0D));
        if (!(span > oneUnit)) {
            throw new IllegalStateException("configured rope range cannot build an extended-span bench: " + oneUnit);
        }
        var pose = context.automation().pose();
        base = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(0, 0, 32);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos a = RopeBenchSupport.fencePillar(level, base, 1, blocks);
                BlockPos b = RopeBenchSupport.fencePillar(level, base.offset(span, 0, 0), 1, blocks);
                LeadConnection connection = RopeBenchSupport.connectTops(
                        level, a, b, LeadKind.NORMAL, LENGTH_UNITS);
                ids.add(connection.id());
                ready = true;
            } catch (Exception e) {
                serverError.set("long-span rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = base.getX() + span * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx, base.getY() + 4.0D, base.getZ() + Math.max(14.0D, span * 0.55D),
                cx, base.getY(), base.getZ()));
        context.automation().setHudHidden(true);
        return ready && context.environment().readiness().ready() && probe() != null
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(ids);
        SuperLeadClientEvents.RopeSimBenchProbe probe = probe();
        if (probe == null) {
            throw new AssertionError("extended rope disappeared at tick " + tick);
        }
        LeadConnection synced = SuperLeadNetwork.findConnectionById(context.minecraft().level, ids.get(0))
                .orElseThrow(() -> new AssertionError("extended connection disappeared at tick " + tick));
        if (synced.lengthUnits() != LENGTH_UNITS) {
            throw new AssertionError("length units desynced: " + synced.lengthUnits());
        }
        nodeCount = probe.nodeCount();
        if (probe.visuallyAtRest() && firstRest < 0) {
            firstRest = tick;
        }
        if (tick > MEASURE_TICKS - TAIL_TICKS) {
            tailMin = Math.min(tailMin, probe.bellyY());
            tailMax = Math.max(tailMax, probe.bellyY());
        }
        if (tick == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-long-span");
        }
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        double amplitude = tailMax - tailMin;
        if (firstRest < 0 || firstRest > MAX_REST_TICK || amplitude > TAIL_LIMIT) {
            throw new AssertionError("long rope did not settle: span=" + span + " firstRest=" + firstRest
                    + " tail=" + amplitude + " nodes=" + nodeCount);
        }
        if (nodeCount < 8) {
            throw new AssertionError("long rope topology unexpectedly coarse: " + nodeCount);
        }
        meshLifecycle.requireAllActiveAtLeastOnce(ids, "long-span");
        context.metrics().record(LONG_NODES, nodeCount);
        context.metrics().record(LONG_REST, firstRest);
        context.metrics().record(LONG_TAIL, amplitude);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            Set<UUID> remove = Set.copyOf(ids);
            List<BlockPos> placed = List.copyOf(blocks);
            server.execute(() -> RopeBenchSupport.teardown(server.overworld(), remove, placed));
        }
    }

    private SuperLeadClientEvents.RopeSimBenchProbe probe() {
        return ids.isEmpty() ? null : SuperLeadClientEvents.probeSimForBench(ids.get(0));
    }

    private static MinecraftServer requireServer(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("rope bench requires the integrated server");
        return server;
    }

    private void failOnServerError() {
        if (serverError.get() != null) throw new IllegalStateException(serverError.get());
    }
}