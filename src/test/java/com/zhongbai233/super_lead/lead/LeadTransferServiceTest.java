package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LeadTransferServiceTest {
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

    private static LeadConnection connection(String id, int x) {
        return new LeadConnection(UUID.fromString(id),
                new LeadAnchor(new BlockPos(0, 64, 0), Direction.UP),
                new LeadAnchor(new BlockPos(x, 64, 0), Direction.UP),
                LeadKind.ITEM, 0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(),
                "", "", LeadConnection.NO_ADVENTURE_OWNER);
    }
}