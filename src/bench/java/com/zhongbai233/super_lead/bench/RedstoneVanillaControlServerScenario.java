package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Same block layout and input cadence as the rope workload, but without ropes. */
final class RedstoneVanillaControlServerScenario implements BenchServerScenario {
    private static final BenchMetricDescriptor TOGGLES = new BenchMetricDescriptor(
            "super_lead.redstone_control.toggles", "toggles", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor POWERED_INPUTS = new BenchMetricDescriptor(
            "super_lead.redstone_control.powered_inputs", "inputs", MetricDirection.NEUTRAL);

    private final List<BlockPos> blocks = new ArrayList<>();
    private final List<BlockPos> inputs = new ArrayList<>();
    private ServerLevel level;
    private int measuredTicks;
    private int toggles;
    private boolean powered;
    private int minimumPowered = Integer.MAX_VALUE;
    private int maximumPowered;

    @Override
    public void setup(BenchServerContext context) {
        level = context.level();
        BlockPos base = RedstoneBenchRig.base(level);
        for (int component = 0; component < RedstoneBenchRig.COMPONENTS; component++) {
            BlockPos center = RedstoneBenchRig.center(base, component);
            placeFence(center);
            BlockPos input = center.below();
            inputs.add(input);
            blocks.add(input);
            for (int rope = 0; rope < RedstoneBenchRig.ROPES_PER_COMPONENT; rope++) {
                placeFence(RedstoneBenchRig.target(center, rope));
            }
        }
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measuredTicks++;
        if (measuredTicks % RedstoneBenchRig.TOGGLE_INTERVAL == 1) {
            powered = !powered;
            toggles++;
            for (BlockPos input : inputs) {
                level.setBlockAndUpdate(input,
                        powered ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        int poweredInputs = 0;
        for (BlockPos input : inputs) {
            if (level.getBlockState(input).is(Blocks.REDSTONE_BLOCK)) {
                poweredInputs++;
            }
        }
        minimumPowered = Math.min(minimumPowered, poweredInputs);
        maximumPowered = Math.max(maximumPowered, poweredInputs);
        context.metrics().record(POWERED_INPUTS, poweredInputs);
        return measuredTicks >= RedstoneBenchRig.MEASURE_TICKS
                ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        int expectedToggles = RedstoneBenchRig.MEASURE_TICKS / RedstoneBenchRig.TOGGLE_INTERVAL;
        if (toggles != expectedToggles || minimumPowered != 0
                || maximumPowered != RedstoneBenchRig.COMPONENTS) {
            throw new AssertionError("redstone control cadence incomplete: toggles=" + toggles
                    + " min=" + minimumPowered + " max=" + maximumPowered);
        }
        context.metrics().record(TOGGLES, toggles);
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            RopeBenchSupport.teardown(level, Set.of(), List.copyOf(blocks));
        }
    }

    private void placeFence(BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.OAK_FENCE.defaultBlockState());
        blocks.add(position);
    }
}