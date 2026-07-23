package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LeadAnchorTest {
    @Test
    void outsideSurfacePositionFollowsExactHitRatherThanController() {
        LeadAnchor north = new LeadAnchor(new BlockPos(10, 64, 20), Direction.NORTH,
                new Vec3(8.75D, 65.25D, 19.0D));
        LeadAnchor east = new LeadAnchor(new BlockPos(10, 64, 20), Direction.EAST,
                new Vec3(12.0D, 65.25D, 20.5D));

        assertEquals(new BlockPos(8, 65, 18), north.outsideSurfacePos());
        assertEquals(new BlockPos(12, 65, 20), east.outsideSurfacePos());
    }

    @Test
    void exactHitPointRoundTripsThroughSavedDataCodec() {
        LeadAnchor anchor = new LeadAnchor(new BlockPos(10, 64, 20), Direction.NORTH,
                new Vec3(9.25D, 65.75D, 18.0D));

        var encoded = LeadAnchor.CODEC.encodeStart(JsonOps.INSTANCE, anchor).getOrThrow();
        LeadAnchor decoded = LeadAnchor.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(anchor, decoded);
    }

    @Test
    void legacyAnchorWithoutHitPointStillDecodes() {
        var legacy = JsonParser.parseString("{\"pos\":[10,64,20],\"face\":\"north\"}");

        LeadAnchor decoded = LeadAnchor.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();

        assertEquals(new BlockPos(10, 64, 20), decoded.pos());
        assertEquals(Direction.NORTH, decoded.face());
        assertNull(decoded.hitPoint());
    }

    @Test
    void preciseGeometryDoesNotChangeLogicalPortIdentity() {
        LeadAnchor left = new LeadAnchor(new BlockPos(10, 64, 20), Direction.NORTH,
                new Vec3(9.1D, 64.5D, 19.0D));
        LeadAnchor right = new LeadAnchor(new BlockPos(10, 64, 20), Direction.NORTH,
                new Vec3(11.9D, 65.5D, 19.0D));

        assertTrue(left.samePort(right));
        assertFalse(left.equals(right));
        assertEquals(left.logicalPort(), right.logicalPort());
    }
}