package com.zhongbai233.super_lead.lead.client.sim;

public interface RopeForceField {
    /**
     * Write per-tick acceleration at world position {@code (wx, wy, wz)} into
     * {@code out[0..2]}. Implementations should add to {@code out}, not overwrite,
     * so
     * multiple fields can be composed.
     */
    void sample(double wx, double wy, double wz, long tick, double[] out3);
}
