package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** End-to-end ITEM rope moving iron ingots between two vanilla barrels. */
final class RopeItemWorkClientScenario implements BenchClientScenario {
    private static final int SPAN = 10;
    private static final int TOTAL_ITEMS = 16;
    private static final int TIMEOUT_TICKS = 300;
    private static final BenchMetricDescriptor TRANSFER_TICKS = new BenchMetricDescriptor(
            "super_lead.rope.item_transfer_ticks", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor TRANSFERRED_ITEMS = new BenchMetricDescriptor(
            "super_lead.rope.item_transferred", "items", MetricDirection.HIGHER_IS_BETTER);

    private final List<UUID> ids = new CopyOnWriteArrayList<>();
    private final List<BlockPos> blocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private final AtomicInteger sourceCount = new AtomicInteger(-1);
    private final AtomicInteger targetCount = new AtomicInteger(-1);
    private volatile boolean ready;
    private BlockPos sourcePos;
    private BlockPos targetPos;
    private int tick;
    private int transferCompletedTick = -1;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("rope bench requires the integrated server");
        var pose = context.automation().pose();
        sourcePos = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(-8, 0, -28);
        targetPos = sourcePos.offset(SPAN, 0, 0);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                level.setBlockAndUpdate(sourcePos, Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(targetPos, Blocks.BARREL.defaultBlockState());
                blocks.add(sourcePos);
                blocks.add(targetPos);
                Container source = container(level, sourcePos);
                source.setItem(0, new ItemStack(Items.IRON_INGOT, TOTAL_ITEMS));
                source.setChanged();
                LeadConnection connection = RopeBenchSupport.connectTops(
                        level, sourcePos, targetPos, LeadKind.ITEM, 1);
                ids.add(connection.id());
                if (!SuperLeadSavedData.get(level).update(connection.id(), c -> c.withExtractAnchor(1), true)) {
                    throw new IllegalStateException("failed to enable source extraction");
                }
                SuperLeadPayloads.sendDirtyToDimension(level);
                sampleContainers(level);
                ready = true;
            } catch (Exception e) {
                serverError.set("item-work rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        double cx = sourcePos.getX() + SPAN * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx - 9.0D, sourcePos.getY() + 4.0D, sourcePos.getZ() + 12.0D,
                cx, sourcePos.getY(), sourcePos.getZ()));
        context.automation().setHudHidden(true);
        if (!ready || !context.environment().readiness().ready() || ids.isEmpty()) return BenchClientStepResult.CONTINUE;
        LeadConnection synced = SuperLeadNetwork.findConnectionById(context.minecraft().level, ids.get(0)).orElse(null);
        return synced != null && synced.kind() == LeadKind.ITEM && synced.extractAnchor() == 1
                && SuperLeadClientEvents.probeSimForBench(ids.get(0)) != null
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(ids);
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        server.execute(() -> {
            try {
                sampleContainers(server.overworld());
            } catch (Exception e) {
                serverError.set("item-work inventory sample failed: " + e);
            }
        });
        boolean transferred = targetCount.get() > 0;
        boolean pulseReceived = ItemFlowAnimator.probeForBench(ids.get(0)) != null;
        if (transferred && pulseReceived && transferCompletedTick < 0) {
            transferCompletedTick = tick;
        }
        if (tick == 80) context.automation().captureScreenshot("rope-item-work");
        if (transferCompletedTick >= 0 && tick >= 80 && meshLifecycle.isActive(ids.get(0))) {
            return BenchClientStepResult.COMPLETE;
        }
        if (tick >= TIMEOUT_TICKS) {
            throw new AssertionError("item rope did not work: source=" + sourceCount.get()
                    + " target=" + targetCount.get() + " pulse=" + pulseReceived);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        failOnServerError();
        int source = sourceCount.get();
        int target = targetCount.get();
        if (source < 0 || target <= 0 || source + target != TOTAL_ITEMS) {
            throw new AssertionError("item conservation failed: source=" + source + " target=" + target);
        }
        ItemFlowAnimator.ItemPulseBenchProbe pulse = ItemFlowAnimator.probeForBench(ids.get(0));
        if (pulse == null || pulse.reverse()) {
            throw new AssertionError("client did not receive the forward item pulse");
        }
        meshLifecycle.requireAllActiveAtLeastOnce(ids, "item-work");
        context.metrics().record(TRANSFER_TICKS, transferCompletedTick);
        context.metrics().record(TRANSFERRED_ITEMS, target);
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

    private void sampleContainers(ServerLevel level) {
        sourceCount.set(countIron(container(level, sourcePos)));
        targetCount.set(countIron(container(level, targetPos)));
    }

    private static Container container(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            throw new IllegalStateException("missing container at " + pos);
        }
        return container;
    }

    private static int countIron(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(Items.IRON_INGOT)) count += stack.getCount();
        }
        return count;
    }

    private void failOnServerError() {
        if (serverError.get() != null) throw new IllegalStateException(serverError.get());
    }
}