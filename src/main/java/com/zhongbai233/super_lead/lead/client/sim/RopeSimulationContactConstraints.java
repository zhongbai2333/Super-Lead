package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies entity-contact constraints and contact reporting to simulated ropes.
 *
 * <p>
 * This layer resolves soft collisions against nearby entities and records
 * contact candidates for gameplay pulses. It sits above terrain constraints so
 * block collision has already stabilized particle positions before entity
 * pushes
 * are applied.
 */
abstract class RopeSimulationContactConstraints extends RopeSimulationTerrainConstraints {
    private static final double ENTITY_FOOT_SUPPORT_HEIGHT = 0.18D;
    private static final double ENTITY_FOOT_SUPPORT_MARGIN = 0.08D;
    private static final double ENTITY_FOOT_SUPPORT_MAX_HORIZONTAL_SPEED = 0.08D;

    protected RopeSimulationContactConstraints(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    // ============================================================================================
    // Constraint: distance (XPBD)
    // ============================================================================================
    protected void solveDistanceConstraints(double targetLen, double alphaTilde, boolean forward) {
        if (forward) {
            for (int i = 0; i < segments; i++)
                solveDistance(i, targetLen, alphaTilde);
        } else {
            for (int i = segments - 1; i >= 0; i--)
                solveDistance(i, targetLen, alphaTilde);
        }
    }

    private void solveDistance(int seg, double targetLen, double alphaTilde) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9)
            return;
        double w1 = pinned[i] ? 0.0D : 1.0D;
        double w2 = pinned[j] ? 0.0D : 1.0D;
        double wsum = w1 + w2;
        if (wsum == 0.0D)
            return;
        double C = len - targetLen;
        double dlambda = (-C - alphaTilde * lambdaDistance[seg]) / (wsum + alphaTilde);
        lambdaDistance[seg] += dlambda;
        double nx = dx / len, ny = dy / len, nz = dz / len;
        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyCorrection(i, -cx * w1, -cy * w1, -cz * w1);
        applyCorrection(j, cx * w2, cy * w2, cz * w2);
    }

    // ============================================================================================
    // Constraint: rope-rope
    // ============================================================================================
    protected void solveRopeRopeConstraints(List<RopeSimulation> neighbors) {
        final double m = ropeRepelDistance;
        refreshSegmentAabbs();
        final double[] amin = this.segAabb;
        final int aSegs = this.segments;
        for (int n = 0; n < neighbors.size(); n++) {
            RopeSimulation other = neighbors.get(n);
            if (other == this)
                continue;
            if (!boundsOverlap(other, m))
                continue;
            // Same as above: skip writing to other's segAabb in parallel; prepare phase did
            // it.
            if (!parallelPhase())
                other.refreshSegmentAabbs();
            final double[] bmin = other.segAabb;
            final int bSegs = other.segments;
            // Per-rope full bbox of `other` (already updated by boundsOverlap).
            final double oMinX = other.minX, oMinY = other.minY, oMinZ = other.minZ;
            final double oMaxX = other.maxX, oMaxY = other.maxY, oMaxZ = other.maxZ;
            for (int i = 0; i < aSegs; i++) {
                int oa = i * 6;
                double aMinX = amin[oa], aMinY = amin[oa + 1], aMinZ = amin[oa + 2];
                double aMaxX = amin[oa + 3], aMaxY = amin[oa + 4], aMaxZ = amin[oa + 5];
                // Row prune: this segment vs other-rope full bbox.
                if (aMaxX + m < oMinX || aMinX - m > oMaxX)
                    continue;
                if (aMaxY + m < oMinY || aMinY - m > oMaxY)
                    continue;
                if (aMaxZ + m < oMinZ || aMinZ - m > oMaxZ)
                    continue;
                for (int j = 0; j < bSegs; j++) {
                    int ob = j * 6;
                    if (aMaxX + m < bmin[ob] || bmin[ob + 3] + m < aMinX)
                        continue;
                    if (aMaxY + m < bmin[ob + 1] || bmin[ob + 4] + m < aMinY)
                        continue;
                    if (aMaxZ + m < bmin[ob + 2] || bmin[ob + 5] + m < aMinZ)
                        continue;
                    solveSegmentPairNoCheck(other, i, j);
                }
            }
        }
    }

    /** Refresh {@link #segAabb} from current node positions. Cheap O(segments). */
    protected void refreshSegmentAabbs() {
        int needLen = segments * 6;
        if (segAabb == null || segAabb.length < needLen)
            segAabb = new double[needLen];
        final double[] sa = segAabb;
        for (int i = 0; i < segments; i++) {
            double ax0 = x[i], ay0 = y[i], az0 = z[i];
            double ax1 = x[i + 1], ay1 = y[i + 1], az1 = z[i + 1];
            int o = i * 6;
            if (ax0 < ax1) {
                sa[o] = ax0;
                sa[o + 3] = ax1;
            } else {
                sa[o] = ax1;
                sa[o + 3] = ax0;
            }
            if (ay0 < ay1) {
                sa[o + 1] = ay0;
                sa[o + 4] = ay1;
            } else {
                sa[o + 1] = ay1;
                sa[o + 4] = ay0;
            }
            if (az0 < az1) {
                sa[o + 2] = az0;
                sa[o + 5] = az1;
            } else {
                sa[o + 2] = az1;
                sa[o + 5] = az0;
            }
        }
    }

    // ============================================================================================
    // Constraint: entity bodies (one-way: entity pushes rope, never the reverse)
    // ============================================================================================
    protected void solveEntityConstraints(List<RopeEntityContact> entityContacts) {
        if (entityContacts.isEmpty() || !visualPushEnabled())
            return;
        double baseRadius = ropeRadius + collisionEps;
        updateBounds();
        for (int e = 0; e < entityContacts.size(); e++) {
            RopeEntityContact contact = entityContacts.get(e);
            AABB box = contact.box();
            double radius = contact.player() ? Math.max(baseRadius, ENTITY_FOOT_SUPPORT_HEIGHT) : baseRadius;
            if (box.maxX + radius < minX || box.minX - radius > maxX)
                continue;
            if (box.maxY + radius < minY || box.minY - radius > maxY)
                continue;
            if (box.maxZ + radius < minZ || box.minZ - radius > maxZ)
                continue;
            for (int i = 0; i < segments; i++) {
                pushSegmentOutOfEntityBox(i, i + 1, box, contact.velocity(), radius);
            }
        }
    }

    private void pushSegmentOutOfEntityBox(int a, int b, AABB box, Vec3 entityVelocity, double radius) {
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double segLenSqr = ux * ux + uy * uy + uz * uz;
        double segLen = Math.sqrt(segLenSqr);
        double verticality = segLen > 1.0e-6D ? Math.abs(uy) / segLen : 0.0D;
        double s;
        double cpx, cpy, cpz;
        double spx, spy, spz;
        if (segLenSqr < 1.0e-12D) {
            s = 0.0D;
            spx = ax;
            spy = ay;
            spz = az;
        } else {
            double mx = (box.minX + box.maxX) * 0.5D - ax;
            double my = (box.minY + box.maxY) * 0.5D - ay;
            double mz = (box.minZ + box.maxZ) * 0.5D - az;
            s = (mx * ux + my * uy + mz * uz) / segLenSqr;
            if (s < 0.0D)
                s = 0.0D;
            else if (s > 1.0D)
                s = 1.0D;
            spx = ax + ux * s;
            spy = ay + uy * s;
            spz = az + uz * s;
        }
        for (int it = 0; it < 4; it++) {
            cpx = spx < box.minX ? box.minX : (spx > box.maxX ? box.maxX : spx);
            cpy = spy < box.minY ? box.minY : (spy > box.maxY ? box.maxY : spy);
            cpz = spz < box.minZ ? box.minZ : (spz > box.maxZ ? box.maxZ : spz);
            if (segLenSqr < 1.0e-12D) {
                spx = ax;
                spy = ay;
                spz = az;
                break;
            }
            double tx = cpx - ax, ty = cpy - ay, tz = cpz - az;
            double ns = (tx * ux + ty * uy + tz * uz) / segLenSqr;
            if (ns < 0.0D)
                ns = 0.0D;
            else if (ns > 1.0D)
                ns = 1.0D;
            if (Math.abs(ns - s) < 1.0e-6D) {
                s = ns;
                spx = ax + ux * s;
                spy = ay + uy * s;
                spz = az + uz * s;
                break;
            }
            s = ns;
            spx = ax + ux * s;
            spy = ay + uy * s;
            spz = az + uz * s;
        }
        cpx = spx < box.minX ? box.minX : (spx > box.maxX ? box.maxX : spx);
        cpy = spy < box.minY ? box.minY : (spy > box.maxY ? box.maxY : spy);
        cpz = spz < box.minZ ? box.minZ : (spz > box.maxZ ? box.maxZ : spz);
        boolean footSupportContact = isFootSupportEntityContact(box, entityVelocity, spx, spy, spz, radius);
        double dx = spx - cpx, dy = spy - cpy, dz = spz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;

        double pushLen, nx, ny, nz;
        if (d2 >= radius * radius) {
            if (!footSupportContact)
                return;
            pushLen = footSupportPushLength(box, spy);
            if (pushLen <= 1.0e-6D)
                return;
            nx = 0.0D;
            ny = -1.0D;
            nz = 0.0D;
        } else if (d2 > 1.0e-12D) {
            double d = Math.sqrt(d2);
            pushLen = radius - d;
            double inv = 1.0D / d;
            nx = dx * inv;
            ny = dy * inv;
            nz = dz * inv;

            if (verticality > 0.82D && cpy > box.minY + 1.0e-6D && cpy < box.maxY - 1.0e-6D) {
                double hLenSqr = nx * nx + nz * nz;
                if (hLenSqr > 1.0e-8D) {
                    double invH = 1.0D / Math.sqrt(hLenSqr);
                    nx *= invH;
                    ny = 0.0D;
                    nz *= invH;
                }
            }

            // A vertical landing/standing contact near the feet should bend the rope
            // downward. Do not let a tiny lateral offset on the foot edge shove the rope
            // sideways out from under the player.
            if (footSupportContact && ny > -0.35D) {
                double footPush = footSupportPushLength(box, spy);
                if (footPush <= 1.0e-6D)
                    return;
                pushLen = Math.max(pushLen, footPush);
                nx = 0.0D;
                ny = -1.0D;
                nz = 0.0D;
            }

            // Budgeted slip-under / slip-over. Default behaviour stays lateral push so the
            // rope
            // shoves the entity around. Only when the rope is jammed against a side face
            // near
            // the top or bottom AND has already absorbed >25% of the entity width worth of
            // horizontal push do we let it ignore that band, so gravity pulls it under (or
            // it
            // drapes over) naturally instead of being teleported into the ground.
            boolean cpOnSideFace = cpy > box.minY + 1.0e-6D && cpy < box.maxY - 1.0e-6D
                    && Math.abs(ny) < 0.30D;
            if (cpOnSideFace) {
                double height = box.maxY - box.minY;
                double slipBand = Math.min(0.40D, height * 0.25D);
                double distBottom = spy - box.minY;
                double distTop = box.maxY - spy;
                boolean nearBottom = distBottom < slipBand && distBottom <= distTop;
                boolean nearTop = !nearBottom && distTop < slipBand;
                if (nearBottom || nearTop) {
                    double width = Math.max(box.maxX - box.minX, box.maxZ - box.minZ);
                    double budget = 0.25D * width;
                    double avgAccum = 0.5D * (entityPushAccum[a] + entityPushAccum[b]);
                    if (avgAccum >= budget) {
                        // Budget exhausted: skip this contact entirely; gravity / distance
                        // constraints will pull the rope through the ignored band naturally.
                        return;
                    }
                    // Otherwise fall through to apply lateral push and credit the budget.
                    double horiz = pushLen * Math.hypot(nx, nz);
                    double oneMinusS_acc = 1.0D - s;
                    double cap = budget * 2.0D;
                    if (!pinned[a]) {
                        entityPushAccum[a] = Math.min(entityPushAccum[a] + horiz * oneMinusS_acc, cap);
                    }
                    if (!pinned[b]) {
                        entityPushAccum[b] = Math.min(entityPushAccum[b] + horiz * s, cap);
                    }
                }
            }
        } else {
            // Segment closest point is inside the box. Choose the smallest-penetration face
            // so
            // a lateral walk-through results in horizontal push (a hard 0,1,0 default makes
            // the
            // rope only ever lift, never get shoved sideways).
            double pxNeg = spx - box.minX;
            double pxPos = box.maxX - spx;
            double pyNeg = spy - box.minY;
            double pyPos = box.maxY - spy;
            double pzNeg = spz - box.minZ;
            double pzPos = box.maxZ - spz;
            double bestPen;
            if (footSupportContact) {
                bestPen = Math.max(0.0D, pyNeg);
                nx = 0.0D;
                ny = -1.0D;
                nz = 0.0D;
            } else {
                bestPen = pxNeg;
                nx = -1.0D;
                ny = 0.0D;
                nz = 0.0D;
                if (pxPos < bestPen) {
                    bestPen = pxPos;
                    nx = 1.0D;
                    ny = 0.0D;
                    nz = 0.0D;
                }
                if (pzNeg < bestPen) {
                    bestPen = pzNeg;
                    nx = 0.0D;
                    ny = 0.0D;
                    nz = -1.0D;
                }
                if (pzPos < bestPen) {
                    bestPen = pzPos;
                    nx = 0.0D;
                    ny = 0.0D;
                    nz = 1.0D;
                }
                if (verticality <= 0.82D) {
                    if (pyNeg < bestPen) {
                        bestPen = pyNeg;
                        nx = 0.0D;
                        ny = -1.0D;
                        nz = 0.0D;
                    }
                    if (pyPos < bestPen) {
                        bestPen = pyPos;
                        nx = 0.0D;
                        ny = 1.0D;
                        nz = 0.0D;
                    }
                }
            }
            pushLen = bestPen + radius;
        }

        if (pushLen <= 1.0e-6D)
            return;

        double wa = pinned[a] ? 0.0D : 1.0D;
        double wb = pinned[b] ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - s;
        double denom = wa * oneMinusS * oneMinusS + wb * s * s;
        if (denom < 1.0e-9D)
            return;
        double horizontalPushScale = entityHorizontalPushScale(entityVelocity, nx, nz);
        double k = pushLen / denom;
        double maxStep = 2.0D * pushLen;
        if (wa > 0.0D) {
            double ka = k * wa * oneMinusS;
            if (ka > maxStep)
                ka = maxStep;
            applyTerrainCorrection(a, nx * ka * horizontalPushScale, ny * ka, nz * ka * horizontalPushScale);
        }
        if (wb > 0.0D) {
            double kb = k * wb * s;
            if (kb > maxStep)
                kb = maxStep;
            applyTerrainCorrection(b, nx * kb * horizontalPushScale, ny * kb, nz * kb * horizontalPushScale);
        }
    }

    private boolean isFootSupportEntityContact(AABB box, Vec3 entityVelocity,
            double spx, double spy, double spz, double radius) {
        double footTop = box.minY + ENTITY_FOOT_SUPPORT_HEIGHT;
        double footBottom = box.minY - Math.max(ropeRadius + collisionEps, 0.10D);
        if (spy < footBottom || spy > footTop) {
            return false;
        }
        double margin = Math.max(ENTITY_FOOT_SUPPORT_MARGIN, Math.min(radius, ENTITY_FOOT_SUPPORT_HEIGHT) + 0.02D);
        if (spx < box.minX - margin || spx > box.maxX + margin
                || spz < box.minZ - margin || spz > box.maxZ + margin) {
            return false;
        }
        if (entityVelocity == null) {
            return true;
        }
        double horizontalSpeed = Math.hypot(entityVelocity.x, entityVelocity.z);
        if (horizontalSpeed <= ENTITY_FOOT_SUPPORT_MAX_HORIZONTAL_SPEED) {
            return true;
        }
        return entityVelocity.y < -0.03D && horizontalSpeed <= -entityVelocity.y * 0.35D;
    }

    private double footSupportPushLength(AABB box, double spy) {
        double footGap = box.minY - spy;
        double reach = ENTITY_FOOT_SUPPORT_HEIGHT;
        return footGap <= 0.0D ? reach : reach - footGap;
    }

    private double entityHorizontalPushScale(Vec3 entityVelocity, double nx, double nz) {
        double hLen = Math.sqrt(nx * nx + nz * nz);
        if (entityVelocity == null || hLen < 1.0e-6D)
            return 1.0D;
        double approachSpeed = (entityVelocity.x * nx + entityVelocity.z * nz) / hLen;
        if (approachSpeed <= 0.02D)
            return 1.0D;
        double gain = tuning.entityPushGain();
        if (gain <= 0.0D)
            return 1.0D;
        double extra = approachSpeed * Math.min(gain, 4.0D);
        if (extra > 1.50D)
            extra = 1.50D;
        return 1.0D + extra;
    }

    private void solveSegmentPairNoCheck(RopeSimulation other, int i, int j) {
        // In parallel mode every cross-rope read goes through the tick-start snapshot
        // the
        // driver published in preparePhysicsParallel. This downgrades inter-rope
        // coupling to
        // Jacobi (vs. the old Gauss-Seidel where each rope saw its predecessors' final
        // state),
        // which is the only way a parallel solve can stay deterministic and not jitter.
        // Within
        // a single rope the loop is still Gauss-Seidel.
        final double[] oX = parallelPhase() ? other.snapX : other.x;
        final double[] oY = parallelPhase() ? other.snapY : other.y;
        final double[] oZ = parallelPhase() ? other.snapZ : other.z;
        RopeMath.closestSegmentPoints(
                x[i], y[i], z[i], x[i + 1], y[i + 1], z[i + 1],
                oX[j], oY[j], oZ[j], oX[j + 1], oY[j + 1], oZ[j + 1],
                pairScratch);
        if (pairScratch.distSqr >= ropeRepelDistance * ropeRepelDistance)
            return;

        double s = pairScratch.s;
        double dist = Math.sqrt(pairScratch.distSqr);
        double nx, ny, nz;
        if (dist < 1.0e-6D) {
            nx = stableSeparation.x;
            ny = stableSeparation.y;
            nz = stableSeparation.z;
        } else {
            nx = pairScratch.dx / dist;
            ny = pairScratch.dy / dist;
            nz = pairScratch.dz / dist;
        }

        double penetration = ropeRepelDistance - dist;

        // Inverse mass per contact point: w = (1-t)^2 * w_i + t^2 * w_{i+1}.
        // Important: this per-rope step must not mutate `other`. The client driver
        // steps ropes
        // sequentially, so moving a neighbour here can happen after that neighbour has
        // already
        // rebuilt velocity for this tick, which injects exactly the kind of vertical
        // jitter seen
        // in 3-rope stacks. Within this step `other` is treated as a static collision
        // target, so
        // only this rope's inverse mass belongs in the denominator. Keeping the
        // neighbour's mass
        // here halves the correction and leaves tiny overlaps that never settle in
        // 3-layer stacks.
        double wA = (1.0D - s) * (1.0D - s) * (pinned[i] ? 0.0D : 1.0D)
                + s * s * (pinned[i + 1] ? 0.0D : 1.0D);
        if (wA < 1.0e-9D)
            return;
        double dlambda = penetration / wA;
        // Parallel mode: cross-rope coupling is Jacobi rather than Gauss-Seidel because
        // every
        // worker reads the tick-start snapshot. Pure Jacobi is famously prone to
        // over-shoot
        // oscillation in 3-way stacks (middle rope sees both neighbours' "stale"
        // positions and
        // double-counts the push). Under-relax to suppress that limit cycle;
        // convergence costs
        // ~1 extra tick on settle but jitter goes away.
        if (parallelPhase())
            dlambda *= ropeRopeParallelRelax;

        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyRopeRopeCorrection(i, (1.0D - s) * (pinned[i] ? 0.0D : 1.0D),
                cx, cy, cz);
        applyRopeRopeCorrection(i + 1, s * (pinned[i + 1] ? 0.0D : 1.0D),
                cx, cy, cz);
    }

    private void applyRopeRopeCorrection(int i, double weight, double cx, double cy, double cz) {
        if (weight == 0.0D)
            return;
        double dx = cx * weight, dy = cy * weight, dz = cz * weight;
        // Support-node Y attenuation: a node already resting on terrain resists being
        // pushed
        // downward (so a rope underneath a stack is not driven into the ground), but
        // can still
        // be lifted upward (so the upper rope is the one that visibly shifts).
        if (supportNode[i] && dy < 0.0D)
            dy *= supportDownInvMass;
        x[i] += dx;
        y[i] += dy;
        z[i] += dz;
        contactNode[i] = true;
        markBoundsDirty();
    }
}
