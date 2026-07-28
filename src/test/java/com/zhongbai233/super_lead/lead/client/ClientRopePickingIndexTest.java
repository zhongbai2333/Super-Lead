package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientRopePickingIndexTest {
    private static final UUID ROPE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

        @Test
        void frameReusesAlreadyImmutableRopeWithoutAnotherDeepCopy() {
                RopePickingFrame.Rope source = rope(ROPE,
                                new double[] { 1.0D, 3.0D }, new double[] { 2.0D, 4.0D }, new double[] { 5.0D, 7.0D });

                RopePickingFrame frame = frame(source);

                assertSame(source, frame.ropes().get(0));
        }

    @Test
    void segmentQueryReturnsCandidatesSortedByDistance() {
        RopePickingFrame frame = frame(
                rope(ROPE, new double[] { 2.0D, 2.0D }, new double[] { 0.2D, 1.0D }, new double[] { 0.0D, 0.0D }),
                rope(UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
                        new double[] { 2.0D, 2.0D }, new double[] { 0.1D, 1.0D }, new double[] { 0.0D, 0.0D }));

        List<ClientRopePickingIndex.Candidate> candidates = new ClientRopePickingIndex(frame, 1.0D)
                .queryRay(0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 4.0D, 0.5D);

        assertEquals(2, candidates.size());
        assertEquals("00000000-0000-0000-0000-0000000000a2", candidates.get(0).connectionId().toString());
        assertEquals(0.01D, candidates.get(0).distanceSqr(), 1.0e-9D);
    }

    @Test
    void segmentCrossingSeveralCellsIsFoundAtFarEnd() {
        RopePickingFrame frame = frame(rope(ROPE,
                new double[] { 0.25D, 9.75D }, new double[] { 0.2D, 0.2D }, new double[] { 0.0D, 0.0D }));

        List<ClientRopePickingIndex.Candidate> candidates = new ClientRopePickingIndex(frame, 2.0D)
                .queryRay(8.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 2.0D, 0.3D);

        assertEquals(1, candidates.size());
        assertEquals(0, candidates.get(0).segment());
    }

    @Test
    void candidatePresentInManyCellsIsDeduplicated() {
        RopePickingFrame frame = frame(rope(ROPE,
                new double[] { -4.0D, 4.0D }, new double[] { 0.1D, 0.1D }, new double[] { 0.0D, 0.0D }));

        List<ClientRopePickingIndex.Candidate> candidates = new ClientRopePickingIndex(frame, 1.0D)
                .queryRay(-5.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 10.0D, 0.2D);

        assertEquals(1, candidates.size());
    }

    @Test
    void frameDeepCopiesInputAndReturnedCoordinateArrays() {
        double[] x = { 1.0D, 3.0D };
        double[] y = { 2.0D, 4.0D };
        double[] z = { 5.0D, 7.0D };
        RopePickingFrame.Rope source = rope(ROPE, x, y, z);
        RopePickingFrame frame = frame(source);
        x[0] = 99.0D;
        source.xCopy()[1] = 88.0D;
        double[] exposedCopy = frame.ropes().get(0).xCopy();
        exposedCopy[0] = 77.0D;

        assertEquals(1.0D, frame.ropes().get(0).x(0));
        assertEquals(3.0D, frame.ropes().get(0).x(1));
        assertEquals(new RopePickingFrame.Bounds(1.0D, 2.0D, 5.0D, 3.0D, 4.0D, 7.0D),
                frame.ropes().get(0).bounds());
    }

    @Test
    void nonFiniteQueryValuesAreRejected() {
        ClientRopePickingIndex index = new ClientRopePickingIndex(frame(rope(ROPE,
                new double[] { 0.0D, 1.0D }, new double[] { 0.0D, 0.0D }, new double[] { 0.0D, 0.0D })));

        assertEquals(List.of(), index.queryRay(0.0D, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, Double.POSITIVE_INFINITY, 0.2D));
        assertEquals(List.of(), index.queryRay(Double.NaN, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, 4.0D, 0.2D));
        assertEquals(List.of(), index.queryRay(0.0D, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, 4.0D, Double.POSITIVE_INFINITY));
    }

    @Test
    void hugeQueryFallsBackWithoutScanningEveryCell() {
        ClientRopePickingIndex index = new ClientRopePickingIndex(frame(rope(ROPE,
                new double[] { 2.0D, 3.0D }, new double[] { 0.1D, 0.1D }, new double[] { 0.0D, 0.0D })), 1.0D);

        assertEquals(1, index.queryRay(0.0D, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, 100_000.0D, 0.2D).size());
    }

    @Test
    void extremelyLongSegmentUsesOverflowIndex() {
        ClientRopePickingIndex index = new ClientRopePickingIndex(frame(rope(ROPE,
                new double[] { -100_000.0D, 100_000.0D }, new double[] { 0.1D, 0.1D },
                new double[] { 0.0D, 0.0D })), 1.0D);

        assertEquals(1, index.queryRay(-1.0D, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, 2.0D, 0.2D).size());
    }

    @Test
    void saturatedPositiveCellCoordinateDoesNotOverflowLoop() {
        double huge = Double.MAX_VALUE / 4.0D;
        ClientRopePickingIndex index = new ClientRopePickingIndex(frame(rope(ROPE,
                new double[] { huge, huge }, new double[] { 0.0D, 0.0D },
                new double[] { 0.0D, 0.0D })), 1.0D);

        assertEquals(List.of(), index.queryRay(0.0D, 0.0D, 0.0D,
                1.0D, 0.0D, 0.0D, 1.0D, 0.2D));
    }

    private static RopePickingFrame frame(RopePickingFrame.Rope... ropes) {
        return new RopePickingFrame(42L, List.of(ropes));
    }

    private static RopePickingFrame.Rope rope(UUID id, double[] x, double[] y, double[] z) {
        return new RopePickingFrame.Rope(id, RopePickingFrame.Source.DYNAMIC, x, y, z);
    }
}