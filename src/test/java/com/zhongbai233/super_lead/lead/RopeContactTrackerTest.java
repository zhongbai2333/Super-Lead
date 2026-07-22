package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RopeContactTrackerTest {
    @Test
    void validContactBudgetStopsAtConfiguredLimit() {
        RopeContactTracker.ContactTickBudget budget = null;
        for (int i = 1; i <= 8; i++) {
            budget = RopeContactTracker.nextContactBudget(budget, 100L, 8);
            assertEquals(i, budget.count());
        }

        assertNull(RopeContactTracker.nextContactBudget(budget, 100L, 8));
    }

    @Test
    void nextTickAndClockRewindStartFreshBudget() {
        var previous = new RopeContactTracker.ContactTickBudget(100L, 8);

        assertEquals(new RopeContactTracker.ContactTickBudget(101L, 1),
                RopeContactTracker.nextContactBudget(previous, 101L, 8));
        assertEquals(new RopeContactTracker.ContactTickBudget(50L, 1),
                RopeContactTracker.nextContactBudget(previous, 50L, 8));
    }

    @Test
    void disabledBudgetRejectsEveryContact() {
        assertNull(RopeContactTracker.nextContactBudget(null, 100L, 0));
        assertNull(RopeContactTracker.nextContactBudget(null, 100L, -1));
    }
}