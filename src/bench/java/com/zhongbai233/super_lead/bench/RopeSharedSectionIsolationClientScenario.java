package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Verifies that rebuilding one rope cannot evict bystanders sharing its section. */
final class RopeSharedSectionIsolationClientScenario implements BenchClientScenario {
    private static final int SPAN = 8;
    private static final int STABLE_TICKS = 20;
    private static final int MAX_REBUILD_TICKS = 240;
    private static final BenchMetricDescriptor BYSTANDER_EXITS = new BenchMetricDescriptor(
            "super_lead.rope.shared_section_bystander_exits", "count", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private volatile boolean rigReady;
    private BlockPos rigBase;
    private int stableTicks;
    private int rebuildTicks;
    private int bystanderExits;
    private boolean invalidated;
    private boolean sawPendingSharedReplacement;
    private boolean sawReplacementComplete;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        var pose = context.automation().pose();
        BlockPos near = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(40, 0, 0);
        // Keep all three complete spans comfortably inside one x/z section.
        rigBase = new BlockPos(
                Math.floorDiv(near.getX(), 16) * 16 + 3,
                Math.floorDiv(near.getY(), 16) * 16 + 4,
                Math.floorDiv(near.getZ(), 16) * 16 + 3);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                for (int row = 0; row < 3; row++) {
                    BlockPos rowBase = base.offset(0, 0, row * 3);
                    BlockPos a = RopeBenchSupport.fencePillar(level, rowBase, 2, placedBlocks);
                    BlockPos b = RopeBenchSupport.fencePillar(level, rowBase.offset(SPAN, 0, 0), 2, placedBlocks);
                    LeadConnection connection = RopeBenchSupport.connectTops(level, a, b);
                    createdConnections.add(connection.id());
                }
                rigReady = true;
            } catch (Exception e) {
                serverError.set("shared section rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        setPose(context);
        if (!rigReady || !context.environment().readiness().ready() || createdConnections.size() != 3) {
            return BenchClientStepResult.CONTINUE;
        }
        for (UUID id : createdConnections) {
            if (SuperLeadClientEvents.probeChunkMeshForBench(id) == null) {
                return BenchClientStepResult.CONTINUE;
            }
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        setPose(context);
        SuperLeadClientEvents.RopeChunkMeshBenchProbe[] probes = probes();
        if (!invalidated) {
            boolean allActive = java.util.Arrays.stream(probes).allMatch(p -> p.meshActive() && !p.refining());
            stableTicks = allActive ? stableTicks + 1 : 0;
            if (stableTicks < STABLE_TICKS) {
                return BenchClientStepResult.CONTINUE;
            }
            StaticRopeChunkRegistry.get().invalidateConnection(
                    context.minecraft().level, createdConnections.get(0));
            invalidated = true;
            probes = probes();
        }

        rebuildTicks++;
        for (int i = 1; i < probes.length; i++) {
            SuperLeadClientEvents.RopeChunkMeshBenchProbe bystander = probes[i];
            if (!bystander.meshActive()) {
                bystanderExits++;
                throw new AssertionError("shared-section bystander " + i
                        + " left mesh during another rope's rebuild: " + bystander);
            }
            sawPendingSharedReplacement |= bystander.meshAccepted()
                    && bystander.acceptedSections() < bystander.sectionCount();
        }
        SuperLeadClientEvents.RopeChunkMeshBenchProbe target = probes[0];
        sawReplacementComplete |= target.meshActive()
                && java.util.Arrays.stream(probes)
                        .allMatch(p -> p.acceptedSections() == p.sectionCount());
        if (sawPendingSharedReplacement && sawReplacementComplete) {
            context.automation().captureScreenshot("rope-shared-section-isolation");
            return BenchClientStepResult.COMPLETE;
        }
        if (rebuildTicks > MAX_REBUILD_TICKS) {
            throw new AssertionError("shared section replacement timed out: pending="
                    + sawPendingSharedReplacement + " complete=" + sawReplacementComplete
                    + " target=" + target);
        }
        return BenchClientStepResult.CONTINUE;
    }

    private SuperLeadClientEvents.RopeChunkMeshBenchProbe[] probes() {
        SuperLeadClientEvents.RopeChunkMeshBenchProbe[] probes =
                new SuperLeadClientEvents.RopeChunkMeshBenchProbe[createdConnections.size()];
        for (int i = 0; i < probes.length; i++) {
            probes[i] = SuperLeadClientEvents.probeChunkMeshForBench(createdConnections.get(i));
            if (probes[i] == null) {
                throw new AssertionError("shared-section rope " + i + " disappeared");
            }
        }
        return probes;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        if (!invalidated || !sawPendingSharedReplacement || !sawReplacementComplete || bystanderExits != 0) {
            throw new AssertionError("incomplete shared-section isolation: invalidated=" + invalidated
                    + " pending=" + sawPendingSharedReplacement + " complete=" + sawReplacementComplete
                    + " bystanderExits=" + bystanderExits);
        }
        context.metrics().record(BYSTANDER_EXITS, bystanderExits);
    }

    private void setPose(BenchClientContext context) {
        context.automation().stopMovement();
        context.automation().setHudHidden(true);
        double cx = rigBase.getX() + SPAN * 0.5D;
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx, rigBase.getY() + 4.0D, rigBase.getZ() + 14.0D,
                cx, rigBase.getY() + 1.5D, rigBase.getZ() + 3.0D));
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