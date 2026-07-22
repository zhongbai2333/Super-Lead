package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SegmentBoxContactTest {
    private static final AABB UNIT_BOX = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    @Test
    void degenerateSegmentFallsBackToFinitePointContact() {
        SegmentBoxContact contact = new SegmentBoxContact().compute(
                2.0D, 0.5D, 0.5D,
                2.0D, 0.5D, 0.5D,
                UNIT_BOX);

        assertEquals(0.0D, contact.s, 0.0D);
        assertEquals(2.0D, contact.spx, 0.0D);
        assertEquals(1.0D, contact.cpx, 0.0D);
        assertEquals(1.0D, contact.distSqr, 0.0D);
        assertTrue(Double.isFinite(contact.dx));
        assertTrue(Double.isFinite(contact.dy));
        assertTrue(Double.isFinite(contact.dz));
        assertTrue(Double.isFinite(contact.distSqr));
    }

    @Test
    void crossingSegmentReportsZeroDistanceInsideBox() {
        SegmentBoxContact contact = new SegmentBoxContact().compute(
                -1.0D, 0.5D, 0.5D,
                2.0D, 0.5D, 0.5D,
                UNIT_BOX);

        assertEquals(0.5D, contact.s, 1.0e-12D);
        assertEquals(0.5D, contact.spx, 1.0e-12D);
        assertEquals(0.0D, contact.distSqr, 1.0e-12D);
    }

    @Test
    void separatedParallelSegmentFindsNearestBoxFace() {
        SegmentBoxContact contact = new SegmentBoxContact().compute(
                -1.0D, 2.0D, 0.5D,
                2.0D, 2.0D, 0.5D,
                UNIT_BOX);

        assertEquals(0.5D, contact.s, 1.0e-12D);
        assertEquals(0.5D, contact.spx, 1.0e-12D);
        assertEquals(1.0D, contact.cpy, 1.0e-12D);
        assertEquals(1.0D, contact.distSqr, 1.0e-12D);
    }
}