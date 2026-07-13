package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class RopeTuningTest {
    @Test
    void unchangedTopologyReturnsSameInstance() {
        RopeTuning tuning = RopeTuning.localDefaults();

        assertSame(tuning, tuning.withTopology(tuning.segmentLength(), tuning.segmentMax()));
    }

    @Test
    void repeatedTopologyVariantReturnsCachedInstance() {
        RopeTuning tuning = RopeTuning.localDefaults();
        double coarseSegmentLength = Math.min(0.90D, tuning.segmentLength() * 1.5D);
        int coarseSegmentMax = Math.max(tuning.minSegments(), (int) Math.ceil(tuning.segmentMax() / 1.5D));

        RopeTuning first = tuning.withTopology(coarseSegmentLength, coarseSegmentMax);
        RopeTuning repeated = tuning.withTopology(coarseSegmentLength, coarseSegmentMax);

        assertSame(first, repeated);
    }

    @Test
    void clearingCachesDropsTopologyVariants() {
        RopeTuning tuning = RopeTuning.localDefaults();
        double coarseSegmentLength = Math.min(0.90D, tuning.segmentLength() * 2.0D);
        int coarseSegmentMax = Math.max(tuning.minSegments(), (int) Math.ceil(tuning.segmentMax() / 2.0D));
        RopeTuning beforeClear = tuning.withTopology(coarseSegmentLength, coarseSegmentMax);

        RopeTuning.clearCache();

        RopeTuning refreshed = RopeTuning.localDefaults();
        assertNotSame(tuning, refreshed);
        assertNotSame(beforeClear, refreshed.withTopology(coarseSegmentLength, coarseSegmentMax));
    }
}