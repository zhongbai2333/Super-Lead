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
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SuperLeadClientEventsTest {

    @Test
    void roundRobinTraversalWrapsWithoutSkippingEntries() {
        assertEquals(2, SuperLeadClientEvents.roundRobinIndex(2, 0, 4));
        assertEquals(3, SuperLeadClientEvents.roundRobinIndex(2, 1, 4));
        assertEquals(0, SuperLeadClientEvents.roundRobinIndex(2, 2, 4));
        assertEquals(1, SuperLeadClientEvents.roundRobinIndex(2, 3, 4));
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
    void parrotForceSnapshotsAreReusedOnlyWithinSameGameTick() {
        assertTrue(SuperLeadClientEvents.canReusePerchForceSnapshot(100L, 100L));
        assertFalse(SuperLeadClientEvents.canReusePerchForceSnapshot(100L, 101L));
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
    void externalContactWakesStaticMesh() {
        RopeSimulation pushed = new RopeSimulation(
                new Vec3(0.0D, 2.0D, 0.0D), new Vec3(4.0D, 2.0D, 0.0D),
                2L, RopeTuning.localDefaults());
        pushed.setExternalContact(100L, 0.5F, 0.2D, 0.0D, 0.0D);

        assertTrue(SuperLeadClientEvents.shouldWakeStaticFromContact(pushed, 100L));
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
}