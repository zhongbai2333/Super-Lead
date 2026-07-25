package com.zhongbai233.super_lead.lead.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SuperLeadClientPayloadsTest {
    @Test
    void unchangedConnectionsIgnoreSnapshotOrder() {
        LeadConnection first = connection(1);
        LeadConnection second = connection(2);

        assertTrue(SuperLeadClientPayloads.changedConnectionIds(
                List.of(first, second), List.of(second, first)).isEmpty());
    }

    @Test
    void additionsAndRemovalsOnlyDisturbTheirOwnConnections() {
        LeadConnection retained = connection(1);
        LeadConnection removed = connection(2);
        LeadConnection added = connection(3);

        assertEquals(Set.of(removed.id(), added.id()),
                SuperLeadClientPayloads.changedConnectionIds(
                        List.of(retained, removed), List.of(retained, added)));
    }

    @Test
    void contentUpdateOnlyDisturbsChangedConnection() {
        LeadConnection changed = connection(1);
        LeadConnection unchanged = connection(2);

        assertEquals(Set.of(changed.id()),
                SuperLeadClientPayloads.changedConnectionIds(
                        List.of(changed, unchanged), List.of(changed.withPower(15), unchanged)));
    }

    @Test
    void oneUpdateDoesNotDisturbHundredsOfUnrelatedRopes() {
        List<LeadConnection> previous = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            previous.add(connection(index));
        }
        List<LeadConnection> current = new ArrayList<>(previous);
        LeadConnection target = current.get(247);
        current.set(247, target.withTier(3));
        Collections.reverse(current);

        assertEquals(Set.of(target.id()),
                SuperLeadClientPayloads.changedConnectionIds(previous, current));
    }

    private static LeadConnection connection(int index) {
        UUID id = new UUID(0L, index + 1L);
        return new LeadConnection(id,
                new LeadAnchor(new BlockPos(index, 64, 0), Direction.UP),
                new LeadAnchor(new BlockPos(index, 64, 8), Direction.UP),
                LeadKind.REDSTONE, 0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(),
                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_PHYSICS_PRESET,
                LeadConnection.NO_ADVENTURE_OWNER);
    }
}