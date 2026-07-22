package com.zhongbai233.super_lead.lead.client.sim;

/** Shared inverse-mass distribution for a positional segment contact. */
final class SegmentEndpointCorrection {
    private static final double MIN_DENOMINATOR = 1.0e-9D;

    private SegmentEndpointCorrection() {
    }

    /**
     * Writes correction magnitudes for the segment endpoints into {@code out[0..1]}.
     * Each endpoint is capped at twice the requested contact displacement so a
     * contact close to a pinned endpoint cannot amplify a tiny penetration into a
     * large free-end snap.
     */
    static boolean compute(double segmentT, boolean pinnedA, boolean pinnedB,
            double displacement, double[] out) {
        if (out == null || out.length < 2) {
            return false;
        }
        out[0] = 0.0D;
        out[1] = 0.0D;
        if (!Double.isFinite(segmentT) || !Double.isFinite(displacement)
                || segmentT < 0.0D || segmentT > 1.0D || displacement <= 0.0D) {
            return false;
        }
        double weightA = pinnedA ? 0.0D : 1.0D;
        double weightB = pinnedB ? 0.0D : 1.0D;
        double oneMinusT = 1.0D - segmentT;
        double denominator = weightA * oneMinusT * oneMinusT + weightB * segmentT * segmentT;
        if (denominator < MIN_DENOMINATOR) {
            return false;
        }
        double scale = displacement / denominator;
        double maxEndpointStep = 2.0D * displacement;
        out[0] = Math.min(scale * weightA * oneMinusT, maxEndpointStep);
        out[1] = Math.min(scale * weightB * segmentT, maxEndpointStep);
        return out[0] > 0.0D || out[1] > 0.0D;
    }
}