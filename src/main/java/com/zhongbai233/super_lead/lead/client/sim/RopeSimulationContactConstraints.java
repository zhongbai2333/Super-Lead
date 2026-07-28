package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import com.zhongbai233.super_lead.lead.physics.RopeSolver;
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
        return solveDistanceConstraints(targetLen, alphaTilde, forward, false);
    }

    protected double solveDistanceConstraints(
            double targetLen, double alphaTilde, boolean forward, boolean tensileOnly) {
        double maxAbsError = 0.0D;
        if (forward) {
            for (int i = 0; i < segments; i++)
                maxAbsError = Math.max(maxAbsError, solveDistance(i, targetLen, alphaTilde, tensileOnly));
        } else {
            for (int i = segments - 1; i >= 0; i--)
                maxAbsError = Math.max(maxAbsError, solveDistance(i, targetLen, alphaTilde, tensileOnly));
        }
        return maxAbsError;
    }

    /**
     * Finishes an unconstrained-air solve with a symmetric rigid projection.
     *
     * <p>This is part of the distance solver rather than a caller-side patch: the
     * compliant XPBD passes determine the motion, then the forward/reverse rigid
     * sweeps remove the small residual stretch without biasing either anchor.
     */
    protected void finalizeFreeDistanceConstraints(double targetLen) {
        finalizeFreeDistanceConstraints(targetLen, false);
    }

    protected void finalizeFreeDistanceConstraints(double targetLen, boolean tensileOnly) {
        solveDistanceConstraints(targetLen, 0.0D, true, tensileOnly);
        solveDistanceConstraints(targetLen, 0.0D, false, tensileOnly);
    }

    private double solveDistance(int seg, double targetLen, double alphaTilde, boolean tensileOnly) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9)
            return 0.0D;
        double C = len - targetLen;
        if (RopeSolver.computeDistanceCorrection(seg, targetLen, alphaTilde,
                lambdaDistance, x, y, z, pinned[i], pinned[j], tensileOnly,
                distanceCorrectionScratch)) {
            applyCorrection(i, distanceCorrectionScratch[0], distanceCorrectionScratch[1],
                    distanceCorrectionScratch[2]);
            applyCorrection(j, distanceCorrectionScratch[3], distanceCorrectionScratch[4],
                    distanceCorrectionScratch[5]);
        }
        return Math.abs(C);
    }

    // ============================================================================================
    // Constraint: rope-rope
    // ============================================================================================
    protected void solveRopeRopeConstraints(List<RopeSimulation> neighbors) {
        refreshSegmentAabbs();
        final double[] amin = this.segAabb;
        final int aSegs = this.segments;
        for (int n = 0; n < neighbors.size(); n++) {
            RopeSimulation other = neighbors.get(n);
            double m = ropeContactDistance(other);
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
                solveSegmentPairNoCheck(other, segment, j, margin);
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
            sa[o] = Math.min(ax0, ax1);
            sa[o + 3] = Math.max(ax0, ax1);
            sa[o + 1] = Math.min(ay0, ay1);
            sa[o + 4] = Math.max(ay0, ay1);
            sa[o + 2] = Math.min(az0, az1);
            sa[o + 5] = Math.max(az0, az1);
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

        if (!SegmentEndpointCorrection.compute(
                contact.s, pinned[a], pinned[b], entityPush.length, segmentEndpointCorrectionScratch)) {
            return;
        }
        double horizontalPushScale = entityHorizontalPushScale(entityVelocity, entityPush.nx, entityPush.nz);
        if (segmentEndpointCorrectionScratch[0] > 0.0D) {
            applyTerrainCorrection(a, entityPush.nx * segmentEndpointCorrectionScratch[0] * horizontalPushScale,
                    entityPush.ny * segmentEndpointCorrectionScratch[0],
                    entityPush.nz * segmentEndpointCorrectionScratch[0] * horizontalPushScale);
        }
        if (segmentEndpointCorrectionScratch[1] > 0.0D) {
            applyTerrainCorrection(b, entityPush.nx * segmentEndpointCorrectionScratch[1] * horizontalPushScale,
                    entityPush.ny * segmentEndpointCorrectionScratch[1],
                    entityPush.nz * segmentEndpointCorrectionScratch[1] * horizontalPushScale);
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

    private void solveSegmentPairNoCheck(RopeSimulation other, int i, int j, double contactDistance) {
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
        double s = pairScratch.s;
        double dist = Math.sqrt(pairScratch.distSqr);
        double nx, ny, nz;
        double penetration;
        if (dist >= contactDistance) {
            return;
        } else if (dist < 1.0e-6D) {
            Vec3 separation = pairStableSeparation(simulationSeed, other.simulationSeed);
            nx = separation.x;
            ny = separation.y;
            nz = separation.z;
            penetration = contactDistance;
        } else {
            nx = pairScratch.dx / dist;
            ny = pairScratch.dy / dist;
            nz = pairScratch.dz / dist;
            penetration = contactDistance - dist;
        }

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

        // Retain the actual contact-point correction, not the XPBD multiplier.
        // applyContactPointVelocityDelta already accounts for effective mass, so
        // storing dlambda directly would amplify friction by 1 / wA.
        recordRopeContactPair(i, other, j, s, pairScratch.t, nx, ny, nz, dlambda * wA);

        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyRopeRopeCorrection(i, (1.0D - s) * (pinned[i] ? 0.0D : 1.0D),
                cx, cy, cz, nx, ny, nz);
        applyRopeRopeCorrection(i + 1, s * (pinned[i + 1] ? 0.0D : 1.0D),
                cx, cy, cz, nx, ny, nz);
    }

    private void recordRopeContactPair(int segment, RopeSimulation other, int otherSegment,
            double selfT, double otherT, double nx, double ny, double nz, double normalCorrection) {
        if (normalCorrection <= ropeContactNormalCorrection[segment]) {
            return;
        }
        ropeContactOther[segment] = other;
        ropeContactOtherSegment[segment] = otherSegment;
        ropeContactSelfT[segment] = selfT;
        ropeContactOtherT[segment] = otherT;
        ropeContactPairNormalX[segment] = nx;
        ropeContactPairNormalY[segment] = ny;
        ropeContactPairNormalZ[segment] = nz;
        ropeContactNormalCorrection[segment] = normalCorrection;
    }

    private void applyRopeRopeCorrection(int i, double weight, double cx, double cy, double cz,
            double nx, double ny, double nz) {
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
        // Rope-rope contact is an inelastic positional constraint. If this correction
        // exists only in x/y/z, the velocity reconstruction at substep end interprets
        // penetration removal as a real separating impulse. Wind then pushes the ropes
        // together again and creates a small perpetual bounce. Move the pre-constraint
        // origin by the same amount so tangential/wind velocity is preserved while the
        // artificial normal velocity is removed.
        xPrev[i] += dx;
        yPrev[i] += dy;
        zPrev[i] += dz;
        contactNode[i] = true;
        ropeContactNode[i] = true;
        ropeContactNormalX[i] += nx * weight;
        ropeContactNormalY[i] += ny * weight;
        ropeContactNormalZ[i] += nz * weight;
        markBoundsDirty();
    }

    /**
     * Removes only velocity that would re-enter a rope-rope contact manifold.
     * Tangential motion and already-separating motion are preserved.
     */
    protected void projectRopeContactVelocities() {
        for (int i = 1; i < nodes - 1; i++) {
            if (!ropeContactNode[i]) {
                continue;
            }
            double nx = ropeContactNormalX[i];
            double ny = ropeContactNormalY[i];
            double nz = ropeContactNormalZ[i];
            double lenSqr = nx * nx + ny * ny + nz * nz;
            if (lenSqr <= 1.0e-12D) {
                // Opposing contacts do not define one valid projection plane. Freezing all
                // components here destroyed legitimate motion along a stack, so leave this
                // ambiguous node to the retained pair contacts instead.
                continue;
            }
            double invLen = 1.0D / Math.sqrt(lenSqr);
            nx *= invLen;
            ny *= invLen;
            nz *= invLen;
            double inward = vx[i] * nx + vy[i] * ny + vz[i] * nz;
            if (inward < 0.0D) {
                vx[i] -= nx * inward;
                vy[i] -= ny * inward;
                vz[i] -= nz * inward;
            }
        }
    }

    /**
     * Resolves rope-rope velocity at retained segment-pair contacts. The neighbour
     * is read-only: each rope applies its own response when it is stepped, avoiding
     * cross-rope writes after a neighbour has already reconstructed its velocity.
     */
    protected void solveRopeContactVelocities(double h) {
        if (!(h > 0.0D)) {
            return;
        }
        double staticFriction = tuning.ropeRopeStaticFriction();
        double dynamicFriction = tuning.ropeRopeDynamicFriction();
        for (int segment = 0; segment < segments; segment++) {
            RopeSimulation other = ropeContactOther[segment];
            if (other == null) {
                continue;
            }
            int otherSegment = ropeContactOtherSegment[segment];
            double selfT = ropeContactSelfT[segment];
            double otherT = ropeContactOtherT[segment];
            double selfA = 1.0D - selfT;
            double selfB = selfT;
            double otherA = 1.0D - otherT;
            double otherB = otherT;

            double[] otherVx = parallelPhase() ? other.snapVx : other.vx;
            double[] otherVy = parallelPhase() ? other.snapVy : other.vy;
            double[] otherVz = parallelPhase() ? other.snapVz : other.vz;
            double selfVx = vx[segment] * selfA + vx[segment + 1] * selfB;
            double selfVy = vy[segment] * selfA + vy[segment + 1] * selfB;
            double selfVz = vz[segment] * selfA + vz[segment + 1] * selfB;
            double relativeX = selfVx
                    - (otherVx[otherSegment] * otherA + otherVx[otherSegment + 1] * otherB);
            double relativeY = selfVy
                    - (otherVy[otherSegment] * otherA + otherVy[otherSegment + 1] * otherB);
            double relativeZ = selfVz
                    - (otherVz[otherSegment] * otherA + otherVz[otherSegment + 1] * otherB);

            double nx = ropeContactPairNormalX[segment];
            double ny = ropeContactPairNormalY[segment];
            double nz = ropeContactPairNormalZ[segment];
            double normalSpeed = relativeX * nx + relativeY * ny + relativeZ * nz;
            double normalDelta = normalSpeed < 0.0D ? -normalSpeed : 0.0D;
            relativeX += nx * normalDelta;
            relativeY += ny * normalDelta;
            relativeZ += nz * normalDelta;

            double tangentSpeed = Math.sqrt(
                    relativeX * relativeX + relativeY * relativeY + relativeZ * relativeZ);
            double normalBudget = Math.max(normalDelta, ropeContactNormalCorrection[segment] / h);
            double frictionDelta = 0.0D;
            if (tangentSpeed > 1.0e-12D && staticFriction > 0.0D) {
                double staticLimit = staticFriction * normalBudget;
                frictionDelta = tangentSpeed <= staticLimit
                        ? tangentSpeed
                        : Math.min(tangentSpeed, dynamicFriction * normalBudget);
            }

            double deltaX = nx * normalDelta;
            double deltaY = ny * normalDelta;
            double deltaZ = nz * normalDelta;
            if (frictionDelta > 0.0D) {
                double scale = -frictionDelta / tangentSpeed;
                deltaX += relativeX * scale;
                deltaY += relativeY * scale;
                deltaZ += relativeZ * scale;
            }
            applyContactPointVelocityDelta(segment, selfT, deltaX, deltaY, deltaZ);
            applyRopeContactRockingResistance(segment, nx, ny, nz, normalBudget);
        }
    }

    /**
     * Damps the contact-point-zero rocking mode: the two segment endpoints can
     * move in opposite normal directions while their interpolated contact point
     * stays still, so ordinary point friction cannot see the motion. Equal and
     * opposite endpoint impulses preserve segment center velocity and affect only
     * this seesaw-like differential normal speed.
     */
    private void applyRopeContactRockingResistance(int segment,
            double nx, double ny, double nz, double normalBudget) {
        double resistance = tuning.ropeRopeRockingResistance();
        if (!(resistance > 0.0D) || !(normalBudget > 0.0D)
                || pinned[segment] || pinned[segment + 1]) {
            return;
        }
        double relativeEndpointSpeed = (vx[segment + 1] - vx[segment]) * nx
                + (vy[segment + 1] - vy[segment]) * ny
                + (vz[segment + 1] - vz[segment]) * nz;
        double magnitude = Math.abs(relativeEndpointSpeed);
        if (magnitude <= 1.0e-12D) {
            return;
        }
        double removed = Math.min(magnitude, resistance * normalBudget);
        double endpointDelta = 0.5D * Math.copySign(removed, relativeEndpointSpeed);
        vx[segment] += nx * endpointDelta;
        vy[segment] += ny * endpointDelta;
        vz[segment] += nz * endpointDelta;
        vx[segment + 1] -= nx * endpointDelta;
        vy[segment + 1] -= ny * endpointDelta;
        vz[segment + 1] -= nz * endpointDelta;
    }

    private void applyContactPointVelocityDelta(int segment, double t,
            double dx, double dy, double dz) {
        double a = 1.0D - t;
        double b = t;
        double wa = pinned[segment] ? 0.0D : a * a;
        double wb = pinned[segment + 1] ? 0.0D : b * b;
        double effectiveMass = wa + wb;
        if (effectiveMass <= 1.0e-12D) {
            return;
        }
        if (wa > 0.0D) {
            double scale = a / effectiveMass;
            vx[segment] += dx * scale;
            vy[segment] += dy * scale;
            vz[segment] += dz * scale;
        }
        if (wb > 0.0D) {
            double scale = b / effectiveMass;
            vx[segment + 1] += dx * scale;
            vy[segment + 1] += dy * scale;
            vz[segment + 1] += dz * scale;
        }
    }

    static Vec3 pairStableSeparation(long selfSeed, long otherSeed) {
        long low = Long.compareUnsigned(selfSeed, otherSeed) <= 0 ? selfSeed : otherSeed;
        long high = low == selfSeed ? otherSeed : selfSeed;
        long mixed = low * 0x9E3779B97F4A7C15L
                ^ Long.rotateLeft(high * 0xC2B2AE3D27D4EB4FL, 23);
        Vec3 axis = com.zhongbai233.super_lead.lead.physics.RopeSagModel.stableUnitVector(mixed);
        return selfSeed == low ? axis : axis.scale(-1.0D);
    }
}
