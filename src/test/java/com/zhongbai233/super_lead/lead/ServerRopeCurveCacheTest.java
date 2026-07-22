package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class ServerRopeCurveCacheTest {
    @Test
    void transferStateDoesNotChangeCurveGeometryIdentity() {
        LeadConnection connection = LeadConnection.create(
                new LeadAnchor(new BlockPos(1, 2, 3), Direction.UP),
                new LeadAnchor(new BlockPos(8, 4, 5), Direction.NORTH),
                LeadKind.REDSTONE);

        assertEquals(ServerRopeCurve.connectionGeometry(connection),
                ServerRopeCurve.connectionGeometry(connection.withPower(15).withTier(3)));
    }

    @Test
    void anchorPositionChangesCurveGeometryIdentity() {
        UUID id = UUID.randomUUID();
        LeadConnection first = new LeadConnection(id,
                new LeadAnchor(new BlockPos(1, 2, 3), Direction.UP),
                new LeadAnchor(new BlockPos(8, 4, 5), Direction.NORTH),
                LeadKind.NORMAL, 0, 0, 0, 1, java.util.List.of(), "physics", "", LeadConnection.NO_ADVENTURE_OWNER);
        LeadConnection moved = new LeadConnection(id,
                new LeadAnchor(new BlockPos(2, 2, 3), Direction.UP),
                first.to(), first.kind(), first.power(), first.tier(), first.extractAnchor(), first.lengthUnits(),
                first.attachments(), first.physicsPreset(), first.manualPhysicsPreset(), first.adventureOwner());

        assertNotEquals(ServerRopeCurve.connectionGeometry(first), ServerRopeCurve.connectionGeometry(moved));
    }
}