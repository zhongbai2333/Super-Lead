package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LeadSignalServiceTest {
    @Test
    void selfEmittedRedstoneNotificationsAreNotStructuralChanges() {
        assertFalse(LeadSignalService.isRedstoneNotificationActive());
    }

    @Test
    void redstoneUpdateRunsOnlyForInitializationOrDirtyState() {
        assertTrue(LeadSignalService.shouldProcessRedstoneUpdate(true, false));
        assertTrue(LeadSignalService.shouldProcessRedstoneUpdate(false, true));
        assertTrue(LeadSignalService.shouldProcessRedstoneUpdate(true, true));
        assertFalse(LeadSignalService.shouldProcessRedstoneUpdate(false, false));
    }

    @Test
    void energyRequestNeverExceedsConfiguredPerCallLimit() {
        assertEquals(Config.energyMaxRequestPerCall(), LeadSignalService.boundedEnergyRequest(Long.MAX_VALUE));
        assertEquals(1234, LeadSignalService.boundedEnergyRequest(1234));
        assertEquals(0, LeadSignalService.boundedEnergyRequest(0));
    }

    @Test
    void handlerCallBudgetRejectsReservationsBeyondLimit() {
        LeadSignalService.EnergyTickBudget budget = new LeadSignalService.EnergyTickBudget(3, Integer.MAX_VALUE);

        assertTrue(budget.reserveCalls(2));
        assertTrue(budget.reserveTransferCalls());
        assertEquals(3, budget.calls());
        assertFalse(budget.canStartCall());
        assertFalse(budget.reserveCalls(1));
        assertEquals(3, budget.calls());
    }

    @Test
    void transferRequiresOneRemainingHandlerCall() {
        LeadSignalService.EnergyTickBudget budget = new LeadSignalService.EnergyTickBudget(2, Integer.MAX_VALUE);

        assertTrue(budget.reserveCalls(1));
        assertTrue(budget.canStartTransfer());
        assertTrue(budget.reserveTransferCalls());
        assertEquals(2, budget.calls());
        assertFalse(budget.canStartTransfer());
    }

    @Test
    void energyEndpointsDeduplicateDifferentHitPointsOnSameLogicalPort() {
        BlockPos pos = new BlockPos(3, 4, 5);
        LeadAnchor first = new LeadAnchor(pos, Direction.EAST, new Vec3(4.0D, 4.2D, 5.2D));
        LeadAnchor second = new LeadAnchor(pos, Direction.EAST, new Vec3(4.0D, 4.8D, 5.8D));
        Set<LeadAnchor> seenPorts = new HashSet<>();

        assertTrue(LeadSignalService.markLogicalPortSeen(seenPorts, first));
        assertFalse(LeadSignalService.markLogicalPortSeen(seenPorts, second));
        assertEquals(1, seenPorts.size());
        assertEquals(first.logicalPort(), seenPorts.iterator().next());
    }

    @Test
    void directionalPortMayBeBothSourceAndTarget() {
        LeadAnchor port = new LeadAnchor(new BlockPos(4, 5, 6), Direction.NORTH,
                new Vec3(4.2D, 5.3D, 6.0D));
        Set<LeadAnchor> seenSources = new HashSet<>();
        Set<LeadAnchor> seenTargets = new HashSet<>();

        assertTrue(LeadSignalService.markLogicalPortSeen(seenSources, port));
        assertTrue(LeadSignalService.markLogicalPortSeen(seenTargets, port));
        assertEquals(Set.of(port.logicalPort()), seenSources);
        assertEquals(Set.of(port.logicalPort()), seenTargets);
    }

    @Test
    void parallelRopesOnSamePortEachContributeBandwidth() {
        BlockPos sourcePos = new BlockPos(1, 2, 3);
        LeadConnection first = LeadConnection.create(
                new LeadAnchor(sourcePos, Direction.EAST, new Vec3(2.0D, 2.2D, 3.2D)),
                new LeadAnchor(new BlockPos(8, 2, 3), Direction.WEST), LeadKind.ENERGY);
        LeadConnection second = LeadConnection.create(
                new LeadAnchor(sourcePos, Direction.EAST, new Vec3(2.0D, 2.8D, 3.8D)),
                new LeadAnchor(new BlockPos(9, 2, 3), Direction.WEST), LeadKind.ENERGY);

        long rate = LeadSignalService.energyComponentRate(List.of(0, 1), List.of(first, second));

        assertEquals((long) Config.energyBaseTransfer() * 2L, rate);
        assertEquals((long) Config.energyBaseTransfer() * 8L,
                LeadSignalService.energyComponentRate(List.of(0, 1), List.of(first, second), 4));
    }

    @Test
    void stableEnergyOutcomeBacksOffToFourTicks() {
        LeadSignalService.EnergyCadenceState cadence = new LeadSignalService.EnergyCadenceState();

        cadence.recordRun(100L, true, true);
        assertEquals(1, cadence.intervalTicks());
        cadence.recordRun(101L, true, true);
        assertEquals(1, cadence.intervalTicks());
        cadence.recordRun(102L, true, true);
        assertEquals(2, cadence.intervalTicks());
        assertFalse(cadence.isDue(103L));
        assertTrue(cadence.isDue(104L));
        cadence.recordRun(104L, true, true);
        assertEquals(2, cadence.intervalTicks());
        cadence.recordRun(106L, true, true);
        assertEquals(4, cadence.intervalTicks());
        assertFalse(cadence.isDue(109L));
        assertTrue(cadence.isDue(110L));
        assertEquals(4, cadence.elapsedTicks(110L));
    }

    @Test
    void energyCadenceResetsWhenOutcomeChangesOrBudgetExhausts() {
        LeadSignalService.EnergyCadenceState cadence = new LeadSignalService.EnergyCadenceState();
        cadence.recordRun(10L, false, true);
        cadence.recordRun(11L, false, true);
        cadence.recordRun(12L, false, true);
        assertEquals(2, cadence.intervalTicks());

        cadence.recordRun(14L, true, true);
        assertEquals(1, cadence.intervalTicks());
        cadence.recordRun(15L, true, false);
        assertEquals(1, cadence.intervalTicks());
        assertTrue(cadence.isDue(16L));
    }

    @Test
    void equalizationStopsAtSharedFillRatio() {
        assertEquals(50L, LeadSignalService.equalizingTransferLimit(100L, 100L, 0L, 100L));
        assertEquals(0L, LeadSignalService.equalizingTransferLimit(50L, 100L, 50L, 100L));
    }

    @Test
    void equalizationHandlesDifferentCapacitiesWithoutOvershooting() {
        // 100/100 and 0/300 balance at 25%, so 75 FE should move.
        assertEquals(75L, LeadSignalService.equalizingTransferLimit(100L, 100L, 0L, 300L));
        // A sub-FE difference cannot move without crossing the balance point.
        assertEquals(0L, LeadSignalService.equalizingTransferLimit(100L, 100L, 99L, 100L));
        assertEquals(1L, LeadSignalService.equalizingTransferLimit(100L, 100L, 98L, 100L));
    }

    @Test
    void sharedPortNeighborBucketIsExpandedOnlyOnce() {
        int connectionCount = 64;
        LeadAnchor logicalPort = new LeadAnchor(new BlockPos(7, 8, 9), Direction.UP);
        List<Integer> neighbors = new ArrayList<>(connectionCount);
        for (int i = 0; i < connectionCount; i++) {
            neighbors.add(i);
        }
        Map<LeadAnchor, List<Integer>> byAnchor = new HashMap<>();
        byAnchor.put(logicalPort, neighbors);
        boolean[] visited = new boolean[connectionCount];
        List<Integer> component = new ArrayList<>();
        Set<LeadAnchor> expandedPorts = new HashSet<>();

        int inspected = 0;
        for (int i = 0; i < connectionCount; i++) {
            LeadAnchor preciseAnchor = new LeadAnchor(logicalPort.pos(), logicalPort.face(),
                    new Vec3(7.1D + i * 0.001D, 9.0D, 9.1D));
            inspected += LeadSignalService.addUnvisitedNeighbors(preciseAnchor, byAnchor, visited, component,
                    expandedPorts);
        }

        assertEquals(connectionCount, inspected);
        assertEquals(connectionCount, component.size());
        assertEquals(1, expandedPorts.size());
    }

    @Test
    void changedRedstoneNotificationsDeduplicateSharedLogicalPorts() {
        LeadAnchor shared = new LeadAnchor(new BlockPos(1, 2, 3), Direction.UP);
        LeadConnection first = LeadConnection.create(
                new LeadAnchor(shared.pos(), shared.face(), new Vec3(1.2D, 3.0D, 3.2D)),
                new LeadAnchor(new BlockPos(8, 2, 3), Direction.UP), LeadKind.REDSTONE).withPower(15);
        LeadConnection second = LeadConnection.create(
                new LeadAnchor(shared.pos(), shared.face(), new Vec3(1.8D, 3.0D, 3.8D)),
                new LeadAnchor(new BlockPos(9, 2, 3), Direction.UP), LeadKind.REDSTONE).withPower(15);

        Set<LeadAnchor> ports = LeadSignalService.changedLogicalPorts(List.of(first, second));

        assertEquals(3, ports.size());
        assertTrue(ports.contains(shared.logicalPort()));
        assertTrue(ports.contains(first.to().logicalPort()));
        assertTrue(ports.contains(second.to().logicalPort()));
    }

        @Test
        void signalIndexMapsAnchorsAndOutsidePositions() {
        LeadConnection connection = LeadConnection.create(
            new LeadAnchor(new BlockPos(1, 2, 3), Direction.EAST),
            new LeadAnchor(new BlockPos(8, 2, 3), Direction.WEST),
            LeadKind.REDSTONE).withPower(9);

        LeadSignalService.SignalIndex index = LeadSignalService.SignalIndex.build(List.of(connection));

        assertEquals(9, index.signalAt(new BlockPos(1, 2, 3)));
        assertEquals(9, index.signalAt(new BlockPos(2, 2, 3)));
        assertEquals(9, index.signalAt(new BlockPos(8, 2, 3)));
        assertEquals(9, index.signalAt(new BlockPos(7, 2, 3)));
        assertTrue(index.hasSignalAt(new BlockPos(2, 2, 3)));
        assertFalse(index.hasSignalAt(new BlockPos(3, 2, 3)));
        }

        @Test
        void directSignalIndexRequiresMatchingFaceAndOutsidePosition() {
        LeadConnection connection = LeadConnection.create(
            new LeadAnchor(new BlockPos(1, 2, 3), Direction.EAST),
            new LeadAnchor(new BlockPos(8, 2, 3), Direction.WEST),
            LeadKind.REDSTONE).withPower(12);

        LeadSignalService.SignalIndex index = LeadSignalService.SignalIndex.build(List.of(connection));

        assertEquals(12, index.directSignalAt(new BlockPos(2, 2, 3), Direction.EAST));
        assertEquals(0, index.directSignalAt(new BlockPos(2, 2, 3), Direction.WEST));
        assertEquals(0, index.directSignalAt(new BlockPos(1, 2, 3), Direction.EAST));
        }

        @Test
        void signalIndexKeepsMaximumPowerAndIgnoresInactiveConnections() {
        LeadAnchor shared = new LeadAnchor(new BlockPos(4, 5, 6), Direction.UP);
        LeadConnection weak = LeadConnection.create(shared,
            new LeadAnchor(new BlockPos(10, 5, 6), Direction.UP), LeadKind.REDSTONE).withPower(4);
        LeadConnection strong = LeadConnection.create(shared,
            new LeadAnchor(new BlockPos(11, 5, 6), Direction.UP), LeadKind.REDSTONE).withPower(15);
        LeadConnection inactive = LeadConnection.create(
            new LeadAnchor(new BlockPos(20, 5, 6), Direction.UP),
            new LeadAnchor(new BlockPos(21, 5, 6), Direction.UP), LeadKind.REDSTONE);
        LeadConnection normal = LeadConnection.create(
            new LeadAnchor(new BlockPos(30, 5, 6), Direction.UP),
            new LeadAnchor(new BlockPos(31, 5, 6), Direction.UP), LeadKind.NORMAL).withPower(15);

        LeadSignalService.SignalIndex index = LeadSignalService.SignalIndex.build(
            List.of(weak, strong, inactive, normal));

        assertEquals(15, index.signalAt(shared.pos()));
        assertEquals(0, index.signalAt(new BlockPos(20, 5, 6)));
        assertEquals(0, index.signalAt(new BlockPos(30, 5, 6)));
        }
}