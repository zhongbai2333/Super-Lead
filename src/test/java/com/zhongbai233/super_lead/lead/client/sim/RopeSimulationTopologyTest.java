package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RopeSimulationTopologyTest {
    private static final Vec3 A = new Vec3(0.0D, 4.0D, 0.0D);
    private static final Vec3 B = new Vec3(8.0D, 4.0D, 0.0D);

    @Test
    void horizontalKnotRopeSkipsCollisionOnlyAtItsMatchingEnds() {
        assertTrue(RopeSimulationTerrainConstraints.shouldSkipAnchorCollision(
                false, true, true, true, false, true, false),
                "a horizontal rope must ignore its first knot block without requiring a wall normal");
        assertTrue(RopeSimulationTerrainConstraints.shouldSkipAnchorCollision(
                false, true, true, false, true, false, true),
                "a horizontal rope must ignore its last knot block without requiring a wall normal");
        assertFalse(RopeSimulationTerrainConstraints.shouldSkipAnchorCollision(
                false, true, true, false, true, true, false),
                "the first segments must not ignore the opposite anchor column");
    }

    @Test
    void ordinaryHorizontalAnchorBlocksKeepTheirCollision() {
        assertFalse(RopeSimulationTerrainConstraints.shouldSkipAnchorCollision(
                false, false, false, true, false, true, false),
                "ordinary surface anchors still need their block collision to prevent wall clipping");
        assertTrue(RopeSimulationTerrainConstraints.shouldSkipAnchorCollision(
                true, false, false, true, false, true, false),
                "the existing vertical wall-rope escape behavior must remain intact");
    }

    @Test
    void playerContactBroadPhaseRejectsDistantBoxes() {
        RopeSimulation sim = new RopeSimulation(A, B, 101L, RopeTuning.localDefaults());

        assertNull(sim.findPlayerContact(
                new AABB(100.0D, 100.0D, 100.0D, 101.0D, 102.0D, 101.0D),
                0.25D, 0.18D));
    }

    @Test
    void identicalPlayerContactQueryReusesResultUntilRopeChanges() {
        RopeSimulation sim = new RopeSimulation(A, B, 102L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        AABB contactBox = new AABB(
                sim.currentX(middle) - 0.15D, sim.currentY(middle) - 0.15D,
                sim.currentZ(middle) - 0.15D,
                sim.currentX(middle) + 0.15D, sim.currentY(middle) + 0.15D,
                sim.currentZ(middle) + 0.15D);

        RopeSimulation.ContactSample first = sim.findPlayerContact(contactBox, 0.25D, 0.18D);
        RopeSimulation.ContactSample cached = sim.findPlayerContact(contactBox, 0.25D, 0.18D);
        assertTrue(first != null && first.depth() > 0.0D);
        assertSame(first, cached, "an identical query must not rescan every rope segment");

        sim.y[middle] += 0.05D;
        sim.markBoundsDirty();
        RopeSimulation.ContactSample refreshed = sim.findPlayerContact(contactBox, 0.25D, 0.18D);
        assertTrue(refreshed != null && refreshed.depth() > 0.0D);
        assertNotSame(first, refreshed, "rope state changes must invalidate the cached contact");

        sim.setUseCollisionProxy(true);
        RopeSimulation.ContactSample proxyContact = sim.findPlayerContact(contactBox, 0.25D, 0.18D);
        assertTrue(proxyContact != null && proxyContact.depth() > 0.0D);
        assertNotSame(refreshed, proxyContact,
            "switching to the proxy curve must invalidate raw-curve contact results");
    }

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
    void acceptedChunkMeshFreezesHiddenSimulationAtVisibleShape() {
        RopeSimulation sim = new RopeSimulation(A, B, 33L,
                RopeTuning.localDefaults().withTopology(0.5D, 64));
        int middle = sim.nodeCount() / 2;
        sim.y[middle] = 1.0D;
        sim.vy[middle] = 3.0D;

        double[] sourceX = { 0.0D, 4.0D, 8.0D };
        double[] sourceY = { 4.0D, 2.6D, 4.0D };
        double[] sourceZ = { 0.0D, 0.0D, 0.0D };
        sim.freezeAtStaticMesh(sourceX, sourceY, sourceZ, A, B, 100L);

        assertEquals(2.6D, sim.y[middle], 1.0e-6D);
        assertEquals(sim.y[middle], sim.yLastTick[middle], 1.0e-9D);
        assertEquals(0.0D, sim.vy[middle], 1.0e-9D);
        assertEquals(100L, sim.lastSteppedTick());
        assertTrue(sim.isSettled(), "a frozen static source must remain mesh-eligible");
        assertFalse(sim.hasEndpointWakeMovement(A, B),
                "unchanged anchors must not immediately wake the frozen simulation");
    }

    @Test
    void meshCollisionTransitionCompletesWithinOneLogicalTick() {
        double initial = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            100.60D, 100.60D, 1.0D, 0.18D);
        double midTick = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            101.10D, 100.60D, 1.0D, 0.18D);
        double complete = RopeSimulationRenderCache.meshCollisionTransitionProgress(
            101.60D, 100.60D, 1.0D, 0.18D);

        assertEquals(0.18D, initial, 1.0e-9D,
            "the first collision frame should already show a small response");
        assertTrue(midTick > initial && midTick < 1.0D,
            "the handoff must move continuously during its remaining logical tick");
        assertEquals(1.0D, complete, 1.0e-9D);
    }

    @Test
    void meshCollisionTransitionWaitsForFirstDynamicTargetBeforeAdvancing() {
        RopeSimulation sim = new RopeSimulation(A, B, 321L,
                RopeTuning.localDefaults().withTopology(0.5D, 64));
        double[] sourceX = { 0.0D, 4.0D, 8.0D };
        double[] sourceY = { 4.0D, 2.75D, 4.0D };
        double[] sourceZ = { 0.0D, 0.0D, 0.0D };
        sim.restorePolylineForRefinement(sourceX, sourceY, sourceZ, A, B);
        int middle = sim.nodeCount() / 2;
        double meshY = sim.y[middle];

        sim.beginMeshCollisionRenderTransition(100L, 0.60F);
        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.60F);
        assertEquals(meshY, sim.renderY(middle), 1.0e-6D);

        // Let an entire render tick pass without a physics publication. The handoff
        // must not silently consume its interpolation window while target == origin.
        sim.setRenderFrameTick(101L);
        sim.prepareRender(0.60F);
        assertEquals(meshY, sim.renderY(middle), 1.0e-6D);
        assertTrue(sim.hasMeshCollisionRenderTransition());

        // The first real dynamic target starts a fresh one-tick transition instead
        // of teleporting after the waiting period.
        sim.y[middle] = meshY + 1.0D;
        sim.publishMeshCollisionRenderTarget(101L, 0.60F);
        sim.prepareRender(0.60F);
        assertEquals(meshY + 0.18D, sim.renderY(middle), 1.0e-6D);

        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.60F);
        assertEquals(meshY + 1.0D, sim.renderY(middle), 1.0e-6D);
        assertFalse(sim.hasMeshCollisionRenderTransition());
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

        sim.wakeForPhysicsChange();

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
    void windSchedulerUsesSameEnvelopeThresholdAsForceApplication() {
        assertFalse(RopeSimulationStepper.isWindEnvelopeActive(1.0e-5D));
        assertTrue(RopeSimulationStepper.isWindEnvelopeActive(1.0001e-5D));
        assertTrue(RopeSimulationStepper.isWindEnvelopeActive(0.01D));
    }

    @Test
    void windParallelToRopeDoesNotCompressDistanceConstraints() {
        double[] projected = new double[3];

        RopeSimulationStepper.projectWindOffTangent(
                2.0D, 0.0D, 0.0D, 4.0D, 0.0D, 0.0D, projected);

        assertEquals(0.0D, projected[0], 1.0e-12D);
        assertEquals(0.0D, projected[1], 1.0e-12D);
        assertEquals(0.0D, projected[2], 1.0e-12D);
    }

    @Test
    void windPerpendicularToRopeKeepsItsPhysicalForce() {
        double[] projected = new double[3];

        RopeSimulationStepper.projectWindOffTangent(
                0.0D, 0.5D, 2.0D, 4.0D, 0.0D, 0.0D, projected);

        assertEquals(0.0D, projected[0], 1.0e-12D);
        assertEquals(0.5D, projected[1], 1.0e-12D);
        assertEquals(2.0D, projected[2], 1.0e-12D);
    }

    @Test
    void diagonalWindRemovesOnlyTangentialComponent() {
        double[] projected = new double[3];

        RopeSimulationStepper.projectWindOffTangent(
                1.0D, 0.0D, 1.0D, 1.0D, 0.0D, 0.0D, projected);

        assertEquals(0.0D, projected[0], 1.0e-12D);
        assertEquals(0.0D, projected[1], 1.0e-12D);
        assertEquals(1.0D, projected[2], 1.0e-12D);
    }

    @Test
    void projectedWindIsOrthogonalToArbitrarySlopedRope() {
        double[] projected = new double[3];

        RopeSimulationStepper.projectWindOffTangent(
                0.7D, -0.3D, 1.2D, 2.0D, 3.0D, -4.0D, projected);

        double tangentDot = projected[0] * 2.0D + projected[1] * 3.0D - projected[2] * 4.0D;
        assertEquals(0.0D, tangentDot, 1.0e-12D);
    }

    @Test
    void degenerateLocalRopeSegmentKeepsFiniteWindForce() {
        double[] projected = new double[3];

        RopeSimulationStepper.projectWindOffTangent(
                0.7D, -0.3D, 1.2D, 0.0D, 0.0D, 0.0D, projected);

        assertEquals(0.7D, projected[0], 0.0D);
        assertEquals(-0.3D, projected[1], 0.0D);
        assertEquals(1.2D, projected[2], 0.0D);
    }

    @Test
    void coincidentRopesUseOppositeStableContactNormals() {
        Vec3 forward = RopeSimulationContactConstraints.pairStableSeparation(11L, 29L);
        Vec3 reverse = RopeSimulationContactConstraints.pairStableSeparation(29L, 11L);

        assertEquals(0.0D, forward.add(reverse).lengthSqr(), 1.0e-12D);
        assertEquals(1.0D, forward.lengthSqr(), 1.0e-12D);
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
    void externalContactRejectsNonFiniteInputAtSimulationBoundary() {
        RopeSimulation sim = new RopeSimulation(A, B, 51L, RopeTuning.localDefaults());
        sim.setExternalContact(100L, 0.5F, 0.2D, 0.0D, 0.0D);
        assertTrue(sim.hasExternalContact(100L));

        sim.setExternalContact(101L, Float.NaN, 0.2D, 0.0D, 0.0D);
        assertFalse(sim.hasExternalContact(101L));

        sim.setExternalContact(102L, 0.5F, Double.POSITIVE_INFINITY, 0.0D, 0.0D);
        assertFalse(sim.hasExternalContact(102L));
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
    void delayedPhysicsUsesBoundedVisualFrameGeneration() {
        RopeSimulation sim = new RopeSimulation(A, B, 91L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 2);
        sim.y[middle] = originY + 0.10D;

        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.5F);
        assertTrue(sim.renderY(middle) > originY + 0.10D,
                "a missed physics result should not freeze immediately at the old target");
        assertTrue(sim.renderY(middle) <= originY + 0.18D + 1.0e-9D,
                "frame generation must remain within the per-node safety cap");

        sim.setRenderFrameTick(104L);
        sim.prepareRender(0.0F);
        assertEquals(originY + 0.175D, sim.renderY(middle), 1.0e-9D,
                "extended stalls must stop at the bounded extrapolation limit");
    }

    @Test
    void generatedFrameBecomesOriginWhenPhysicsResumes() {
        RopeSimulation sim = new RopeSimulation(A, B, 92L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 2);
        sim.y[middle] = originY + 0.10D;
        sim.prepareScheduledRenderStep(103L, 2);
        sim.y[middle] = originY + 0.30D;

        sim.setRenderFrameTick(103L);
        sim.prepareRender(0.0F);
        assertEquals(originY + 0.15D, sim.renderY(middle), 1.0e-9D,
                "the resumed solve must continue from the last generated frame without snapping back");
    }

    @Test
    void generatedFramesNeverMovePinnedEndpointsPastPhysicsTargets() {
        RopeSimulation sim = new RopeSimulation(A, B, 93L, RopeTuning.localDefaults());

        sim.prepareScheduledRenderStep(100L, 2);
        sim.x[0] += 1.0D;
        sim.x[sim.nodeCount() - 1] += 1.0D;
        sim.setRenderFrameTick(103L);
        sim.prepareRender(0.0F);

        assertEquals(sim.currentX(0), sim.renderX(0), 1.0e-9D);
        assertEquals(sim.currentX(sim.nodeCount() - 1), sim.renderX(sim.nodeCount() - 1), 1.0e-9D);
    }

    @Test
    void hotUpshiftReturnsToDirectPreviousTickInterpolation() {
        RopeSimulation sim = new RopeSimulation(A, B, 10L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 4);
        sim.y[middle] = originY + 4.0D;
        sim.prepareDirectRenderStep();
        sim.yLastTick[middle] = originY + 2.0D;
        sim.y[middle] = originY + 6.0D;

        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.5F);
        assertEquals(originY + 4.0D, sim.renderY(middle), 1.0e-9D,
            "interval-one rendering must use the ordinary previous-tick interpolation path");
    }

    @Test
    void delayedAsyncPublicationStartsFreshVisualInterval() {
        RopeSimulation live = new RopeSimulation(A, B, 11L, RopeTuning.localDefaults());
        RopeSimulation worker = new RopeSimulation(A, B, 11L, RopeTuning.localDefaults());
        int middle = live.nodeCount() / 2;
        double originY = live.currentY(middle);

        // The solve was submitted at tick 100 but only becomes available at tick 103.
        // Its visual interval must not have elapsed while the worker was pending.
        worker.y[middle] = originY + 4.0D;
        live.setRenderFrameTick(103L);
        live.prepareRender(0.0F);
        live.copyMutableStateFrom(worker);
        live.beginAsyncPublishedRenderStep(103L, 4);

        live.setRenderFrameTick(103L);
        live.prepareRender(0.0F);
        assertEquals(originY, live.renderY(middle), 1.0e-9D,
                "publication must begin at the shape visible before the worker arrived");

        live.setRenderFrameTick(105L);
        live.prepareRender(0.0F);
        assertEquals(originY + 2.0D, live.renderY(middle), 1.0e-9D,
                "the delayed result should animate over its full interval after publication");
    }

    @Test
    void samePartialTickRebuildsCacheAfterScheduledStateChanges() {
        RopeSimulation sim = new RopeSimulation(A, B, 13L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 2);
        sim.y[middle] = originY + 1.0D;
        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.25F);
        assertEquals(originY + 0.125D, sim.renderY(middle), 1.0e-9D);
        long firstGeneration = sim.renderCacheGeneration;

        sim.prepareScheduledRenderStep(102L, 2);
        sim.y[middle] = originY + 2.0D;
        sim.setRenderFrameTick(102L);
        sim.prepareRender(0.25F);

        assertTrue(sim.renderCacheGeneration > firstGeneration);
        assertEquals(sim.renderStateGeneration, sim.renderCacheGeneration);
        assertEquals(102L, sim.renderCacheFrameTick);
        assertTrue(sim.renderY(middle) > originY + 0.125D,
                "same partial tick must not reuse nodes from the previous scheduled interval");
    }

    @Test
    void nextSolveContinuesFromLastActuallyRenderedPartialFrameInProductionOrder() {
        RopeSimulation sim = new RopeSimulation(A, B, 131L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 2);
        sim.y[middle] = originY + 1.0D;
        sim.setRenderFrameTick(100L);
        float displayedPartialTick = 0.20F;
        sim.prepareRender(displayedPartialTick);
        double displayedY = sim.renderY(middle);
        assertTrue(displayedY > originY && displayedY < originY + 1.0D,
            "the first display frame must be inside the old visual interval");

        // Production discovers visible connections and advances their render-frame
        // tick before stepConnectionEntry prepares the next scheduled solve. A new
        // result arriving here must still begin at the actual pixels, not at the old
        // target inferred from integer currentTick.
        sim.setRenderFrameTick(102L);
        sim.prepareScheduledRenderStep(102L, 2);
        sim.y[middle] = originY + 2.0D;
        sim.prepareRender(0.0F);

        assertEquals(displayedY, sim.renderY(middle), 1.0e-9D,
            "new solve must not jump over the unseen remainder of the previous interval");
        sim.prepareRender(0.5F);
        assertEquals(displayedY + (originY + 2.0D - displayedY) * 0.25D,
            sim.renderY(middle), 1.0e-9D);
    }

    @Test
    void changingCollisionProxyInvalidatesPreparedRenderCache() {
        RopeSimulation sim = new RopeSimulation(A, B, 14L, RopeTuning.localDefaults());
        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.5F);
        long rawGeneration = sim.renderCacheGeneration;

        sim.setUseCollisionProxy(true);
        assertFalse(sim.renderCacheValid);
        assertTrue(sim.renderStateGeneration > rawGeneration);

        sim.prepareRender(0.5F);
        assertTrue(sim.renderCacheValid);
        assertTrue(sim.useCollisionProxy);
        assertEquals(sim.renderStateGeneration, sim.renderCacheGeneration);
    }

    @Test
    void stablePhysicsCannotFreezeAnActiveScheduledVisualInterval() {
        RopeSimulation sim = new RopeSimulation(A, B, 15L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 2);
        sim.y[middle] = originY + 1.0D;
        // Reproduces the production combination: physics can report stable while a
        // previously published visual interval still has distance left to display.
        sim.renderStable = true;
        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.10F);
        double earlyY = sim.renderY(middle);
        sim.prepareRender(0.60F);
        double laterY = sim.renderY(middle);

        assertTrue(laterY > earlyY + 0.2D,
                "active scheduled interpolation must not use stable cross-partial cache reuse");
    }

    @Test
    void continuousPhysicsReturnsToDirectPreviousTickInterpolation() {
        RopeSimulation sim = new RopeSimulation(A, B, 16L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.prepareScheduledRenderStep(100L, 4);
        sim.y[middle] = originY + 4.0D;
        sim.prepareDirectRenderStep();
        sim.yLastTick[middle] = originY + 1.0D;
        sim.y[middle] = originY + 3.0D;
        sim.setRenderFrameTick(101L);

        sim.prepareRender(0.25F);
        assertEquals(originY + 1.5D, sim.renderY(middle), 1.0e-9D);
        assertFalse(sim.scheduledRenderActive);
    }

    @Test
    void directNoOpPublicationCannotFreezeAnOldPartialFrame() {
        RopeSimulation sim = new RopeSimulation(A, B, 17L, RopeTuning.localDefaults());
        int middle = sim.nodeCount() / 2;
        double originY = sim.currentY(middle);

        sim.yLastTick[middle] = originY;
        sim.y[middle] = originY + 2.0D;
        sim.setRenderFrameTick(100L);
        sim.prepareRender(0.25F);
        assertEquals(originY + 0.5D, sim.renderY(middle), 1.0e-9D);

        sim.prepareDirectRenderStep();
        sim.yLastTick[middle] = sim.y[middle];
        sim.renderStable = true;
        sim.prepareRender(0.75F);

        assertEquals(originY + 2.0D, sim.renderY(middle), 1.0e-9D,
                "a stable no-op solve must rebuild from its newly published origin");
    }

    @Test
    void oneTickAsyncPublicationUsesWorkerPreviousTickInterpolation() {
        RopeSimulation live = new RopeSimulation(A, B, 18L, RopeTuning.localDefaults());
        RopeSimulation worker = new RopeSimulation(A, B, 18L, RopeTuning.localDefaults());
        int middle = live.nodeCount() / 2;
        double originY = live.currentY(middle);

        live.prepareScheduledRenderStep(100L, 4);
        live.setRenderFrameTick(101L);
        live.prepareRender(0.25F);

        worker.yLastTick[middle] = originY + 1.0D;
        worker.y[middle] = originY + 3.0D;
        live.copyMutableStateFrom(worker);
        live.beginAsyncPublishedRenderStep(101L, 1);

        live.prepareRender(0.0F);
        assertEquals(originY + 1.0D, live.renderY(middle), 1.0e-9D);
        live.prepareRender(0.5F);
        assertEquals(originY + 2.0D, live.renderY(middle), 1.0e-9D);
        live.prepareRender(1.0F);
        assertEquals(originY + 3.0D, live.renderY(middle), 1.0e-9D);

        assertFalse(live.scheduledRenderActive);
    }
}