package com.zhongbai233.super_lead.lead.client.geom;

/** Cheap render-only cubic smoothing constrained around the physical polyline. */
public final class BoundedHermiteCurve {
    public static final double DEFAULT_MAX_DEVIATION = 0.08D;
    private static final double CHORD_DEVIATION_RATIO = 0.20D;
    private static final double NEIGHBOR_DEVIATION_RATIO = 0.50D;
    private static final double EPS = 1.0e-12D;

    private BoundedHermiteCurve() {
    }

    public static void sampleSegment(
            double[] x, double[] y, double[] z, int segment, double t, double[] out) {
        if (x == null || y == null || z == null || out == null
                || x.length == 0 || y.length != x.length || z.length != x.length || out.length < 3) {
            throw new IllegalArgumentException("curve arrays must be non-null, equally sized, and output length >= 3");
        }
        int last = x.length - 1;
        int p1 = Math.max(0, Math.min(segment, last));
        int p2 = Math.max(0, Math.min(segment + 1, last));
        int p0 = Math.max(0, p1 - 1);
        int p3 = Math.min(last, p2 + 1);
        sample(
                x[p0], y[p0], z[p0],
                x[p1], y[p1], z[p1],
                x[p2], y[p2], z[p2],
                x[p3], y[p3], z[p3],
                t, DEFAULT_MAX_DEVIATION, out);
    }

    public static void sample(
            double p0x, double p0y, double p0z,
            double p1x, double p1y, double p1z,
            double p2x, double p2y, double p2z,
            double p3x, double p3y, double p3z,
            double t, double maxDeviation, double[] out) {
        if (out == null || out.length < 3) {
            throw new IllegalArgumentException("curve output length must be >= 3");
        }
        if (!Double.isFinite(t))
            t = 0.5D;
        if (!Double.isFinite(maxDeviation))
            maxDeviation = maxDeviation > 0.0D ? DEFAULT_MAX_DEVIATION : 0.0D;
        double clampedT = Math.max(0.0D, Math.min(1.0D, t));
        double chordX = p2x - p1x;
        double chordY = p2y - p1y;
        double chordZ = p2z - p1z;
        double chordLength = length(chordX, chordY, chordZ);
        double lx = p1x + chordX * clampedT;
        double ly = p1y + chordY * clampedT;
        double lz = p1z + chordZ * clampedT;
        if (clampedT <= 0.0D || clampedT >= 1.0D || chordLength < EPS || maxDeviation <= 0.0D) {
            setFiniteOrLinear(lx, ly, lz, lx, ly, lz, out);
            return;
        }

        double prevLength = length(p1x - p0x, p1y - p0y, p1z - p0z);
        double nextLength = length(p3x - p2x, p3y - p2y, p3z - p2z);
        double m1x = (p2x - p0x) * 0.5D;
        double m1y = (p2y - p0y) * 0.5D;
        double m1z = (p2z - p0z) * 0.5D;
        double m2x = (p3x - p1x) * 0.5D;
        double m2y = (p3y - p1y) * 0.5D;
        double m2z = (p3z - p1z) * 0.5D;
        double m1Length = length(m1x, m1y, m1z);
        double m1Limit = prevLength < EPS ? chordLength : Math.min(prevLength, chordLength);
        double m1Scale = tangentScale(m1Length, m1Limit);
        m1x *= m1Scale;
        m1y *= m1Scale;
        m1z *= m1Scale;
        double m2Length = length(m2x, m2y, m2z);
        double m2Limit = nextLength < EPS ? chordLength : Math.min(chordLength, nextLength);
        double m2Scale = tangentScale(m2Length, m2Limit);
        m2x *= m2Scale;
        m2y *= m2Scale;
        m2z *= m2Scale;

        double t2 = clampedT * clampedT;
        double t3 = t2 * clampedT;
        double h00 = 2.0D * t3 - 3.0D * t2 + 1.0D;
        double h10 = t3 - 2.0D * t2 + clampedT;
        double h01 = -2.0D * t3 + 3.0D * t2;
        double h11 = t3 - t2;
        double hx = h00 * p1x + h10 * m1x + h01 * p2x + h11 * m2x;
        double hy = h00 * p1y + h10 * m1y + h01 * p2y + h11 * m2y;
        double hz = h00 * p1z + h10 * m1z + h01 * p2z + h11 * m2z;

        double allowed = Math.min(maxDeviation, chordLength * CHORD_DEVIATION_RATIO);
        if (prevLength >= EPS)
            allowed = Math.min(allowed, prevLength * NEIGHBOR_DEVIATION_RATIO);
        if (nextLength >= EPS)
            allowed = Math.min(allowed, nextLength * NEIGHBOR_DEVIATION_RATIO);
        double dx = hx - lx;
        double dy = hy - ly;
        double dz = hz - lz;
        double deviation = length(dx, dy, dz);
        if (deviation > allowed && deviation > EPS) {
            double scale = allowed / deviation;
            hx = lx + dx * scale;
            hy = ly + dy * scale;
            hz = lz + dz * scale;
        }
        setFiniteOrLinear(hx, hy, hz, lx, ly, lz, out);
    }

    private static double tangentScale(double length, double limit) {
        if (length < EPS || limit <= 0.0D)
            return 0.0D;
        return length > limit ? limit / length : 1.0D;
    }

    private static double length(double x, double y, double z) {
        return Math.hypot(Math.hypot(x, y), z);
    }

    private static void setFiniteOrLinear(
            double x, double y, double z, double lx, double ly, double lz, double[] out) {
        out[0] = Double.isFinite(x) ? x : lx;
        out[1] = Double.isFinite(y) ? y : ly;
        out[2] = Double.isFinite(z) ? z : lz;
        if (!Double.isFinite(out[0]))
            out[0] = 0.0D;
        if (!Double.isFinite(out[1]))
            out[1] = 0.0D;
        if (!Double.isFinite(out[2]))
            out[2] = 0.0D;
    }
}