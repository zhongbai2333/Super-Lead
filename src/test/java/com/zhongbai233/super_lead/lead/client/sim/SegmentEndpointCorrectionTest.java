package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SegmentEndpointCorrectionTest {
    private final double[] correction = new double[2];

    @Test
    void midpointContactMovesClosestPointByRequestedDisplacement() {
        assertTrue(SegmentEndpointCorrection.compute(0.5D, false, false, 0.10D, correction));

        assertEquals(0.10D, correction[0], 1.0e-12D);
        assertEquals(0.10D, correction[1], 1.0e-12D);
        assertEquals(0.10D, correction[0] * 0.5D + correction[1] * 0.5D, 1.0e-12D);
    }

    @Test
    void contactNearPinnedEndpointCannotAmplifyFreeEndpointSnap() {
        assertTrue(SegmentEndpointCorrection.compute(0.01D, true, false, 0.10D, correction));

        assertEquals(0.0D, correction[0], 0.0D);
        assertEquals(0.20D, correction[1], 1.0e-12D,
                "free endpoint correction must stay capped at twice the penetration");
    }

    @Test
    void bothPinnedEndpointsProduceNoCorrection() {
        assertFalse(SegmentEndpointCorrection.compute(0.5D, true, true, 0.10D, correction));
        assertEquals(0.0D, correction[0], 0.0D);
        assertEquals(0.0D, correction[1], 0.0D);
    }

    @Test
    void invalidContactInputIsRejectedAndClearsScratch() {
        correction[0] = 1.0D;
        correction[1] = 1.0D;

        assertFalse(SegmentEndpointCorrection.compute(Double.NaN, false, false, 0.10D, correction));
        assertEquals(0.0D, correction[0], 0.0D);
        assertEquals(0.0D, correction[1], 0.0D);
        assertFalse(SegmentEndpointCorrection.compute(0.5D, false, false,
                Double.POSITIVE_INFINITY, correction));
    }
}