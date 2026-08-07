package com.zhongbai233.super_lead.bench;

import net.minecraft.core.BlockPos;

/** Shared geometry and cadence for paired vanilla/network redstone benchmarks. */
final class RedstoneBenchRig {
    static final int COMPONENTS = 16;
    static final int ROPES_PER_COMPONENT = 8;
    static final int TOGGLE_INTERVAL = 4;
    static final int MEASURE_TICKS = 240;
    static final int[][] TARGET_OFFSETS = {
            {-6, 0}, {-4, -4}, {0, -6}, {4, -4},
            {6, 0}, {4, 4}, {0, 6}, {-4, 4}
    };

    private RedstoneBenchRig() {
    }

    static BlockPos base(net.minecraft.server.level.ServerLevel level) {
        return RopeBenchSupport.serverSpawn(level).above(40).offset(-24, 0, -24);
    }

    static BlockPos center(BlockPos base, int component) {
        return base.offset((component % 4) * 16, 0, (component / 4) * 16);
    }

    static BlockPos target(BlockPos center, int rope) {
        return center.offset(TARGET_OFFSETS[rope][0], 0, TARGET_OFFSETS[rope][1]);
    }
}