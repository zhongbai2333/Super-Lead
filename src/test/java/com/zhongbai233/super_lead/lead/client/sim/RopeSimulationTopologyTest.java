package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
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
        void meshExitRestoreAlsoReplacesReusableSimulationShapeAndRenderHistory() {
        RopeSimulation reusable = new RopeSimulation(A, B, 31L,
            RopeTuning.localDefaults().withTopology(0.5D, 64));
        int middle = reusable.nodeCount() / 2;
        reusable.y[middle] = 1.0D;
        reusable.yLastTick[middle] = 0.5D;
        reusable.vy[middle] = 2.0D;

        double[] sourceX = { 0.0D, 4.0D, 8.0D };
        double[] sourceY = { 4.0D, 2.75D, 4.0D };
        double[] sourceZ = { 0.0D, 0.0D, 0.0D };
        reusable.restorePolylineForRefinement(sourceX, sourceY, sourceZ, A, B);

        assertEquals(2.75D, reusable.y[middle], 1.0e-6D,
            "dynamic rendering must resume from the last visible mesh polyline");
        assertEquals(reusable.y[middle], reusable.yLastTick[middle], 1.0e-9D,
            "the first dynamic frame must not interpolate from the stale sim shape");
        assertEquals(0.0D, reusable.vy[middle], 1.0e-9D,
            "stale velocity must not kick the rope during the visual handoff");
        assertFalse(reusable.isSettled());
        }

        @Test
        void meshCollisionTransitionSurvivesAcrossLogicalTicks() {
        double initial = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            100.60D, 100.60D, 3.0D, 0.18D);
        double nextTick = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            101.20D, 100.60D, 3.0D, 0.18D);
        double later = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            102.60D, 100.60D, 3.0D, 0.18D);
        double complete = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            103.60D, 100.60D, 3.0D, 0.18D);

        assertEquals(0.18D, initial, 1.0e-9D,
            "the first collision frame should already show a small response");
        assertTrue(nextTick > initial && nextTick < 1.0D,
            "crossing a logical tick must continue rather than discard the transition");
        assertTrue(later > nextTick && later < 1.0D);
        assertEquals(1.0D, complete, 1.0e-9D);
        }

    @Test
    void meshCollisionTransitionIsActiveImmediatelyAfterMeshRestore() {
        RopeSimulation sim = new RopeSimulation(A, B, 32L,
                RopeTuning.localDefaults().withTopology(0.5D, 64));
        double[] sourceX = { 0.0D, 4.0D, 8.0D };
        double[] sourceY = { 4.0D, 2.75D, 4.0D };
        double[] sourceZ = { 0.0D, 0.0D, 0.0D };

        sim.restorePolylineForRefinement(sourceX, sourceY, sourceZ, A, B);
        sim.beginMeshCollisionRenderTransition(100L, 0.60F);

        assertTrue(sim.hasMeshCollisionRenderTransition(),
                "the dynamic collision path must not replace this with tick-local phase freezing");
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

    @Test
    void catchUpEndpointInterpolationAdvancesMonotonicallyAcrossLogicalTicks() {
        double previous = 0.0D;
        for (int tick = 0; tick < 4; tick++) {
            for (int substep = 0; substep < 2; substep++) {
                double fraction = RopeSimulationStepper.logicalSubstepFraction(tick, substep, 2, 4L);
                assertTrue(fraction > previous,
                        "catch-up endpoint motion must never restart from the old endpoint");
                previous = fraction;
            }
        }
        assertEquals(1.0D, previous, 0.0D);
    }

    @Test
    void singleTickEndpointInterpolationStillCoversWholeMove() {
        assertEquals(0.25D, RopeSimulationStepper.logicalSubstepFraction(0, 0, 4, 1L), 0.0D);
        assertEquals(0.50D, RopeSimulationStepper.logicalSubstepFraction(0, 1, 4, 1L), 0.0D);
        assertEquals(0.75D, RopeSimulationStepper.logicalSubstepFraction(0, 2, 4, 1L), 0.0D);
        assertEquals(1.00D, RopeSimulationStepper.logicalSubstepFraction(0, 3, 4, 1L), 0.0D);
    }

    @Test
    void catchUpEndpointSpeedUsesPerTickDisplacement() {
        assertEquals(1.0D, RopeSimulationStepper.endpointSpeedScale(1L), 0.0D);
        assertEquals(0.25D, RopeSimulationStepper.endpointSpeedScale(4L), 0.0D);
    }

    @Test
    void catchUpRenderInterpolationStartsAtFinalLogicalTick() {
        assertFalse(RopeSimulationStepper.isFinalCatchUpTick(0, 4L));
        assertFalse(RopeSimulationStepper.isFinalCatchUpTick(1, 4L));
        assertFalse(RopeSimulationStepper.isFinalCatchUpTick(2, 4L));
        assertTrue(RopeSimulationStepper.isFinalCatchUpTick(3, 4L));
        assertTrue(RopeSimulationStepper.isFinalCatchUpTick(0, 1L));
    }

    @Test
    void catchUpStepsReplayTheirOwnLogicalTicks() {
        assertEquals(97L, RopeSimulationStepper.catchUpSimulationTick(100L, 4L, 0));
        assertEquals(98L, RopeSimulationStepper.catchUpSimulationTick(100L, 4L, 1));
        assertEquals(99L, RopeSimulationStepper.catchUpSimulationTick(100L, 4L, 2));
        assertEquals(100L, RopeSimulationStepper.catchUpSimulationTick(100L, 4L, 3));
    }

    @Test
    void externalContactDoesNotLeakBackwardIntoCatchUpHistory() {
        RopeSimulation sim = new RopeSimulation(A, B, 5L, RopeTuning.localDefaults());
        sim.setExternalContact(100L, 0.5F, 0.2D, 0.0D, 0.0D);

        assertFalse(sim.hasExternalContact(99L));
        assertTrue(sim.hasExternalContact(100L));
        assertTrue(sim.hasExternalContact(105L));
        assertFalse(sim.hasExternalContact(106L));
    }

    @Test
    void endpointMovementWakesSchedulerBeforeNextLowRateSlot() {
        RopeSimulation sim = new RopeSimulation(A, B, 6L, RopeTuning.localDefaults());

        assertFalse(sim.hasEndpointWakeMovement(A, B),
                "unpublished endpoint history must not create a false wake");

        sim.endpointInit = true;
        sim.lastAx = A.x;
        sim.lastAy = A.y;
        sim.lastAz = A.z;
        sim.lastBx = B.x;
        sim.lastBy = B.y;
        sim.lastBz = B.z;
        double belowThreshold = Math.sqrt(sim.endpointWakeDistanceSqr) * 0.5D;
        double aboveThreshold = Math.sqrt(sim.endpointWakeDistanceSqr) * 1.1D;

        assertFalse(sim.hasEndpointWakeMovement(A.add(belowThreshold, 0.0D, 0.0D), B));
        assertTrue(sim.hasEndpointWakeMovement(A.add(aboveThreshold, 0.0D, 0.0D), B),
                "anchor motion must promote an IDLE rope immediately instead of waiting eight ticks");
    }

    @Test
    void predictionIgnoresEmptySpaceInsideWholeRopeBounds() {
        Vec3 diagonalA = new Vec3(0.0D, 0.0D, 0.0D);
        Vec3 diagonalB = new Vec3(8.0D, 8.0D, 0.0D);
        RopeSimulation sim = new RopeSimulation(diagonalA, diagonalB, 7L, RopeTuning.localDefaults());
        AABB farCornerInsideWholeBounds = new AABB(
                -0.3D, 7.7D, -0.3D,
                0.3D, 8.3D, 0.3D);

        assertEquals(0.0D,
                sim.predictedBoxCollisionRisk(farCornerInsideWholeBounds, 0.1D, 2.0D), 1.0e-9D,
                "a long rope's coarse AABB must not wake physics when every real segment is far away");
    }

    @Test
    void predictionStillRaisesRiskNearActualRopeSegment() {
        RopeSimulation sim = new RopeSimulation(A, B, 8L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        AABB nearMiddle = new AABB(
                sim.currentX(middle) - 0.1D, sim.currentY(middle) - 0.1D, sim.currentZ(middle) - 0.1D,
                sim.currentX(middle) + 0.1D, sim.currentY(middle) + 0.1D, sim.currentZ(middle) + 0.1D);

        assertEquals(1.0D, sim.predictedBoxCollisionRisk(nearMiddle, 0.1D, 2.0D), 1.0e-9D);
    }

    @Test
    void coolingStepRendersContinuouslyAcrossWholeSolveInterval() {
        RopeSimulation sim = new RopeSimulation(A, B, 9L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 4);
        sim.y[middle] = originY + 4.0D;

        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.5F);
        assertEquals(originY + 0.5D, sim.renderY(middle), 1.0e-9D);

        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.0F);
        assertEquals(originY + 2.0D, sim.renderY(middle), 1.0e-9D,
                "a four-tick solve must still be moving halfway through its visual interval");

        sim.setRenderFrameTick(104L);
        sim.prepareRender(0.0F);
        assertEquals(originY + 4.0D, sim.renderY(middle), 1.0e-9D);
    }

    @Test
    void earlyHotUpshiftContinuesFromCurrentlyVisibleShape() {
        RopeSimulation sim = new RopeSimulation(A, B, 10L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 4);
        sim.y[middle] = originY + 4.0D;
        sim.prepareScheduledRenderStep(102L, 1);
        sim.y[middle] = originY + 6.0D;

        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.0F);
        assertEquals(originY + 2.0D, sim.renderY(middle), 1.0e-9D,
                "an early solve must start from the old interval's visible midpoint");

        sim.prepareRender(0.5F);
        assertEquals(originY + 4.0D, sim.renderY(middle), 1.0e-9D);
    }
}