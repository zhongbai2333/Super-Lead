package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import org.junit.jupiter.api.Test;

class RopeStaticGeometryTest {
    @Test
    void insertedPhysicsNodesDoNotCreateExtraColorStripes() {
        double[] x = { 0.0D, 0.2D, 0.5D, 0.7D, 1.0D };
        double[] flat = new double[x.length];

        int[] stripes = RopeStaticGeometry.buildSegmentStripeIndices(x, flat, flat, 0.5D);

        assertArrayEquals(new int[] { 0, 0, 1, 1 }, stripes);
    }

    @Test
    void stripePhaseFollowsThreeDimensionalArcLength() {
        double[] x = { 0.0D, 0.3D, 0.3D, 0.3D };
        double[] y = { 0.0D, 0.0D, 0.4D, 0.4D };
        double[] z = { 0.0D, 0.0D, 0.0D, 0.5D };

        int[] stripes = RopeStaticGeometry.buildSegmentStripeIndices(x, y, z, 0.5D);

        assertArrayEquals(new int[] { 0, 1, 1 }, stripes);
    }

    @Test
    void chunkLocalConversionPreservesFractionAtLargeWorldCoordinates() {
        double sectionOrigin = 30_000_000.0D;
        double worldCoordinate = sectionOrigin + 0.03125D;

        assertEquals(0.03125F,
                RopeSectionMeshDriver.localCoordinate(worldCoordinate, sectionOrigin),
                1.0e-7F);
    }

    @Test
    void visualSmoothingPreservesSourceNodesAndAddsOneMidpointPerSegment() {
        double[] x = { 0.0D, 1.0D, 2.0D };
        double[] y = { 0.0D, 1.0D, 0.0D };
        double[] z = { 0.0D, 0.0D, 0.0D };

        RopeStaticGeometry.Points3 smoothed = RopeStaticGeometry.smoothVisualPolyline(x, y, z);

        assertEquals(5, smoothed.x().length);
        assertEquals(x[0], smoothed.x()[0]);
        assertEquals(y[0], smoothed.y()[0]);
        assertEquals(x[1], smoothed.x()[2]);
        assertEquals(y[1], smoothed.y()[2]);
        assertEquals(x[2], smoothed.x()[4]);
        assertEquals(y[2], smoothed.y()[4]);
    }

    @Test
    void visualStripeDensificationSkipsZeroLengthSegmentsWithoutTrailingZeros() {
        double[] x = { 0.0D, 0.0D, 1.0D, 1.0D };
        double[] y = new double[x.length];
        double[] z = new double[x.length];

        RopeStaticGeometry.Points3 points = RopeStaticGeometry.densifyForVisualStripes(
                x, y, z, RopeTuning.localDefaults());

        assertEquals(points.x().length, points.y().length);
        assertEquals(points.x().length, points.z().length);
        assertEquals(0.0D, points.x()[0]);
        assertEquals(1.0D, points.x()[points.x().length - 1]);
        for (int i = 1; i < points.x().length; i++) {
            org.junit.jupiter.api.Assertions.assertTrue(points.x()[i] > points.x()[i - 1]);
        }
    }
}