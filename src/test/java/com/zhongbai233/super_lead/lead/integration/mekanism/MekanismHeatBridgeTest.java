package com.zhongbai233.super_lead.lead.integration.mekanism;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mekanism.api.heat.IHeatHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

class MekanismHeatBridgeTest {
    @Test
    void balanceCommitsEqualHeatTransfer() {
        TestHeatHandler hot = new TestHeatHandler(400.0D, 10.0D);
        TestHeatHandler cold = new TestHeatHandler(300.0D, 10.0D);

        double transferred = MekanismHeatBridge.balance(hot, cold, 200.0D);

        assertEquals(200.0D, transferred);
        assertEquals(380.0D, hot.getTemperature());
        assertEquals(320.0D, cold.getTemperature());
    }

    @Test
    void balanceUsesMekanismInverseConductionStep() {
        TestHeatHandler hot = new TestHeatHandler(400.0D, 10.0D);
        TestHeatHandler cold = new TestHeatHandler(300.0D, 30.0D);

        double transferred = MekanismHeatBridge.balance(hot, cold, 10_000.0D);

        assertEquals(375.0D, transferred);
        assertEquals(362.5D, hot.getTemperature());
        assertEquals(312.5D, cold.getTemperature());
    }

    @Test
    void largerInverseConductionSlowsTransfer() {
        TestHeatHandler hot = new TestHeatHandler(400.0D, 10.0D, 3.0D, true, true);
        TestHeatHandler cold = new TestHeatHandler(300.0D, 10.0D, 7.0D, true, true);

        double transferred = MekanismHeatBridge.balance(hot, cold, 10_000.0D);

        assertEquals(50.0D, transferred);
        assertEquals(395.0D, hot.getTemperature());
        assertEquals(305.0D, cold.getTemperature());
    }

    @Test
    void rejectedEndpointRollsBackTheOtherSide() {
        TestHeatHandler hot = new TestHeatHandler(400.0D, 10.0D, 1.0D, true, true);
        TestHeatHandler rejectingCold = new TestHeatHandler(300.0D, 10.0D, 1.0D, false, true);

        double transferred = MekanismHeatBridge.balance(hot, rejectingCold, 200.0D);

        assertEquals(0.0D, transferred);
        assertEquals(400.0D, hot.getTemperature());
        assertEquals(300.0D, rejectingCold.getTemperature());
    }

    @Test
    void rejectedExtractionRollsBackTheReceivingSide() {
        TestHeatHandler rejectingHot = new TestHeatHandler(400.0D, 10.0D, 1.0D, true, false);
        TestHeatHandler cold = new TestHeatHandler(300.0D, 10.0D, 1.0D, true, true);

        double transferred = MekanismHeatBridge.balance(rejectingHot, cold, 200.0D);

        assertEquals(0.0D, transferred);
        assertEquals(400.0D, rejectingHot.getTemperature());
        assertEquals(300.0D, cold.getTemperature());
    }

    @Test
    void unusableOrInvalidHandlerDoesNotTransfer() {
        TestHeatHandler valid = new TestHeatHandler(400.0D, 10.0D);
        TestHeatHandler invalid = new TestHeatHandler(300.0D, 0.0D);

        assertEquals(0.0D, MekanismHeatBridge.balance(valid, invalid, 200.0D));
        assertEquals(400.0D, valid.getTemperature());
        assertEquals(300.0D, invalid.getTemperature());
    }

    private static final class TestHeatHandler extends SnapshotJournal<Double> implements IHeatHandler {
        private double heat;
        private final double capacity;
        private final double inverseConduction;
        private final boolean acceptsInsert;
        private final boolean acceptsExtract;

        private TestHeatHandler(double temperature, double capacity) {
            this(temperature, capacity, 1.0D, true, true);
        }

        private TestHeatHandler(double temperature, double capacity, double inverseConduction,
                boolean acceptsInsert, boolean acceptsExtract) {
            this.heat = temperature * capacity;
            this.capacity = capacity;
            this.inverseConduction = inverseConduction;
            this.acceptsInsert = acceptsInsert;
            this.acceptsExtract = acceptsExtract;
        }

        @Override
        public double getTemperature() {
            return capacity > 0.0D ? heat / capacity : 300.0D;
        }

        @Override
        public double getInverseConduction() {
            return inverseConduction;
        }

        @Override
        public double getHeatCapacity() {
            return capacity;
        }

        @Override
        public void handleHeat(double transfer, TransactionContext transaction) {
            if ((transfer > 0.0D && !acceptsInsert)
                    || (transfer < 0.0D && !acceptsExtract)) {
                return;
            }
            updateSnapshots(transaction);
            heat = Math.max(0.0D, heat + transfer);
        }

        @Override
        protected Double createSnapshot() {
            return heat;
        }

        @Override
        protected void revertToSnapshot(Double snapshot) {
            heat = snapshot;
        }
    }
}