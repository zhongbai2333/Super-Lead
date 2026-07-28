package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zhongbai233.super_lead.lead.ItemPulse;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ItemFlowAnimatorTest {
    @AfterEach
    void clearState() {
        ItemFlowAnimator.clearAll();
    }

    @Test
    void benchProbeKeepsLastReceiptAfterAnimationExpires() {
        UUID id = UUID.randomUUID();
        ItemFlowAnimator.queue(new ItemPulse(id, false, 20L, 10));

        assertFalse(ItemFlowAnimator.activePulses(id, 40L, 0.0F).iterator().hasNext());
        ItemFlowAnimator.ItemPulseBenchProbe probe = ItemFlowAnimator.probeForBench(id);

        assertEquals(20L, probe.startTick());
        assertEquals(10, probe.durationTicks());
        assertFalse(probe.reverse());
    }

    @Test
    void cleanupAlsoRemovesBenchReceipt() {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        ItemFlowAnimator.queue(new ItemPulse(kept, false, 1L, 10));
        ItemFlowAnimator.queue(new ItemPulse(removed, true, 2L, 10));

        ItemFlowAnimator.retainAll(Set.of(kept));

        assertNull(ItemFlowAnimator.probeForBench(removed));
        assertEquals(1L, ItemFlowAnimator.probeForBench(kept).startTick());
    }
}