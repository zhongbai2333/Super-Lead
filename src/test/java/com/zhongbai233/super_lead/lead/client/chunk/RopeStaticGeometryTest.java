package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RopeStaticGeometryTest {
    @Test
    void relightReusesAllGeometryArraysAndOnlyReplacesLightData() {
        UUID id = UUID.randomUUID();
        double[] x = { 0.0D, 1.0D };
        double[] y = { 2.0D, 2.0D };
        double[] z = { 0.0D, 0.0D };
        float[] side = { 0.1F, 0.1F };
        float[] zero = { 0.0F, 0.0F };
        int[] oldLight = { 1, 1 };
        int[] colors = { 1, 2, 3, 4 };
        RopeSectionSnapshot snapshot = new RopeSectionSnapshot(
                id, x, y, z, side, zero, zero, zero, side, zero, oldLight, colors);
        RopeStaticGeometryResult existing = new RopeStaticGeometryResult(snapshot, Set.of(1L));
        int[] newLight = { 15, 14 };

        RopeStaticGeometryResult relit = RopeStaticGeometry.withNodeLight(existing, newLight);

        assertSame(x, relit.snapshot.x);
        assertSame(y, relit.snapshot.y);
        assertSame(snapshot.sx, relit.snapshot.sx);
        assertSame(colors, relit.snapshot.segmentColorARGB);
        assertSame(newLight, relit.snapshot.nodeLight);
    }

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