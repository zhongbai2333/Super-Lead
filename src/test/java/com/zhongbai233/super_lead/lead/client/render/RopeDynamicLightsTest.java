package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class RopeDynamicLightsTest {
    @Test
    void expensiveLightRefreshRunsAtMostOncePerGameTick() {
        assertTrue(RopeDynamicLights.shouldUpdateForTick(Long.MIN_VALUE, 100L));
        assertFalse(RopeDynamicLights.shouldUpdateForTick(100L, 100L));
        assertTrue(RopeDynamicLights.shouldUpdateForTick(100L, 101L));
        assertTrue(RopeDynamicLights.shouldUpdateForTick(100L, 50L));
    }

    @Test
    void queryRangeIncludesEveryCellTouchedByEffectSphere() {
        RopeDynamicLights.LightCellRange range = RopeDynamicLights.queryCellRange(
                0.5D, 0.5D, 0.5D, 7.75D);

        assertEquals(-1, range.minX());
        assertEquals(1, range.maxX());
        assertEquals(-1, range.minY());
        assertEquals(1, range.maxY());
        assertEquals(-1, range.minZ());
        assertEquals(1, range.maxZ());
    }

    @Test
    void queryRangeUsesFloorForNegativeCoordinates() {
        RopeDynamicLights.LightCellRange range = RopeDynamicLights.queryCellRange(
                -7.25D, -7.25D, -7.25D, 7.75D);

        assertEquals(-2, range.minX());
        assertEquals(0, range.maxX());
        assertEquals(-2, range.minY());
        assertEquals(0, range.maxY());
        assertEquals(-2, range.minZ());
        assertEquals(0, range.maxZ());
    }

    @Test
    void spatialIndexGroupsSourcesByCellWithoutDroppingEntries() {
        Map<BlockPos, RopeDynamicLights.Source> sources = Map.of(
                new BlockPos(0, 0, 0), new RopeDynamicLights.Source(0.5D, 0.5D, 0.5D, 10),
                new BlockPos(1, 0, 0), new RopeDynamicLights.Source(1.5D, 0.5D, 0.5D, 8),
                new BlockPos(100, 0, 0), new RopeDynamicLights.Source(100.5D, 0.5D, 0.5D, 6));

        Map<?, ?> index = RopeDynamicLights.buildSpatialIndex(sources);

        assertEquals(2, index.size());
        assertEquals(3, index.values().stream().mapToInt(value -> ((java.util.List<?>) value).size()).sum());
        assertTrue(index.values().stream().anyMatch(value -> ((java.util.List<?>) value).size() == 2));
    }
}