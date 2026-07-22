package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LeadEndpointLayoutTest {
    @Test
    void anchorAndNeighborChangesInvalidateCachedShape() {
        Set<BlockPos> anchors = Set.of(new BlockPos(10, 64, 10));

        assertTrue(LeadEndpointLayout.affectsAnchorNeighborhood(anchors, new BlockPos(10, 64, 10)));
        assertTrue(LeadEndpointLayout.affectsAnchorNeighborhood(anchors, new BlockPos(11, 65, 9)));
    }

    @Test
    void distantBlockChangesKeepEndpointCache() {
        Set<BlockPos> anchors = Set.of(new BlockPos(10, 64, 10));

        assertFalse(LeadEndpointLayout.affectsAnchorNeighborhood(anchors, new BlockPos(12, 64, 10)));
        assertFalse(LeadEndpointLayout.affectsAnchorNeighborhood(anchors, new BlockPos(10, 62, 10)));
    }

    @Test
    void nonGeometricConnectionChangesKeepEndpointRevision() {
        LeadClientConnectionCache.clearAll();
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection connection = connection(Direction.UP, new BlockPos(0, 64, 0));
        LeadClientConnectionCache.replaceAll(key, List.of(connection));
        long revision = LeadClientConnectionCache.endpointLayoutRevision(key);

        LeadClientConnectionCache.replaceAll(key, List.of(connection.withPower(15).withTier(3)
                .withLengthUnits(LeadConnection.MAX_LENGTH_UNITS).withPhysicsPreset("soft")));

        assertEquals(revision, LeadClientConnectionCache.endpointLayoutRevision(key));
        assertTrue(LeadClientConnectionCache.revision(key) > revision);
    }

    @Test
    void anchorPositionAndFaceChangesAdvanceEndpointRevision() {
        LeadClientConnectionCache.clearAll();
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection connection = connection(Direction.UP, new BlockPos(0, 64, 0));
        LeadClientConnectionCache.replaceAll(key, List.of(connection));
        long initial = LeadClientConnectionCache.endpointLayoutRevision(key);

        LeadConnection faceChanged = new LeadConnection(connection.id(),
                new LeadAnchor(connection.from().pos(), Direction.NORTH), connection.to(), connection.kind(),
                connection.power(), connection.tier(), connection.extractAnchor(), connection.lengthUnits(),
                connection.attachments(), connection.physicsPreset(), connection.manualPhysicsPreset(),
                connection.adventureOwner());
        LeadClientConnectionCache.replaceAll(key, List.of(faceChanged));
        long faceRevision = LeadClientConnectionCache.endpointLayoutRevision(key);

        LeadConnection moved = new LeadConnection(connection.id(), faceChanged.from(),
                new LeadAnchor(new BlockPos(9, 64, 0), Direction.UP), connection.kind(), connection.power(),
                connection.tier(), connection.extractAnchor(), connection.lengthUnits(), connection.attachments(),
                connection.physicsPreset(), connection.manualPhysicsPreset(), connection.adventureOwner());
        LeadClientConnectionCache.replaceAll(key, List.of(moved));

        assertTrue(faceRevision > initial);
        assertTrue(LeadClientConnectionCache.endpointLayoutRevision(key) > faceRevision);
    }

    @Test
    void inputOrderAndDuplicateIdsDoNotChangeEndpointRevision() {
        LeadClientConnectionCache.clearAll();
        NetworkKey key = new NetworkKey(null, true);
        LeadConnection first = connection(Direction.UP, new BlockPos(0, 64, 0));
        LeadConnection second = new LeadConnection(UUID.randomUUID(), first.from(), first.to(), first.kind(),
                0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(), LeadConnection.NO_PHYSICS_PRESET,
                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_ADVENTURE_OWNER);
        LeadClientConnectionCache.replaceAll(key, List.of(first, second));
        long revision = LeadClientConnectionCache.endpointLayoutRevision(key);

        LeadClientConnectionCache.replaceAll(key, List.of(second, first, first));

        assertEquals(revision, LeadClientConnectionCache.endpointLayoutRevision(key));
    }

    private static LeadConnection connection(Direction fromFace, BlockPos fromPos) {
        return new LeadConnection(UUID.randomUUID(), new LeadAnchor(fromPos, fromFace),
                new LeadAnchor(new BlockPos(8, 64, 0), Direction.UP), LeadKind.REDSTONE,
                0, 0, 0, LeadConnection.MIN_LENGTH_UNITS, List.of(), LeadConnection.NO_PHYSICS_PRESET,
                LeadConnection.NO_PHYSICS_PRESET, LeadConnection.NO_ADVENTURE_OWNER);
    }
}