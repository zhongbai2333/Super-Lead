package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Observes the dedicated-server cadence rig from a separate remote client JVM. */
final class RemoteCadenceClientScenario implements BenchClientScenario {
    private static final int MEASURE_TICKS = 160;
    private static final int MIN_RENDER_SAMPLES = 30;
    private static final BenchMetricDescriptor RENDER_SAMPLES = new BenchMetricDescriptor(
            "super_lead.paired.remote_render_samples", "frames", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor PUBLICATIONS = new BenchMetricDescriptor(
            "super_lead.paired.remote_physics_publications", "count", MetricDirection.HIGHER_IS_BETTER);

    private final List<UUID> connectionIds = new ArrayList<>();
    private int measuredTicks;
    private BenchClientPose viewPose;

    @Override
    public void setup(BenchClientContext context) {
        context.automation().stopMovement();
        context.automation().setHudHidden(true);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        if (!context.environment().readiness().ready()) return BenchClientStepResult.CONTINUE;
        discoverConnections(context);
        if (connectionIds.size() != ServerCadenceRigScenario.CONNECTIONS) {
            return BenchClientStepResult.CONTINUE;
        }
        for (UUID id : connectionIds) {
            if (SuperLeadClientEvents.probeSimForBench(id) == null) return BenchClientStepResult.CONTINUE;
        }
        viewPose = computeViewPose(context.level(), connectionIds.get(0));
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        holdView(context);
        SuperLeadClientEvents.resetVisualCadenceForBench(connectionIds);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        holdView(context);
        measuredTicks++;
        return measuredTicks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        SuperLeadClientEvents.RopeVisualCadenceBenchProbe aggregate = aggregateCadence();
        long minimumSamples = (long) connectionIds.size() * MIN_RENDER_SAMPLES;
        if (aggregate.renderSamples() < minimumSamples) {
            throw new AssertionError("remote dense rig was not dynamically rendered often enough: samples="
                    + aggregate.renderSamples() + " expectedAtLeast=" + minimumSamples);
        }
        if (aggregate.physicsPublications() == 0L || aggregate.movingSamples() == 0L) {
            throw new AssertionError("remote cadence observed no animated publications: " + aggregate);
        }
        context.metrics().record(RENDER_SAMPLES, aggregate.renderSamples());
        context.metrics().record(PUBLICATIONS, aggregate.physicsPublications());
    }

    private void discoverConnections(BenchClientContext context) {
        Level level = context.level();
        connectionIds.clear();
        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            connectionIds.add(connection.id());
        }
        connectionIds.sort(Comparator.naturalOrder());
        if (connectionIds.size() > ServerCadenceRigScenario.CONNECTIONS) {
            connectionIds.subList(ServerCadenceRigScenario.CONNECTIONS, connectionIds.size()).clear();
        }
    }

    private BenchClientPose computeViewPose(Level level, UUID id) {
        LeadConnection connection = SuperLeadNetwork.findConnectionById(level, id).orElseThrow();
        BlockPos from = connection.from().pos();
        double x = from.getX() + 8.0D;
        double y = from.getY() + 12.0D;
        double z = from.getZ() + 32.0D;
        return RopeBenchSupport.lookPose(x, y, z, from.getX() + 8.0D, from.getY() + 4.0D, from.getZ() + 4.0D);
    }

    private void holdView(BenchClientContext context) {
        context.automation().stopMovement();
        if (viewPose != null) context.automation().setPose(viewPose);
        context.automation().setHudHidden(true);
    }

    private SuperLeadClientEvents.RopeVisualCadenceBenchProbe aggregateCadence() {
        long renders = 0, moving = 0, publications = 0, gapFrames = 0;
        double gapMs = 0, staleMs = 0, step = 0, distance = 0;
        int stale = 0, maxStale = 0;
        for (UUID id : connectionIds) {
            var probe = SuperLeadClientEvents.probeVisualCadenceForBench(id);
            if (probe == null) continue;
            renders += probe.renderSamples(); moving += probe.movingSamples();
            publications += probe.physicsPublications(); stale = Math.max(stale, probe.currentStaleFrames());
            maxStale = Math.max(maxStale, probe.maxStaleFrames()); gapFrames = Math.max(gapFrames, probe.maxPublicationGapFrames());
            gapMs = Math.max(gapMs, probe.maxPublicationGapMs()); staleMs = Math.max(staleMs, probe.maxStaleMs());
            step = Math.max(step, probe.maxRenderedStep()); distance += probe.totalRenderedDistance();
        }
        return new SuperLeadClientEvents.RopeVisualCadenceBenchProbe(
                renders, moving, publications, stale, maxStale, (int) gapFrames, gapMs, staleMs, step, distance);
    }
}
