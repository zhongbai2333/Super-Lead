package com.zhongbai233.super_lead.lead.client.sim;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RopeSimulation extends RopeSimulationStepper {

    public RopeSimulation(Vec3 a, Vec3 b) {
        this(a, b, 0L, false, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, boolean tight) {
        this(a, b, seed, tight, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, boolean tight, RopeTuning tuning) {
        super(a, b, seed, tight, tuning);
    }

    public static RopeSimulation visualLeash(Vec3 a, Vec3 b) {
        RopeSimulation sim = new RopeSimulation(a, b);
        sim.setCatenary(a, b, 0.08D);
        return sim;
    }

    public static void beginParallelPhase() {
        RopeSimulationCore.beginParallelPhase();
    }

    public static void endParallelPhase() {
        RopeSimulationCore.endParallelPhase();
    }

    public ContactSample findPlayerContact(AABB box, double radius) {
        double totalLen = 0.0D;
        for (int s = 0; s < segments; s++) {
            double sx = x[s + 1] - x[s];
            double sy = y[s + 1] - y[s];
            double sz = z[s + 1] - z[s];
            totalLen += Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        if (totalLen < 1.0e-6D) return null;

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double walked = 0.0D;
        ContactSample best = null;
        double bestSeparation = Double.POSITIVE_INFINITY;

        for (int s = 0; s < segments; s++) {
            double ax = x[s], ay = y[s], az = z[s];
            double bx = x[s + 1], by = y[s + 1], bz = z[s + 1];
            double sx = bx - ax, sy = by - ay, sz = bz - az;
            double segLenSqr = sx * sx + sy * sy + sz * sz;
            double segLen = Math.sqrt(segLenSqr);
            double u = 0.0D;
            if (segLenSqr > 1.0e-9D) {
                u = ((centerX - ax) * sx + (centerY - ay) * sy + (centerZ - az) * sz) / segLenSqr;
                if (u < 0.0D) u = 0.0D; else if (u > 1.0D) u = 1.0D;
            }

            double qx = ax, qy = ay, qz = az;
            double cx = centerX, cy = centerY, cz = centerZ;
            for (int it = 0; it < 4; it++) {
                qx = ax + sx * u;
                qy = ay + sy * u;
                qz = az + sz * u;
                cx = clamp(qx, box.minX, box.maxX);
                cy = clamp(qy, box.minY, box.maxY);
                cz = clamp(qz, box.minZ, box.maxZ);
                if (segLenSqr <= 1.0e-9D) break;
                double next = ((cx - ax) * sx + (cy - ay) * sy + (cz - az) * sz) / segLenSqr;
                if (next < 0.0D) next = 0.0D; else if (next > 1.0D) next = 1.0D;
                if (Math.abs(next - u) < 1.0e-6D) {
                    u = next;
                    qx = ax + sx * u;
                    qy = ay + sy * u;
                    qz = az + sz * u;
                    cx = clamp(qx, box.minX, box.maxX);
                    cy = clamp(qy, box.minY, box.maxY);
                    cz = clamp(qz, box.minZ, box.maxZ);
                    break;
                }
                u = next;
            }

            boolean inside = qx >= box.minX && qx <= box.maxX
                    && qy >= box.minY && qy <= box.maxY
                    && qz >= box.minZ && qz <= box.maxZ;
            double rawNx;
            double rawNz;
            double separation;
            if (inside) {
                double toMinX = qx - box.minX;
                double toMaxX = box.maxX - qx;
                double toMinZ = qz - box.minZ;
                double toMaxZ = box.maxZ - qz;
                double exit = toMinX;
                rawNx = 1.0D;
                rawNz = 0.0D;
                if (toMaxX < exit) { exit = toMaxX; rawNx = -1.0D; rawNz = 0.0D; }
                if (toMinZ < exit) { exit = toMinZ; rawNx = 0.0D; rawNz = 1.0D; }
                if (toMaxZ < exit) { exit = toMaxZ; rawNx = 0.0D; rawNz = -1.0D; }
                separation = -Math.max(0.0D, exit);
            } else {
                rawNx = cx - qx;
                rawNz = cz - qz;
                double nLen = Math.sqrt(rawNx * rawNx + rawNz * rawNz);
                if (nLen < 1.0e-5D) {
                    rawNx = centerX - qx;
                    rawNz = centerZ - qz;
                    nLen = Math.sqrt(rawNx * rawNx + rawNz * rawNz);
                }
                if (nLen < 1.0e-5D) continue;
                rawNx /= nLen;
                rawNz /= nLen;
                double dx = cx - qx;
                double dy = cy - qy;
                double dz = cz - qz;
                separation = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            double nx = rawNx;
            double nz = rawNz;
            double horizontalLen = Math.sqrt(sx * sx + sz * sz);
            if (horizontalLen > 1.0e-5D) {
                // Stable side normal for diagonal ropes: use the vector from the rope point to
                // the player's centre, projected perpendicular to the rope's horizontal tangent.
                // The nearest-face normal above is only a fallback; using it directly makes a
                // diagonal rope alternately push X/Z as the sampled point crosses AABB faces.
                double tx = sx / horizontalLen;
                double tz = sz / horizontalLen;
                double vx = centerX - qx;
                double vz = centerZ - qz;
                double along = vx * tx + vz * tz;
                double sideX = vx - tx * along;
                double sideZ = vz - tz * along;
                double sideLen = Math.sqrt(sideX * sideX + sideZ * sideZ);
                if (sideLen > 1.0e-5D) {
                    nx = sideX / sideLen;
                    nz = sideZ / sideLen;
                }
            } else {
                double vx = centerX - qx;
                double vz = centerZ - qz;
                double sideLen = Math.sqrt(vx * vx + vz * vz);
                if (sideLen > 1.0e-5D) {
                    nx = vx / sideLen;
                    nz = vz / sideLen;
                }
            }
            if (separation >= radius || separation >= bestSeparation) {
                walked += segLen;
                continue;
            }
            double t = (walked + segLen * u) / totalLen;
            bestSeparation = separation;
            best = new ContactSample(qx, qy, qz, t, nx, nz, radius - separation);
            walked += segLen;
        }

        return best;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    public record ContactSample(double x, double y, double z, double t,
            double normalX, double normalZ, double depth) {}
}
