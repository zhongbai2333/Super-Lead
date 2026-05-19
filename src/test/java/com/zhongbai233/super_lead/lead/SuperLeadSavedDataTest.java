package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SuperLeadSavedDataTest {
    @Test
    void addTracksOwnedReferencedAndDirtyChunks() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000001",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.ITEM);

        data.add(connection);

        Set<Long> expectedChunks = Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(1, 0),
                SuperLeadSavedData.chunkKey(2, 0));
        assertEquals(expectedChunks, data.chunksForConnection(connection.id()));
        assertEquals(expectedChunks, data.allChunkKeys());
        assertEquals(expectedChunks, data.consumeDirtyChunkKeys());
        assertTrue(data.consumeDirtyChunkKeys().isEmpty());
    }

    @Test
    void updateMarksOldAndNewCoveredChunksDirty() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000002",
                new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), LeadKind.NORMAL);
        data.add(connection);
        data.consumeDirtyChunkKeys();

        data.update(connection.id(), old -> new LeadConnection(old.id(), old.from(),
                new LeadAnchor(new BlockPos(-32, 64, 0), Direction.UP), old.kind(), old.power(), old.tier(),
                old.extractAnchor(), old.attachments(), old.physicsPreset(), old.manualPhysicsPreset(),
                old.adventureOwner()), true);

        Set<Long> expectedDirty = Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(1, 0),
                SuperLeadSavedData.chunkKey(2, 0),
                SuperLeadSavedData.chunkKey(-1, 0),
                SuperLeadSavedData.chunkKey(-2, 0));
        assertEquals(expectedDirty, data.consumeDirtyChunkKeys());
        assertEquals(Set.of(
                SuperLeadSavedData.chunkKey(0, 0),
                SuperLeadSavedData.chunkKey(-1, 0),
                SuperLeadSavedData.chunkKey(-2, 0)), data.chunksForConnection(connection.id()));
    }

    @Test
    void kindIndexUpdatesWhenConnectionKindChanges() {
        SuperLeadSavedData data = new SuperLeadSavedData();
        LeadConnection connection = connection("00000000-0000-0000-0000-000000000003",
                new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), LeadKind.NORMAL);
        data.add(connection);

        data.update(connection.id(), old -> old.withKind(LeadKind.FLUID), true);

        assertTrue(data.connectionsOfKindFast(LeadKind.NORMAL).isEmpty());
        assertEquals(1, data.connectionsOfKindFast(LeadKind.FLUID).size());
    }

    private static LeadConnection connection(String id, BlockPos from, BlockPos to, LeadKind kind) {
        return new LeadConnection(UUID.fromString(id), new LeadAnchor(from, Direction.UP),
                new LeadAnchor(to, Direction.UP), kind, 0, 0, 0, List.of(),
                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_PHYSICS_PRESET,
                LeadConnection.NO_ADVENTURE_OWNER);
    }
}
