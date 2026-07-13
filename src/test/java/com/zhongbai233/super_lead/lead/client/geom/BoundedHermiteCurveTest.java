package com.zhongbai233.super_lead.lead.client.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedHermiteCurveTest {
    private static final double EPS = 1.0e-9D;

    @Test
    void preservesSegmentEndpointsExactly() {
        double[] out = new double[3];
        BoundedHermiteCurve.sample(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0.0D, 0.08D, out);
        assertPoint(out, 1, 2, 3);

        BoundedHermiteCurve.sample(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1.0D, 0.08D, out);
        assertPoint(out, 4, 5, 6);
    }

    @Test
    void collinearPointsStayOnTheLine() {
        double[] out = new double[3];
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0D;
            BoundedHermiteCurve.sample(-1, 0, 0, 0, 0, 0, 1, 0, 0, 2, 0, 0, t, 0.08D, out);
            assertEquals(t, out[0], EPS);
            assertEquals(0.0D, out[1], EPS);
            assertEquals(0.0D, out[2], EPS);
        }
    }

    @Test
    void repeatedPointsRemainFinite() {
        double[] out = new double[3];
        BoundedHermiteCurve.sample(0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0.5D, 0.08D, out);
        assertFinite(out);

        BoundedHermiteCurve.sample(0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 0.5D, 0.08D, out);
        assertPoint(out, 1, 1, 1);
    }

    @Test
    void sharpTurnNeverExceedsDeviationBound() {
        double[] out = new double[3];
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0D;
            BoundedHermiteCurve.sample(0, -10, 0, 0, 0, 0, 1, 0, 0, 1, 10, 0,
                    t, BoundedHermiteCurve.DEFAULT_MAX_DEVIATION, out);
            double linearX = t;
            double deviation = Math.sqrt(
                    (out[0] - linearX) * (out[0] - linearX)
                            + out[1] * out[1] + out[2] * out[2]);
            assertTrue(deviation <= BoundedHermiteCurve.DEFAULT_MAX_DEVIATION + EPS,
                    "curve escaped its render-only deviation bound");
            assertFinite(out);
        }
    }

    @Test
    void nonFiniteInputStillProducesFiniteOutput() {
        double[] out = new double[3];
        BoundedHermiteCurve.sample(
                Double.NaN, 0, 0, 0, 0, 0, 1, 0, 0, Double.POSITIVE_INFINITY, 0, 0,
                Double.NaN, Double.NaN, out);
        assertFinite(out);
    }

    private static void assertPoint(double[] point, double x, double y, double z) {
        assertEquals(x, point[0], EPS);
        assertEquals(y, point[1], EPS);
        assertEquals(z, point[2], EPS);
    }

    private static void assertFinite(double[] point) {
        assertTrue(Double.isFinite(point[0]));
        assertTrue(Double.isFinite(point[1]));
        assertTrue(Double.isFinite(point[2]));
    }
}