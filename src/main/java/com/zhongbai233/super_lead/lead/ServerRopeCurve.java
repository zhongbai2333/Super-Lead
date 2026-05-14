package com.zhongbai233.super_lead.lead;

import java.util.Arrays;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Deterministic server-side coarse physics rope used by zipline and contact validation. */
final class ServerRopeCurve {
    private static final double EPS = 1.0e-8D;
    private static final int MIN_SEGMENTS = 4;
    private static final int MAX_SERVER_SEGMENTS = 64;
    private static final int MIN_RELAX_TICKS = 36;
    private static final int MAX_RELAX_TICKS = 96;
    private static final double GRAVITY_SCALE = 1.0D;
    private static final double INITIAL_SAG_FACTOR = 0.06D;
    private static final double MAX_INITIAL_SAG = 8.0D;

    private ServerRopeCurve() {
    }

    static Shape from(ServerLevel level, LeadConnection connection, Vec3 a, Vec3 b) {
        if (connection == null || connection.physicsPreset().isBlank()) {
            return straight(a, b);
        }
        return from(a, b, ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset()));
    }

    static Shape from(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        double chord = a.distanceTo(b);
        if (chord < EPS || tuning == null || !tuning.physicsEnabled() || Math.abs(tuning.gravity()) < EPS) {
            return straight(a, b);
        }

        double targetLength = targetLength(a, b, tuning);
        int segments = segmentCount(chord, tuning);
        int nodes = segments + 1;
        double[] x = new double[nodes];
        double[] y = new double[nodes];
        double[] z = new double[nodes];
        double[] px = new double[nodes];
        double[] py = new double[nodes];
        double[] pz = new double[nodes];
        double[] vx = new double[nodes];
        double[] vy = new double[nodes];
        double[] vz = new double[nodes];
        double[] lambda = new double[segments];

        initialiseNodes(a, b, Math.abs(targetLength - chord), tuning, x, y, z);
        System.arraycopy(x, 0, px, 0, nodes);
        System.arraycopy(y, 0, py, 0, nodes);
        System.arraycopy(z, 0, pz, 0, nodes);

        double targetSegment = targetLength / segments;
        int iterations = Math.max(tuning.iterAir(), (segments + 1) / 2);
        int relaxTicks = relaxTicks(segments, iterations);
        double damping = clamp(tuning.damping(), 0.0D, 0.999D);
        double alpha = Math.max(0.0D, tuning.compliance());
        for (int tick = 0; tick < relaxTicks; tick++) {
            Arrays.fill(lambda, 0.0D);
            for (int i = 1; i < nodes - 1; i++) {
                px[i] = x[i];
                py[i] = y[i];
                pz[i] = z[i];
                vx[i] *= damping;
                vy[i] = vy[i] * damping + tuning.gravity() * GRAVITY_SCALE;
                vz[i] *= damping;
                x[i] += vx[i];
                y[i] += vy[i];
                z[i] += vz[i];
            }
            pin(a, b, x, y, z);

            for (int it = 0; it < iterations; it++) {
                if ((it & 1) == 0) {
                    for (int s = 0; s < segments; s++) {
                        solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                    }
                } else {
                    for (int s = segments - 1; s >= 0; s--) {
                        solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                    }
                }
                pin(a, b, x, y, z);
            }

            for (int i = 1; i < nodes - 1; i++) {
                vx[i] = x[i] - px[i];
                vy[i] = y[i] - py[i];
                vz[i] = z[i] - pz[i];
            }
        }

        return shapeFromNodes(a, b, targetLength, x, y, z);
    }

    static Vec3 point(Shape shape, double rawT) {
        double target = shape.length() * clamp01(rawT);
        int seg = segmentAt(shape, target);
        double start = shape.lengths()[seg];
        double end = shape.lengths()[seg + 1];
        double f = end - start <= EPS ? 0.0D : (target - start) / (end - start);
        return new Vec3(
                shape.x()[seg] + (shape.x()[seg + 1] - shape.x()[seg]) * f,
                shape.y()[seg] + (shape.y()[seg + 1] - shape.y()[seg]) * f,
                shape.z()[seg] + (shape.z()[seg + 1] - shape.z()[seg]) * f);
    }

    static Vec3 tangent(Shape shape, double rawT) {
        double target = shape.length() * clamp01(rawT);
        int seg = segmentAt(shape, target);
        Vec3 tangent = new Vec3(
                shape.x()[seg + 1] - shape.x()[seg],
                shape.y()[seg + 1] - shape.y()[seg],
                shape.z()[seg + 1] - shape.z()[seg]);
        if (tangent.lengthSqr() < EPS * EPS) {
            Vec3 fallback = shape.b().subtract(shape.a());
            return fallback.lengthSqr() < EPS * EPS ? new Vec3(1.0D, 0.0D, 0.0D) : fallback.normalize();
        }
        return tangent.normalize();
    }

    static double distancePointToCurveSqr(Shape shape, Vec3 point, double[] out) {
        double best = Double.POSITIVE_INFINITY;
        double[] candidate = new double[4];
        for (int i = 0; i < shape.x().length - 1; i++) {
            Vec3 a = new Vec3(shape.x()[i], shape.y()[i], shape.z()[i]);
            Vec3 b = new Vec3(shape.x()[i + 1], shape.y()[i + 1], shape.z()[i + 1]);
            double segLen = shape.lengths()[i + 1] - shape.lengths()[i];
            double d = closestPointOnSegment(a, b, point, candidate, shape.lengths()[i], segLen, shape.length());
            if (d < best) {
                best = d;
                if (out != null) {
                    out[0] = candidate[0];
                    out[1] = candidate[1];
                    out[2] = candidate[2];
                    out[3] = candidate[3];
                }
            }
        }
        return best;
    }

    static double freeSlack(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        if (tuning == null || !tuning.physicsEnabled() || Math.abs(tuning.gravity()) < EPS) {
            return 0.0D;
        }
        return Math.abs(targetLength(a, b, tuning) - a.distanceTo(b));
    }

    private static Shape straight(Vec3 a, Vec3 b) {
        double[] x = { a.x, b.x };
        double[] y = { a.y, b.y };
        double[] z = { a.z, b.z };
        double length = Math.max(a.distanceTo(b), EPS);
        double[] lengths = { 0.0D, length };
        return new Shape(a, b, x, y, z, lengths, length, length);
    }

    private static void initialiseNodes(Vec3 a, Vec3 b, double freeSlack, ServerPhysicsTuning tuning,
            double[] x, double[] y, double[] z) {
        int last = x.length - 1;
        double sagSign = tuning.gravity() < 0.0D ? -1.0D : 1.0D;
        double sag = Math.min(MAX_INITIAL_SAG,
                Math.max(Math.abs(tuning.gravity()) * 2.5D,
                        Math.max(freeSlack * 6.0D, a.distanceTo(b) * INITIAL_SAG_FACTOR)));
        for (int i = 0; i <= last; i++) {
            double t = i / (double) last;
            double bend = Math.sin(Math.PI * t) * sag * sagSign;
            x[i] = a.x + (b.x - a.x) * t;
            y[i] = a.y + (b.y - a.y) * t + bend;
            z[i] = a.z + (b.z - a.z) * t;
        }
        pin(a, b, x, y, z);
    }

    private static void solveDistance(int seg, double targetLen, double alpha,
            double[] lambda, double[] x, double[] y, double[] z) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < EPS) {
            return;
        }
        double wi = i == 0 ? 0.0D : 1.0D;
        double wj = j == x.length - 1 ? 0.0D : 1.0D;
        double wsum = wi + wj;
        if (wsum <= 0.0D) {
            return;
        }
        double c = len - targetLen;
        double dlambda = (-c - alpha * lambda[seg]) / (wsum + alpha);
        lambda[seg] += dlambda;
        double nx = dx / len;
        double ny = dy / len;
        double nz = dz / len;
        if (wi > 0.0D) {
            x[i] -= nx * dlambda * wi;
            y[i] -= ny * dlambda * wi;
            z[i] -= nz * dlambda * wi;
        }
        if (wj > 0.0D) {
            x[j] += nx * dlambda * wj;
            y[j] += ny * dlambda * wj;
            z[j] += nz * dlambda * wj;
        }
    }

    private static Shape shapeFromNodes(Vec3 a, Vec3 b, double targetLength,
            double[] x, double[] y, double[] z) {
        int nodes = x.length;
        double[] outX = Arrays.copyOf(x, nodes);
        double[] outY = Arrays.copyOf(y, nodes);
        double[] outZ = Arrays.copyOf(z, nodes);
        double[] lengths = new double[nodes];
        for (int i = 1; i < nodes; i++) {
            double dx = outX[i] - outX[i - 1];
            double dy = outY[i] - outY[i - 1];
            double dz = outZ[i] - outZ[i - 1];
            lengths[i] = lengths[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double length = Math.max(lengths[nodes - 1], EPS);
        return new Shape(a, b, outX, outY, outZ, lengths, length, targetLength);
    }

    private static int segmentAt(Shape shape, double distance) {
        double[] lengths = shape.lengths();
        int lastSegment = lengths.length - 2;
        if (distance <= 0.0D) {
            return 0;
        }
        if (distance >= lengths[lengths.length - 1]) {
            return lastSegment;
        }
        int lo = 0;
        int hi = lastSegment;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lengths[mid + 1] < distance) {
                lo = mid + 1;
            } else if (lengths[mid] > distance) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(lastSegment, lo));
    }

    private static int segmentCount(double chord, ServerPhysicsTuning tuning) {
        double segmentLength = Math.max(0.05D, tuning.segmentLength());
        int cap = Math.max(MIN_SEGMENTS, Math.min(MAX_SERVER_SEGMENTS, tuning.segmentMax()));
        return Math.max(MIN_SEGMENTS, Math.min(cap, (int) Math.ceil(chord / segmentLength)));
    }

    private static int relaxTicks(int segments, int iterations) {
        return Math.max(MIN_RELAX_TICKS, Math.min(MAX_RELAX_TICKS, segments + iterations));
    }

    private static void pin(Vec3 a, Vec3 b, double[] x, double[] y, double[] z) {
        int last = x.length - 1;
        x[0] = a.x;
        y[0] = a.y;
        z[0] = a.z;
        x[last] = b.x;
        y[last] = b.y;
        z[last] = b.z;
    }

    private static double targetLength(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        double chord = a.distanceTo(b);
        return Math.max(EPS, chord * slackFactor(a, b, tuning));
    }

    private static double slackFactor(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        if (Math.abs(tuning.gravity()) < EPS) {
            return 1.0D;
        }
        if (a.distanceToSqr(b) < EPS * EPS) {
            return 1.0D;
        }
        return tuning.slack();
    }

    private static double closestPointOnSegment(
            Vec3 a, Vec3 b, Vec3 p, double[] out, double walkedBefore, double segmentLength, double totalLength) {
        double sx = b.x - a.x;
        double sy = b.y - a.y;
        double sz = b.z - a.z;
        double lenSqr = sx * sx + sy * sy + sz * sz;
        double t = 0.0D;
        if (lenSqr > EPS) {
            t = ((p.x - a.x) * sx + (p.y - a.y) * sy + (p.z - a.z) * sz) / lenSqr;
            t = clamp01(t);
        }
        double qx = a.x + sx * t;
        double qy = a.y + sy * t;
        double qz = a.z + sz * t;
        if (out != null) {
            out[0] = qx;
            out[1] = qy;
            out[2] = qz;
            out[3] = totalLength <= EPS ? t : (walkedBefore + segmentLength * t) / totalLength;
        }
        double dx = p.x - qx;
        double dy = p.y - qy;
        double dz = p.z - qz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    record Shape(Vec3 a, Vec3 b, double[] x, double[] y, double[] z,
            double[] lengths, double length, double targetLength) {
    }
}