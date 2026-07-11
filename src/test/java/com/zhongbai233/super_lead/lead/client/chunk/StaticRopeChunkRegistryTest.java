package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class StaticRopeChunkRegistryTest {

    @Test
    void repeatedFramesDoNotCountTheSamePhysicsSample() {
        var first = StaticRopeChunkRegistry.advanceExitDebounce(null, 100L, 1.0e-4D);
        var repeated = StaticRopeChunkRegistry.advanceExitDebounce(first, 100L, 1.0e-4D);

        assertSame(first, repeated);
        assertEquals(1, repeated.nonQuietSteps());
    }

    @Test
    void distinctPhysicsSamplesAccumulateExitEvidence() {
        var first = StaticRopeChunkRegistry.advanceExitDebounce(null, 100L, 1.0e-4D);
        var second = StaticRopeChunkRegistry.advanceExitDebounce(first, 104L, 1.0e-4D);
        var third = StaticRopeChunkRegistry.advanceExitDebounce(second, 108L, 1.0e-4D);

        assertEquals(3, third.nonQuietSteps());
        assertEquals(108L, third.lastSteppedTick());
    }

    @Test
    void lod3EntryCanConfirmThreeSparseLowMotionSamples() {
        var first = StaticRopeChunkRegistry.advanceExitDebounce(null, 100L, 1.0e-4D);
        var repeatedFrame = StaticRopeChunkRegistry.advanceExitDebounce(first, 100L, 1.0e-4D);
        var second = StaticRopeChunkRegistry.advanceExitDebounce(repeatedFrame, 104L, 1.0e-4D);
        var third = StaticRopeChunkRegistry.advanceExitDebounce(second, 108L, 1.0e-4D);

        assertEquals(1, repeatedFrame.nonQuietSteps());
        assertEquals(3, third.nonQuietSteps());
    }

    @Test
    void hardDisturbanceBypassesDebounce() {
        var previous = new StaticRopeChunkRegistry.ExitDebounce(100L, 1);

        assertNull(StaticRopeChunkRegistry.advanceExitDebounce(previous, 104L, 5.0e-4D));
    }

    @Test
    void uninitializedPhysicsSampleDoesNotCount() {
        assertNull(StaticRopeChunkRegistry.advanceExitDebounce(null, Long.MIN_VALUE, 1.0e-4D));
    }

    @Test
    void rewoundStepTickStartsFreshEvidence() {
        var previous = new StaticRopeChunkRegistry.ExitDebounce(100L, 2);
        var reset = StaticRopeChunkRegistry.advanceExitDebounce(previous, 50L, 1.0e-4D);

        assertEquals(1, reset.nonQuietSteps());
        assertEquals(50L, reset.lastSteppedTick());
    }

    @Test
    void lightInfluenceDetectsSegmentWhoseNodesRemainOutside() {
        AABB lightBounds = new AABB(4.0D, 3.0D, -1.0D, 6.0D, 5.0D, 1.0D);

        assertTrue(StaticRopeChunkRegistry.segmentBoundsIntersect(
                0.0D, 4.0D, 0.0D, 10.0D, 4.0D, 0.0D, lightBounds));
    }

    @Test
    void lightInfluenceRejectsDisjointSegmentBounds() {
        AABB lightBounds = new AABB(4.0D, 3.0D, -1.0D, 6.0D, 5.0D, 1.0D);

        assertFalse(StaticRopeChunkRegistry.segmentBoundsIntersect(
                0.0D, 8.0D, 0.0D, 10.0D, 8.0D, 0.0D, lightBounds));
    }
}