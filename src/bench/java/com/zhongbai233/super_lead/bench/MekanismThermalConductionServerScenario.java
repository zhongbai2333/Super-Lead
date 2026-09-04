package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismHeatBridge;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.content.network.transmitter.ThermodynamicConductor;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.tile.transmitter.TileEntityThermodynamicConductor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Real Mekanism heat handlers covering conduction, conservation and sided disablement. */
final class MekanismThermalConductionServerScenario implements BenchServerScenario {
    private static final double HOT_TEMPERATURE = 400.0D;
    private static final double COLD_TEMPERATURE = 300.0D;
    private static final BenchMetricDescriptor HEAT_MOVED = new BenchMetricDescriptor(
            "super_lead.thermal.heat_moved", "heat", MetricDirection.NEUTRAL);

    private final List<BlockPos> blocks = new ArrayList<>();
    private final Set<ChunkPos> forcedChunks = new HashSet<>();
    private ServerLevel level;
    private BlockPos hotPos;
    private BlockPos coldPos;
    private UUID connectionId;
    private Direction hotFace;
    private double movedHeat;
    private boolean formulaVerified;
    private boolean disabledSideVerified;
    private boolean measured;

    @Override
    public void setup(BenchServerContext context) {
        if (!ModList.get().isLoaded("mekanism")) {
            throw new IllegalStateException("thermal conduction requires Mekanism in benchRuntimeMod");
        }
        level = context.level();
        Block conductor = BuiltInRegistries.BLOCK.getValue(
                Identifier.fromNamespaceAndPath("mekanism", "basic_thermodynamic_conductor"));
        if (conductor == null || BuiltInRegistries.BLOCK.getKey(conductor).getNamespace().equals("minecraft")) {
            throw new IllegalStateException("missing mekanism:basic_thermodynamic_conductor");
        }

        hotPos = RopeBenchSupport.serverSpawn(level).above(24).offset(20, 0, -8);
        coldPos = hotPos.offset(4, 0, 0);
        placeConductor(hotPos, conductor);
        placeConductor(coldPos, conductor);
        hotFace = Direction.EAST;
        Direction coldFace = Direction.WEST;
        LeadAnchor hotAnchor = new LeadAnchor(hotPos, hotFace);
        LeadAnchor coldAnchor = new LeadAnchor(coldPos, coldFace);
        requireSidedHandler(hotAnchor);
        requireSidedHandler(coldAnchor);

        LeadConnection connection = SuperLeadNetwork.connect(level, hotAnchor, coldAnchor,
                LeadKind.THERMAL, null, LeadConnection.MIN_LENGTH_UNITS);
        if (connection == null) {
            throw new IllegalStateException("thermal conduction refused test connection");
        }
        connectionId = connection.id();

        setTemperature(hotPos, HOT_TEMPERATURE);
        setTemperature(coldPos, COLD_TEMPERATURE);
        IHeatHandler hot = requireSidedHandler(hotAnchor);
        IHeatHandler cold = requireSidedHandler(coldAnchor);
        double expected = expectedTransfer(hot, cold, Config.thermalBaseTransfer());
        double hotBefore = hot.getTemperature() * hot.getHeatCapacity();
        double coldBefore = cold.getTemperature() * cold.getHeatCapacity();
        SuperLeadNetwork.tickThermal(level);
        double hotAfter = hot.getTemperature() * hot.getHeatCapacity();
        double coldAfter = cold.getTemperature() * cold.getHeatCapacity();
        double removed = hotBefore - hotAfter;
        double inserted = coldAfter - coldBefore;
        assertClose("Mekanism conduction formula", expected, removed);
        assertClose("Mekanism heat conservation", removed, inserted);
        if (removed <= 0.0D) {
            throw new AssertionError("thermal lead did not move heat");
        }
        movedHeat = removed;
        formulaVerified = true;

        transmitter(hotPos).setConnectionTypeRaw(hotFace, ConnectionType.NONE);
        if (MekanismHeatBridge.handler(level, hotAnchor) != null) {
            throw new AssertionError("disabled Mekanism conductor side still exposed a heat handler");
        }
        setTemperature(hotPos, HOT_TEMPERATURE);
        setTemperature(coldPos, COLD_TEMPERATURE);
        double disabledHotBefore = temperature(hotPos);
        double disabledColdBefore = temperature(coldPos);
        SuperLeadNetwork.tickThermal(level);
        assertClose("disabled source side", disabledHotBefore, temperature(hotPos));
        assertClose("disabled target side", disabledColdBefore, temperature(coldPos));
        disabledSideVerified = true;
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        if (!measured) {
            context.metrics().record(HEAT_MOVED, movedHeat);
            measured = true;
        }
        return BenchStepResult.COMPLETE;
    }

    @Override
    public void verify(BenchServerContext context) {
        if (!formulaVerified || !disabledSideVerified || !measured) {
            throw new AssertionError("thermal Mekanism verification was incomplete");
        }
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            try {
                Set<UUID> connections = connectionId == null ? Set.of() : Set.of(connectionId);
                RopeBenchSupport.teardown(level, connections, List.copyOf(blocks));
            } finally {
                for (ChunkPos chunk : forcedChunks) {
                    level.setChunkForced(chunk.x(), chunk.z(), false);
                }
                forcedChunks.clear();
            }
        }
    }

    private void placeConductor(BlockPos position, Block conductor) {
        ChunkPos chunk = new ChunkPos(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ()));
        if (forcedChunks.add(chunk)) {
            level.setChunkForced(chunk.x(), chunk.z(), true);
            level.getChunk(chunk.x(), chunk.z());
        }
        level.setBlockAndUpdate(position, conductor.defaultBlockState());
        blocks.add(position);
        transmitter(position);
    }

    private IHeatHandler requireSidedHandler(LeadAnchor anchor) {
        IHeatHandler handler = MekanismHeatBridge.handler(level, anchor);
        if (handler == null) {
            throw new IllegalStateException("missing sided Mekanism heat handler at " + anchor);
        }
        return handler;
    }

    private ThermodynamicConductor transmitter(BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof TileEntityThermodynamicConductor tile)) {
            throw new IllegalStateException("missing Mekanism thermodynamic conductor at " + position);
        }
        return tile.getTransmitter();
    }

    private void setTemperature(BlockPos position, double temperature) {
        ThermodynamicConductor conductor = transmitter(position);
        try (Transaction transaction = Transaction.openRoot()) {
            conductor.buffer.setHeat(temperature * conductor.buffer.getHeatCapacity(), transaction);
            transaction.commit();
        }
    }

    private double temperature(BlockPos position) {
        return transmitter(position).buffer.getTemperature();
    }

    private static double expectedTransfer(IHeatHandler hot, IHeatHandler cold, double maxHeat) {
        double hotTemperature = hot.getTemperature();
        double coldTemperature = cold.getTemperature();
        double hotCapacity = hot.getHeatCapacity();
        double coldCapacity = cold.getHeatCapacity();
        double equilibrium = (hotTemperature * hotCapacity + coldTemperature * coldCapacity)
                / (hotCapacity + coldCapacity);
        double conduction = hot.getInverseConduction() + cold.getInverseConduction();
        return Math.min(maxHeat, (hotTemperature - equilibrium) * hotCapacity / conduction);
    }

    private static void assertClose(String label, double expected, double actual) {
        double tolerance = Math.max(1.0e-6D, Math.abs(expected) * 1.0e-9D);
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
}
