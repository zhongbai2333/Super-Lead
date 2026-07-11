package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class RopeNeighborBucketsTest {
    @Test
    void earlyExitStopsCrowdedBucketTraversalImmediately() {
        RopeNeighborBuckets buckets = new RopeNeighborBuckets(4.0D);
        AABB cell = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        for (int i = 0; i < 100; i++) {
            buckets.add(i, cell);
        }

        List<Integer> visited = new ArrayList<>();
        boolean completed = buckets.forEachCandidateWhile(cell, candidate -> {
            visited.add(candidate);
            return visited.size() < 7;
        });

        assertFalse(completed);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), visited);
    }

    @Test
    void fullTraversalReportsCompletion() {
        RopeNeighborBuckets buckets = new RopeNeighborBuckets(4.0D);
        buckets.add(4, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D));
        buckets.add(9, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D));

        List<Integer> visited = new ArrayList<>();
        boolean completed = buckets.forEachCandidateWhile(
                new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                candidate -> {
                    visited.add(candidate);
                    return true;
                });

        assertTrue(completed);
        assertEquals(List.of(4, 9), visited);
    }
}