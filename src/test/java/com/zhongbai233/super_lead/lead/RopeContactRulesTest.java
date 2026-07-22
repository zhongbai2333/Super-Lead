package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RopeContactRulesTest {
    @Test
    void inwardMotionWithoutExitIntentIsBlocked() {
        assertTrue(RopeContactRules.shouldBlockSideMotion(-0.20D, 0.0D));
    }

    @Test
    void outwardMotionIsNeverBlocked() {
        assertFalse(RopeContactRules.shouldBlockSideMotion(0.01D, -1.0D));
    }

    @Test
    void explicitExitIntentReleasesSideContact() {
        assertFalse(RopeContactRules.shouldBlockSideMotion(
                -0.20D, RopeContactRules.EXIT_INPUT_DOT + 0.01D));
    }
}