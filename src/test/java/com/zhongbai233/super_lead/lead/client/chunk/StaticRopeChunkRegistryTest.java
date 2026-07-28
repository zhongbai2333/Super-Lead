package com.zhongbai233.super_lead.lead.client.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

class StaticRopeChunkRegistryTest {
    @Test
    void unchangedPublishedSourceCanReuseItsImmutableBake() {
        UUID id = UUID.randomUUID();
        RopeStaticGeometryResult geometry = new RopeStaticGeometryResult(
                bakedSnapshot(id), Set.of(1L));

        assertTrue(StaticRopeChunkRegistry.canReusePublishedSource(
                id, false, Set.of(), Map.of(id, geometry)));
    }

    @Test
    void changedOrFullyInvalidatedSourceMustBeRebuilt() {
        UUID id = UUID.randomUUID();
        RopeStaticGeometryResult geometry = new RopeStaticGeometryResult(
                bakedSnapshot(id), Set.of(1L));

        assertFalse(StaticRopeChunkRegistry.canReusePublishedSource(
                id, false, Set.of(id), Map.of(id, geometry)));
        assertFalse(StaticRopeChunkRegistry.canReusePublishedSource(
                id, true, Set.of(), Map.of(id, geometry)));
    }

    @Test
    void missingPublishedGeometryCannotBeReused() {
        assertFalse(StaticRopeChunkRegistry.canReusePublishedSource(
                UUID.randomUUID(), false, Set.of(), Map.of()));
    }

    @Test
    void configuredTransparentEditingRopeNeverEntersChunkMesh() {
        assertTrue(StaticRopeChunkRegistry.skipStaticMeshForTransparency(true, false));
    }

    @Test
    void currentlyInvisibleRopeNeverEntersChunkMesh() {
        assertTrue(StaticRopeChunkRegistry.skipStaticMeshForTransparency(false, true));
    }

    @Test
    void ordinaryVisibleRopeRemainsEligibleForChunkMesh() {
        assertFalse(StaticRopeChunkRegistry.skipStaticMeshForTransparency(false, false));
    }

    @Test
    void ropeColorsArePartitionedByAlphaIntoChunkLayers() {
        assertTrue(RopeSectionMeshDriver.colorBelongsToLayer(0xFFFFFFFF, ChunkSectionLayer.SOLID));
        assertFalse(RopeSectionMeshDriver.colorBelongsToLayer(0xFFFFFFFF, ChunkSectionLayer.TRANSLUCENT));
        assertFalse(RopeSectionMeshDriver.colorBelongsToLayer(0x80FFFFFF, ChunkSectionLayer.SOLID));
        assertTrue(RopeSectionMeshDriver.colorBelongsToLayer(0x80FFFFFF, ChunkSectionLayer.TRANSLUCENT));
        assertFalse(RopeSectionMeshDriver.colorBelongsToLayer(0x00FFFFFF, ChunkSectionLayer.SOLID));
        assertFalse(RopeSectionMeshDriver.colorBelongsToLayer(0x00FFFFFF, ChunkSectionLayer.TRANSLUCENT));
    }

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

    // The per-registry motion thresholds and the high-LOD entry debounce were
    // replaced by RopeSimulation's unified at-rest state (entry debounce and exit
    // hysteresis both live in the solver now), so their tests moved to
    // RopeSimulation-level coverage of updateSettleState.

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
    void sharedSectionRebuildPreservesAcceptedBystanders() {
        UUID direct = UUID.randomUUID();
        UUID bystanderA = UUID.randomUUID();
        UUID bystanderB = UUID.randomUUID();
        Map<UUID, Set<Long>> sections = Map.of(
                direct, Set.of(11L),
                bystanderA, Set.of(11L),
                bystanderB, Set.of(11L, 12L));

        Set<UUID> preserved = StaticRopeChunkRegistry.preserveAcceptedConnections(
                Set.of(direct, bystanderA, bystanderB), Set.of(direct), sections, Set.of(11L, 12L));

        assertEquals(Set.of(bystanderA, bystanderB), preserved);
    }

    @Test
    void removedOrUnpublishedBystandersCannotRemainAccepted() {
        UUID removed = UUID.randomUUID();
        UUID missingSection = UUID.randomUUID();
        Map<UUID, Set<Long>> sections = Map.of(missingSection, Set.of(11L, 12L));

        Set<UUID> preserved = StaticRopeChunkRegistry.preserveAcceptedConnections(
                Set.of(removed, missingSection), Set.of(), sections, Set.of(11L));

        assertTrue(preserved.isEmpty());
    }

    @Test
    void lightOnlySharedSectionReplacementKeepsEveryAcceptedConnection() {
        UUID relit = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Map<UUID, Set<Long>> sections = Map.of(relit, Set.of(11L), bystander, Set.of(11L));

        Set<UUID> preserved = StaticRopeChunkRegistry.preserveAcceptedConnections(
                Set.of(relit, bystander), Set.of(), sections, Set.of(11L));

        assertEquals(Set.of(relit, bystander), preserved);
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

    private static RopeSectionSnapshot bakedSnapshot(UUID connectionId) {
        return new RopeSectionSnapshot(
                connectionId,
                new double[] { 0.0D, 1.0D }, new double[] { 0.0D, 0.0D }, new double[] { 0.0D, 0.0D },
                new float[] { 1.0F, 1.0F }, new float[] { 0.0F, 0.0F }, new float[] { 0.0F, 0.0F },
                new float[] { 0.0F, 0.0F }, new float[] { 1.0F, 1.0F }, new float[] { 0.0F, 0.0F },
                new int[] { 0, 0 }, new int[] { 0xFFFFFFFF });
    }
}