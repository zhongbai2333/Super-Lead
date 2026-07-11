package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Public entry point for the client rope particle simulation pipeline.
 *
 * <p>
 * The implementation is split through a small inheritance stack so hot loops
 * can share array-backed state without allocating per segment. Callers should
 * treat this class as the facade and avoid reaching into lower constraint
 * layers
 * directly.
 */
public final class RopeSimulation extends RopeSimulationStepper {
    private static final double TOP_SUPPORT_SNAP = 0.025D;

    private final SegmentBoxContact playerContactScratch = new SegmentBoxContact();
    private final PlayerContactCurve playerContactCurveScratch = new PlayerContactCurve();
    private final PlayerContactContext playerContactContextScratch = new PlayerContactContext();
    private final PlayerContactGeometry playerContactGeometryScratch = new PlayerContactGeometry();
    private final PlayerContactAccumulator playerContactAccumulatorScratch = new PlayerContactAccumulator();
    private final double[] normalScratch = new double[3];
    private final double[] smoothNormalScratch = new double[3];

    public RopeSimulation(Vec3 a, Vec3 b) {
        this(a, b, 0L, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed) {
        this(a, b, seed, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
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
        return findPlayerContact(box, radius, 0.0D);
    }

    public ContactSample findPlayerContact(AABB box, double radius, double topPadding) {
        PlayerContactCurve curve = preparePlayerContactCurve(playerContactCurveScratch);
        if (curve.totalLen < 1.0e-6D)
            return null;

        PlayerContactContext context = playerContactContextScratch.set(
                box, radius, Math.max(0.0D, topPadding),
                (box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D);
        PlayerContactAccumulator accumulator = playerContactAccumulatorScratch.reset();
        double walked = 0.0D;
        for (int s = 0; s < segments; s++) {
            walked += samplePlayerContactSegment(curve, context, s, walked, accumulator);
        }
        return accumulator.result();
    }

    private PlayerContactCurve preparePlayerContactCurve(PlayerContactCurve out) {
        double[] sampleX = x;
        double[] sampleY = y;
        double[] sampleZ = z;
        double totalLen = 0.0D;
        if (useCollisionProxy) {
            totalLen = prepareCollisionProxy();
            sampleX = proxyX;
            sampleY = proxyY;
            sampleZ = proxyZ;
        } else {
            totalLen = 0.0D;
            for (int s = 0; s < segments; s++) {
                double sx = sampleX[s + 1] - sampleX[s];
                double sy = sampleY[s + 1] - sampleY[s];
                double sz = sampleZ[s + 1] - sampleZ[s];
                totalLen += Math.sqrt(sx * sx + sy * sy + sz * sz);
            }
        }
            double dx = sampleX[nodes - 1] - sampleX[0];
            double dy = sampleY[nodes - 1] - sampleY[0];
            double dz = sampleZ[nodes - 1] - sampleZ[0];
            double chordLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double targetLen = chordLen > 1.0e-6D
                ? chordLen * RopeSagModel.slackFactor(dx, dy, dz, tuning.slack(), tuning.gravity()) / segments
                : 0.0D;
        double freeSlack = Math.max(0.0D, targetLen * segments - chordLen);
            return out.set(sampleX, sampleY, sampleZ, totalLen, targetLen, freeSlack);
    }

    private double samplePlayerContactSegment(
            PlayerContactCurve curve,
            PlayerContactContext context,
            int segment,
            double walked,
            PlayerContactAccumulator accumulator) {
        double ax = curve.x[segment], ay = curve.y[segment], az = curve.z[segment];
        double bx = curve.x[segment + 1], by = curve.y[segment + 1], bz = curve.z[segment + 1];
        double sx = bx - ax, sy = by - ay, sz = bz - az;
        double segLen = Math.sqrt(sx * sx + sy * sy + sz * sz);
        SegmentBoxContact contact = playerContactScratch.compute(
                ax, ay, az, bx, by, bz, context.box, 1.0e-9D);
        PlayerContactGeometry geometry = playerContactGeometry(context, contact, sx, sy, sz, segLen,
            playerContactGeometryScratch);
        if (geometry == null)
            return 0.0D;

        smoothPlayerContactNormal(context, geometry, sx, sy, sz, segLen);
        boolean topSupportCandidate = topSupportCandidate(context, geometry);
        double effectiveRadius = topSupportCandidate
                ? Math.max(context.radius, geometry.separation + 1.0e-6D)
                : context.radius;
        if (geometry.separation >= effectiveRadius)
            return segLen;

        double t = (walked + segLen * contact.s) / curve.totalLen;
        double depth = contactDepth(context, geometry, topSupportCandidate);
        if (depth <= 0.0D)
            return segLen;

        double tx = segLen > 1.0e-6D ? sx / segLen : 0.0D;
        double ty = segLen > 1.0e-6D ? sy / segLen : 0.0D;
        double tz = segLen > 1.0e-6D ? sz / segLen : 0.0D;
        double slackAllowance = slackAllowance(curve, segLen, t);
        double weight = contactBlendWeight(depth, context.radius);
        accumulator.add(geometry, t, tx, ty, tz, depth, slackAllowance, weight);
        return segLen;
    }

    private PlayerContactGeometry playerContactGeometry(
            PlayerContactContext context,
            SegmentBoxContact contact,
            double sx,
            double sy,
            double sz,
            double segLen,
            PlayerContactGeometry out) {
        if (insideBox(context.box, contact.spx, contact.spy, contact.spz)) {
            return insidePlayerContactGeometry(context, contact, sx, sy, sz, segLen, out);
        }
        return outsidePlayerContactGeometry(context, contact, out);
    }

    private PlayerContactGeometry insidePlayerContactGeometry(
            PlayerContactContext context,
            SegmentBoxContact contact,
            double sx,
            double sy,
            double sz,
            double segLen,
            PlayerContactGeometry out) {
        stableSegmentNormal(
                context.centerX - contact.spx,
                context.centerY - contact.spy,
                context.centerZ - contact.spz,
                sx, sy, sz, segLen, normalScratch);
        double exit = Math.min(Math.min(contact.spx - context.box.minX, context.box.maxX - contact.spx),
                Math.min(Math.min(contact.spy - context.box.minY, context.box.maxY - contact.spy),
                        Math.min(contact.spz - context.box.minZ, context.box.maxZ - contact.spz)));
        return out.set(contact, normalScratch[0], normalScratch[1], normalScratch[2], -Math.max(0.0D, exit));
    }

    private PlayerContactGeometry outsidePlayerContactGeometry(
            PlayerContactContext context, SegmentBoxContact contact, PlayerContactGeometry out) {
        double rawNx = contact.cpx - contact.spx;
        double rawNy = contact.cpy - contact.spy;
        double rawNz = contact.cpz - contact.spz;
        double nLen = Math.sqrt(rawNx * rawNx + rawNy * rawNy + rawNz * rawNz);
        if (nLen < 1.0e-5D) {
            rawNx = context.centerX - contact.spx;
            rawNy = context.centerY - contact.spy;
            rawNz = context.centerZ - contact.spz;
            nLen = Math.sqrt(rawNx * rawNx + rawNy * rawNy + rawNz * rawNz);
        }
        if (nLen < 1.0e-5D)
            return null;
        double invLen = 1.0D / nLen;
        return out.set(contact,
                rawNx * invLen, rawNy * invLen, rawNz * invLen, Math.sqrt(contact.distSqr));
    }

    private static boolean insideBox(AABB box, double x, double y, double z) {
        return x >= box.minX && x <= box.maxX
                && y >= box.minY && y <= box.maxY
                && z >= box.minZ && z <= box.maxZ;
    }

    private void smoothPlayerContactNormal(
            PlayerContactContext context,
            PlayerContactGeometry geometry,
            double sx,
            double sy,
            double sz,
            double segLen) {
        smoothSideNormal(
                context.centerX - geometry.qx,
                context.centerY - geometry.qy,
            context.centerZ - geometry.qz,
            geometry.nx, geometry.ny, geometry.nz, sx, sy, sz, segLen, smoothNormalScratch);
        geometry.nx = smoothNormalScratch[0];
        geometry.ny = smoothNormalScratch[1];
        geometry.nz = smoothNormalScratch[2];
    }

    private static boolean topSupportCandidate(PlayerContactContext context, PlayerContactGeometry geometry) {
        double horizontalDx = geometry.cx - geometry.qx;
        double horizontalDz = geometry.cz - geometry.qz;
        double horizontalSeparation = Math.sqrt(horizontalDx * horizontalDx + horizontalDz * horizontalDz);
        double footDeltaY = context.box.minY - geometry.qy;
        double topVerticalReach = context.radius + TOP_SUPPORT_SNAP;
        double topHorizontalReach = context.radius + Math.min(context.topPadding * 0.25D, 0.045D);
        return context.topPadding > 0.0D
                && footDeltaY >= -context.radius
                && footDeltaY <= topVerticalReach
                && horizontalSeparation <= topHorizontalReach;
    }

    private static double contactDepth(
            PlayerContactContext context, PlayerContactGeometry geometry, boolean topSupportCandidate) {
        double depth = context.radius - geometry.separation;
        if (!topSupportCandidate || depth > 0.0D)
            return depth;
        double topDepth = context.radius + TOP_SUPPORT_SNAP - (context.box.minY - geometry.qy);
        return topDepth <= 0.0D ? 0.0D : Math.min(topDepth, context.radius);
    }

    private static double slackAllowance(PlayerContactCurve curve, double segLen, double t) {
        double localSlack = Math.max(0.0D, curve.targetLen - segLen);
        double midspanSlack = curve.freeSlack * Math.sin(Math.PI * clamp(t, 0.0D, 1.0D));
        return localSlack + midspanSlack;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static void stableSegmentNormal(double vx, double vy, double vz,
            double sx, double sy, double sz, double segLen, double[] out) {
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
            if (segLen > 1.0e-6D) {
                double tx = sx / segLen;
                double tz = sz / segLen;
                double sideX = -tz;
                double sideZ = tx;
                double sideLen = Math.sqrt(sideX * sideX + sideZ * sideZ);
                if (sideLen > 1.0e-5D) {
                    out[0] = sideX / sideLen;
                    out[1] = 0.0D;
                    out[2] = sideZ / sideLen;
                    return;
                }
            }
            out[0] = 1.0D;
            out[1] = 0.0D;
            out[2] = 0.0D;
            return;
        }
        out[0] = vx / len;
        out[1] = vy / len;
        out[2] = vz / len;
    }

    private static void smoothSideNormal(double centerX, double centerY, double centerZ,
            double nx, double ny, double nz, double sx, double sy, double sz, double segLen, double[] out) {
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
            out[0] = nx;
            out[1] = ny;
            out[2] = nz;
            return;
        }
        vx /= len;
        vy /= len;
        vz /= len;
        double blend = 0.58D;
        nx = nx * (1.0D - blend) + vx * blend;
        ny = ny * (1.0D - blend) + vy * blend;
        nz = nz * (1.0D - blend) + vz * blend;
        len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-5D) {
            out[0] = vx;
            out[1] = vy;
            out[2] = vz;
            return;
        }
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    private static double contactBlendWeight(double depth, double radius) {
        double x = clamp(depth / Math.max(radius, 1.0e-6D), 0.0D, 1.0D);
        return 0.05D + x * x;
    }

    private static final class PlayerContactCurve {
        double[] x;
        double[] y;
        double[] z;
        double totalLen;
        double targetLen;
        double freeSlack;

        PlayerContactCurve set(double[] x, double[] y, double[] z,
                double totalLen, double targetLen, double freeSlack) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.totalLen = totalLen;
            this.targetLen = targetLen;
            this.freeSlack = freeSlack;
            return this;
        }
    }

    private static final class PlayerContactContext {
        AABB box;
        double radius;
        double topPadding;
        double centerX;
        double centerY;
        double centerZ;

        PlayerContactContext set(AABB box, double radius, double topPadding,
                double centerX, double centerY, double centerZ) {
            this.box = box;
            this.radius = radius;
            this.topPadding = topPadding;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            return this;
        }
    }

    private static final class PlayerContactGeometry {
        double qx;
        double qy;
        double qz;
        double cx;
        double cz;
        double separation;
        double nx;
        double ny;
        double nz;

        PlayerContactGeometry set(SegmentBoxContact contact,
                double nx, double ny, double nz, double separation) {
            this.qx = contact.spx;
            this.qy = contact.spy;
            this.qz = contact.spz;
            this.cx = contact.cpx;
            this.cz = contact.cpz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.separation = separation;
            return this;
        }
    }

    private static final class PlayerContactAccumulator {
        private ContactSample best;
        private double bestSeparation = Double.POSITIVE_INFINITY;
        private double weightSum;
        private double sumX;
        private double sumY;
        private double sumZ;
        private double sumT;
        private double sumNx;
        private double sumNy;
        private double sumNz;
        private double sumTx;
        private double sumTy;
        private double sumTz;
        private double sumDepth;
        private double sumSlack;
        private double outNx;
        private double outNy;
        private double outNz;
        private double outTx;
        private double outTy;
        private double outTz;

        PlayerContactAccumulator reset() {
            best = null;
            bestSeparation = Double.POSITIVE_INFINITY;
            weightSum = 0.0D;
            sumX = sumY = sumZ = sumT = 0.0D;
            sumNx = sumNy = sumNz = 0.0D;
            sumTx = sumTy = sumTz = 0.0D;
            sumDepth = sumSlack = 0.0D;
            return this;
        }

        void add(PlayerContactGeometry geometry,
                double t, double tx, double ty, double tz,
                double depth, double slackAllowance, double weight) {
            double nx = geometry.nx;
            double ny = geometry.ny;
            double nz = geometry.nz;
            if (weightSum > 1.0e-9D && nx * sumNx + ny * sumNy + nz * sumNz < 0.0D) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            if (weightSum > 1.0e-9D && tx * sumTx + ty * sumTy + tz * sumTz < 0.0D) {
                tx = -tx;
                ty = -ty;
                tz = -tz;
            }
            weightSum += weight;
            sumX += geometry.qx * weight;
            sumY += geometry.qy * weight;
            sumZ += geometry.qz * weight;
            sumT += t * weight;
            sumNx += nx * weight;
            sumNy += ny * weight;
            sumNz += nz * weight;
            sumTx += tx * weight;
            sumTy += ty * weight;
            sumTz += tz * weight;
            sumDepth += depth * weight;
            sumSlack += slackAllowance * weight;
            if (geometry.separation < bestSeparation) {
                bestSeparation = geometry.separation;
                best = new ContactSample(geometry.qx, geometry.qy, geometry.qz, t, nx, ny, nz,
                        tx, ty, tz, depth, slackAllowance);
            }
        }

        ContactSample result() {
            if (best == null || weightSum <= 1.0e-9D)
                return best;
            double invWeight = 1.0D / weightSum;
            setNormalOrBest(sumNx * invWeight, sumNy * invWeight, sumNz * invWeight,
                    best.normalX(), best.normalY(), best.normalZ());
            setTangentOrBest(sumTx * invWeight, sumTy * invWeight, sumTz * invWeight,
                    best.tangentX(), best.tangentY(), best.tangentZ());
            double depth = Math.max(sumDepth * invWeight, best.depth() * 0.65D);
            return new ContactSample(sumX * invWeight, sumY * invWeight, sumZ * invWeight,
                    clamp(sumT * invWeight, 0.0D, 1.0D),
                    outNx, outNy, outNz,
                    outTx, outTy, outTz,
                    depth, sumSlack * invWeight);
        }

        private void setNormalOrBest(double x, double y, double z, double bestX, double bestY, double bestZ) {
            double len = Math.sqrt(x * x + y * y + z * z);
            if (len < 1.0e-5D) {
                outNx = bestX;
                outNy = bestY;
                outNz = bestZ;
                return;
            }
            double invLen = 1.0D / len;
            outNx = x * invLen;
            outNy = y * invLen;
            outNz = z * invLen;
        }

        private void setTangentOrBest(double x, double y, double z, double bestX, double bestY, double bestZ) {
            double len = Math.sqrt(x * x + y * y + z * z);
            if (len < 1.0e-5D) {
                outTx = bestX;
                outTy = bestY;
                outTz = bestZ;
                return;
            }
            double invLen = 1.0D / len;
            outTx = x * invLen;
            outTy = y * invLen;
            outTz = z * invLen;
        }
    }

    public record ContactSample(double x, double y, double z, double t,
            double normalX, double normalY, double normalZ,
            double tangentX, double tangentY, double tangentZ,
            double depth, double slack) {
    }
}
