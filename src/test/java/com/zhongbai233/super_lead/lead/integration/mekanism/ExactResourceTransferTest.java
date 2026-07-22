package com.zhongbai233.super_lead.lead.integration.mekanism;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

class ExactResourceTransferTest {
    private static final TestResource RESOURCE = new TestResource(false);

    @Test
    void completeInsertionCommitsBothHandlers() {
        TestHandler source = new TestHandler(100, 100);
        TestHandler target = new TestHandler(0, 100);

        int transferred = ExactResourceTransfer.transfer(source, target, 0, RESOURCE, 40);

        assertEquals(40, transferred);
        assertEquals(60, source.amount);
        assertEquals(40, target.amount);
    }

    @Test
    void partialInsertionRollsBackSourceAndTarget() {
        TestHandler source = new TestHandler(100, 100);
        TestHandler target = new TestHandler(0, 15);

        int transferred = ExactResourceTransfer.transfer(source, target, 0, RESOURCE, 40);

        assertEquals(0, transferred);
        assertEquals(100, source.amount);
        assertEquals(0, target.amount);
    }

    @Test
    void zeroInsertionRollsBackExtraction() {
        TestHandler source = new TestHandler(100, 100);
        TestHandler target = new TestHandler(0, 0);

        int transferred = ExactResourceTransfer.transfer(source, target, 0, RESOURCE, 40);

        assertEquals(0, transferred);
        assertEquals(100, source.amount);
        assertEquals(0, target.amount);
    }

    @Test
    void rejectedExtractedAmountRollsBackBeforeInsertion() {
        TestHandler source = new TestHandler(20, 100);
        TestHandler target = new TestHandler(0, 100);

        int transferred = ExactResourceTransfer.transfer(
                source, target, 0, RESOURCE, 40, extracted -> extracted >= 40);

        assertEquals(0, transferred);
        assertEquals(20, source.amount);
        assertEquals(0, target.amount);
    }

    private record TestResource(boolean empty) implements Resource {
        @Override
        public boolean isEmpty() {
            return empty;
        }
    }

    private static final class TestHandler extends SnapshotJournal<Integer>
            implements ResourceHandler<TestResource> {
        private int amount;
        private final int capacity;

        private TestHandler(int amount, int capacity) {
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        protected Integer createSnapshot() {
            return amount;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            amount = snapshot;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public TestResource getResource(int slot) {
            return amount > 0 ? RESOURCE : new TestResource(true);
        }

        @Override
        public long getAmountAsLong(int slot) {
            return amount;
        }

        @Override
        public long getCapacityAsLong(int slot, TestResource resource) {
            return capacity;
        }

        @Override
        public boolean isValid(int slot, TestResource resource) {
            return slot == 0 && resource != null && !resource.isEmpty();
        }

        @Override
        public int insert(int slot, TestResource resource, int requested, TransactionContext transaction) {
            if (!isValid(slot, resource) || requested <= 0) {
                return 0;
            }
            updateSnapshots(transaction);
            int inserted = Math.min(requested, capacity - amount);
            amount += inserted;
            return inserted;
        }

        @Override
        public int extract(int slot, TestResource resource, int requested, TransactionContext transaction) {
            if (!isValid(slot, resource) || requested <= 0) {
                return 0;
            }
            updateSnapshots(transaction);
            int extracted = Math.min(requested, amount);
            amount -= extracted;
            return extracted;
        }
    }
}