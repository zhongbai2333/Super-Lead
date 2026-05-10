package com.zhongbai233.super_lead.lead;

import net.minecraft.world.phys.Vec3;

/**
 * Stripped-down Verlet rope simulation that runs on the server. Used exclusively as the
 * source of truth for player-vs-rope contacts, so its only job is to bend believably
 * under gravity between two pinned endpoints; it skips terrain, rope-rope, attachments,
 * substep adaptation, render baking and every other feature of the client simulation.
 *
 * <p>Cost per tick at default settings (8 segments, 6 iterations): roughly
 * 9 + 8 \u00d7 6 \u00d7 (a few flops + 1 sqrt) \u2248 a few hundred floating-point operations
 * \u2014 an order of magnitude cheaper than the client sim.
 */
public final class ServerRopeVerlet {
    public static final int DEFAULT_SEGMENTS  = 8;
    public static final int DEFAULT_ITERATIONS = 6;
    public static final double DEFAULT_GRAVITY = -0.018D; // blocks / tick^2
    public static final double DEFAULT_DAMPING = 0.985D;

    private final int segments;
    private final int nodes;
    private final int iterations;
    private final double[] x, y, z;
    private final double[] xPrev, yPrev, zPrev;
    private double restLen;
    private boolean initialized;

    public ServerRopeVerlet() {
        this(DEFAULT_SEGMENTS, DEFAULT_ITERATIONS);
    }

    public ServerRopeVerlet(int segments, int iterations) {
        this.segments = segments;
        this.nodes = segments + 1;
        this.iterations = iterations;
        this.x = new double[nodes];
        this.y = new double[nodes];
        this.z = new double[nodes];
        this.xPrev = new double[nodes];
        this.yPrev = new double[nodes];
        this.zPrev = new double[nodes];
    }

    public int segments() { return segments; }
    public int nodes()    { return nodes; }
    public double nodeX(int i) { return x[i]; }
    public double nodeY(int i) { return y[i]; }
    public double nodeZ(int i) { return z[i]; }

    /** Reset to a straight line between {@code a} and {@code b}. */
    public void reset(Vec3 a, Vec3 b) {
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            x[i] = a.x + (b.x - a.x) * t;
            y[i] = a.y + (b.y - a.y) * t;
            z[i] = a.z + (b.z - a.z) * t;
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
        }
        restLen = a.distanceTo(b) / segments;
        initialized = true;
    }

    /** Advance one tick. Endpoints are pinned to {@code a} / {@code b}. */
    public void step(Vec3 a, Vec3 b) {
        step(a, b, DEFAULT_GRAVITY, DEFAULT_DAMPING);
    }

    /** Advance one tick using zone-resolved coarse physics parameters. */
    public void step(Vec3 a, Vec3 b, double gravity, double damping) {
        step(a, b, gravity, damping, 1.0D, iterations);
    }

    /** Advance one tick using zone-resolved physics parameters. */
    public void step(Vec3 a, Vec3 b, double gravity, double damping,
            double slackFactor, int constraintIterations) {
        if (!initialized) {
            reset(a, b);
            return;
        }
        double targetSlack = Double.isFinite(slackFactor) ? Math.max(1.0D, slackFactor) : 1.0D;
        double newLen = a.distanceTo(b) * targetSlack / segments;
        int passes = Math.max(1, constraintIterations);
        // Smoothly retarget rest length when the connection moves; without this a sudden
        // change in endpoint distance would over- or under-stretch the rope visibly.
        restLen = restLen * 0.85D + newLen * 0.15D;

        // 1. Verlet integrate interior nodes (apply gravity + inherited velocity).
        for (int i = 1; i < nodes - 1; i++) {
            double vx = (x[i] - xPrev[i]) * damping;
            double vy = (y[i] - yPrev[i]) * damping;
            double vz = (z[i] - zPrev[i]) * damping;
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
            x[i] += vx;
            y[i] += vy + gravity;
            z[i] += vz;
        }

        // 2. Pin endpoints to current connection anchors.
        x[0] = a.x; y[0] = a.y; z[0] = a.z;
        xPrev[0] = a.x; yPrev[0] = a.y; zPrev[0] = a.z;
        int last = nodes - 1;
        x[last] = b.x; y[last] = b.y; z[last] = b.z;
        xPrev[last] = b.x; yPrev[last] = b.y; zPrev[last] = b.z;

        // 3. Distance constraint passes (Gauss-Seidel, alternating direction).
        for (int it = 0; it < passes; it++) {
            if ((it & 1) == 0) {
                for (int s = 0; s < segments; s++) solveDistance(s);
            } else {
                for (int s = segments - 1; s >= 0; s--) solveDistance(s);
            }
            // Re-pin endpoints (the solver above can drift them by up to half a correction).
            x[0] = a.x; y[0] = a.y; z[0] = a.z;
            x[last] = b.x; y[last] = b.y; z[last] = b.z;
        }
    }

    /**
     * Apply a soft player-contact displacement to the rope itself, then re-tighten the chain.
     * The caller supplies a world-space push vector; nodes around {@code t} receive a smooth
     * cosine falloff so a body hit bends a small rope span instead of teleporting one vertex.
     */
    public void applyContact(Vec3 a, Vec3 b, double t, double dx, double dy, double dz, int constraintIterations) {
        if (!initialized) {
            reset(a, b);
            return;
        }
        double center = clamp01(t);
        double window = Math.max(2.5D / segments, 0.20D);
        for (int i = 1; i < nodes - 1; i++) {
            double nodeT = i / (double) segments;
            double dist = Math.abs(nodeT - center);
            if (dist >= window) continue;
            double w = 0.5D * (1.0D + Math.cos(Math.PI * dist / window));
            double px = dx * w;
            double py = dy * w;
            double pz = dz * w;
            x[i] += px;
            y[i] += py;
            z[i] += pz;
            // Move previous positions with the correction so collision displacement changes the
            // shape without injecting a huge artificial Verlet velocity on the next tick.
            xPrev[i] += px;
            yPrev[i] += py;
            zPrev[i] += pz;
        }
        pinEndpoints(a, b);
        int passes = Math.max(1, constraintIterations);
        for (int it = 0; it < passes; it++) {
            if ((it & 1) == 0) {
                for (int s = 0; s < segments; s++) solveDistance(s);
            } else {
                for (int s = segments - 1; s >= 0; s--) solveDistance(s);
            }
            pinEndpoints(a, b);
        }
    }

    private void pinEndpoints(Vec3 a, Vec3 b) {
        x[0] = a.x; y[0] = a.y; z[0] = a.z;
        int last = nodes - 1;
        x[last] = b.x; y[last] = b.y; z[last] = b.z;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private void solveDistance(int seg) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-9D) return;
        double diff = (len - restLen) / len * 0.5D;
        double cx = dx * diff;
        double cy = dy * diff;
        double cz = dz * diff;
        // Both endpoints corrected equally; the per-iteration repin above re-clamps the true
        // endpoints back, so interior nodes effectively converge toward the chain solution.
        if (i != 0) {
            x[i] += cx; y[i] += cy; z[i] += cz;
        }
        if (j != nodes - 1) {
            x[j] -= cx; y[j] -= cy; z[j] -= cz;
        }
    }

    /** Closest-point query against the polyline of nodes. Returns null if the curve is
     *  degenerate. {@code outClosest} (length 4) receives [x, y, z, t] where {@code t} is
     *  the fractional position along the rope (0..1). */
    public double closestPointTo(double px, double py, double pz, double[] outClosest) {
        double bestDistSqr = Double.POSITIVE_INFINITY;
        double totalLen = 0.0D;
        for (int s = 0; s < segments; s++) {
            double sx = x[s + 1] - x[s];
            double sy = y[s + 1] - y[s];
            double sz = z[s + 1] - z[s];
            totalLen += Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        if (totalLen < 1.0e-6D) return Double.POSITIVE_INFINITY;
        double walked = 0.0D;
        for (int s = 0; s < segments; s++) {
            double sx = x[s + 1] - x[s];
            double sy = y[s + 1] - y[s];
            double sz = z[s + 1] - z[s];
            double segLenSqr = sx * sx + sy * sy + sz * sz;
            double segLen = Math.sqrt(segLenSqr);
            double u = 0.0D;
            if (segLenSqr > 1.0e-9D) {
                u = ((px - x[s]) * sx + (py - y[s]) * sy + (pz - z[s]) * sz) / segLenSqr;
                if (u < 0.0D) u = 0.0D; else if (u > 1.0D) u = 1.0D;
            }
            double qx = x[s] + sx * u;
            double qy = y[s] + sy * u;
            double qz = z[s] + sz * u;
            double rx = px - qx, ry = py - qy, rz = pz - qz;
            double dSqr = rx * rx + ry * ry + rz * rz;
            if (dSqr < bestDistSqr) {
                bestDistSqr = dSqr;
                outClosest[0] = qx;
                outClosest[1] = qy;
                outClosest[2] = qz;
                outClosest[3] = (walked + segLen * u) / totalLen;
            }
            walked += segLen;
        }
        return bestDistSqr;
    }
}
