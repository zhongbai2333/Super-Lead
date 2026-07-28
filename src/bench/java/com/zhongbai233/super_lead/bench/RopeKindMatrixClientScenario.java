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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Physical and synchronization matrix for every built-in rope kind. */
final class RopeKindMatrixClientScenario implements BenchClientScenario {
    private static final int SPAN = 8;
    private static final int ROW_GAP = 3;
    private static final int MEASURE_TICKS = 400;
    private static final int MAX_REST_TICK = 320;
    private static final BenchMetricDescriptor KIND_WORST_REST = new BenchMetricDescriptor(
            "super_lead.rope.kind_worst_rest", "ticks", MetricDirection.LOWER_IS_BETTER);

    private final Map<LeadKind, UUID> ids = new EnumMap<>(LeadKind.class);
    private final List<BlockPos> blocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean ready;
    private BlockPos base;
    private int tick;
    private long worstRest;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("rope bench requires the integrated server");
        var pose = context.automation().pose();
        base = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(-20, 0, 24);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                int row = 0;
                for (LeadKind kind : LeadKind.values()) {
                    BlockPos a = RopeBenchSupport.fencePillar(level, base.offset(0, 0, row * ROW_GAP), 1, blocks);
                    BlockPos b = RopeBenchSupport.fencePillar(level, a.offset(SPAN, 0, 0), 1, blocks);
                    ids.put(kind, RopeBenchSupport.connectTops(level, a, b, kind, 1).id());
                    row++;
                }
                ready = true;
            } catch (Exception e) {
                serverError.set("kind matrix setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = base.getX() + SPAN * 0.5D;
        double cz = base.getZ() + (LeadKind.values().length - 1) * ROW_GAP * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx - 14.0D, base.getY() + 8.0D, cz + 18.0D, cx, base.getY(), cz));
        context.automation().setHudHidden(true);
        if (!ready || !context.environment().readiness().ready() || ids.size() != LeadKind.values().length) {
            return BenchClientStepResult.CONTINUE;
        }
        for (UUID id : ids.values()) if (SuperLeadClientEvents.probeSimForBench(id) == null) return BenchClientStepResult.CONTINUE;
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(ids.values());
        boolean allRest = true;
        for (Map.Entry<LeadKind, UUID> entry : ids.entrySet()) {
            LeadConnection connection = SuperLeadNetwork.findConnectionById(context.minecraft().level, entry.getValue())
                    .orElseThrow(() -> new AssertionError(entry.getKey() + " connection disappeared"));
            if (connection.kind() != entry.getKey()) {
                throw new AssertionError("kind desynced: expected=" + entry.getKey() + " actual=" + connection.kind());
            }
            SuperLeadClientEvents.RopeSimBenchProbe probe = SuperLeadClientEvents.probeSimForBench(entry.getValue());
            if (probe == null || !Double.isFinite(probe.bellyY())) {
                throw new AssertionError(entry.getKey() + " simulation missing or non-finite");
            }
            allRest &= probe.visuallyAtRest();
        }
        if (allRest && worstRest == 0) worstRest = tick;
        if (tick == MEASURE_TICKS - 1) context.automation().captureScreenshot("rope-kind-matrix");
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        if (worstRest == 0 || worstRest > MAX_REST_TICK) {
            throw new AssertionError("not all rope kinds settled: tick=" + worstRest);
        }
        meshLifecycle.requireAllActiveAtLeastOnce(ids.values(), "kind-matrix");
        context.metrics().record(KIND_WORST_REST, worstRest);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            Set<UUID> remove = Set.copyOf(ids.values());
            List<BlockPos> placed = List.copyOf(blocks);
            server.execute(() -> RopeBenchSupport.teardown(server.overworld(), remove, placed));
        }
    }

    private void failOnServerError() {
        if (serverError.get() != null) throw new IllegalStateException(serverError.get());
    }
}