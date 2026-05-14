package com.zhongbai233.super_lead.lead.physics;

import net.minecraft.world.phys.Vec3;

/**
 * Shared rope shape math used by client simulation, LOD fallback curves, and
 * server-side coarse curves.
 */
public final class RopeSagModel {
    private static final double EPS = 1.0e-9D;
    private static final double SAG_ARC_APPROX_FACTOR = 0.375D; // 3 / 8
    private static final double SLACK_LENGTH_SCALE = 0.05D;
    private static final double FULL_TAUT_PROJECTION_DIAL = 0.30D;
    private static final double STEEP_VERTICALITY = 0.984807753012208D; // sin(80deg)
    private static final double STEEP_LOCK_HORIZONTAL_RATIO = 0.17364817766693033D; // cos(80deg)
    private static final double FULL_SLACK_HORIZONTAL_RATIO = 0.45D;

    private RopeSagModel() {
    }

    public static double slackFactor(Vec3 a, Vec3 b, double slack, double gravity) {
        double chord = a.distanceTo(b);
        if (chord < EPS) {
            return 1.0D;
        }
        return targetLength(a, b, slack, gravity) / chord;
    }

    public static double targetLength(Vec3 a, Vec3 b, double slack, double gravity) {
        double chord = a.distanceTo(b);
        if (chord < EPS || Math.abs(gravity) < EPS) {
            return Math.max(0.0D, chord);
        }
        return Math.max(0.0D, chord * effectiveSlack(a, b, chord, slack, gravity));
    }

    public static double physicsTargetLength(Vec3 a, Vec3 b, double slack, double gravity) {
        double chord = a.distanceTo(b);
        if (chord < EPS) {
            return Math.max(0.0D, chord);
        }
        return Math.max(EPS, chord * lengthFactor(slack));
    }

    public static double targetLength(double chord, double slack, double gravity) {
        if (chord < EPS || Math.abs(gravity) < EPS) {
            return Math.max(0.0D, chord);
        }
        return Math.max(0.0D, chord * lengthFactor(slack));
    }

    public static double lengthFactor(double slack) {
        double dial = sanitizedSlack(slack);
        return 1.0D + dial * dial * SLACK_LENGTH_SCALE;
    }

    /**
     * Extra numerical tension used only to keep the low end of the slack dial honest.
     * <p>
     * The distance constraint alone has a valid taut solution at {@code slack == 0},
     * but a finite-iteration Verlet/XPBD chain can retain old sag and gravity-induced
     * transverse velocity for many frames. This weight blends solver nodes back toward
     * the endpoint chord for the intentionally tight part of the dial: 0.0 = fully
     * taut, 0.1 = very tight, 0.3+ = normal rope physics.
     */
    public static double tautProjectionWeight(double slack) {
        double dial = sanitizedSlack(slack);
        if (dial <= EPS) {
            return 1.0D;
        }
        if (dial >= FULL_TAUT_PROJECTION_DIAL) {
            return 0.0D;
        }
        return 1.0D - smoothstep(clamp01(dial / FULL_TAUT_PROJECTION_DIAL));
    }

    public static double freeSlack(Vec3 a, Vec3 b, double slack, double gravity) {
        return Math.max(0.0D, targetLength(a, b, slack, gravity) - a.distanceTo(b));
    }

    public static double freeSlack(double chord, double slack, double gravity) {
        return Math.max(0.0D, targetLength(chord, slack, gravity) - chord);
    }

    /**
     * Mid-span sag for a shallow parabolic/catenary approximation.
     * <p>
     * For y = 4h t(1-t), the small-slope arc-length approximation is
     * L/chord ~= 1 + 8h^2/(3 chord^2), therefore
    * h ~= chord * sqrt(3/8 * (lengthFactor - 1)).
     */
    public static double midspanSag(Vec3 a, Vec3 b, double slack, double gravity) {
        double chord = a.distanceTo(b);
        if (chord < EPS || Math.abs(gravity) < EPS) {
            return 0.0D;
        }
        return midspanSagForLengthFactor(chord, effectiveSlack(a, b, chord, slack, gravity), gravity);
    }

    /**
     * Returns true for ropes whose chord is at least 80 degrees from horizontal.
     * At that angle gravity mostly acts along the rope axis, so slack cannot form a
     * stable hanging catenary; extra length instead collapses into a bottom fold.
     */
    public static boolean isSteep(Vec3 a, Vec3 b, double gravity) {
        if (Math.abs(gravity) < EPS) {
            return false;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double lenSqr = dx * dx + dy * dy + dz * dz;
        return lenSqr > EPS * EPS && Math.abs(dy) / Math.sqrt(lenSqr) >= STEEP_VERTICALITY;
    }

    public static double effectiveSlack(Vec3 a, Vec3 b, double slack, double gravity) {
        double chord = a.distanceTo(b);
        return effectiveSlack(a, b, chord, slack, gravity);
    }

    public static double midspanSag(double chord, double slack, double gravity) {
        return midspanSagForLengthFactor(chord, lengthFactor(slack), gravity);
    }

    private static double midspanSagForLengthFactor(double chord, double lengthFactor, double gravity) {
        if (chord < EPS || Math.abs(gravity) < EPS) {
            return 0.0D;
        }
        double extra = Math.max(0.0D, lengthFactor - 1.0D);
        return chord * Math.sqrt(SAG_ARC_APPROX_FACTOR * extra);
    }

    public static int segmentCount(double chord, double segmentLength, int minSegments, int maxSegments) {
        double safeSegmentLength = Math.max(EPS, segmentLength);
        int min = Math.max(1, minSegments);
        int max = Math.max(min, maxSegments);
        return Math.max(min, Math.min(max, (int) Math.ceil(chord / safeSegmentLength)));
    }

    public static Vec3 stableUnitVector(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + 0xBF58476D1CE4E5B9L;
        double angle = ((h ^ (h >>> 33)) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        return new Vec3(Math.cos(angle), 0.35D, Math.sin(angle)).normalize();
    }

    public static void writeCatenary(Vec3 a, Vec3 b, double slack, double gravity, Vec3 fallbackDirection,
            double[] x, double[] y, double[] z) {
        int last = x.length - 1;
        if (last < 0) {
            return;
        }
        double sag = midspanSag(a, b, slack, gravity);
        Vec3 sagDir = sagDirection(a, b, gravity, fallbackDirection);
        for (int i = 0; i <= last; i++) {
            double t = last == 0 ? 0.0D : i / (double) last;
            double bend = Math.sin(Math.PI * t) * sag;
            x[i] = a.x + (b.x - a.x) * t + sagDir.x * bend;
            y[i] = a.y + (b.y - a.y) * t + sagDir.y * bend;
            z[i] = a.z + (b.z - a.z) * t + sagDir.z * bend;
        }
        x[0] = a.x;
        y[0] = a.y;
        z[0] = a.z;
        x[last] = b.x;
        y[last] = b.y;
        z[last] = b.z;
    }

    public static Vec3 point(Vec3 a, Vec3 b, double t, double slack, double gravity, Vec3 fallbackDirection) {
        double clamped = clamp01(t);
        double sag = midspanSag(a, b, slack, gravity);
        Vec3 sagDir = sagDirection(a, b, gravity, fallbackDirection);
        double bend = Math.sin(Math.PI * clamped) * sag;
        return new Vec3(
                a.x + (b.x - a.x) * clamped + sagDir.x * bend,
                a.y + (b.y - a.y) * clamped + sagDir.y * bend,
                a.z + (b.z - a.z) * clamped + sagDir.z * bend);
    }

    public static Vec3 tangent(Vec3 a, Vec3 b, double t, double slack, double gravity, Vec3 fallbackDirection) {
        double clamped = clamp01(t);
        double sag = midspanSag(a, b, slack, gravity);
        Vec3 sagDir = sagDirection(a, b, gravity, fallbackDirection);
        double bendDerivative = Math.PI * Math.cos(Math.PI * clamped) * sag;
        Vec3 tangent = new Vec3(
                b.x - a.x + sagDir.x * bendDerivative,
                b.y - a.y + sagDir.y * bendDerivative,
                b.z - a.z + sagDir.z * bendDerivative);
        return tangent.lengthSqr() < EPS * EPS ? fallbackTangent(a, b) : tangent.normalize();
    }

    public static Vec3 sagDirection(Vec3 a, Vec3 b, double gravity, Vec3 fallbackDirection) {
        if (Math.abs(gravity) < EPS) {
            return new Vec3(0.0D, 0.0D, 0.0D);
        }
        Vec3 chord = b.subtract(a);
        double chordLenSqr = chord.lengthSqr();
        Vec3 gravityDir = new Vec3(0.0D, gravity < 0.0D ? -1.0D : 1.0D, 0.0D);
        if (chordLenSqr > EPS * EPS) {
            Vec3 projectedGravity = rejectAlong(gravityDir, chord, chordLenSqr);
            if (projectedGravity.lengthSqr() > EPS * EPS) {
                return projectedGravity.normalize();
            }
            Vec3 fallback = fallbackDirection != null ? fallbackDirection : endpointFallback(a, b);
            Vec3 projectedFallback = rejectAlong(fallback, chord, chordLenSqr);
            if (projectedFallback.lengthSqr() > EPS * EPS) {
                return projectedFallback.normalize();
            }
            Vec3 horizontal = new Vec3(-chord.z, 0.0D, chord.x);
            if (horizontal.lengthSqr() > EPS * EPS) {
                return horizontal.normalize();
            }
        }
        Vec3 fallback = fallbackDirection != null ? fallbackDirection : endpointFallback(a, b);
        return fallback.lengthSqr() > EPS * EPS ? fallback.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    private static Vec3 rejectAlong(Vec3 v, Vec3 axis, double axisLenSqr) {
        return v.subtract(axis.scale(v.dot(axis) / axisLenSqr));
    }

    private static Vec3 fallbackTangent(Vec3 a, Vec3 b) {
        Vec3 chord = b.subtract(a);
        return chord.lengthSqr() < EPS * EPS ? new Vec3(1.0D, 0.0D, 0.0D) : chord.normalize();
    }

    private static Vec3 endpointFallback(Vec3 a, Vec3 b) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, Double.doubleToLongBits(a.x));
        h = mix(h, Double.doubleToLongBits(a.y));
        h = mix(h, Double.doubleToLongBits(a.z));
        h = mix(h, Double.doubleToLongBits(b.x));
        h = mix(h, Double.doubleToLongBits(b.y));
        h = mix(h, Double.doubleToLongBits(b.z));
        double angle = ((h >>> 11) * 0x1.0p-53D) * Math.PI * 2.0D;
        return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static double sanitizedSlack(double slack) {
        return Double.isFinite(slack) ? Math.max(0.0D, slack) : 0.0D;
    }

    private static double effectiveSlack(Vec3 a, Vec3 b, double chord, double slack, double gravity) {
        double requestedSlack = lengthFactor(slack);
        if (chord < EPS || Math.abs(gravity) < EPS) {
            return 1.0D;
        }
        double horizontalRatio = Math.hypot(b.x - a.x, b.z - a.z) / chord;
        double weight = (horizontalRatio - STEEP_LOCK_HORIZONTAL_RATIO)
                / (FULL_SLACK_HORIZONTAL_RATIO - STEEP_LOCK_HORIZONTAL_RATIO);
        weight = smoothstep(clamp01(weight));
        return Math.max(0.0D, 1.0D + (requestedSlack - 1.0D) * weight);
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }
}