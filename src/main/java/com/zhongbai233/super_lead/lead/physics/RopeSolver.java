package com.zhongbai233.super_lead.lead.physics;

/** Shared low-level XPBD helpers for client and server rope solvers. */
public final class RopeSolver {
    private static final double EPS = 1.0e-9D;

    private RopeSolver() {
    }

    /**
     * Computes a distance-constraint correction without applying it.
     *
     * @param outCorrection length >= 6; receives i(dx,dy,dz), j(dx,dy,dz)
     * @return true if a correction was produced
     */
    public static boolean computeDistanceCorrection(int seg, double targetLen, double alpha,
            double[] lambda, double[] x, double[] y, double[] z,
            boolean pinnedI, boolean pinnedJ, boolean softInequality,
            double[] outCorrection) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < EPS) {
            return false;
        }
        double c = len - targetLen;
        if (softInequality && c <= 0.0D) {
            return false;
        }
        double wi = pinnedI ? 0.0D : 1.0D;
        double wj = pinnedJ ? 0.0D : 1.0D;
        double wsum = wi + wj;
        if (wsum <= 0.0D) {
            return false;
        }
        double dlambda = (-c - alpha * lambda[seg]) / (wsum + alpha);
        lambda[seg] += dlambda;
        double nx = dx / len;
        double ny = dy / len;
        double nz = dz / len;
        double cx = nx * dlambda;
        double cy = ny * dlambda;
        double cz = nz * dlambda;
        outCorrection[0] = -cx * wi;
        outCorrection[1] = -cy * wi;
        outCorrection[2] = -cz * wi;
        outCorrection[3] = cx * wj;
        outCorrection[4] = cy * wj;
        outCorrection[5] = cz * wj;
        return true;
    }
}