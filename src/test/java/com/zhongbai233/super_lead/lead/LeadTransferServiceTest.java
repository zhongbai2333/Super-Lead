package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LeadTransferServiceTest {
    @Test
    void aeEndpointChunksDeduplicateSameChunk() {
        LeadConnection connection = LeadConnection.create(
                new LeadAnchor(new BlockPos(0, 64, 0), Direction.UP),
                new LeadAnchor(new BlockPos(8, 64, 8), Direction.NORTH), LeadKind.AE_NETWORK);

        assertEquals(Set.of(SuperLeadSavedData.chunkKey(0, 0)),
                LeadTransferService.aeEndpointChunks(connection));
    }

    @Test
    void unvisitedBranchesPreserveAdjacencyOrder() {
        LeadConnection first = connection("00000000-0000-0000-0000-000000000201", 1);
        LeadConnection visited = connection("00000000-0000-0000-0000-000000000202", 2);
        LeadConnection third = connection("00000000-0000-0000-0000-000000000203", 3);

        List<LeadConnection> branches = LeadTransferService.unvisitedBranches(
                List.of(first, visited, third), Set.of(visited.id()));

        assertEquals(List.of(first, third), branches);
    }

    @Test
    void emptyOrFullyVisitedAdjacencyReturnsCanonicalEmptyList() {
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000204", 4);

        assertSame(List.of(), LeadTransferService.unvisitedBranches(List.of(), Set.of()));
        assertSame(List.of(), LeadTransferService.unvisitedBranches(
                List.of(connection), Set.of(connection.id())));
    }

    @Test
    void thermalPairCursorVisitsEveryPairBeforeWrapping() {
        int endpoints = 4;
        var cursor = LeadTransferService.ThermalPairCursor.START;
        Set<String> pairs = new LinkedHashSet<>();
        long pairCount = LeadTransferService.thermalPairCount(endpoints);

        for (long i = 0; i < pairCount; i++) {
            pairs.add(cursor.first() + ":" + cursor.second());
            cursor = LeadTransferService.advanceThermalPairCursor(cursor, endpoints);
        }

        assertEquals(Set.of("0:1", "0:2", "0:3", "1:2", "1:3", "2:3"), pairs);
        assertEquals(LeadTransferService.ThermalPairCursor.START, cursor);
    }

    @Test
    void thermalPairCursorResetsWhenEndpointCountShrinks() {
        var stale = new LeadTransferService.ThermalPairCursor(3, 5);

        assertEquals(LeadTransferService.ThermalPairCursor.START,
                LeadTransferService.normalizeThermalPairCursor(stale, 3));
        assertEquals(0L, LeadTransferService.thermalPairCount(1));
    }

    @Test
    void thermalSharedPositionBucketIsExpandedOnlyOnce() {
        int connectionCount = 64;
        BlockPos shared = new BlockPos(3, 64, 5);
        List<Integer> neighbors = new ArrayList<>(connectionCount);
        for (int i = 0; i < connectionCount; i++) {
            neighbors.add(i);
        }
        Map<BlockPos, List<Integer>> byPosition = new HashMap<>();
        byPosition.put(shared, neighbors);
        boolean[] visited = new boolean[connectionCount];
        List<Integer> component = new ArrayList<>();
        Set<BlockPos> expanded = new HashSet<>();

        int inspected = 0;
        for (int i = 0; i < connectionCount; i++) {
            inspected += LeadTransferService.addUnvisitedNeighborsByPos(shared, byPosition, visited, component,
                    expanded);
        }

        assertEquals(connectionCount, inspected);
        assertEquals(connectionCount, component.size());
        assertEquals(Set.of(shared), expanded);
    }

    @Test
    void transferSourcesAreGroupedBySourceChunkAndPosition() {
        LeadConnection first = connection("00000000-0000-0000-0000-000000000205", 4)
                .withExtractAnchor(1);
        LeadConnection second = connection("00000000-0000-0000-0000-000000000206", 8)
                .withExtractAnchor(2);
        LeadConnection third = connection("00000000-0000-0000-0000-000000000207", 32)
                .withExtractAnchor(2);
        LeadConnection undirected = connection("00000000-0000-0000-0000-000000000208", 48);
        Map<Long, Map<BlockPos, List<LeadConnection>>> byChunk = new HashMap<>();

        LeadTransferService.indexTransferSource(byChunk, first);
        LeadTransferService.indexTransferSource(byChunk, second);
        LeadTransferService.indexTransferSource(byChunk, third);
        LeadTransferService.indexTransferSource(byChunk, undirected);

        long originChunk = SuperLeadSavedData.chunkKey(0, 0);
        long distantChunk = SuperLeadSavedData.chunkKey(2, 0);
        assertEquals(Set.of(originChunk, distantChunk), byChunk.keySet());
        assertEquals(Set.of(first.from().pos(), second.to().pos()), byChunk.get(originChunk).keySet());
        assertEquals(List.of(third), byChunk.get(distantChunk).get(third.to().pos()));
    }

    private static LeadConnection connection(String id, int x) {
        return new LeadConnection(UUID.fromString(id),
                new LeadAnchor(new BlockPos(0, 64, 0), Direction.UP),
                new LeadAnchor(new BlockPos(x, 64, 0), Direction.UP),
                LeadKind.ITEM, 0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(),
                "", "", LeadConnection.NO_ADVENTURE_OWNER);
    }
}
