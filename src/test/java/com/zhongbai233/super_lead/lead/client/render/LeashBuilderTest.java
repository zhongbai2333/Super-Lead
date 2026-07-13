package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zhongbai233.super_lead.lead.client.geom.BoundedHermiteCurve;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LeashBuilderTest {
    private static final double EPS = 1.0e-9D;

    @Test
    void bakedFaceCullingAcceptsTwoTrailingEndCaps() {
        assertEquals(true, LeashBuilder.hasCullableBakedLayout(12, 12 * 16 + 8));
    }

    @Test
    void bakedFaceCullingRejectsUnexpectedVertexLayout() {
        assertEquals(false, LeashBuilder.hasCullableBakedLayout(12, 12 * 16 + 4));
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
}
