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
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Real Mekanism Energy Cube workload with eight ropes sharing one source face. */
final class MekanismEnergyFanoutServerScenario implements BenchServerScenario {
    private static final int TARGETS = 8;
    private static final int REQUESTED_INITIAL_ENERGY = 1_000_000;
    private static final int MEASURE_TICKS = 240;
    private static final BenchMetricDescriptor FE_MOVED = new BenchMetricDescriptor(
            "super_lead.energy_fanout.fe_moved", "FE", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor TARGETS_SERVED = new BenchMetricDescriptor(
            "super_lead.energy_fanout.targets_served", "targets", MetricDirection.HIGHER_IS_BETTER);

    private final List<UUID> connectionIds = new ArrayList<>();
    private final List<BlockPos> blocks = new ArrayList<>();
    private final List<BlockPos> targets = new ArrayList<>();
    private ServerLevel level;
    private BlockPos source;
    private Direction sourceFace;
    private long initialEnergy;
    private int measuredTicks;

    @Override
    public void setup(BenchServerContext context) {
        if (!ModList.get().isLoaded("mekanism")) {
            throw new IllegalStateException("energy fanout requires Mekanism in benchRuntimeMod");
        }
        level = context.level();
        Block cube = BuiltInRegistries.BLOCK.getValue(
                Identifier.fromNamespaceAndPath("mekanism", "basic_energy_cube"));
        if (cube == null || BuiltInRegistries.BLOCK.getKey(cube).getNamespace().equals("minecraft")) {
            throw new IllegalStateException("missing mekanism:basic_energy_cube");
        }
        source = RopeBenchSupport.serverSpawn(level).above(24).offset(12, 0, -12);
        placeCube(source, cube);
        initialEnergy = chargeCube(source, REQUESTED_INITIAL_ENERGY);
        configureCube(source, "OUTPUT");
        sourceFace = findExtractFace(source);

        for (int index = 0; index < TARGETS; index++) {
            int x = (index % 4) * 3 - 5;
            int z = (index / 4) * 6 + 5;
            BlockPos target = source.offset(x, 0, z);
            placeCube(target, cube);
            targets.add(target);
            configureCube(target, "INPUT");
            Direction targetFace = findInsertFace(target);
            LeadConnection connection = SuperLeadNetwork.connect(level,
                    new LeadAnchor(source, sourceFace), new LeadAnchor(target, targetFace),
                    LeadKind.ENERGY, null, LeadConnection.MIN_LENGTH_UNITS);
            if (connection == null) {
                throw new IllegalStateException("energy fanout refused connection " + index);
            }
            connectionIds.add(connection.id());
            if (!SuperLeadSavedData.get(level).update(connection.id(), rope -> rope.withExtractAnchor(1), true)) {
                throw new IllegalStateException("energy fanout failed to set extraction on " + connection.id());
            }
        }
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measuredTicks++;
        context.metrics().record(FE_MOVED, targetEnergy());
        return measuredTicks >= MEASURE_TICKS ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        long sourceEnergy = unsidedHandler(source).getAmountAsLong();
        long moved = targetEnergy();
        int served = 0;
        for (BlockPos target : targets) {
            if (unsidedHandler(target).getAmountAsLong() > 0L) {
                served++;
            }
        }
        if (sourceEnergy + moved != initialEnergy) {
            throw new AssertionError("energy fanout conservation failed: initial=" + initialEnergy
                    + " source=" + sourceEnergy + " targets=" + moved);
        }
        if (moved <= 0L || served != TARGETS) {
            throw new AssertionError("energy fanout incomplete: moved=" + moved + " served=" + served);
        }
        context.metrics().record(FE_MOVED, moved);
        context.metrics().record(TARGETS_SERVED, served);
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level != null) {
            RopeBenchSupport.teardown(level, Set.copyOf(connectionIds), List.copyOf(blocks));
        }
    }

    private void placeCube(BlockPos position, Block cube) {
        level.setBlockAndUpdate(position, cube.defaultBlockState());
        blocks.add(position);
    }

    private Direction findExtractFace(BlockPos position) {
        for (Direction face : Direction.values()) {
            EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, position, face);
            if (handler != null && simulateExtract(handler, 1) > 0) {
                return face;
            }
        }
        throw new IllegalStateException("no extract-capable Energy Cube face at " + position);
    }

    private Direction findInsertFace(BlockPos position) {
        for (Direction face : Direction.values()) {
            EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, position, face);
            if (handler != null && simulateInsert(handler, 1) > 0) {
                return face;
            }
        }
        throw new IllegalStateException("no insert-capable Energy Cube face at " + position);
    }

    private EnergyHandler unsidedHandler(BlockPos position) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, position, null);
        if (handler == null) {
            throw new IllegalStateException("missing unsided Energy Cube capability at " + position);
        }
        return handler;
    }

    private long chargeCube(BlockPos position, long requestedEnergy) {
        Object cube = level.getBlockEntity(position);
        if (cube == null || !cube.getClass().getName().equals("mekanism.common.tile.TileEntityEnergyCube")) {
            throw new IllegalStateException("missing Mekanism Energy Cube block entity at " + position);
        }
        try {
            Object container = cube.getClass().getMethod("energyContainer").invoke(cube);
            long capacity = (long) container.getClass().getMethod("getCapacityAsLong").invoke(container);
            long charged = Math.min(requestedEnergy, capacity);
            try (Transaction transaction = Transaction.openRoot()) {
                container.getClass().getMethod("setEnergy", long.class,
                        net.neoforged.neoforge.transfer.transaction.TransactionContext.class)
                        .invoke(container, charged, transaction);
                transaction.commit();
            }
            long stored = (long) container.getClass().getMethod("getAmountAsLong").invoke(container);
            if (stored != charged || charged <= 0L) {
                throw new IllegalStateException("basic energy cube refused setup charge");
            }
            return charged;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("incompatible Mekanism Energy Cube fixture API", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configureCube(BlockPos position, String dataTypeName) {
        Object cube = level.getBlockEntity(position);
        if (cube == null) {
            throw new IllegalStateException("missing Mekanism Energy Cube block entity at " + position);
        }
        try {
            Class<? extends Enum> transmissionClass = (Class<? extends Enum>) Class.forName(
                    "mekanism.common.lib.transmitter.TransmissionType").asSubclass(Enum.class);
            Class<? extends Enum> dataTypeClass = (Class<? extends Enum>) Class.forName(
                    "mekanism.common.tile.component.config.DataType").asSubclass(Enum.class);
            Class<?> relativeSideClass = Class.forName("mekanism.api.RelativeSide");
            Object energy = Enum.valueOf(transmissionClass, "ENERGY");
            Object dataType = Enum.valueOf(dataTypeClass, dataTypeName);
            Object configComponent = cube.getClass().getMethod("getConfig").invoke(cube);
            Object config = configComponent.getClass().getMethod("getConfig", transmissionClass)
                    .invoke(configComponent, energy);
            var setDataType = config.getClass().getMethod("setDataType", dataTypeClass, relativeSideClass);
            for (Object side : relativeSideClass.getEnumConstants()) {
                setDataType.invoke(config, dataType, side);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("incompatible Mekanism Energy Cube side-config API", exception);
        }
    }

    private long targetEnergy() {
        long total = 0L;
        for (BlockPos target : targets) {
            total += unsidedHandler(target).getAmountAsLong();
        }
        return total;
    }

    private static int simulateInsert(EnergyHandler handler, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            return handler.insert(amount, transaction);
        }
    }

    private static int simulateExtract(EnergyHandler handler, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            return handler.extract(amount, transaction);
        }
    }
}