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

    /**
     * Enable/disable the smoothed proxy curve for rendering and contact sampling.
     */
    public void setUseCollisionProxy(boolean use) {
        this.useCollisionProxy = use;
    }

    public ContactSample findPlayerContact(AABB box, double radius) {
        double totalLen = 0.0D;
        for (int s = 0; s < segments; s++) {
            double sx = x[s + 1] - x[s];
            double sy = y[s + 1] - y[s];
            double sz = z[s + 1] - z[s];
            totalLen += Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        if (totalLen < 1.0e-6D)
            return null;

        Vec3 endpointA = new Vec3(x[0], y[0], z[0]);
        Vec3 endpointB = new Vec3(x[nodes - 1], y[nodes - 1], z[nodes - 1]);
        double chordLen = endpointA.distanceTo(endpointB);
        double targetLen = chordLen > 1.0e-6D ? chordLen * slackFactor(endpointA, endpointB) / segments : 0.0D;
        double freeSlack = Math.max(0.0D, targetLen * segments - chordLen);

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double walked = 0.0D;
        ContactSample best = null;
        double bestSeparation = Double.POSITIVE_INFINITY;
        double weightSum = 0.0D;
        double sumX = 0.0D, sumY = 0.0D, sumZ = 0.0D, sumT = 0.0D;
        double sumNx = 0.0D, sumNy = 0.0D, sumNz = 0.0D;
        double sumTx = 0.0D, sumTy = 0.0D, sumTz = 0.0D;
        double sumDepth = 0.0D, sumSlack = 0.0D;

        for (int s = 0; s < segments; s++) {
            double ax = x[s], ay = y[s], az = z[s];
            double bx = x[s + 1], by = y[s + 1], bz = z[s + 1];
            double sx = bx - ax, sy = by - ay, sz = bz - az;
            double segLenSqr = sx * sx + sy * sy + sz * sz;
            double segLen = Math.sqrt(segLenSqr);
            double u = 0.0D;
            if (segLenSqr > 1.0e-9D) {
                u = ((centerX - ax) * sx + (centerY - ay) * sy + (centerZ - az) * sz) / segLenSqr;
                if (u < 0.0D)
                    u = 0.0D;
                else if (u > 1.0D)
                    u = 1.0D;
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
                if (segLenSqr <= 1.0e-9D)
                    break;
                double next = ((cx - ax) * sx + (cy - ay) * sy + (cz - az) * sz) / segLenSqr;
                if (next < 0.0D)
                    next = 0.0D;
                else if (next > 1.0D)
                    next = 1.0D;
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
            double rawNy;
            double rawNz;
            double separation;
            if (inside) {
                double[] normal = stableSegmentNormal(centerX - qx, centerY - qy, centerZ - qz,
                        sx, sy, sz, segLen);
                rawNx = normal[0];
                rawNy = normal[1];
                rawNz = normal[2];
                double exit = Math.min(Math.min(qx - box.minX, box.maxX - qx),
                        Math.min(Math.min(qy - box.minY, box.maxY - qy),
                                Math.min(qz - box.minZ, box.maxZ - qz)));
                separation = -Math.max(0.0D, exit);
            } else {
                rawNx = cx - qx;
                rawNy = cy - qy;
                rawNz = cz - qz;
                double nLen = Math.sqrt(rawNx * rawNx + rawNy * rawNy + rawNz * rawNz);
                if (nLen < 1.0e-5D) {
                    rawNx = centerX - qx;
                    rawNy = centerY - qy;
                    rawNz = centerZ - qz;
                    nLen = Math.sqrt(rawNx * rawNx + rawNy * rawNy + rawNz * rawNz);
                }
                if (nLen < 1.0e-5D)
                    continue;
                rawNx /= nLen;
                rawNy /= nLen;
                rawNz /= nLen;
                double dx = cx - qx;
                double dy = cy - qy;
                double dz = cz - qz;
                separation = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            {
                double[] smoothed = smoothSideNormal(centerX - qx, centerY - qy, centerZ - qz,
                        rawNx, rawNy, rawNz, sx, sy, sz, segLen);
                rawNx = smoothed[0];
                rawNy = smoothed[1];
                rawNz = smoothed[2];
            }
            if (separation >= radius) {
                walked += segLen;
                continue;
            }
            double t = (walked + segLen * u) / totalLen;
            double tx = 0.0D;
            double ty = 0.0D;
            double tz = 0.0D;
            if (segLen > 1.0e-6D) {
                tx = sx / segLen;
                ty = sy / segLen;
                tz = sz / segLen;
            }
            double localSlack = Math.max(0.0D, targetLen - segLen);
            double midspanSlack = freeSlack * Math.sin(Math.PI * clamp(t, 0.0D, 1.0D));
            double slackAllowance = localSlack + midspanSlack;
            double depth = radius - separation;
            double weight = contactBlendWeight(depth, radius);
            if (weightSum > 1.0e-9D && rawNx * sumNx + rawNy * sumNy + rawNz * sumNz < 0.0D) {
                rawNx = -rawNx;
                rawNy = -rawNy;
                rawNz = -rawNz;
            }
            if (weightSum > 1.0e-9D && tx * sumTx + ty * sumTy + tz * sumTz < 0.0D) {
                tx = -tx;
                ty = -ty;
                tz = -tz;
            }
            weightSum += weight;
            sumX += qx * weight;
            sumY += qy * weight;
            sumZ += qz * weight;
            sumT += t * weight;
            sumNx += rawNx * weight;
            sumNy += rawNy * weight;
            sumNz += rawNz * weight;
            sumTx += tx * weight;
            sumTy += ty * weight;
            sumTz += tz * weight;
            sumDepth += depth * weight;
            sumSlack += slackAllowance * weight;
            if (separation < bestSeparation) {
                bestSeparation = separation;
                best = new ContactSample(qx, qy, qz, t, rawNx, rawNy, rawNz,
                        tx, ty, tz, depth, slackAllowance);
            }
            walked += segLen;
        }

        if (best == null || weightSum <= 1.0e-9D)
            return best;

        double invWeight = 1.0D / weightSum;
        double nx = sumNx * invWeight;
        double ny = sumNy * invWeight;
        double nz = sumNz * invWeight;
        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 1.0e-5D) {
            nx = best.normalX();
            ny = best.normalY();
            nz = best.normalZ();
        } else {
            nx /= nLen;
            ny /= nLen;
            nz /= nLen;
        }
        double tx = sumTx * invWeight;
        double ty = sumTy * invWeight;
        double tz = sumTz * invWeight;
        double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tLen < 1.0e-5D) {
            tx = best.tangentX();
            ty = best.tangentY();
            tz = best.tangentZ();
        } else {
            tx /= tLen;
            ty /= tLen;
            tz /= tLen;
        }
        double depth = Math.max(sumDepth * invWeight, best.depth() * 0.65D);
        return new ContactSample(sumX * invWeight, sumY * invWeight, sumZ * invWeight,
                clamp(sumT * invWeight, 0.0D, 1.0D), nx, ny, nz, tx, ty, tz,
                depth, sumSlack * invWeight);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double[] stableSegmentNormal(double vx, double vy, double vz,
            double sx, double sy, double sz, double segLen) {
        if (segLen > 1.0e-6D) {
            double tx = sx / segLen;
            double ty = sy / segLen;
            double tz = sz / segLen;
            double along = vx * tx + vy * ty + vz * tz;
            vx -= tx * along;
            vy -= ty * along;
            vz -= tz * along;
        }
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0e-5D) {
            return new double[] { 0.0D, 1.0D, 0.0D };
        }
        return new double[] { vx / len, vy / len, vz / len };
    }

    private static double[] smoothSideNormal(double centerX, double centerY, double centerZ,
            double nx, double ny, double nz, double sx, double sy, double sz, double segLen) {
        double vx = centerX;
        double vy = Math.abs(ny) > 0.35D ? centerY : 0.0D;
        double vz = centerZ;
        if (segLen > 1.0e-6D) {
            double tx = sx / segLen;
            double ty = sy / segLen;
            double tz = sz / segLen;
            double along = vx * tx + vy * ty + vz * tz;
            vx -= tx * along;
            vy -= ty * along;
            vz -= tz * along;
        }
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0e-5D || vx * nx + vy * ny + vz * nz <= 0.0D) {
            return new double[] { nx, ny, nz };
        }
        vx /= len;
        vy /= len;
        vz /= len;
        double blend = 0.58D;
        nx = nx * (1.0D - blend) + vx * blend;
        ny = ny * (1.0D - blend) + vy * blend;
        nz = nz * (1.0D - blend) + vz * blend;
        len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-5D)
            return new double[] { vx, vy, vz };
        return new double[] { nx / len, ny / len, nz / len };
    }

    private static double contactBlendWeight(double depth, double radius) {
        double x = clamp(depth / Math.max(radius, 1.0e-6D), 0.0D, 1.0D);
        return 0.05D + x * x;
    }

    public record ContactSample(double x, double y, double z, double t,
            double normalX, double normalY, double normalZ,
            double tangentX, double tangentY, double tangentZ,
            double depth, double slack) {
    }
}
