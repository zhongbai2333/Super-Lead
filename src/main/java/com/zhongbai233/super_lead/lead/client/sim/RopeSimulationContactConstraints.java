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
 * contact candidates for gameplay pulses. It runs <em>before</em> terrain
 * constraints so that block collision always has the final word — when a
 * player pushes a rope against a wall the rope slides into the player's
 * bounding box rather than clipping into blocks.
 */
abstract class RopeSimulationContactConstraints extends RopeSimulationTerrainConstraints {
    private static final double ENTITY_FOOT_SUPPORT_HEIGHT = 0.18D;
    private static final double ENTITY_FOOT_SUPPORT_MARGIN = 0.08D;
    private static final double ENTITY_FOOT_SUPPORT_MAX_HORIZONTAL_SPEED = 0.08D;

    private final SegmentBoxContact entitySegmentBoxContact = new SegmentBoxContact();
    private final EntityPush entityPush = new EntityPush();

    protected RopeSimulationContactConstraints(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    // ============================================================================================
    // Constraint: distance (XPBD)
    // ============================================================================================
    protected double solveDistanceConstraints(double targetLen, double alphaTilde, boolean forward) {
        double maxAbsError = 0.0D;
        if (forward) {
            for (int i = 0; i < segments; i++)
                maxAbsError = Math.max(maxAbsError, solveDistance(i, targetLen, alphaTilde));
        } else {
            for (int i = segments - 1; i >= 0; i--)
                maxAbsError = Math.max(maxAbsError, solveDistance(i, targetLen, alphaTilde));
        }
        return maxAbsError;
    }

    private double solveDistance(int seg, double targetLen, double alphaTilde) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9)
            return 0.0D;
        double w1 = pinned[i] ? 0.0D : 1.0D;
        double w2 = pinned[j] ? 0.0D : 1.0D;
        double wsum = w1 + w2;
        if (wsum == 0.0D)
            return 0.0D;
        double C = len - targetLen;
        double dlambda = (-C - alphaTilde * lambdaDistance[seg]) / (wsum + alphaTilde);
        lambdaDistance[seg] += dlambda;
        double nx = dx / len, ny = dy / len, nz = dz / len;
        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyCorrection(i, -cx * w1, -cy * w1, -cz * w1);
        applyCorrection(j, cx * w2, cy * w2, cz * w2);
        return Math.abs(C);
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
            if (!prepareNeighborForRopeRopeSolve(other, m))
                continue;
            solveNeighborSegmentPairs(other, amin, aSegs, m);
        }
    }

    private boolean prepareNeighborForRopeRopeSolve(RopeSimulation other, double margin) {
        if (other == this || !boundsOverlap(other, margin))
            return false;
        // Same as above: skip writing to other's segAabb in parallel; prepare phase did
        // it.
        if (!parallelPhase()) {
            other.refreshSegmentAabbs();
        }
        return true;
    }

    private void solveNeighborSegmentPairs(RopeSimulation other, double[] amin, int aSegs, double margin) {
        final double[] bmin = other.segAabb;
        final int bSegs = other.segments;
        for (int i = 0; i < aSegs; i++) {
            int oa = i * 6;
            if (!segmentOverlapsRopeBounds(amin, oa, other, margin))
                continue;
            solveSegmentAgainstNeighbor(other, amin, bmin, bSegs, i, oa, margin);
        }
    }

    private void solveSegmentAgainstNeighbor(
            RopeSimulation other, double[] amin, double[] bmin, int bSegs, int segment, int offset, double margin) {
        for (int j = 0; j < bSegs; j++) {
            int ob = j * 6;
            if (segmentAabbsOverlap(amin, offset, bmin, ob, margin)) {
                solveSegmentPairNoCheck(other, segment, j);
            }
        }
    }

    private static boolean segmentOverlapsRopeBounds(
            double[] segmentAabb, int offset, RopeSimulation other, double margin) {
        return segmentAabb[offset + 3] + margin >= other.minX
                && segmentAabb[offset] - margin <= other.maxX
                && segmentAabb[offset + 4] + margin >= other.minY
                && segmentAabb[offset + 1] - margin <= other.maxY
                && segmentAabb[offset + 5] + margin >= other.minZ
                && segmentAabb[offset + 2] - margin <= other.maxZ;
    }

    private static boolean segmentAabbsOverlap(double[] aabbA, int offsetA, double[] aabbB, int offsetB,
            double margin) {
        return aabbA[offsetA + 3] + margin >= aabbB[offsetB]
                && aabbB[offsetB + 3] + margin >= aabbA[offsetA]
                && aabbA[offsetA + 4] + margin >= aabbB[offsetB + 1]
                && aabbB[offsetB + 4] + margin >= aabbA[offsetA + 1]
                && aabbA[offsetA + 5] + margin >= aabbB[offsetB + 2]
                && aabbB[offsetB + 5] + margin >= aabbA[offsetA + 2];
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
        RopeContactResponseModel.Weights response = RopeContactResponseModel.weights(tuning.slack());
        if (!response.hasEntityVolume())
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
                pushSegmentOutOfEntityBox(i, i + 1, box, contact.velocity(), radius, response.entityVolume());
            }
        }
    }

    private void pushSegmentOutOfEntityBox(int a, int b, AABB box, Vec3 entityVelocity, double radius,
            double entityVolumeScale) {
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        SegmentBoxContact contact = entitySegmentBoxContact.compute(ax, ay, az, bx, by, bz, box);
        double segLen = Math.sqrt(contact.segLenSqr);
        double verticality = segLen > 1.0e-6D ? Math.abs(contact.uy) / segLen : 0.0D;
        boolean footSupportContact = isFootSupportEntityContact(box, entityVelocity,
                contact.spx, contact.spy, contact.spz, radius);
        if (!resolveEntityPush(a, b, box, contact, verticality, footSupportContact, radius, entityPush)) {
            return;
        }
        if (!footSupportContact && !removeSegmentTangentPush(contact, entityPush)) {
            return;
        }
        entityPush.length *= entityVolumeScale;
        if (entityPush.length <= 1.0e-6D) {
            return;
        }

        double wa = pinned[a] ? 0.0D : 1.0D;
        double wb = pinned[b] ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - contact.s;
        double denom = wa * oneMinusS * oneMinusS + wb * contact.s * contact.s;
        if (denom < 1.0e-9D)
            return;
        double horizontalPushScale = entityHorizontalPushScale(entityVelocity, entityPush.nx, entityPush.nz);
        double k = entityPush.length / denom;
        double maxStep = 2.0D * entityPush.length;
        if (wa > 0.0D) {
            double ka = k * wa * oneMinusS;
            if (ka > maxStep)
                ka = maxStep;
            applyTerrainCorrection(a, entityPush.nx * ka * horizontalPushScale, entityPush.ny * ka,
                    entityPush.nz * ka * horizontalPushScale);
        }
        if (wb > 0.0D) {
            double kb = k * wb * contact.s;
            if (kb > maxStep)
                kb = maxStep;
            applyTerrainCorrection(b, entityPush.nx * kb * horizontalPushScale, entityPush.ny * kb,
                    entityPush.nz * kb * horizontalPushScale);
        }
    }

    private static boolean removeSegmentTangentPush(SegmentBoxContact contact, EntityPush push) {
        if (contact.segLenSqr <= 1.0e-12D) {
            return true;
        }
        double invLen = 1.0D / Math.sqrt(contact.segLenSqr);
        double tx = contact.ux * invLen;
        double ty = contact.uy * invLen;
        double tz = contact.uz * invLen;
        double along = push.nx * tx + push.ny * ty + push.nz * tz;
        push.nx -= tx * along;
        push.ny -= ty * along;
        push.nz -= tz * along;
        double len = Math.sqrt(push.nx * push.nx + push.ny * push.ny + push.nz * push.nz);
        if (len <= 1.0e-5D) {
            return false;
        }
        double inv = 1.0D / len;
        push.nx *= inv;
        push.ny *= inv;
        push.nz *= inv;
        push.length *= len;
        return true;
    }

    private boolean resolveEntityPush(int a, int b, AABB box, SegmentBoxContact contact,
            double verticality, boolean footSupportContact, double radius, EntityPush out) {
        if (contact.distSqr >= radius * radius) {
            return outsideFootSupportPush(box, contact.spy, footSupportContact, out);
        }
        if (contact.distSqr > 1.0e-12D) {
            return surfaceEntityPush(a, b, box, contact, verticality, footSupportContact, radius, out);
        }
        return insideEntityPush(box, contact, verticality, footSupportContact, radius, out);
    }

    private boolean outsideFootSupportPush(AABB box, double spy, boolean footSupportContact, EntityPush out) {
        if (!footSupportContact)
            return false;
        double footPush = footSupportPushLength(box, spy);
        if (footPush <= 1.0e-6D)
            return false;
        out.set(footPush, 0.0D, -1.0D, 0.0D);
        return true;
    }

    private boolean surfaceEntityPush(int a, int b, AABB box, SegmentBoxContact contact,
            double verticality, boolean footSupportContact, double radius, EntityPush out) {
        double d = Math.sqrt(contact.distSqr);
        double inv = 1.0D / d;
        out.set(radius - d, contact.dx * inv, contact.dy * inv, contact.dz * inv);
        normalizeVerticalSidePush(box, contact, verticality, out);
        if (!applyFootSupportOverride(box, contact.spy, footSupportContact, out)) {
            return false;
        }
        return creditOrSkipSlipBand(a, b, box, contact, out);
    }

    private void normalizeVerticalSidePush(AABB box, SegmentBoxContact contact, double verticality, EntityPush out) {
        if (verticality <= 0.82D || contact.cpy <= box.minY + 1.0e-6D || contact.cpy >= box.maxY - 1.0e-6D) {
            return;
        }
        double hLenSqr = out.nx * out.nx + out.nz * out.nz;
        if (hLenSqr <= 1.0e-8D) {
            return;
        }
        double invH = 1.0D / Math.sqrt(hLenSqr);
        out.nx *= invH;
        out.ny = 0.0D;
        out.nz *= invH;
    }

    private boolean applyFootSupportOverride(AABB box, double spy, boolean footSupportContact, EntityPush out) {
        if (!footSupportContact || out.ny <= -0.35D) {
            return true;
        }
        double footPush = footSupportPushLength(box, spy);
        if (footPush <= 1.0e-6D)
            return false;
        out.set(Math.max(out.length, footPush), 0.0D, -1.0D, 0.0D);
        return true;
    }

    private boolean creditOrSkipSlipBand(int a, int b, AABB box, SegmentBoxContact contact, EntityPush out) {
        if (contact.cpy <= box.minY + 1.0e-6D || contact.cpy >= box.maxY - 1.0e-6D || Math.abs(out.ny) >= 0.30D) {
            return true;
        }
        double height = box.maxY - box.minY;
        double slipBand = Math.min(0.40D, height * 0.25D);
        double distBottom = contact.spy - box.minY;
        double distTop = box.maxY - contact.spy;
        boolean nearBottom = distBottom < slipBand && distBottom <= distTop;
        boolean nearTop = !nearBottom && distTop < slipBand;
        if (!nearBottom && !nearTop) {
            return true;
        }

        double width = Math.max(box.maxX - box.minX, box.maxZ - box.minZ);
        double budget = 0.25D * width;
        double avgAccum = 0.5D * (entityPushAccum[a] + entityPushAccum[b]);
        if (avgAccum >= budget) {
            return false;
        }

        double horiz = out.length * Math.hypot(out.nx, out.nz);
        double cap = budget * 2.0D;
        if (!pinned[a]) {
            entityPushAccum[a] = Math.min(entityPushAccum[a] + horiz * (1.0D - contact.s), cap);
        }
        if (!pinned[b]) {
            entityPushAccum[b] = Math.min(entityPushAccum[b] + horiz * contact.s, cap);
        }
        return true;
    }

    private boolean insideEntityPush(AABB box, SegmentBoxContact contact,
            double verticality, boolean footSupportContact, double radius, EntityPush out) {
        double pyNeg = contact.spy - box.minY;
        if (footSupportContact) {
            out.set(Math.max(0.0D, pyNeg) + radius, 0.0D, -1.0D, 0.0D);
            return true;
        }

        double bestPen = contact.spx - box.minX;
        out.set(bestPen, -1.0D, 0.0D, 0.0D);
        bestPen = chooseInsideFace(box.maxX - contact.spx, 1.0D, 0.0D, 0.0D, bestPen, out);
        bestPen = chooseInsideFace(contact.spz - box.minZ, 0.0D, 0.0D, -1.0D, bestPen, out);
        bestPen = chooseInsideFace(box.maxZ - contact.spz, 0.0D, 0.0D, 1.0D, bestPen, out);
        if (verticality <= 0.82D) {
            bestPen = chooseInsideFace(pyNeg, 0.0D, -1.0D, 0.0D, bestPen, out);
            bestPen = chooseInsideFace(box.maxY - contact.spy, 0.0D, 1.0D, 0.0D, bestPen, out);
        }
        out.length = bestPen + radius;
        return true;
    }

    private static double chooseInsideFace(double penetration, double nx, double ny, double nz,
            double bestPen, EntityPush out) {
        if (penetration < bestPen) {
            out.set(penetration, nx, ny, nz);
            return penetration;
        }
        return bestPen;
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

    private static final class EntityPush {
        private double length;
        private double nx;
        private double ny;
        private double nz;

        private void set(double length, double nx, double ny, double nz) {
            this.length = length;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
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
