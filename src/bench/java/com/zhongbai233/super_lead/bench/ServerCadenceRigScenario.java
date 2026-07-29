package com.zhongbai233.super_lead.bench;

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

/** Creates the authoritative dense rope rig on a dedicated server for paired cadence runs. */
final class ServerCadenceRigScenario implements BenchServerScenario {
    static final int CONNECTIONS = 53;
    private static final int COLUMNS = 9;
    private static final int SPAN = 8;
    private static final int MEASURE_TICKS = 220;

    private final List<UUID> connectionIds = new ArrayList<>();
    private final List<BlockPos> placedBlocks = new ArrayList<>();
    private int measuredTicks;
    private ServerLevel level;
    private BlockPos rigBase;

    @Override
    public void setup(BenchServerContext context) {
        level = context.level();
        if (context.server().getPlayerList().getPlayers().isEmpty()) {
            throw new IllegalStateException("paired cadence rig requires a logged-in remote player");
        }
        BlockPos player = context.server().getPlayerList().getPlayers().get(0).blockPosition();
        rigBase = player.above(24).offset(-16, 0, 8);
        for (int index = 0; index < CONNECTIONS; index++) {
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            BlockPos a = placeFence(level, rigBase.offset(column * 2, row * 3, 0));
            BlockPos b = placeFence(level, rigBase.offset(column * 2, row * 3, SPAN));
            LeadConnection connection = SuperLeadNetwork.connect(level,
                    new LeadAnchor(a, Direction.UP), new LeadAnchor(b, Direction.UP),
                    LeadKind.NORMAL, null, LeadConnection.MIN_LENGTH_UNITS);
            if (connection == null) {
                throw new IllegalStateException("dedicated cadence rig refused connection " + index);
            }
            connectionIds.add(connection.id());
        }
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measuredTicks++;
        return measuredTicks >= MEASURE_TICKS ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        if (connectionIds.size() != CONNECTIONS || measuredTicks != MEASURE_TICKS) {
            throw new AssertionError("dedicated cadence rig incomplete: connections=" + connectionIds.size()
                    + " ticks=" + measuredTicks);
        }
        context.metrics().record(new com.zhongbai233.bench.api.BenchMetricDescriptor(
                "super_lead.paired.server_cadence_connections", "count",
                com.zhongbai233.bench.api.MetricDirection.NEUTRAL), connectionIds.size());
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            RopeBenchSupport.teardown(level, Set.copyOf(connectionIds), List.copyOf(placedBlocks));
        }
    }

    private BlockPos placeFence(ServerLevel level, BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.OAK_FENCE.defaultBlockState());
        placedBlocks.add(position);
        return position;
    }
}
