package com.zhongbai233.super_lead.lead.client.sim;

/**
 * Builds a stable proxy curve from raw rope solver nodes.
 * <p>
 * The solver nodes are intentionally allowed to stretch/compress temporarily
 * while
 * XPBD corrections propagate. Rendering and gameplay contact are much more
 * stable
 * if they see a smoothed, arc-length-resampled and distance-projected curve
 * instead of the raw per-segment state.
 */
final class RopeCollisionProxy {
    private static final double EPS = 1.0e-9D;
    private static final int PROJECTION_ITERATIONS = 5;

    private RopeCollisionProxy() {
    }

    static double rebuild(int count,
            double[] srcX, double[] srcY, double[] srcZ,
            double desiredLength,
            double[] outX, double[] outY, double[] outZ, double[] outLen,
            double[] workX, double[] workY, double[] workZ) {
        if (count <= 0)
            return 0.0D;
        if (count == 1) {
            outX[0] = srcX[0];
            outY[0] = srcY[0];
            outZ[0] = srcZ[0];
            outLen[0] = 0.0D;
            return 0.0D;
        }

        smooth(srcX, srcY, srcZ, workX, workY, workZ, count);
        smooth(workX, workY, workZ, outX, outY, outZ, count);
        double smoothedLength = measure(outX, outY, outZ, count);
        if (smoothedLength < EPS) {
            copySource(srcX, srcY, srcZ, outX, outY, outZ, count);
            return computeLengths(outX, outY, outZ, outLen, count);
        }

        resampleUniform(outX, outY, outZ, smoothedLength, workX, workY, workZ, count);
        copySource(workX, workY, workZ, outX, outY, outZ, count);

        double chord = distance(outX[0], outY[0], outZ[0], outX[count - 1], outY[count - 1], outZ[count - 1]);
        double targetTotal = Double.isFinite(desiredLength) && desiredLength > EPS
                ? Math.max(chord, desiredLength)
                : Math.max(chord, smoothedLength);
        double targetSegment = targetTotal / (count - 1);
        for (int it = 0; it < PROJECTION_ITERATIONS; it++) {
            if ((it & 1) == 0) {
                for (int i = 0; i < count - 1; i++) {
                    projectSegment(outX, outY, outZ, i, i + 1, targetSegment, count);
                }
            } else {
                for (int i = count - 2; i >= 0; i--) {
                    projectSegment(outX, outY, outZ, i, i + 1, targetSegment, count);
                }
            }
            outX[0] = srcX[0];
            outY[0] = srcY[0];
            outZ[0] = srcZ[0];
            outX[count - 1] = srcX[count - 1];
            outY[count - 1] = srcY[count - 1];
            outZ[count - 1] = srcZ[count - 1];
        }
        return computeLengths(outX, outY, outZ, outLen, count);
    }

    private static void smooth(double[] srcX, double[] srcY, double[] srcZ,
            double[] outX, double[] outY, double[] outZ, int count) {
        outX[0] = srcX[0];
        outY[0] = srcY[0];
        outZ[0] = srcZ[0];
        outX[count - 1] = srcX[count - 1];
        outY[count - 1] = srcY[count - 1];
        outZ[count - 1] = srcZ[count - 1];
        for (int i = 1; i < count - 1; i++) {
            outX[i] = srcX[i] * 0.50D + (srcX[i - 1] + srcX[i + 1]) * 0.25D;
            outY[i] = srcY[i] * 0.50D + (srcY[i - 1] + srcY[i + 1]) * 0.25D;
            outZ[i] = srcZ[i] * 0.50D + (srcZ[i - 1] + srcZ[i + 1]) * 0.25D;
        }
    }

    private static void resampleUniform(double[] srcX, double[] srcY, double[] srcZ, double totalLength,
            double[] outX, double[] outY, double[] outZ, int count) {
        outX[0] = srcX[0];
        outY[0] = srcY[0];
        outZ[0] = srcZ[0];
        outX[count - 1] = srcX[count - 1];
        outY[count - 1] = srcY[count - 1];
        outZ[count - 1] = srcZ[count - 1];

        int seg = 0;
        double walked = 0.0D;
        double segLen = segmentLength(srcX, srcY, srcZ, seg);
        for (int i = 1; i < count - 1; i++) {
            double target = totalLength * i / (count - 1);
            while (seg < count - 2 && walked + segLen < target) {
                walked += segLen;
                seg++;
                segLen = segmentLength(srcX, srcY, srcZ, seg);
            }
            double local = segLen > EPS ? (target - walked) / segLen : 0.0D;
            if (local < 0.0D)
                local = 0.0D;
            else if (local > 1.0D)
                local = 1.0D;
            outX[i] = srcX[seg] + (srcX[seg + 1] - srcX[seg]) * local;
            outY[i] = srcY[seg] + (srcY[seg + 1] - srcY[seg]) * local;
            outZ[i] = srcZ[seg] + (srcZ[seg + 1] - srcZ[seg]) * local;
        }
    }

    private static void projectSegment(double[] x, double[] y, double[] z,
            int a, int b, double targetLength, int count) {
        double dx = x[b] - x[a];
        double dy = y[b] - y[a];
        double dz = z[b] - z[a];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < EPS)
            return;
        double wa = a == 0 ? 0.0D : 1.0D;
        double wb = b == count - 1 ? 0.0D : 1.0D;
        double w = wa + wb;
        if (w <= 0.0D)
            return;
        double corr = (len - targetLength) / w;
        double nx = dx / len;
        double ny = dy / len;
        double nz = dz / len;
        if (wa > 0.0D) {
            x[a] += nx * corr * wa;
            y[a] += ny * corr * wa;
            z[a] += nz * corr * wa;
        }
        if (wb > 0.0D) {
            x[b] -= nx * corr * wb;
            y[b] -= ny * corr * wb;
            z[b] -= nz * corr * wb;
        }
    }

    private static double measure(double[] x, double[] y, double[] z, int count) {
        double total = 0.0D;
        for (int i = 1; i < count; i++) {
            total += distance(x[i - 1], y[i - 1], z[i - 1], x[i], y[i], z[i]);
        }
        return total;
    }

    private static double computeLengths(double[] x, double[] y, double[] z, double[] outLen, int count) {
        outLen[0] = 0.0D;
        for (int i = 1; i < count; i++) {
            outLen[i] = outLen[i - 1] + distance(x[i - 1], y[i - 1], z[i - 1], x[i], y[i], z[i]);
        }
        return outLen[count - 1];
    }

    private static double segmentLength(double[] x, double[] y, double[] z, int segment) {
        return distance(x[segment], y[segment], z[segment], x[segment + 1], y[segment + 1], z[segment + 1]);
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void copySource(double[] srcX, double[] srcY, double[] srcZ,
            double[] dstX, double[] dstY, double[] dstZ, int count) {
        System.arraycopy(srcX, 0, dstX, 0, count);
        System.arraycopy(srcY, 0, dstY, 0, count);
        System.arraycopy(srcZ, 0, dstZ, 0, count);
    }
}
