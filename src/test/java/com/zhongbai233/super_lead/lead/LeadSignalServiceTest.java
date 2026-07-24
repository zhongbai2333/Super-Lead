package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.Config;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LeadSignalServiceTest {
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