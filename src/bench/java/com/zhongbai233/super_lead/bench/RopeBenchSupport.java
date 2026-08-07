package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Shared rig plumbing for the rope bench scenarios. Server-thread methods only. */
final class RopeBenchSupport {

    private RopeBenchSupport() {
    }

    static BlockPos serverSpawn(ServerLevel level) {
        return level.getLevelData().getRespawnData().pos();
    }

    /** Places a fence pillar and returns the top (anchor) block position. */
    static BlockPos fencePillar(ServerLevel level, BlockPos base, int height, List<BlockPos> placed) {
        BlockPos top = base;
        for (int i = 0; i < height; i++) {
            top = base.above(i);
            level.setBlockAndUpdate(top, Blocks.OAK_FENCE.defaultBlockState());
            placed.add(top);
        }
        return top;
    }

    /** Connects two anchor tops on their UP faces; throws when refused. */
    static LeadConnection connectTops(ServerLevel level, BlockPos a, BlockPos b) {
        return connectTops(level, a, b, LeadKind.NORMAL, LeadConnection.MIN_LENGTH_UNITS);
    }

    static LeadConnection connectTops(ServerLevel level, BlockPos a, BlockPos b,
            LeadKind kind, int lengthUnits) {
        LeadConnection connection = SuperLeadNetwork.connect(level,
                new LeadAnchor(a, Direction.UP), new LeadAnchor(b, Direction.UP),
                kind, null, lengthUnits);
        if (connection == null) {
            throw new IllegalStateException("connect() refused " + a + " -> " + b);
        }
        return connection;
    }

    /** Connects two anchors on explicit faces; throws when refused. */
    static LeadConnection connectFaces(ServerLevel level, BlockPos a, Direction faceA,
            BlockPos b, Direction faceB) {
        return connectFaces(level, a, faceA, b, faceB,
            LeadKind.NORMAL, LeadConnection.MIN_LENGTH_UNITS);
        }

        static LeadConnection connectFaces(ServerLevel level, BlockPos a, Direction faceA,
            BlockPos b, Direction faceB, LeadKind kind, int lengthUnits) {
        LeadConnection connection = SuperLeadNetwork.connect(level,
                new LeadAnchor(a, faceA), new LeadAnchor(b, faceB),
            kind, null, lengthUnits);
        if (connection == null) {
            throw new IllegalStateException("connect() refused " + a + " " + faceA + " -> " + b + " " + faceB);
        }
        return connection;
    }

    /** Removes the scenario's connections and blocks, then syncs clients. */
    static void teardown(ServerLevel level, Set<UUID> connectionIds, List<BlockPos> blocks) {
        SuperLeadSavedData.get(level).removeIf(connection -> connectionIds.contains(connection.id()));
        for (BlockPos pos : blocks) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        SuperLeadPayloads.sendDirtyToDimension(level);
    }

    static BenchClientPose lookPose(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new BenchClientPose(x, y, z, yaw, pitch);
    }
}
