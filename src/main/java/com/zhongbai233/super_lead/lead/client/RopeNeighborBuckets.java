package com.zhongbai233.super_lead.lead.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.world.phys.AABB;

/**
 * Small spatial hash for rope-neighbor lookups.
 */
public final class RopeNeighborBuckets {
    private static final int GRID_KEY_BIAS = 1 << 20;

    private final double gridSize;
    private final Map<Long, List<Integer>> buckets = new HashMap<>();

    public RopeNeighborBuckets(double gridSize) {
        this.gridSize = gridSize;
    }

    public void add(int index, AABB bounds) {
        CellRange range = CellRange.of(bounds, gridSize);
        for (int cx = range.minX; cx <= range.maxX; cx++) {
            for (int cy = range.minY; cy <= range.maxY; cy++) {
                for (int cz = range.minZ; cz <= range.maxZ; cz++) {
                    buckets.computeIfAbsent(cellKey(cx, cy, cz), key -> new ArrayList<>()).add(index);
                }
            }
        }
    }

    public void forEachCandidate(AABB bounds, IntConsumer consumer) {
        CellRange range = CellRange.of(bounds, gridSize);
        for (int cx = range.minX; cx <= range.maxX; cx++) {
            for (int cy = range.minY; cy <= range.maxY; cy++) {
                for (int cz = range.minZ; cz <= range.maxZ; cz++) {
                    List<Integer> candidates = buckets.get(cellKey(cx, cy, cz));
                    if (candidates != null) {
                        for (int candidate : candidates) {
                            consumer.accept(candidate);
                        }
                    }
                }
            }
        }
    }

    private static long cellKey(int x, int y, int z) {
        long px = (x + GRID_KEY_BIAS) & 0x1FFFFFL;
        long py = (y + GRID_KEY_BIAS) & 0x1FFFFFL;
        long pz = (z + GRID_KEY_BIAS) & 0x1FFFFFL;
        return (px << 42) | (py << 21) | pz;
    }

    private record CellRange(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        static CellRange of(AABB bounds, double gridSize) {
            return new CellRange(
                    cell(bounds.minX, gridSize), cell(bounds.maxX, gridSize),
                    cell(bounds.minY, gridSize), cell(bounds.maxY, gridSize),
                    cell(bounds.minZ, gridSize), cell(bounds.maxZ, gridSize));
        }

        private static int cell(double value, double gridSize) {
            return (int) Math.floor(value / gridSize);
        }
    }
}
