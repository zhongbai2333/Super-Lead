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
    void balanceStopsAtEquilibrium() {
        TestHeatHandler hot = new TestHeatHandler(400.0D, 10.0D);
        TestHeatHandler cold = new TestHeatHandler(300.0D, 30.0D);

        double transferred = MekanismHeatBridge.balance(hot, cold, 10_000.0D);

        assertEquals(750.0D, transferred);
        assertEquals(325.0D, hot.getTemperature());
        assertEquals(325.0D, cold.getTemperature());
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

        private TestHeatHandler(double temperature, double capacity) {
            this.heat = temperature * capacity;
            this.capacity = capacity;
        }

        @Override
        public double getTemperature() {
            return capacity > 0.0D ? heat / capacity : 300.0D;
        }

        @Override
        public double getInverseConduction() {
            return 1.0D;
        }

        @Override
        public double getHeatCapacity() {
            return capacity;
        }

        @Override
        public void handleHeat(double transfer, TransactionContext transaction) {
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