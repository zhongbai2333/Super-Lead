package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Exercises LOD0/1/2/3 mesh visibility and coarse-to-fine physics handoff. */
final class RopeLodMeshHandoffClientScenario implements BenchClientScenario {
    private static final int SPAN = 8;
    private static final int MAX_STAGE_TICKS = 240;
    private static final double[] DISTANCES = { 6.0D, 12.0D, 30.0D, 60.0D, 6.0D };
    private static final int[] EXPECTED_LODS = { 0, 1, 2, 3, 0 };
    private static final BenchMetricDescriptor LOD_BANDS_SEEN = new BenchMetricDescriptor(
            "super_lead.rope.lod_mesh_bands_seen", "count", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor REENTRY_TICKS = new BenchMetricDescriptor(
            "super_lead.rope.lod_mesh_reentry_ticks", "ticks", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private volatile boolean rigReady;
    private BlockPos rigBase;
    private int stage;
    private int stageTicks;
    private int bandsSeen;
    private int reentryTicks = -1;
    private int baselineVisualStripes = -1;
    private boolean sawCoarseMesh;
    private boolean sawRefinement;
    private boolean sawDynamicAfterFarMesh;
    private boolean sawFineMeshAgain;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        var pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(-32, 0, 16);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos a = RopeBenchSupport.fencePillar(level, base, 2, placedBlocks);
                BlockPos b = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), 2, placedBlocks);
                LeadConnection connection = RopeBenchSupport.connectTops(level, a, b);
                createdConnections.add(connection.id());
                rigReady = true;
            } catch (Exception e) {
                serverError.set("LOD mesh rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        setStagePose(context);
        context.automation().setHudHidden(true);
        if (!rigReady || !context.environment().readiness().ready() || createdConnections.isEmpty()) {
            return BenchClientStepResult.CONTINUE;
        }
        return SuperLeadClientEvents.probeSimForBench(createdConnections.get(0)) == null
                ? BenchClientStepResult.CONTINUE : BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        setStagePose(context);
        stageTicks++;
        UUID id = createdConnections.get(0);
        SuperLeadClientEvents.RopeChunkMeshBenchProbe probe = SuperLeadClientEvents.probeChunkMeshForBench(id);
        if (probe == null) {
            throw new AssertionError("LOD lifecycle probe disappeared at stage " + stage);
        }
        int expectedLod = EXPECTED_LODS[stage];
        boolean inBand = probe.renderLod() == expectedLod;
        if (stage == 3 && probe.meshActive() && probe.coarseTopology()) {
            sawCoarseMesh = true;
        }
        if (stage == 4) {
            sawRefinement |= probe.refining();
            sawDynamicAfterFarMesh |= !probe.meshActive() && probe.hasSimulation();
            if (probe.meshActive() && !probe.coarseTopology() && !probe.refining()) {
                sawFineMeshAgain = true;
                reentryTicks = stageTicks;
            }
        }
        boolean stageComplete = switch (stage) {
            case 0, 1, 2 -> inBand && probe.meshActive();
            case 3 -> inBand && sawCoarseMesh;
            case 4 -> inBand && sawRefinement && sawDynamicAfterFarMesh && sawFineMeshAgain;
            default -> false;
        };
        if (stageComplete) {
            if (probe.visualStripeCount() <= 0) {
                throw new AssertionError("LOD stage " + stage + " has no visual stripe coordinates: " + probe);
            }
            if (baselineVisualStripes < 0) {
                baselineVisualStripes = probe.visualStripeCount();
            } else if (probe.visualStripeCount() != baselineVisualStripes) {
                throw new AssertionError("LOD changed rope UV density at stage " + stage
                        + ": baselineStripes=" + baselineVisualStripes
                        + " actualStripes=" + probe.visualStripeCount()
                        + " physicsNodes=" + probe.nodeCount() + " probe=" + probe);
            }
            bandsSeen++;
            if (stage == DISTANCES.length - 1) {
                context.automation().captureScreenshot("rope-lod-mesh-handoff");
                return BenchClientStepResult.COMPLETE;
            }
            stage++;
            stageTicks = 0;
            setStagePose(context);
        } else if (stageTicks > MAX_STAGE_TICKS) {
            throw new AssertionError("LOD stage " + stage + " timed out: probe=" + probe);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        if (bandsSeen != DISTANCES.length || !sawCoarseMesh || !sawRefinement
                || !sawDynamicAfterFarMesh || !sawFineMeshAgain || baselineVisualStripes <= 0) {
            throw new AssertionError("incomplete LOD mesh lifecycle: bands=" + bandsSeen
                    + " coarse=" + sawCoarseMesh + " refine=" + sawRefinement
                    + " dynamic=" + sawDynamicAfterFarMesh + " fineMesh=" + sawFineMeshAgain
                    + " visualStripes=" + baselineVisualStripes);
        }
        context.metrics().record(LOD_BANDS_SEEN, bandsSeen);
        context.metrics().record(REENTRY_TICKS, reentryTicks);
    }

    private void setStagePose(BenchClientContext context) {
        context.automation().stopMovement();
        double cx = rigBase.getX() + SPAN * 0.5D;
        double cy = rigBase.getY() + 1.5D;
        double distance = DISTANCES[Math.min(stage, DISTANCES.length - 1)];
        BenchClientPose pose = RopeBenchSupport.lookPose(cx, cy + 1.0D, rigBase.getZ() + distance,
                cx, cy, rigBase.getZ());
        context.automation().setPose(pose);
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