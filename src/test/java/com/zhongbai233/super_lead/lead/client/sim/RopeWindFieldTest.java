package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RopeWindFieldTest {
    private final double[] sample = new double[4];

    @Test
    void uniformCellSamplesRemainUnchangedByBilinearBlend() {
        var wind = new RopeSimulationStepper.WindSample(0.60D, 0.60D, 0.80D, 1.25D);

        RopeSimulationStepper.bilinearBlendWind(
                wind, wind, wind, wind, 0.37D, 0.81D, 1.0D, 0.0D, sample);

        assertEquals(0.60D, sample[0], 1.0e-12D);
        assertEquals(0.60D, sample[1], 1.0e-12D);
        assertEquals(0.80D, sample[2], 1.0e-12D);
        assertEquals(1.25D, sample[3], 1.0e-12D);
    }

    @Test
    void oppositeEqualWindsCancelWithoutProducingInvalidDirection() {
        var east = new RopeSimulationStepper.WindSample(1.0D, 1.0D, 0.0D, 1.0D);
        var west = new RopeSimulationStepper.WindSample(1.0D, -1.0D, 0.0D, 1.0D);

        RopeSimulationStepper.bilinearBlendWind(
                east, west, east, west, 0.5D, 0.5D, 0.0D, 1.0D, sample);

        assertEquals(1.0D, sample[0], 1.0e-12D);
        assertEquals(0.0D, sample[3], 1.0e-12D);
        for (double value : sample) {
            assertTrue(Double.isFinite(value));
        }
    }

    @Test
    void calmBlendUsesConfiguredFallbackDirection() {
        var calm = RopeSimulationStepper.WindSample.NONE;

        RopeSimulationStepper.bilinearBlendWind(
                calm, calm, calm, calm, 0.5D, 0.5D, 0.25D, -0.75D, sample);

        assertEquals(0.0D, sample[0], 0.0D);
        assertEquals(0.25D, sample[1], 0.0D);
        assertEquals(-0.75D, sample[2], 0.0D);
        assertEquals(0.0D, sample[3], 0.0D);
    }

    @Test
    void interpolationWeightsSelectExpectedCorner() {
        var northWest = new RopeSimulationStepper.WindSample(0.25D, 1.0D, 0.0D, 0.50D);
        var northEast = new RopeSimulationStepper.WindSample(0.50D, 0.0D, 1.0D, 0.75D);
        var southWest = new RopeSimulationStepper.WindSample(0.75D, -1.0D, 0.0D, 1.00D);
        var southEast = new RopeSimulationStepper.WindSample(1.00D, 0.0D, -1.0D, 1.25D);

        RopeSimulationStepper.bilinearBlendWind(
                northWest, northEast, southWest, southEast,
                1.0D, 1.0D, 1.0D, 0.0D, sample);

        assertEquals(1.0D, sample[0], 1.0e-12D);
        assertEquals(0.0D, sample[1], 1.0e-12D);
        assertEquals(-1.0D, sample[2], 1.0e-12D);
        assertEquals(1.25D, sample[3], 1.0e-12D);
    }

    @Test
    void probeCoordinateKeyUsesExactStableDoubleBits() {
        assertTrue(RopeSimulationStepper.sameBits(12.25D, 12.25D));
        assertTrue(RopeSimulationStepper.sameBits(Double.NaN, Double.NaN));
        assertFalse(RopeSimulationStepper.sameBits(0.0D, -0.0D));
        assertFalse(RopeSimulationStepper.sameBits(12.25D, Math.nextUp(12.25D)));
    }

    @Test
    void probeCacheKeyRejectsTickTuningAndEndpointChanges() {
        Object tuning = new Object();
        double[] endpoints = { 1.0D, 2.0D, 3.0D, 4.0D, 5.0D, 6.0D };

        assertTrue(probeKeyMatches(42L, tuning, endpoints, 42L, tuning, endpoints));
        assertFalse(probeKeyMatches(42L, tuning, endpoints, 43L, tuning, endpoints));
        assertFalse(probeKeyMatches(42L, tuning, endpoints, 42L, new Object(), endpoints));

        double[] moved = endpoints.clone();
        moved[3] = Math.nextUp(moved[3]);
        assertFalse(probeKeyMatches(42L, tuning, endpoints, 42L, tuning, moved));
    }

    @Test
    void quadCacheKeyRejectsTickTuningAndCellChanges() {
        Object tuning = new Object();

        assertTrue(RopeSimulationStepper.windQuadKeyMatches(12L, tuning, -3, 7, 12L, tuning, -3, 7));
        assertFalse(RopeSimulationStepper.windQuadKeyMatches(12L, tuning, -3, 7, 13L, tuning, -3, 7));
        assertFalse(RopeSimulationStepper.windQuadKeyMatches(12L, tuning, -3, 7, 12L, new Object(), -3, 7));
        assertFalse(RopeSimulationStepper.windQuadKeyMatches(12L, tuning, -3, 7, 12L, tuning, -2, 7));
        assertFalse(RopeSimulationStepper.windQuadKeyMatches(12L, tuning, -3, 7, 12L, tuning, -3, 8));
    }

    private static boolean probeKeyMatches(long cachedTick, Object cachedTuning, double[] cached,
            long tick, Object currentTuning, double[] current) {
        return RopeSimulationStepper.windProbeKeyMatches(cachedTick, cachedTuning,
                cached[0], cached[1], cached[2], cached[3], cached[4], cached[5],
                tick, currentTuning,
                current[0], current[1], current[2], current[3], current[4], current[5]);
    }
}