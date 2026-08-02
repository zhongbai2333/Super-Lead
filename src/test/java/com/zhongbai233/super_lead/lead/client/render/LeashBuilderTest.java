package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.geom.BoundedHermiteCurve;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LeashBuilderTest {
    private static final double EPS = 1.0e-9D;

    @Test
    void bakedFaceCullingAcceptsTwoTrailingEndCaps() {
        assertTrue(LeashBuilder.hasCullableBakedLayout(12, 12 * 16 + 8));
    }

    @Test
    void bakedFaceCullingRejectsUnexpectedVertexLayout() {
        assertFalse(LeashBuilder.hasCullableBakedLayout(12, 12 * 16 + 4));
    }

    @Test
    void bakedFullFaceFlagsRemainAlignedAfterMetadataGrowth() {
        RopeSimulation sim = new RopeSimulation(
                new Vec3(0.0D, 3.0D, 0.0D),
                new Vec3(5.0D, 1.0D, 2.0D),
                19L,
                RopeTuning.localDefaults());
        sim.beginBake(64);
        for (int i = 0; i < 12; i++) {
            sim.appendBakedSegment(i, (i & 1) == 0,
                    i, 0.0D, 0.0D,
                    1.0D, 0.0D, 0.0D,
                    0.0D, 1.0D, 0.0D);
        }

        assertEquals(12, sim.bakedSegmentCount());
        for (int i = 0; i < 12; i++) {
            assertEquals((i & 1) == 0, sim.bakedSegFullFaces()[i]);
            assertEquals(i, sim.bakedSegSourceSegment()[i]);
        }
    }

    @Test
    void coarsePhysicsSpanRetainsFineVisualStripeDensity() {
        assertEquals(2, LeashBuilder.visualStripeSpanCount(0.0D, 1.0D, 0.5D));
        assertEquals(2, LeashBuilder.visualStripeSpanCount(1.0D, 2.0D, 0.5D));
    }

    @Test
    void visualStripePhaseComesFromArcLengthNotPhysicsSegmentIndex() {
        assertEquals(0, LeashBuilder.visualStripeIndex(0.0D, 0.5D));
        assertEquals(1, LeashBuilder.visualStripeIndex(0.5D, 0.5D));
        assertEquals(7, LeashBuilder.visualStripeIndex(3.5D, 0.5D));
    }

    @Test
    void stripeBoundaryFractionsSplitCoarseSpanInHalf() {
        assertEquals(0.0D, LeashBuilder.visualStripeFraction(0.0D, 1.0D, 0.5D, 0, true), EPS);
        assertEquals(0.5D, LeashBuilder.visualStripeFraction(0.0D, 1.0D, 0.5D, 0, false), EPS);
        assertEquals(0.5D, LeashBuilder.visualStripeFraction(0.0D, 1.0D, 0.5D, 1, true), EPS);
        assertEquals(1.0D, LeashBuilder.visualStripeFraction(0.0D, 1.0D, 0.5D, 1, false), EPS);
    }

    @Test
    void squareMidpointMatchesStaticHermiteSampling() {
        RopeSimulation sim = new RopeSimulation(
                new Vec3(0.0D, 4.0D, 0.0D),
                new Vec3(8.0D, 2.0D, 3.0D),
                42L,
                RopeTuning.localDefaults());
        sim.prepareRender(1.0F);
        int segment = Math.max(1, sim.nodeCount() / 2 - 1);
        int p0 = segment - 1;
        int p1 = segment;
        int p2 = segment + 1;
        int p3 = Math.min(sim.nodeCount() - 1, segment + 2);
        double[] expected = new double[3];
        double[] actual = new double[3];

        BoundedHermiteCurve.sample(
                sim.renderX(p0), sim.renderY(p0), sim.renderZ(p0),
                sim.renderX(p1), sim.renderY(p1), sim.renderZ(p1),
                sim.renderX(p2), sim.renderY(p2), sim.renderZ(p2),
                sim.renderX(p3), sim.renderY(p3), sim.renderZ(p3),
                0.5D, BoundedHermiteCurve.DEFAULT_MAX_DEVIATION, expected);
        LeashBuilder.sampleSquareCurvePoint(sim, segment, 0.5D, actual);

        assertEquals(expected[0], actual[0], EPS);
        assertEquals(expected[1], actual[1], EPS);
        assertEquals(expected[2], actual[2], EPS);
    }

    @Test
    void squareCurvePreservesPhysicalSegmentEndpoints() {
        RopeSimulation sim = new RopeSimulation(
                new Vec3(0.0D, 3.0D, 0.0D),
                new Vec3(5.0D, 1.0D, 2.0D),
                7L,
                RopeTuning.localDefaults());
        sim.prepareRender(1.0F);
        int segment = sim.nodeCount() / 2;
        double[] point = new double[3];

        LeashBuilder.sampleSquareCurvePoint(sim, segment, 0.0D, point);
        assertEquals(sim.renderX(segment), point[0], EPS);
        assertEquals(sim.renderY(segment), point[1], EPS);
        assertEquals(sim.renderZ(segment), point[2], EPS);

        LeashBuilder.sampleSquareCurvePoint(sim, segment, 1.0D, point);
        assertEquals(sim.renderX(segment + 1), point[0], EPS);
        assertEquals(sim.renderY(segment + 1), point[1], EPS);
        assertEquals(sim.renderZ(segment + 1), point[2], EPS);
    }

    @Test
    void cachedCurveMidpointsMatchDirectSamplingAcrossSegments() {
        RopeSimulation sim = new RopeSimulation(
                new Vec3(0.0D, 4.0D, 0.0D),
                new Vec3(8.0D, 2.0D, 3.0D),
                81L,
                RopeTuning.localDefaults());
        sim.prepareRender(0.65F);
        sim.acquireCurveMidScratch();
        double[] midX = sim.curveMidX();
        double[] midY = sim.curveMidY();
        double[] midZ = sim.curveMidZ();
        double[] midpoint = new double[3];
        double[] direct = new double[3];
        double[] cached = new double[3];
        double[] fractions = { 0.0D, 0.2D, 0.5D, 0.8D, 1.0D };

        for (int segment = 0; segment < sim.nodeCount() - 1; segment++) {
            LeashBuilder.sampleSquareCurvePoint(sim, segment, 0.5D, midpoint);
            midX[segment] = midpoint[0];
            midY[segment] = midpoint[1];
            midZ[segment] = midpoint[2];
            for (double fraction : fractions) {
                LeashBuilder.sampleSquareCurvePoint(sim, segment, fraction, direct);
                LeashBuilder.sampleSquareCurvePoint(
                        sim, segment, fraction, midX, midY, midZ, cached);
                assertEquals(direct[0], cached[0], EPS);
                assertEquals(direct[1], cached[1], EPS);
                assertEquals(direct[2], cached[2], EPS);
            }
        }
    }

    @Test
    void rgbShadingPreservesOriginalAlpha() {
        assertEquals(0x80563412, LeashBuilder.combineAlphaAndRgb(0x80ABCDEF, 0x563412));
        assertEquals(0x00563412, LeashBuilder.combineAlphaAndRgb(0x00ABCDEF, 0x563412));
        assertEquals(0xFF563412, LeashBuilder.combineAlphaAndRgb(0xFFABCDEF, 0x563412));
    }

    @Test
    void deferredJobSnapshotSurvivesCallerListReuse() {
        RopeSimulation first = new RopeSimulation(
                new Vec3(0.0D, 3.0D, 0.0D), new Vec3(4.0D, 1.0D, 0.0D),
                91L, RopeTuning.localDefaults());
        RopeSimulation second = new RopeSimulation(
                new Vec3(0.0D, 4.0D, 0.0D), new Vec3(5.0D, 2.0D, 0.0D),
                92L, RopeTuning.localDefaults());
        RopeJob firstJob = new RopeJob(first, 0, 0, 0, 0, 0,
                LeadKind.NORMAL, false, 0, null, 0);
        RopeJob secondJob = new RopeJob(second, 0, 0, 0, 0, 0,
                LeadKind.NORMAL, false, 0, null, 0);
        ArrayList<RopeJob> reusable = new ArrayList<>();
        reusable.add(firstJob);

        List<RopeJob> submitted = LeashBuilder.deferredJobSnapshot(reusable);
        reusable.clear();
        reusable.add(secondJob);

        assertEquals(1, submitted.size());
        assertSame(firstJob, submitted.getFirst());
        assertSame(secondJob, reusable.getFirst());
    }

}
