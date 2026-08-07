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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Periodically dirties many independent eight-way REDSTONE rope components. */
final class RedstoneNetworkLoadServerScenario implements BenchServerScenario {
    private static final BenchMetricDescriptor TOGGLES = new BenchMetricDescriptor(
            "super_lead.redstone_load.toggles", "toggles", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor POWERED = new BenchMetricDescriptor(
            "super_lead.redstone_load.powered_connections", "connections", MetricDirection.NEUTRAL);

    private final List<UUID> connectionIds = new ArrayList<>();
    private final List<BlockPos> blocks = new ArrayList<>();
    private final List<BlockPos> inputs = new ArrayList<>();
    private ServerLevel level;
    private int measuredTicks;
    private int toggles;
    private boolean powered;
    private long minimumPowered = Long.MAX_VALUE;
    private long maximumPowered;

    @Override
    public void setup(BenchServerContext context) {
        level = context.level();
        BlockPos base = RedstoneBenchRig.base(level);
        for (int component = 0; component < RedstoneBenchRig.COMPONENTS; component++) {
            BlockPos center = RedstoneBenchRig.center(base, component);
            level.setBlockAndUpdate(center, Blocks.OAK_FENCE.defaultBlockState());
            blocks.add(center);
            BlockPos input = center.below();
            inputs.add(input);
            blocks.add(input);
            LeadAnchor shared = new LeadAnchor(center, Direction.UP);
            for (int rope = 0; rope < RedstoneBenchRig.ROPES_PER_COMPONENT; rope++) {
                BlockPos target = RedstoneBenchRig.target(center, rope);
                level.setBlockAndUpdate(target, Blocks.OAK_FENCE.defaultBlockState());
                blocks.add(target);
                LeadConnection connection = SuperLeadNetwork.connect(level, shared,
                        new LeadAnchor(target, Direction.UP), LeadKind.REDSTONE, null,
                        LeadConnection.MIN_LENGTH_UNITS);
                if (connection == null) {
                    throw new IllegalStateException("redstone load refused component=" + component + " rope=" + rope);
                }
                connectionIds.add(connection.id());
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
        long poweredConnections = 0L;
        for (UUID id : connectionIds) {
            LeadConnection connection = SuperLeadNetwork.findConnectionById(level, id).orElse(null);
            if (connection != null && connection.powered()) {
                poweredConnections++;
            }
        }
        minimumPowered = Math.min(minimumPowered, poweredConnections);
        maximumPowered = Math.max(maximumPowered, poweredConnections);
        context.metrics().record(POWERED, poweredConnections);
        return measuredTicks >= RedstoneBenchRig.MEASURE_TICKS
            ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        if (connectionIds.size() != RedstoneBenchRig.COMPONENTS * RedstoneBenchRig.ROPES_PER_COMPONENT) {
            throw new AssertionError("redstone load connection count changed: " + connectionIds.size());
        }
        if (toggles < RedstoneBenchRig.MEASURE_TICKS / RedstoneBenchRig.TOGGLE_INTERVAL - 1) {
            throw new AssertionError("redstone load did not toggle enough: " + toggles);
        }
        long expectedConnections = (long) RedstoneBenchRig.COMPONENTS * RedstoneBenchRig.ROPES_PER_COMPONENT;
        if (minimumPowered != 0L || maximumPowered != expectedConnections) {
            throw new AssertionError("redstone load did not fully propagate ON/OFF: min=" + minimumPowered
                    + " max=" + maximumPowered + " expected=" + expectedConnections);
        }
        context.metrics().record(TOGGLES, toggles);
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            RopeBenchSupport.teardown(level, Set.copyOf(connectionIds), List.copyOf(blocks));
        }
    }
}