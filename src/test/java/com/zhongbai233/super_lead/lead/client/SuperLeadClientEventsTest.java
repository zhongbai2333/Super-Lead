package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}