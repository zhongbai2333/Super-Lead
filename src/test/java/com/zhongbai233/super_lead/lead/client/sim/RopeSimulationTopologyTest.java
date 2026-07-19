package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RopeSimulationTopologyTest {
    private static final Vec3 A = new Vec3(0.0D, 4.0D, 0.0D);
    private static final Vec3 B = new Vec3(8.0D, 4.0D, 0.0D);

    @Test
    void exactTopologyRejectsSingleSegmentDifference() {
        RopeTuning base = RopeTuning.localDefaults();
        RopeTuning eightSegments = base.withTopology(1.0D, 64);
        RopeTuning nineSegments = base.withTopology(0.90D, 64);
        RopeSimulation coarse = new RopeSimulation(A, B, 1L, eightSegments);

        assertEquals(9, coarse.nodeCount());
        assertTrue(coarse.matchesLength(A, B, nineSegments),
                "legacy hysteresis intentionally tolerates a one-segment difference");
        assertFalse(coarse.matchesTopology(A, B, nineSegments),
                "an explicit LOD transition must rebuild even for one extra segment");
        assertFalse(coarse.matchesTopologyProfile(nineSegments));
        assertTrue(coarse.matchesTopologyProfile(eightSegments));
    }

    @Test
    void shapeOnlyTopologyTransferInvalidatesCoarsePhysicsHistory() {
        RopeTuning base = RopeTuning.localDefaults();
        RopeSimulation coarse = new RopeSimulation(A, B, 2L, base.withTopology(1.0D, 64));
        int middle = coarse.nodeCount() / 2;
        coarse.y[middle] = 2.75D;
        coarse.xLastTick[middle] = coarse.x[middle] - 0.4D;
        coarse.vx[middle] = 1.5D;
        coarse.settledTicks = coarse.settleThresholdTicks;
        coarse.quietTicks = 99;
        coarse.blockHashInit = true;
        coarse.terrainNearbyLast = true;
        coarse.contactNode[middle] = true;

        RopeSimulation fine = new RopeSimulation(A, B, 2L, base.withTopology(0.5D, 64));
        fine.resampleShapeForTopologyChange(coarse, A, B);

        assertTrue(fine.y[fine.nodeCount() / 2] < 3.5D,
                "the visible coarse shape should be preserved instead of restarting at the catenary");
        assertEquals(fine.x[fine.nodeCount() / 2], fine.xLastTick[fine.nodeCount() / 2], 1.0e-9D);
        assertEquals(0.0D, fine.vx[fine.nodeCount() / 2], 1.0e-9D);
        assertEquals(0, fine.settledTicks);
        assertEquals(0, fine.quietTicks);
        assertFalse(fine.blockHashInit);
        assertFalse(fine.terrainNearbyLast);
        assertFalse(fine.contactNode[fine.nodeCount() / 2]);
        assertFalse(fine.isSettled());
    }

    @Test
    void meshShapeRestorePreservesPolylineButInvalidatesPhysicsHistory() {
        RopeSimulation fine = new RopeSimulation(A, B, 3L,
                RopeTuning.localDefaults().withTopology(0.5D, 64));
        fine.settledTicks = fine.settleThresholdTicks;
        fine.quietTicks = 99;
        fine.blockHashInit = true;
        fine.terrainNearbyLast = true;

        float[] sourceX = { 0.0F, 4.0F, 8.0F };
        float[] sourceY = { 4.0F, 2.5F, 4.0F };
        float[] sourceZ = { 0.0F, 0.0F, 0.0F };
        fine.restoreShapeForRefinement(sourceX, sourceY, sourceZ, A, B);

        int middle = fine.nodeCount() / 2;
        assertEquals(2.5D, fine.y[middle], 1.0e-6D);
        assertEquals(A.x, fine.x[0], 1.0e-9D);
        assertEquals(A.y, fine.y[0], 1.0e-9D);
        assertEquals(B.x, fine.x[fine.nodeCount() - 1], 1.0e-9D);
        assertEquals(B.y, fine.y[fine.nodeCount() - 1], 1.0e-9D);
        assertEquals(fine.y[middle], fine.yLastTick[middle], 1.0e-9D);
        assertEquals(0, fine.settledTicks);
        assertEquals(0, fine.quietTicks);
        assertFalse(fine.blockHashInit);
        assertFalse(fine.terrainNearbyLast);
        assertFalse(fine.isSettled());
    }

    @Test
    void terrainChangeWakeInvalidatesSettledCollisionHistoryImmediately() {
        RopeSimulation sim = new RopeSimulation(A, B, 4L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        sim.settledTicks = sim.settleThresholdTicks;
        sim.quietTicks = 99;
        sim.ropeStackQuietTicks = 99;
        sim.blockHashInit = true;
        sim.lastBlockHashCheckTick = 100L;
        sim.terrainNearbyLast = true;
        sim.supportNode[middle] = true;
        sim.contactNode[middle] = true;
        sim.vx[middle] = 1.0D;

        sim.wakeForTerrainChange();

        assertEquals(0, sim.settledTicks);
        assertEquals(0, sim.quietTicks);
        assertEquals(0, sim.ropeStackQuietTicks);
        assertFalse(sim.blockHashInit);
        assertEquals(Long.MIN_VALUE, sim.lastBlockHashCheckTick);
        assertFalse(sim.terrainNearbyLast);
        assertFalse(sim.supportNode[middle]);
        assertFalse(sim.contactNode[middle]);
        assertEquals(0.0D, sim.vx[middle], 1.0e-9D);
        assertFalse(sim.isSettled());
    }

    @Test
    void verticalWindProfileHasNoNetLiftAcrossRope() {
        for (int segments : new int[] { 4, 5, 16, 31 }) {
            double weightedSum = 0.0D;
            for (int node = 1; node < segments; node++) {
                double t = node / (double) segments;
                weightedSum += Math.sin(Math.PI * t)
                        * RopeSimulationStepper.verticalWindProfile(node, segments);
            }
            assertEquals(0.0D, weightedSum, 1.0e-12D,
                    "vertical wind must bend locally without lifting the whole rope");
        }
    }

    @Test
    void verticalWindDoesNotPushPinnedEndpoints() {
        assertEquals(0.0D, RopeSimulationStepper.verticalWindProfile(0, 16), 0.0D);
        assertEquals(0.0D, RopeSimulationStepper.verticalWindProfile(16, 16), 0.0D);
    }
}