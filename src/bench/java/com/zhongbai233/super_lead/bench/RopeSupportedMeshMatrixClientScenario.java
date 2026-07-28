package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
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
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Chunk-mesh regression for block-supported stacks and terrain-draped ropes. */
final class RopeSupportedMeshMatrixClientScenario implements BenchClientScenario {
    private static final int SPAN = 6;
    private static final int MEASURE_TICKS = 500;
    private static final int MIN_MESH_TICKS = 40;
    // Entering fine topology can legitimately retire the first coarse mesh once
    // (and section compilation can expose one bounded handoff). Repeated exits
    // after that are the real work-environment churn this scenario guards against.
    private static final int MAX_HANDOFF_EXITS = 2;
    private static final BenchMetricDescriptor MIN_MESH_ACCEPTED_TICKS = new BenchMetricDescriptor(
            "super_lead.rope.supported_mesh_min_accepted_ticks", "ticks", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor TERRAIN_ROPES = new BenchMetricDescriptor(
            "super_lead.rope.supported_mesh_terrain_ropes", "count", MetricDirection.HIGHER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private volatile boolean rigReady;
    private BlockPos rigBase;
    private int tick;
    private final int[] meshTicks = new int[4];
    private final boolean[] meshWasActive = new boolean[4];
    private final int[] meshExitCount = new int[4];
    private final String[] lastMeshExit = { "none", "none", "none", "none" };
    private final boolean[] terrainSeen = new boolean[4];
    private final double[] minBelly = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        var pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(24, 0, 0);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos stackA = RopeBenchSupport.fencePillar(level, base, 4, placedBlocks);
                BlockPos stackB = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), 4, placedBlocks);
                // A 3x3 full-block table catches all three coincident ropes at the belly.
                for (int x = 2; x <= 4; x++) {
                    for (int z = -1; z <= 1; z++) {
                        place(level, base.offset(x, 2, z));
                    }
                }
                for (int i = 0; i < 3; i++) {
                    LeadConnection connection = RopeBenchSupport.connectFaces(level,
                            stackA, Direction.UP, stackB, Direction.UP);
                    createdConnections.add(connection.id());
                }

                BlockPos drapeBase = base.offset(0, 0, 6);
                BlockPos drapeA = RopeBenchSupport.fencePillar(level, drapeBase, 4, placedBlocks);
                BlockPos drapeB = RopeBenchSupport.fencePillar(level, drapeBase.offset(SPAN, 0, 0), 4, placedBlocks);
                // Two-block-high terrain ridge: the rope must drape over its top.
                for (int y = 1; y <= 2; y++) {
                    for (int z = -1; z <= 1; z++) {
                        place(level, drapeBase.offset(SPAN / 2, y, z));
                    }
                }
                createdConnections.add(RopeBenchSupport.connectFaces(level,
                        drapeA, Direction.UP, drapeB, Direction.UP).id());
                rigReady = true;
            } catch (Exception e) {
                serverError.set("supported mesh rig setup failed: " + e);
            }
        });
    }

    private void place(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        placedBlocks.add(pos);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = rigBase.getX() + SPAN * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx, rigBase.getY() + 5.0D, rigBase.getZ() + 14.0D,
                cx, rigBase.getY() + 3.0D, rigBase.getZ() + 3.0D));
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
        for (int i = 0; i < createdConnections.size(); i++) {
            UUID id = createdConnections.get(i);
            SuperLeadClientEvents.RopeSimBenchProbe sim = SuperLeadClientEvents.probeSimForBench(id);
            SuperLeadClientEvents.RopeChunkMeshBenchProbe mesh = SuperLeadClientEvents.probeChunkMeshForBench(id);
            if (sim == null || mesh == null) {
                throw new AssertionError("supported rope " + i + " disappeared at tick " + tick);
            }
            minBelly[i] = Math.min(minBelly[i], sim.bellyY());
            terrainSeen[i] |= sim.terrainNearby();
            if (mesh.meshActive()) {
                meshTicks[i]++;
                meshWasActive[i] = true;
            } else if (meshWasActive[i]) {
                meshExitCount[i]++;
                meshWasActive[i] = false;
                lastMeshExit[i] = "measureTick=" + tick
                        + " claimed=" + mesh.claimed()
                        + " accepted=" + mesh.meshAccepted()
                        + " sections=" + mesh.acceptedSections() + "/" + mesh.sectionCount()
                        + " awaiting=" + mesh.awaitingSections()
                        + " pending=" + mesh.pendingDirtySections()
                        + " rest=" + mesh.visuallyAtRest()
                        + " terrain=" + mesh.terrainNearby()
                        + " refining=" + mesh.refining()
                        + " hold=" + mesh.lastDynamicHoldReason()
                        + " holdTick=" + mesh.lastDynamicHoldTick()
                        + " holdUntil=" + mesh.dynamicHoldUntilTick()
                        + " holdCount=" + mesh.dynamicHoldCount();
            }
        }
        if (tick == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-supported-mesh-matrix");
        }
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        int leastMeshTicks = Integer.MAX_VALUE;
        int terrainCount = 0;
        // Both support surfaces top out at rigBase.y + 3. Rope centers may approach
        // within their radius but must never fall through the full blocks.
        double supportTop = rigBase.getY() + 3.0D;
        for (int i = 0; i < createdConnections.size(); i++) {
            leastMeshTicks = Math.min(leastMeshTicks, meshTicks[i]);
            if (terrainSeen[i]) {
                terrainCount++;
            }
            if (!terrainSeen[i]) {
                throw new AssertionError("supported rope " + i + " never observed nearby terrain");
            }
            if (minBelly[i] < supportTop - 0.10D) {
                throw new AssertionError("supported rope " + i + " fell through terrain: belly="
                        + minBelly[i] + " supportTop=" + supportTop);
            }
            if (meshTicks[i] < MIN_MESH_TICKS) {
                throw new AssertionError("supported rope " + i
                        + " never completed terrain-to-chunk-mesh handoff: meshTicks=" + meshTicks[i]);
            }
            if (meshExitCount[i] > MAX_HANDOFF_EXITS) {
                throw new AssertionError("supported rope " + i
                        + " repeatedly left chunk mesh without an external disturbance: exits="
                        + meshExitCount[i] + " meshTicks=" + meshTicks[i]
                        + " lastExit=[" + lastMeshExit[i] + "]");
            }
        }
        context.metrics().record(MIN_MESH_ACCEPTED_TICKS, leastMeshTicks);
        context.metrics().record(TERRAIN_ROPES, terrainCount);
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