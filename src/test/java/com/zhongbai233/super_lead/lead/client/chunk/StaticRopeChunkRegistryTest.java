package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class StaticRopeChunkRegistryTest {
    @Test
    void dynamicRenderingKeepsOneAcceptedMeshSafetyTick() {
    assertTrue(StaticRopeChunkRegistry.handoffNeedsDynamicOverlap(
        true, Long.MIN_VALUE, 100L, 100L));
    assertFalse(StaticRopeChunkRegistry.handoffNeedsDynamicOverlap(
        true, Long.MIN_VALUE, 100L, 101L));
    }

    @Test
    void unacceptedClaimUsesBoundedDynamicLinger() {
    assertTrue(StaticRopeChunkRegistry.handoffNeedsDynamicOverlap(
        false, 100L, Long.MIN_VALUE, 102L));
    assertFalse(StaticRopeChunkRegistry.handoffNeedsDynamicOverlap(
        false, 100L, Long.MIN_VALUE, 103L));
    }

    @Test
    void recentWindKeepsRopeDynamicAcrossShortGustGaps() {
        assertTrue(StaticRopeChunkRegistry.isWindCoolingDown(100L, 140L));
        assertFalse(StaticRopeChunkRegistry.isWindCoolingDown(100L, 141L));
        assertFalse(StaticRopeChunkRegistry.isWindCoolingDown(Long.MIN_VALUE, 140L));
        assertFalse(StaticRopeChunkRegistry.isWindCoolingDown(150L, 140L));
    }

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
    void sparseLowMotionSamplesAreDeduplicatedForHighLodEntry() {
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

    @Test
    void retirementWaitsForEverySectionGeneration() {
        Map<Long, Long> targets = Map.of(11L, 3L, 12L, 7L);

        assertFalse(StaticRopeChunkRegistry.generationsReached(targets, Map.of(11L, 3L, 12L, 6L)));
        assertTrue(StaticRopeChunkRegistry.generationsReached(targets, Map.of(11L, 3L, 12L, 7L)));
    }

    @Test
    void staleSectionBuildCannotCompleteRetirement() {
        assertFalse(StaticRopeChunkRegistry.generationsReached(Map.of(11L, 4L), Map.of(11L, 3L)));
        assertTrue(StaticRopeChunkRegistry.generationsReached(Map.of(11L, 4L), Map.of(11L, 5L)));
    }

    @Test
    void bakedAttachmentSelectionKeepsOnlyFirstCopyOfAttachmentId() {
        UUID connectionId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID attachmentId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        RopeAttachmentRenderer.BakedAttachment first = bakedAttachment(connectionId, attachmentId, 1.0D);
        RopeAttachmentRenderer.BakedAttachment duplicate = bakedAttachment(connectionId, attachmentId, 2.0D);

        List<RopeAttachmentRenderer.BakedAttachment> selected =
                StaticRopeChunkRegistry.selectBakedAttachmentsForRender(
                        List.of(first, duplicate), ignored -> false);

        assertEquals(List.of(first), selected);
    }

    private static RopeAttachmentRenderer.BakedAttachment bakedAttachment(
            UUID connectionId, UUID attachmentId, double x) {
        return new RopeAttachmentRenderer.BakedAttachment(
            connectionId, attachmentId, ItemStack.EMPTY, true, false, 1,
                x, 2.0D, 3.0D,
                0.0D, 2.0D, 3.0D,
                4.0D, 2.0D, 3.0D,
                x, 1.5D, 3.0D,
                RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.DISPLAY_DEFAULT,
                RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.OVERRIDE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, Map.of());
    }
}