package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class RopeStaticGeometryTest {
    @Test
    void insertedPhysicsNodesDoNotCreateExtraColorStripes() {
        float[] x = { 0.0F, 0.2F, 0.5F, 0.7F, 1.0F };
        float[] flat = new float[x.length];

        int[] stripes = RopeStaticGeometry.buildSegmentStripeIndices(x, flat, flat, 0.5D);

        assertArrayEquals(new int[] { 0, 0, 1, 1 }, stripes);
    }

    @Test
    void stripePhaseFollowsThreeDimensionalArcLength() {
        float[] x = { 0.0F, 0.3F, 0.3F, 0.3F };
        float[] y = { 0.0F, 0.0F, 0.4F, 0.4F };
        float[] z = { 0.0F, 0.0F, 0.0F, 0.5F };

        int[] stripes = RopeStaticGeometry.buildSegmentStripeIndices(x, y, z, 0.5D);

        assertArrayEquals(new int[] { 0, 1, 1 }, stripes);
    }
}