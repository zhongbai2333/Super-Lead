package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Dense-scene regression for smooth rope animation when frame rate and solver
 * cost remain healthy. The 53-connection rig mirrors the reported production
 * scene size and runs through the authoritative integrated server sync path.
 */
final class RopeAnimationCadenceClientScenario implements BenchClientScenario {
    private static final int CONNECTIONS = 53;
    private static final int COLUMNS = 9;
    private static final int SPAN = 8;
    private static final int MEASURE_TICKS = 160;
    private static final int MIN_RENDER_SAMPLES = 30;

    private static final BenchMetricDescriptor RENDER_SAMPLES = new BenchMetricDescriptor(
            "super_lead.visual.render_samples", "frames", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor MOVING_RATIO = new BenchMetricDescriptor(
            "super_lead.visual.moving_frame_ratio", "ratio", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor PUBLICATIONS = new BenchMetricDescriptor(
            "super_lead.visual.physics_publications", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor PUBLICATION_GAP = new BenchMetricDescriptor(
            "super_lead.visual.max_publication_gap", "ms", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor STALE_FRAMES = new BenchMetricDescriptor(
            "super_lead.visual.max_stale", "ms", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor MAX_RENDERED_STEP = new BenchMetricDescriptor(
            "super_lead.visual.max_rendered_step", "blocks", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private volatile boolean rigReady;
    private BlockPos rigBase;
    private BenchClientPose viewPose;
    private int measuredTicks;
    private final StringBuilder trace = new StringBuilder(
            "tick,renderSamples,movingSamples,publications,maxPublicationGapFrames,maxStaleFrames,maxPublicationGapMs,maxStaleMs,maxRenderedStep,totalDistance\n");

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope cadence bench requires the integrated server");
        }
        BenchClientPose pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(-16, 0, 8);
        BlockPos base = rigBase;
        server.execute(() -> createRig(server.overworld(), base));
    }

    private void createRig(ServerLevel level, BlockPos base) {
        try {
            for (int index = 0; index < CONNECTIONS; index++) {
                int column = index % COLUMNS;
                int row = index / COLUMNS;
                BlockPos a = RopeBenchSupport.fencePillar(
                        level, base.offset(column * 2, row * 3, 0), 1, placedBlocks);
                BlockPos b = RopeBenchSupport.fencePillar(
                        level, base.offset(column * 2, row * 3, SPAN), 1, placedBlocks);
                LeadConnection connection = RopeBenchSupport.connectTops(level, a, b);
                createdConnections.add(connection.id());
            }
            rigReady = true;
        } catch (Exception e) {
            serverError.set("animation cadence rig setup failed: " + e);
        }
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        holdView(context);
        if (!rigReady || !context.environment().readiness().ready()
                || createdConnections.size() != CONNECTIONS) {
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
    public BenchClientStepResult warmup(BenchClientContext context) {
        holdView(context);
        SuperLeadClientEvents.resetVisualCadenceForBench(createdConnections);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        holdView(context);
        measuredTicks++;
        SuperLeadClientEvents.RopeVisualCadenceBenchProbe aggregate = aggregateCadence();
        trace.append(measuredTicks).append(',')
                .append(aggregate.renderSamples()).append(',')
                .append(aggregate.movingSamples()).append(',')
                .append(aggregate.physicsPublications()).append(',')
                .append(aggregate.maxPublicationGapFrames()).append(',')
                .append(aggregate.maxStaleFrames()).append(',')
                .append(aggregate.maxPublicationGapMs()).append(',')
                .append(aggregate.maxStaleMs()).append(',')
                .append(aggregate.maxRenderedStep()).append(',')
                .append(aggregate.totalRenderedDistance()).append('\n');
        if (measuredTicks == 40) {
            context.automation().captureScreenshot("rope-animation-cadence-dynamic");
        }
        return measuredTicks >= MEASURE_TICKS
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        SuperLeadClientEvents.RopeVisualCadenceBenchProbe aggregate = aggregateCadence();
        long minimumSamples = (long) CONNECTIONS * MIN_RENDER_SAMPLES;
        if (aggregate.renderSamples() < minimumSamples) {
            throw new AssertionError("dense rope rig was not dynamically rendered often enough: samples="
                    + aggregate.renderSamples() + " expectedAtLeast=" + minimumSamples);
        }
        if (aggregate.physicsPublications() == 0L || aggregate.movingSamples() == 0L) {
            throw new AssertionError("cadence probe observed no animated publications: " + aggregate);
        }
        double movingRatio = aggregate.movingSamples() / (double) Math.max(1L, aggregate.renderSamples());
        context.metrics().record(RENDER_SAMPLES, aggregate.renderSamples());
        context.metrics().record(MOVING_RATIO, movingRatio);
        context.metrics().record(PUBLICATIONS, aggregate.physicsPublications());
        context.metrics().record(PUBLICATION_GAP, aggregate.maxPublicationGapMs());
        context.metrics().record(STALE_FRAMES, aggregate.maxStaleMs());
        context.metrics().record(MAX_RENDERED_STEP, aggregate.maxRenderedStep());
        try {
            Files.writeString(context.resultDirectory().resolve("rope-animation-cadence-trace.csv"), trace);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write animation cadence trace", e);
        }
    }

    private SuperLeadClientEvents.RopeVisualCadenceBenchProbe aggregateCadence() {
        long renderSamples = 0L;
        long movingSamples = 0L;
        long publications = 0L;
        int stale = 0;
        int maxStale = 0;
        int publicationGap = 0;
        double publicationGapMs = 0.0D;
        double staleMs = 0.0D;
        double maxStep = 0.0D;
        double distance = 0.0D;
        for (UUID id : createdConnections) {
            SuperLeadClientEvents.RopeVisualCadenceBenchProbe probe =
                    SuperLeadClientEvents.probeVisualCadenceForBench(id);
            if (probe == null) {
                continue;
            }
            renderSamples += probe.renderSamples();
            movingSamples += probe.movingSamples();
            publications += probe.physicsPublications();
            stale = Math.max(stale, probe.currentStaleFrames());
            maxStale = Math.max(maxStale, probe.maxStaleFrames());
            publicationGap = Math.max(publicationGap, probe.maxPublicationGapFrames());
            publicationGapMs = Math.max(publicationGapMs, probe.maxPublicationGapMs());
            staleMs = Math.max(staleMs, probe.maxStaleMs());
            maxStep = Math.max(maxStep, probe.maxRenderedStep());
            distance += probe.totalRenderedDistance();
        }
        return new SuperLeadClientEvents.RopeVisualCadenceBenchProbe(
                renderSamples, movingSamples, publications, stale, maxStale,
                publicationGap, publicationGapMs, staleMs, maxStep, distance);
    }

    private void holdView(BenchClientContext context) {
        if (viewPose == null && rigBase != null) {
            double targetX = rigBase.getX() + (COLUMNS - 1);
            double targetY = rigBase.getY() + 8.0D;
            double targetZ = rigBase.getZ() + SPAN * 0.5D;
            viewPose = RopeBenchSupport.lookPose(targetX, targetY + 4.0D, targetZ + 32.0D,
                    targetX, targetY, targetZ);
        }
        context.automation().stopMovement();
        if (viewPose != null) {
            context.automation().setPose(viewPose);
        }
        context.automation().setHudHidden(true);
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
}