package com.zhongbai233.super_lead.lead.client.geom;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RopeMath {
    public static final double EPS = 1.0e-8D;

    private RopeMath() {
    }

    /**
     * Closest points on two finite segments [p1,p2] and [q1,q2]. Writes into out.
     */
    public static void closestSegmentPoints(
            double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
            double q1x, double q1y, double q1z, double q2x, double q2y, double q2z,
            SegmentPair out) {
        double ux = p2x - p1x, uy = p2y - p1y, uz = p2z - p1z;
        double vx = q2x - q1x, vy = q2y - q1y, vz = q2z - q1z;
        double wx = p1x - q1x, wy = p1y - q1y, wz = p1z - q1z;
        double aa = ux * ux + uy * uy + uz * uz;
        double bb = ux * vx + uy * vy + uz * vz;
        double cc = vx * vx + vy * vy + vz * vz;
        double dd = ux * wx + uy * wy + uz * wz;
        double ee = vx * wx + vy * wy + vz * wz;
        double denom = aa * cc - bb * bb;
        double s, t;
        if (aa < EPS && cc < EPS) {
            s = 0.0;
            t = 0.0;
        } else if (aa < EPS) {
            s = 0.0;
            t = clamp01(ee / cc);
        } else if (cc < EPS) {
            t = 0.0;
            s = clamp01(-dd / aa);
        } else {
            s = denom < EPS ? 0.0 : clamp01((bb * ee - cc * dd) / denom);
            t = clamp01((bb * s + ee) / cc);
            s = clamp01((bb * t - dd) / aa);
            t = clamp01((bb * s + ee) / cc);
        }
        double pcx = p1x + ux * s, pcy = p1y + uy * s, pcz = p1z + uz * s;
        double qcx = q1x + vx * t, qcy = q1y + vy * t, qcz = q1z + vz * t;
        out.s = s;
        out.t = t;
        out.dx = pcx - qcx;
        out.dy = pcy - qcy;
        out.dz = pcz - qcz;
        out.distSqr = out.dx * out.dx + out.dy * out.dy + out.dz * out.dz;
    }

    /**
     * Segment vs AABB intersection. Returns true and writes hit time t and
     * recommended push (out)
     * when the segment intersects; false otherwise.
     */
    public static boolean intersectSegmentAabb(
            double fx, double fy, double fz, double tx, double ty, double tz,
            AABB box, double cornerEps, double topEps, SegmentHit out) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double xEntry, xExit;
        if (Math.abs(dx) < 1e-9) {
            if (fx < box.minX || fx > box.maxX)
                return false;
            xEntry = 0.0;
            xExit = 1.0;
        } else {
            double t1 = (box.minX - fx) / dx, t2 = (box.maxX - fx) / dx;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            xEntry = t1;
            xExit = t2;
        }
        double yEntry, yExit;
        if (Math.abs(dy) < 1e-9) {
            if (fy < box.minY || fy > box.maxY)
                return false;
            yEntry = 0.0;
            yExit = 1.0;
        } else {
            double t1 = (box.minY - fy) / dy, t2 = (box.maxY - fy) / dy;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            yEntry = t1;
            yExit = t2;
        }
        double zEntry, zExit;
        if (Math.abs(dz) < 1e-9) {
            if (fz < box.minZ || fz > box.maxZ)
                return false;
            zEntry = 0.0;
            zExit = 1.0;
        } else {
            double t1 = (box.minZ - fz) / dz, t2 = (box.maxZ - fz) / dz;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            zEntry = t1;
            zExit = t2;
        }
        double tMin = Math.max(0.0, Math.max(xEntry, Math.max(yEntry, zEntry)));
        double tMax = Math.min(1.0, Math.min(xExit, Math.min(yExit, zExit)));
        if (tMin > tMax || tMax < 0.0 || tMin > 1.0)
            return false;

        double sxMin = Math.min(fx, tx), sxMax = Math.max(fx, tx);
        double syMin = Math.min(fy, ty), syMax = Math.max(fy, ty);
        double szMin = Math.min(fz, tz), szMax = Math.max(fz, tz);
        double pushX = separationPush(sxMin, sxMax, box.minX, box.maxX);
        double pushY = separationPush(syMin, syMax, box.minY, box.maxY);
        double pushZ = separationPush(szMin, szMax, box.minZ, box.maxZ);
        double ax = Math.abs(pushX), ay = Math.abs(pushY), az = Math.abs(pushZ);
        double min = Math.min(ax, Math.min(ay, az));
        double midT = (tMin + tMax) * 0.5;
        if (pushY > 0.0 && ay <= min + topEps) {
            out.t = midT;
            out.dx = 0.0;
            out.dy = pushY;
            out.dz = 0.0;
            return true;
        }
        out.t = midT;
        out.dx = ax <= min + cornerEps ? pushX : 0.0;
        out.dy = ay <= min + cornerEps ? pushY : 0.0;
        out.dz = az <= min + cornerEps ? pushZ : 0.0;
        return true;
    }

    public static double separationPush(double minA, double maxA, double minB, double maxB) {
        double toMin = minB - maxA;
        double toMax = maxB - minA;
        return Math.abs(toMin) < Math.abs(toMax) ? toMin : toMax;
    }

    public static boolean containsInclusive(AABB box, double x, double y, double z) {
        return x >= box.minX && x <= box.maxX
                && y >= box.minY && y <= box.maxY
                && z >= box.minZ && z <= box.maxZ;
    }

    public static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    public static Vec3 stableUnitVector(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + 0xBF58476D1CE4E5B9L;
        double angle = ((h ^ (h >>> 33)) & 0xFFFFL) / 65535.0D * Math.PI * 2.0;
        return new Vec3(Math.cos(angle), 0.35, Math.sin(angle)).normalize();
    }

}
