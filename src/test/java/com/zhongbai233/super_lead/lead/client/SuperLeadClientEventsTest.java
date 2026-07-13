package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SuperLeadClientEventsTest {

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
}