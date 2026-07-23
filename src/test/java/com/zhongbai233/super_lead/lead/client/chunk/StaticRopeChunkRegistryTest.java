package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class StaticRopeChunkRegistryTest {
    @Test
    void pureClaimExpansionCanBeDebouncedIntoOneRebuild() {
        UUID existing = UUID.randomUUID();
        UUID added = UUID.randomUUID();

        assertTrue(StaticRopeChunkRegistry.shouldDeferClaimExpansion(
                Set.of(existing), Set.of(existing),
                Set.of(existing, added), Set.of(existing, added)));
    }

    @Test
    void claimRemovalIsNeverDeferred() {
        UUID removed = UUID.randomUUID();

        assertFalse(StaticRopeChunkRegistry.shouldDeferClaimExpansion(
                Set.of(removed), Set.of(removed), Set.of(), Set.of()));
    }

    @Test
    void sourceDowngradeIsNeverDeferred() {
        UUID existing = UUID.randomUUID();

        assertFalse(StaticRopeChunkRegistry.shouldDeferClaimExpansion(
                Set.of(existing), Set.of(existing), Set.of(existing), Set.of()));
    }

    @Test
    void claimExpansionDebounceEndsAfterQuietWindow() {
        assertTrue(StaticRopeChunkRegistry.continueClaimExpansionDebounce(100L, 102L, 104L, 3, 8));
        assertFalse(StaticRopeChunkRegistry.continueClaimExpansionDebounce(100L, 102L, 105L, 3, 8));
    }

    @Test
    void claimExpansionDebounceHasAbsoluteDeadline() {
        assertFalse(StaticRopeChunkRegistry.continueClaimExpansionDebounce(100L, 107L, 108L, 3, 8));
        assertFalse(StaticRopeChunkRegistry.continueClaimExpansionDebounce(110L, 110L, 100L, 3, 8));
    }

    @Test
    void unobservedMeshBuildWaitsBeforeRetrying() {
        assertFalse(StaticRopeChunkRegistry.buildRetryDue(100L, 119L, 20));
        assertTrue(StaticRopeChunkRegistry.buildRetryDue(100L, 120L, 20));
        assertFalse(StaticRopeChunkRegistry.buildRetryDue(120L, 121L, 20));
    }

    @Test
    void unsubmittedAndRewoundBuildsCanRetryImmediately() {
        assertTrue(StaticRopeChunkRegistry.buildRetryDue(Long.MIN_VALUE, 100L, 20));
        assertTrue(StaticRopeChunkRegistry.buildRetryDue(100L, 50L, 20));
    }

    @Test
    void pendingInitialSubmissionIsNotQueuedAsRetry() {
        LinkedHashSet<Long> awaiting = new LinkedHashSet<>(List.of(1L));

        Set<Long> queued = StaticRopeChunkRegistry.queueDueUnmeshedRetries(
                awaiting, Set.of(1L), Set.of(), Set.of(1L), Set.of(), Map.of(), 100L, 20, 2);

        assertTrue(queued.isEmpty());
        assertEquals(Set.of(1L), awaiting);
    }

    @Test
    void submittedSectionRemainsAwaitingUntilRetryDeadline() {
        LinkedHashSet<Long> awaiting = new LinkedHashSet<>(List.of(1L));

        Set<Long> queued = StaticRopeChunkRegistry.queueDueUnmeshedRetries(
                awaiting, Set.of(1L), Set.of(), Set.of(), Set.of(), Map.of(1L, 100L), 119L, 20, 2);

        assertTrue(queued.isEmpty());
        assertEquals(Set.of(1L), awaiting);
    }

    @Test
    void dueRetryRemainsAwaitingUntilBuildIsObserved() {
        LinkedHashSet<Long> awaiting = new LinkedHashSet<>(List.of(1L, 2L, 3L));
        Map<Long, Long> submitted = new HashMap<>(Map.of(1L, 100L, 2L, 100L, 3L, 100L));

        Set<Long> queued = StaticRopeChunkRegistry.queueDueUnmeshedRetries(
                awaiting, Set.of(1L, 2L, 3L), Set.of(), Set.of(), Set.of(), submitted, 120L, 20, 2);

        assertEquals(Set.of(1L, 2L), queued);
        assertEquals(Set.of(1L, 2L, 3L), awaiting);
        assertEquals(100L, submitted.get(1L));
    }

    @Test
    void acceptedAndUnpublishedSectionsLeaveAwaitingQueue() {
        LinkedHashSet<Long> awaiting = new LinkedHashSet<>(List.of(1L, 2L, 3L));

        StaticRopeChunkRegistry.queueDueUnmeshedRetries(
                awaiting, Set.of(1L, 2L), Set.of(2L), Set.of(1L), Set.of(), Map.of(), 100L, 20, 2);

        assertEquals(Set.of(1L), awaiting);
    }

    @Test
    void watchdogRunsAtBoundedIntervalsAndAfterClockRewind() {
        assertTrue(StaticRopeChunkRegistry.watchdogProbeDue(Long.MIN_VALUE, 100L, 20));
        assertFalse(StaticRopeChunkRegistry.watchdogProbeDue(100L, 119L, 20));
        assertTrue(StaticRopeChunkRegistry.watchdogProbeDue(100L, 120L, 20));
        assertTrue(StaticRopeChunkRegistry.watchdogProbeDue(100L, 50L, 20));
    }

    @Test
    void dirtyBatchPrioritizesVisibleMeshChangesAndCapsNewMeshes() {
        LinkedHashSet<Long> urgent = new LinkedHashSet<>(List.of(1L, 2L, 3L));
        LinkedHashSet<Long> normal = new LinkedHashSet<>(List.of(10L, 11L, 12L, 13L));

        Set<Long> batch = StaticRopeChunkRegistry.drainDirtyBatch(urgent, normal, 12, 2);

        assertEquals(List.of(1L, 2L, 3L, 10L, 11L), List.copyOf(batch));
        assertTrue(urgent.isEmpty());
        assertEquals(Set.of(12L, 13L), normal);
    }

    @Test
    void urgentDirtySectionsCanUseEntireFrameBudget() {
        LinkedHashSet<Long> urgent = new LinkedHashSet<>();
        for (long key = 0L; key < 14L; key++) {
            urgent.add(key);
        }
        LinkedHashSet<Long> normal = new LinkedHashSet<>(List.of(20L, 21L));

        Set<Long> batch = StaticRopeChunkRegistry.drainDirtyBatch(urgent, normal, 12, 2);

        assertEquals(12, batch.size());
        assertEquals(Set.of(12L, 13L), urgent);
        assertEquals(Set.of(20L, 21L), normal);
    }

    @Test
    void acceptedMeshKeepsOneTickOfCoincidentDynamicFallback() {
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
    void highLodFreezeRequiresThreeDistinctLowMotionSamples() {
        var first = StaticRopeChunkRegistry.advanceExitDebounce(null, 100L, 1.0e-4D);
        var second = StaticRopeChunkRegistry.advanceExitDebounce(first, 104L, 1.0e-4D);
        var third = StaticRopeChunkRegistry.advanceExitDebounce(second, 108L, 1.0e-4D);

        assertEquals(1, first.nonQuietSteps());
        assertEquals(2, second.nonQuietSteps());
        assertEquals(3, third.nonQuietSteps());
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
    void claimedRopeKeepsMeshThroughResidualMotionBand() {
        assertTrue(StaticRopeChunkRegistry.shouldRetainClaim(4.0e-5D));
        assertTrue(StaticRopeChunkRegistry.shouldRetainClaim(4.99e-4D));
    }

    @Test
    void claimedRopeExitsMeshOnHardMotion() {
        assertFalse(StaticRopeChunkRegistry.shouldRetainClaim(5.0e-4D));
        assertFalse(StaticRopeChunkRegistry.shouldRetainClaim(1.0e-2D));
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
    void retirementCanReleaseAsSoonAsClearGenerationIsObserved() {
        assertTrue(StaticRopeChunkRegistry.generationsReached(Map.of(11L, 4L), Map.of(11L, 4L)));
    }

    @Test
    void completedRetirementDoesNotHideDynamicRopeUntilNextMaintenance() {
        assertTrue(StaticRopeChunkRegistry.retirementNeedsStaticFallback(Long.MIN_VALUE));
        assertFalse(StaticRopeChunkRegistry.retirementNeedsStaticFallback(100L));
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

    @Test
    void acceptedAttachmentCanRenderDuringDynamicRopeFallbackTick() {
        UUID connectionId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        RopeAttachmentRenderer.BakedAttachment attachment = bakedAttachment(
                connectionId, UUID.fromString("00000000-0000-0000-0000-000000000013"), 1.0D);

        List<RopeAttachmentRenderer.BakedAttachment> selected =
                StaticRopeChunkRegistry.selectBakedAttachmentsForRender(
                        List.of(attachment), ignored -> false);

        assertEquals(List.of(attachment), selected);
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