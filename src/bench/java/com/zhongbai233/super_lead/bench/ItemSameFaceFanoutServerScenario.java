package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Exercises eight ITEM ropes sharing one source capability face. */
final class ItemSameFaceFanoutServerScenario implements BenchServerScenario {
    private static final int TARGETS = 8;
    private static final int INITIAL_ITEMS = 64;
    private static final int MEASURE_TICKS = 240;
        private static final int[][] TARGET_OFFSETS = {
            {-4, 0}, {-3, -3}, {0, -4}, {3, -3},
            {4, 0}, {3, 3}, {0, 4}, {-3, 3}
        };
    private static final BenchMetricDescriptor ITEMS_MOVED = new BenchMetricDescriptor(
            "super_lead.item_fanout.items_moved", "items", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor TARGETS_SERVED = new BenchMetricDescriptor(
            "super_lead.item_fanout.targets_served", "targets", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor FAIRNESS_SPREAD = new BenchMetricDescriptor(
            "super_lead.item_fanout.fairness_spread", "items", MetricDirection.LOWER_IS_BETTER);

    private final List<UUID> connectionIds = new ArrayList<>();
    private final List<BlockPos> blocks = new ArrayList<>();
    private final List<BlockPos> targetPositions = new ArrayList<>();
    private ServerLevel level;
    private BlockPos sourcePosition;
    private int measuredTicks;
    private int lastMoved;

    @Override
    public void setup(BenchServerContext context) {
        level = context.level();
        sourcePosition = RopeBenchSupport.serverSpawn(level).above(24).offset(-12, 0, -12);
        placeBarrel(sourcePosition);
        Container source = container(sourcePosition);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, INITIAL_ITEMS));
        source.setChanged();

        LeadAnchor sharedSource = new LeadAnchor(sourcePosition, Direction.UP);
        for (int index = 0; index < TARGETS; index++) {
            int x = TARGET_OFFSETS[index][0];
            int z = TARGET_OFFSETS[index][1];
            BlockPos targetPosition = sourcePosition.offset(x, 0, z);
            placeBarrel(targetPosition);
            targetPositions.add(targetPosition);
            LeadConnection connection = SuperLeadNetwork.connect(level, sharedSource,
                    new LeadAnchor(targetPosition, Direction.UP), LeadKind.ITEM, null,
                    LeadConnection.MIN_LENGTH_UNITS);
            if (connection == null) {
                throw new IllegalStateException("item fanout refused connection " + index);
            }
            connectionIds.add(connection.id());
            if (!SuperLeadSavedData.get(level).update(connection.id(), rope -> rope.withExtractAnchor(1), true)) {
                throw new IllegalStateException("item fanout failed to set extraction on " + connection.id());
            }
        }
        SuperLeadPayloads.sendDirtyToDimension(level);
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measuredTicks++;
        int moved = sum(targetCounts());
        context.metrics().record(ITEMS_MOVED, moved);
        lastMoved = moved;
        return measuredTicks >= MEASURE_TICKS ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        List<Integer> counts = targetCounts();
        int source = countIron(container(sourcePosition));
        int moved = 0;
        int served = 0;
        int minimum = counts.isEmpty() ? 0 : Integer.MAX_VALUE;
        int maximum = 0;
        for (int count : counts) {
            moved += count;
            if (count > 0) {
                served++;
            }
            minimum = Math.min(minimum, count);
            maximum = Math.max(maximum, count);
        }
        if (connectionIds.size() != TARGETS || source + moved != INITIAL_ITEMS || moved != lastMoved) {
            throw new AssertionError("item fanout conservation failed: ropes=" + connectionIds.size()
                    + " source=" + source + " moved=" + moved + " sampled=" + lastMoved);
        }
        if (served != TARGETS) {
            throw new AssertionError("item fanout did not serve every target: " + counts);
        }
        context.metrics().record(ITEMS_MOVED, moved);
        context.metrics().record(TARGETS_SERVED, served);
        context.metrics().record(FAIRNESS_SPREAD, maximum - minimum);
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            RopeBenchSupport.teardown(level, Set.copyOf(connectionIds), List.copyOf(blocks));
        }
    }

    private void placeBarrel(BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.BARREL.defaultBlockState());
        blocks.add(position);
    }

    private List<Integer> targetCounts() {
        List<Integer> counts = new ArrayList<>(targetPositions.size());
        for (BlockPos position : targetPositions) {
            counts.add(countIron(container(position)));
        }
        return counts;
    }

    private static int sum(List<Integer> values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private Container container(BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof Container container)) {
            throw new IllegalStateException("missing barrel at " + position);
        }
        return container;
    }

    private static int countIron(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.IRON_INGOT)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}