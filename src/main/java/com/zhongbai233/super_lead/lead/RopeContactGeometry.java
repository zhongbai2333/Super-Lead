package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.preset.PhysicsZone;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class RopeContactGeometry {
    private RopeContactGeometry() {
    }

    static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value))
                return false;
        }
        return true;
    }

    static double closestPointOnPlausibleClientRope(
            Vec3 a, Vec3 b, Vec3 p, ServerPhysicsTuning tuning, double[] out) {
        double dist = a.distanceTo(b);
        if (dist < 1.0e-6D || Math.abs(tuning.gravity()) < 1.0e-9D) {
            return closestPointOnSegment(a, b, p, out, 0.0D, 0.0D, 1.0D);
        }

        return ServerRopeCurve.distancePointToCurveSqr(ServerRopeCurve.from(a, b, tuning), p, out);
    }

    private static double closestPointOnSegment(
            Vec3 a, Vec3 b, Vec3 p, double[] out, double walkedBefore, double segmentLength, double totalLength) {
        double sx = b.x - a.x;
        double sy = b.y - a.y;
        double sz = b.z - a.z;
        double lenSqr = sx * sx + sy * sy + sz * sz;
        double t = 0.0D;
        if (lenSqr > 1.0e-9D) {
            t = ((p.x - a.x) * sx + (p.y - a.y) * sy + (p.z - a.z) * sz) / lenSqr;
            t = clamp01(t);
        }
        double qx = a.x + sx * t;
        double qy = a.y + sy * t;
        double qz = a.z + sz * t;
        out[0] = qx;
        out[1] = qy;
        out[2] = qz;
        out[3] = totalLength <= 1.0e-6D ? t : (walkedBefore + segmentLength * t) / totalLength;
        double dx = p.x - qx;
        double dy = p.y - qy;
        double dz = p.z - qz;
        return dx * dx + dy * dy + dz * dz;
    }

    static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    static PhysicsZone findZoneForRope(List<PhysicsZone> zones, Vec3 a, Vec3 b) {
        Vec3 mid = a.add(b).scale(0.5D);
        for (PhysicsZone zone : zones) {
            if (zone.contains(mid.x, mid.y, mid.z))
                return zone;
        }
        for (PhysicsZone zone : zones) {
            if (segmentIntersects(zone.area(), a, b))
                return zone;
        }
        return null;
    }

    static boolean segmentIntersects(AABB box, Vec3 a, Vec3 b) {
        if (contains(box, a) || contains(box, b))
            return true;
        double t0 = 0.0D;
        double t1 = 1.0D;
        double[] lo = { box.minX, box.minY, box.minZ };
        double[] hi = { box.maxX, box.maxY, box.maxZ };
        double[] p = { a.x, a.y, a.z };
        double[] d = { b.x - a.x, b.y - a.y, b.z - a.z };
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9D) {
                if (p[axis] < lo[axis] || p[axis] >= hi[axis])
                    return false;
                continue;
            }
            double inv = 1.0D / d[axis];
            double ta = (lo[axis] - p[axis]) * inv;
            double tb = (hi[axis] - p[axis]) * inv;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
            }
            if (ta > t0)
                t0 = ta;
            if (tb < t1)
                t1 = tb;
            if (t0 > t1)
                return false;
        }
        return t1 >= 0.0D && t0 <= 1.0D;
    }

    private static boolean contains(AABB box, Vec3 p) {
        return p.x >= box.minX && p.x < box.maxX
                && p.y >= box.minY && p.y < box.maxY
                && p.z >= box.minZ && p.z < box.maxZ;
    }
}
