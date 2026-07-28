package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Multiple block/item attachments synchronized on one resting rope. */
final class RopeAttachmentClientScenario implements BenchClientScenario {
    private static final int SPAN = 10;
    private static final int EXPECTED_ATTACHMENTS = 4;
    private static final int MEASURE_TICKS = 400;
    private static final BenchMetricDescriptor ATTACHMENT_REST = new BenchMetricDescriptor(
            "super_lead.rope.attachment_ticks_to_rest", "ticks", MetricDirection.LOWER_IS_BETTER);

    private final List<UUID> ids = new CopyOnWriteArrayList<>();
    private final List<BlockPos> blocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean ready;
    private BlockPos base;
    private int tick;
    private long firstRest = -1;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("rope bench requires the integrated server");
        var pose = context.automation().pose();
        base = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(20, 0, -20);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos a = RopeBenchSupport.fencePillar(level, base, 1, blocks);
                BlockPos b = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), 1, blocks);
                LeadConnection connection = RopeBenchSupport.connectTops(level, a, b);
                ids.add(connection.id());
                ItemStack[] stacks = {
                        new ItemStack(Items.LANTERN), new ItemStack(Items.SOUL_LANTERN),
                        new ItemStack(Items.OAK_HANGING_SIGN), new ItemStack(Items.IRON_INGOT)
                };
                for (int i = 0; i < stacks.length; i++) {
                    if (!SuperLeadNetwork.addAttachment(level, connection, 0.2D + i * 0.2D, stacks[i], 1)) {
                        throw new IllegalStateException("attachment " + i + " was refused");
                    }
                }
                ready = true;
            } catch (Exception e) {
                serverError.set("attachment rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = base.getX() + SPAN * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx - 10.0D, base.getY() + 3.0D, base.getZ() + 12.0D, cx, base.getY() - 1.0D, base.getZ()));
        context.automation().setHudHidden(true);
        if (!ready || !context.environment().readiness().ready() || ids.isEmpty()) return BenchClientStepResult.CONTINUE;
        LeadConnection synced = SuperLeadNetwork.findConnectionById(context.minecraft().level, ids.get(0)).orElse(null);
        return synced != null && synced.attachments().size() == EXPECTED_ATTACHMENTS
                && SuperLeadClientEvents.probeSimForBench(ids.get(0)) != null
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(ids);
        LeadConnection synced = SuperLeadNetwork.findConnectionById(context.minecraft().level, ids.get(0))
                .orElseThrow(() -> new AssertionError("attachment rope disappeared"));
        if (synced.attachments().size() != EXPECTED_ATTACHMENTS
                || !synced.attachments().get(0).stack().is(Items.LANTERN)
                || !synced.attachments().get(1).stack().is(Items.SOUL_LANTERN)
                || !synced.attachments().get(2).stack().is(Items.OAK_HANGING_SIGN)
                || !synced.attachments().get(3).stack().is(Items.IRON_INGOT)) {
            throw new AssertionError("attachment payload changed: " + synced.attachments());
        }
        SuperLeadClientEvents.RopeSimBenchProbe probe = SuperLeadClientEvents.probeSimForBench(ids.get(0));
        if (probe == null) throw new AssertionError("attachment rope simulation disappeared");
        if (probe.visuallyAtRest() && firstRest < 0) firstRest = tick;
        if (tick == MEASURE_TICKS - 1) context.automation().captureScreenshot("rope-attachments");
        return tick >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        if (firstRest < 0 || firstRest > 320) throw new AssertionError("attachment rope never settled: " + firstRest);
        meshLifecycle.requireAllActiveAtLeastOnce(ids, "attachments");
        context.metrics().record(ATTACHMENT_REST, firstRest);
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

    private void failOnServerError() {
        if (serverError.get() != null) throw new IllegalStateException(serverError.get());
    }
}