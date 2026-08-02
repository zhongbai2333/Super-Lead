package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.sim.RopeActivityScheduler;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SuperLeadClientEventsTest {

    @Test
    void pickingIndexIsSkippedWithoutAnInteractionThatQueriesIt() {
        assertFalse(SuperLeadClientEvents.shouldBuildPickingIndex(false, false, false));
        assertTrue(SuperLeadClientEvents.shouldBuildPickingIndex(true, false, false));
        assertTrue(SuperLeadClientEvents.shouldBuildPickingIndex(false, true, false));
        assertTrue(SuperLeadClientEvents.shouldBuildPickingIndex(false, false, true));
    }
    @Test
    void circularNeighborScanVisitsEveryIndexFromRotatedStart() {
        assertEquals(3, SuperLeadClientEvents.circularIndex(3, 0, 5));
        assertEquals(4, SuperLeadClientEvents.circularIndex(3, 1, 5));
        assertEquals(0, SuperLeadClientEvents.circularIndex(3, 2, 5));
        assertEquals(1, SuperLeadClientEvents.circularIndex(3, 3, 5));
        assertEquals(2, SuperLeadClientEvents.circularIndex(3, 4, 5));
    }

    @Test
    void neighborPriorityPrefersCloserBoundsRegardlessOfTraversalOrder() {
        AABB origin = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        AABB near = new AABB(0.5D, 0.0D, 0.0D, 1.5D, 1.0D, 1.0D);
        AABB far = new AABB(3.0D, 0.0D, 0.0D, 4.0D, 1.0D, 1.0D);

        assertTrue(SuperLeadClientEvents.neighborPriorityScore(origin, near)
                < SuperLeadClientEvents.neighborPriorityScore(origin, far));
        assertEquals(SuperLeadClientEvents.neighborPriorityScore(origin, near),
                SuperLeadClientEvents.neighborPriorityScore(near, origin));
    }

            @Test
            void neighborPairKeyIsCanonicalAcrossRotatedScans() {
            assertEquals(SuperLeadClientEvents.neighborPairKey(2, 9),
                SuperLeadClientEvents.neighborPairKey(9, 2));
            assertFalse(SuperLeadClientEvents.neighborPairKey(2, 9)
                == SuperLeadClientEvents.neighborPairKey(2, 10));
            }

    @Test
    void transparentEditingModeTracksToolSelectionTransitions() {
        SuperLeadClientEvents.updateTransparentEditingMode(false);

        assertTrue(SuperLeadClientEvents.updateTransparentEditingMode(true));
        assertFalse(SuperLeadClientEvents.updateTransparentEditingMode(true));
        assertTrue(SuperLeadClientEvents.updateTransparentEditingMode(false));
        assertFalse(SuperLeadClientEvents.updateTransparentEditingMode(false));
    }

    @Test
    void mainHandShearsRevealGlobally() {
        var context = SuperLeadClientEvents.transparentRevealContext(true, true);

        assertTrue(context.globalReveal());
        assertFalse(context.localReveal());
    }

    @Test
    void nonShearsRopeActionsRevealLocally() {
        var context = SuperLeadClientEvents.transparentRevealContext(false, true);

        assertFalse(context.globalReveal());
        assertTrue(context.localReveal());
    }

    @Test
    void noRopeActionHidesTransparentRopes() {
        var context = SuperLeadClientEvents.transparentRevealContext(false, false);

        assertFalse(context.revealsAny());
    }

    @Test
    void roundRobinTraversalWrapsWithoutSkippingEntries() {
        assertEquals(2, SuperLeadClientEvents.roundRobinIndex(2, 0, 4));
        assertEquals(3, SuperLeadClientEvents.roundRobinIndex(2, 1, 4));
        assertEquals(0, SuperLeadClientEvents.roundRobinIndex(2, 2, 4));
        assertEquals(1, SuperLeadClientEvents.roundRobinIndex(2, 3, 4));
    }

    @Test
    void overloadedRoundRobinAdvancesPastConsumedWindow() {
        assertEquals(8, SuperLeadClientEvents.nextRoundRobinStart(0, 32, 8));
        assertEquals(16, SuperLeadClientEvents.nextRoundRobinStart(8, 32, 8));
        assertEquals(0, SuperLeadClientEvents.nextRoundRobinStart(24, 32, 8));
    }

    @Test
    void pacedRoundRobinStillMovesWhenNoBudgetWasConsumed() {
        assertEquals(6, SuperLeadClientEvents.nextRoundRobinStart(5, 32, 0));
        assertEquals(0, SuperLeadClientEvents.nextRoundRobinStart(31, 32, 0));
    }

    @Test
    void constantParrotLoadDoesNotRepeatedlyWakeStaticMesh() {
        UUID rope = UUID.fromString("00000000-0000-0000-0000-000000000101");

        assertTrue(SuperLeadClientEvents.changedMembership(Set.of(), Set.of(rope)).contains(rope));
        assertTrue(SuperLeadClientEvents.changedMembership(Set.of(rope), Set.of(rope)).isEmpty());
        assertTrue(SuperLeadClientEvents.changedMembership(Set.of(rope), Set.of()).contains(rope));
    }

    @Test
    void renderLodLevelTracksConfiguredDistanceBands() {
        assertEquals(0, SuperLeadClientEvents.renderLodLevel(8.0D * 8.0D, 8.0D, 20.0D, 48.0D));
        assertEquals(1, SuperLeadClientEvents.renderLodLevel(8.1D * 8.1D, 8.0D, 20.0D, 48.0D));
        assertEquals(1, SuperLeadClientEvents.renderLodLevel(20.0D * 20.0D, 8.0D, 20.0D, 48.0D));
        assertEquals(2, SuperLeadClientEvents.renderLodLevel(20.1D * 20.1D, 8.0D, 20.0D, 48.0D));
        assertEquals(2, SuperLeadClientEvents.renderLodLevel(48.0D * 48.0D, 8.0D, 20.0D, 48.0D));
        assertEquals(3, SuperLeadClientEvents.renderLodLevel(48.1D * 48.1D, 8.0D, 20.0D, 48.0D));
    }

    @Test
    void acceptedChunkMeshRendersEvenInLod3() {
        assertTrue(SuperLeadClientEvents.shouldUseStaticChunkMeshRender(true, true, false));
        assertFalse(SuperLeadClientEvents.shouldUseStaticChunkMeshRender(true, false, false));
        assertFalse(SuperLeadClientEvents.shouldUseStaticChunkMeshRender(true, true, true));
        assertFalse(SuperLeadClientEvents.shouldUseStaticChunkMeshRender(false, true, false));
    }

    @Test
    void terrainLodStepsImmediatelyWithoutHistory() {
        assertTrue(SuperLeadClientEvents.shouldStepTerrainLod(Long.MIN_VALUE, 100L));
    }

    @Test
    void terrainLodFreezesShapeBetweenSparseUpdates() {
        assertFalse(SuperLeadClientEvents.shouldStepTerrainLod(100L, 103L));
    }

    @Test
    void terrainLodRunsAgainAtIntervalBoundary() {
        assertTrue(SuperLeadClientEvents.shouldStepTerrainLod(100L, 104L));
    }

    @Test
    void entityContactCandidatesAreReusedOnlyWithinSameGameTick() {
        assertTrue(SuperLeadClientEvents.canReuseEntityContactSnapshot(100L, 100L));
        assertFalse(SuperLeadClientEvents.canReuseEntityContactSnapshot(100L, 101L));
        assertFalse(SuperLeadClientEvents.canReuseEntityContactSnapshot(Long.MIN_VALUE, 100L));
    }

    @Test
    void maintainableSimulationMembershipPublishesOncePerGameTick() {
        assertTrue(SuperLeadClientEvents.shouldUpdateMaintainableSimIds(99L, 100L));
        assertFalse(SuperLeadClientEvents.shouldUpdateMaintainableSimIds(100L, 100L));
    }

    @Test
    void clientTickDrivesPhysicsWhenNoRenderCallbackCoveredTheTick() {
        assertTrue(SuperLeadClientEvents.shouldDrivePhysicsTick(99L, 100L));
        assertTrue(SuperLeadClientEvents.shouldDrivePhysicsTick(Long.MIN_VALUE, 100L));
    }

    @Test
    void renderFallbackDoesNotRepeatPhysicsAlreadyDrivenByClientTick() {
        assertFalse(SuperLeadClientEvents.shouldDrivePhysicsTick(100L, 100L));
    }

    @Test
    void physicsDriverRecoversAfterClientGameTimeRewinds() {
        assertTrue(SuperLeadClientEvents.shouldDrivePhysicsTick(100L, 20L));
    }

    @Test
    void parrotForceSnapshotsAreReusedOnlyWithinSameGameTick() {
        assertTrue(SuperLeadClientEvents.canReusePerchForceSnapshot(100L, 100L));
        assertFalse(SuperLeadClientEvents.canReusePerchForceSnapshot(100L, 101L));
    }

    @Test
    void staleEmptySnapshotDoesNotPermanentlyHoldSettledRopeDynamic() {
        assertFalse(SuperLeadClientEvents.snapshotRequiresDynamicHold(100L, 107L, false));
    }

    @Test
    void missingOrActiveSnapshotConservativelyKeepsRopeDynamic() {
        assertTrue(SuperLeadClientEvents.snapshotRequiresDynamicHold(Long.MIN_VALUE, 107L, false));
        assertTrue(SuperLeadClientEvents.snapshotRequiresDynamicHold(100L, 107L, true));
        assertTrue(SuperLeadClientEvents.snapshotRequiresDynamicHold(108L, 107L, false));
    }

    @Test
    void acceptedActiveMeshDoesNotReenterDynamicFromMissingDynamicSnapshots() {
        assertFalse(SuperLeadClientEvents.shouldEvaluateDynamicRelease(true, false));
    }

    @Test
    void claimedMeshStillEvaluatesReleaseWhileDynamicallyLingering() {
        assertTrue(SuperLeadClientEvents.shouldEvaluateDynamicRelease(true, true));
    }

    @Test
    void unacceptedRopeEvaluatesDynamicReleaseReasons() {
        assertTrue(SuperLeadClientEvents.shouldEvaluateDynamicRelease(false, false));
    }

    @Test
    void lowDetailShapeStartsRefinementWhenEnteringPhysicsRange() {
        assertTrue(SuperLeadClientEvents.shouldStartLodRefinement(true, false, 24.0D, 25.0D));
    }

    @Test
    void refinementWakeIsNotRepeatedEveryFrame() {
        assertFalse(SuperLeadClientEvents.shouldStartLodRefinement(true, true, 24.0D, 25.0D));
    }

    @Test
    void lowDetailShapeStaysCachedOutsidePhysicsRange() {
        assertFalse(SuperLeadClientEvents.shouldStartLodRefinement(true, false, 26.0D, 25.0D));
    }

    @Test
    void refinementWaitsUntilStrictlyInsideFineTopologyRange() {
        assertFalse(SuperLeadClientEvents.shouldStartLodRefinement(true, false, 25.0D, 25.0D));
    }

    @Test
    void acceptedStaticMeshIgnoresStableRopeContact() {
        assertFalse(SuperLeadClientEvents.shouldWakeAcceptedStaticFromMotionForTest(
                0.02D * 0.02D, false));
    }

    @Test
    void acceptedStaticMeshStillWakesForDrivenContact() {
        assertTrue(SuperLeadClientEvents.shouldWakeAcceptedStaticFromMotionForTest(
                0.02D * 0.02D, true));
    }

    @Test
    void ropeStackContactForcesImmediatePhysicsAtHighLod() {
        assertTrue(SuperLeadClientEvents.requiresImmediatePhysicsStep(
                false, true, false, false, 0.0D));
    }

    @Test
    void quietIsolatedRopeCanStillUseLodCadence() {
        assertFalse(SuperLeadClientEvents.requiresImmediatePhysicsStep(
                false, false, false, false, 0.0D));
    }

    @Test
    void unchangedTuningCanReuseSimulation() {
        RopeTuning tuning = RopeTuning.localDefaults();

        assertFalse(SuperLeadClientEvents.tuningRequiresRebuild(tuning, tuning));
    }

    @Test
    void changedTuningRebuildsSimulationToRefreshConstructorConstants() {
        RopeTuning current = RopeTuning.localDefaults();
        RopeTuning changed = current.withTopology(current.segmentLength() * 1.25D, current.segmentMax());

        assertTrue(SuperLeadClientEvents.tuningRequiresRebuild(current, changed));
    }

    @Test
    void settledContactDoesNotRepeatedlyWakeStaticMesh() {
        RopeSimulation settled = new RopeSimulation(
                new Vec3(0.0D, 2.0D, 0.0D), new Vec3(4.0D, 2.0D, 0.0D),
                1L, RopeTuning.localDefaults());

        assertFalse(SuperLeadClientEvents.shouldWakeStaticFromContact(settled, 100L));
    }

    @Test
    void externalContactWithoutMotionDoesNotChurnStaticMesh() {
        RopeSimulation pushed = new RopeSimulation(
                new Vec3(0.0D, 2.0D, 0.0D), new Vec3(4.0D, 2.0D, 0.0D),
                2L, RopeTuning.localDefaults());
        pushed.setExternalContact(100L, 0.5F, 0.2D, 0.0D, 0.0D);

        assertFalse(SuperLeadClientEvents.shouldWakeStaticFromContact(pushed, 100L));
    }

    @Test
    void stableStackCorrectionDoesNotWakeStaticMesh() {
        assertFalse(SuperLeadClientEvents.shouldWakeStaticFromContact(1.0e-4D, false));
    }

    @Test
    void visibleUndrivenImpactWakesStaticMesh() {
        assertTrue(SuperLeadClientEvents.shouldWakeStaticFromContact(4.0e-4D, false));
    }

    @Test
    void drivenContactUsesSensitiveWakeThreshold() {
        assertTrue(SuperLeadClientEvents.shouldWakeStaticFromContact(4.0e-5D, true));
    }

    @Test
    void sleepingStaticContactDoesNotRequireReverseNeighborList() {
        HashSet<UUID> staticContacts = new HashSet<>();

        SuperLeadClientEvents.recordReverseNeighborOrStaticWake(
                true, false, UUID.randomUUID(), staticContacts, null, null);

        assertTrue(staticContacts.isEmpty());
    }

    @Test
    void activeStaticContactRecordsWakeWithoutReverseNeighborList() {
        HashSet<UUID> staticContacts = new HashSet<>();
        UUID id = UUID.randomUUID();

        SuperLeadClientEvents.recordReverseNeighborOrStaticWake(
                true, true, id, staticContacts, null, null);

        assertTrue(staticContacts.contains(id));
    }

    @Test
    void dynamicContactAddsReverseNeighbor() {
        RopeSimulation sim = new RopeSimulation(
                new Vec3(0.0D, 2.0D, 0.0D), new Vec3(4.0D, 2.0D, 0.0D),
                3L, RopeTuning.localDefaults());
        ArrayList<RopeSimulation> reverseNeighbors = new ArrayList<>();

        SuperLeadClientEvents.recordReverseNeighborOrStaticWake(
                false, false, UUID.randomUUID(), new HashSet<>(), reverseNeighbors, sim);

        assertEquals(1, reverseNeighbors.size());
        assertTrue(reverseNeighbors.contains(sim));
    }

    @Test
    void staticAttachmentKeepsRopePointAsSwingSupport() {
        RopeAttachmentRenderer.BakedAttachment attachment = new RopeAttachmentRenderer.BakedAttachment(
                UUID.randomUUID(), UUID.randomUUID(), ItemStack.EMPTY, true, false, 1,
                3.0D, 7.0D, 11.0D,
                2.0D, 7.0D, 11.0D,
                4.0D, 7.0D, 11.0D,
                3.0D, 5.5D, 11.0D,
                RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.DISPLAY_DEFAULT,
                RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.OVERRIDE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, Map.of());

        assertEquals(new Vec3(3.0D, 7.0D, 11.0D),
                SuperLeadClientEvents.staticAttachmentSupportPoint(attachment));
    }

    @Test
    void attachmentSwingUsesTickAlignedRopeShape() {
        assertEquals(1.0F, SuperLeadClientEvents.attachmentSwingSamplePartialTick());
    }

    @Test
    void collisionObservedMidTickRestartsOnlyThatTicksRenderInterpolation() {
        var phase = new SuperLeadClientEvents.CollisionRenderPhase(100L, 0.60F);

        assertEquals(0.0F, SuperLeadClientEvents.collisionRenderPartialTick(phase, 100L, 0.60F), 1.0e-6F);
        assertEquals(0.5F, SuperLeadClientEvents.collisionRenderPartialTick(phase, 100L, 0.80F), 1.0e-6F);
        assertEquals(1.0F, SuperLeadClientEvents.collisionRenderPartialTick(phase, 100L, 1.00F), 1.0e-6F);
        assertEquals(0.20F, SuperLeadClientEvents.collisionRenderPartialTick(phase, 101L, 0.20F), 1.0e-6F);
    }

    @Test
    void collisionRenderInterpolationDoesNotRunBeforeContactPhase() {
        var phase = new SuperLeadClientEvents.CollisionRenderPhase(100L, 0.60F);

        assertEquals(0.0F, SuperLeadClientEvents.collisionRenderPartialTick(phase, 100L, 0.40F), 1.0e-6F);
        assertEquals(0.40F, SuperLeadClientEvents.collisionRenderPartialTick(null, 100L, 0.40F), 1.0e-6F);
    }

    @Test
    void meshCollisionWakesImmediatelyBeforeCurrentTicksPhysicsPass() {
        assertFalse(SuperLeadClientEvents.shouldQueueMeshCollisionWake(99L, 100L, false));
    }

    @Test
    void meshCollisionQueuesAfterCurrentTicksPhysicsPass() {
        assertTrue(SuperLeadClientEvents.shouldQueueMeshCollisionWake(100L, 100L, false));
    }

    @Test
    void queuedMeshCollisionIsConsumedOnNextTick() {
        assertFalse(SuperLeadClientEvents.shouldQueueMeshCollisionWake(100L, 101L, true));
    }

    @Test
    void repeatedCollisionKeepsEarliestRenderPhaseWithinTick() {
        var first = new SuperLeadClientEvents.CollisionRenderPhase(100L, 0.25F);

        assertEquals(first, SuperLeadClientEvents.updatedCollisionRenderPhase(first, 100L, 0.70F));
        assertEquals(0.10F,
                SuperLeadClientEvents.updatedCollisionRenderPhase(first, 101L, 0.10F).partialTick(),
                1.0e-6F);
    }

    @Test
    void playerContactRestartsInterpolationOnlyOnContactEdge() {
        assertTrue(SuperLeadClientEvents.shouldRestartContactRenderInterpolation(null, 100L));
        assertFalse(SuperLeadClientEvents.shouldRestartContactRenderInterpolation(100L, 100L));
        assertFalse(SuperLeadClientEvents.shouldRestartContactRenderInterpolation(100L, 101L));
    }

    @Test
    void playerContactRestartsAfterGapOrClockRewind() {
        assertTrue(SuperLeadClientEvents.shouldRestartContactRenderInterpolation(100L, 102L));
        assertTrue(SuperLeadClientEvents.shouldRestartContactRenderInterpolation(100L, 50L));
    }

    @Test
    void playerContactRecoveryKeepsAsyncPhysicsDisabledBriefly() {
        assertTrue(SuperLeadClientEvents.isRecentPlayerContact(100L, 100L));
        assertTrue(SuperLeadClientEvents.isRecentPlayerContact(100L, 102L));
        assertFalse(SuperLeadClientEvents.isRecentPlayerContact(100L, 103L));
        assertFalse(SuperLeadClientEvents.isRecentPlayerContact(100L, 99L));
        assertFalse(SuperLeadClientEvents.isRecentPlayerContact(null, 100L));
    }

    @Test
    void activeTrendOverridesSettledLongInterval() {
        var active = new RopeActivityScheduler.State(
                RopeActivityScheduler.Tier.ACTIVE, 0.5D, 0, 100L);

        assertEquals(2, SuperLeadClientEvents.activityInterval(active, 0.0D, true));
    }

    @Test
    void activeWindForcesContinuousPhysicsEvenWhenActivityWouldThrottle() {
        var active = new RopeActivityScheduler.State(
                RopeActivityScheduler.Tier.ACTIVE, 0.5D, 0, 100L);

        assertEquals(1, SuperLeadClientEvents.scheduledPhysicsInterval(active, 256.0D, true, true));
        assertEquals(2, SuperLeadClientEvents.scheduledPhysicsInterval(active, 256.0D, true, false));
    }

    @Test
    void unsettledRopesCannotEnterSparseVisualPhysicsScheduling() {
        assertTrue(SuperLeadClientEvents.requiresContinuousVisualPhysics(false));
        assertFalse(SuperLeadClientEvents.requiresContinuousVisualPhysics(true));
        assertEquals(1, SuperLeadClientEvents.visualPhysicsInterval(2, false));
        assertEquals(1, SuperLeadClientEvents.visualPhysicsInterval(4, false));
        assertEquals(1, SuperLeadClientEvents.visualPhysicsInterval(8, false));
        assertEquals(4, SuperLeadClientEvents.visualPhysicsInterval(4, true));
    }

    @Test
    void overloadSkipsDiscardPhysicsDebtButPlannedPacingDoesNot() {
        assertTrue(SuperLeadClientEvents.isOverloadSkipState("budget"));
        assertTrue(SuperLeadClientEvents.isOverloadSkipState("wind-budget"));
        assertTrue(SuperLeadClientEvents.isOverloadSkipState("circuit-breaker"));
        assertFalse(SuperLeadClientEvents.isOverloadSkipState("active-skip/2"));
        assertFalse(SuperLeadClientEvents.isOverloadSkipState("idle-skip/8"));
    }
}