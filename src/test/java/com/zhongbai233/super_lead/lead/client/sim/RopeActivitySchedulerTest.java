package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RopeActivitySchedulerTest {
    @Test
    void collisionRiskImmediatelyPromotesIdleRopeToHot() {
        var idle = new RopeActivityScheduler.State(
                RopeActivityScheduler.Tier.IDLE, 0.01D, 0, 100L);

        var hot = RopeActivityScheduler.update(idle, 101L, 0.90D, true);

        assertEquals(RopeActivityScheduler.Tier.HOT, hot.tier());
        assertEquals(1, hot.tier().interval());
    }

    @Test
    void fallingActivityRequiresEvidenceAndDropsOnlyOneTier() {
        var state = RopeActivityScheduler.State.initial(100L);

        for (long tick = 101L; tick <= 105L; tick++) {
            state = RopeActivityScheduler.update(state, tick, 0.0D, false);
        }

        assertEquals(RopeActivityScheduler.Tier.ACTIVE, state.tier(),
                "quiet trend must not jump directly from HOT to IDLE");
    }

    @Test
    void repeatedLowerSampleInSameTickDoesNotDependOnRenderFrameCount() {
        var first = RopeActivityScheduler.update(
                RopeActivityScheduler.State.initial(100L), 101L, 0.0D, false);
        var repeated = RopeActivityScheduler.update(first, 101L, 0.0D, false);

        assertSame(first, repeated);
    }

    @Test
    void tierIntervalsRemainDiscreteFixedStepRates() {
        assertEquals(1, RopeActivityScheduler.Tier.HOT.interval());
        assertEquals(2, RopeActivityScheduler.Tier.ACTIVE.interval());
        assertEquals(4, RopeActivityScheduler.Tier.COOLING.interval());
        assertEquals(8, RopeActivityScheduler.Tier.IDLE.interval());
    }

    @Test
    void nonFiniteSampleCannotPoisonExistingSchedulerState() {
        var active = new RopeActivityScheduler.State(
                RopeActivityScheduler.Tier.ACTIVE, 0.50D, 1, 100L);

        assertSame(active, RopeActivityScheduler.update(active, 101L, Double.NaN, false));
        assertSame(active, RopeActivityScheduler.update(active, 101L, Double.POSITIVE_INFINITY, false));
    }

    @Test
    void nonFiniteFirstSampleFailsSafeToHot() {
        var state = RopeActivityScheduler.update(null, 100L, Double.NaN, false);

        assertEquals(RopeActivityScheduler.Tier.HOT, state.tier());
        assertEquals(1.0D, state.activity(), 0.0D);
        assertEquals(RopeActivityScheduler.Tier.HOT, RopeActivityScheduler.tierFor(Double.NaN));
    }
}