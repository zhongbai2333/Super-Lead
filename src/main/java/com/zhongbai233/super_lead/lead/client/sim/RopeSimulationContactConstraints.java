package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationContactConstraints extends RopeSimulationTerrainConstraints {
    protected RopeSimulationContactConstraints(Vec3 a, Vec3 b, long seed, boolean tight, RopeTuning tuning) {
        super(a, b, seed, tight, tuning);
    }

    // ============================================================================================
    // Constraint: distance (XPBD)
    // ============================================================================================
    protected void solveDistanceConstraints(double targetLen, double alphaTilde, boolean forward) {
        if (forward) {
            for (int i = 0; i < segments; i++) solveDistance(i, targetLen, alphaTilde);
        } else {
            for (int i = segments - 1; i >= 0; i--) solveDistance(i, targetLen, alphaTilde);
        }
    }

    private void solveDistance(int seg, double targetLen, double alphaTilde) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return;
        double w1 = pinned[i] ? 0.0D : 1.0D;
        double w2 = pinned[j] ? 0.0D : 1.0D;
        double wsum = w1 + w2;
        if (wsum == 0.0D) return;
        double C = len - targetLen;
        double dlambda = (-C - alphaTilde * lambdaDistance[seg]) / (wsum + alphaTilde);
        lambdaDistance[seg] += dlambda;
        double nx = dx / len, ny = dy / len, nz = dz / len;
        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyCorrection(i, -cx * w1, -cy * w1, -cz * w1);
        applyCorrection(j, cx * w2, cy * w2, cz * w2);
    }

    // ============================================================================================
    // Constraint: rope-rope (with soft layer bias)
    // ============================================================================================
    protected void solveRopeRopeConstraints(List<RopeSimulation> neighbors) {
        final double m = ROPE_REPEL_DISTANCE;
        refreshSegmentAabbs();
        final double[] amin = this.segAabb;
        final int aSegs = this.segments;
        for (int n = 0; n < neighbors.size(); n++) {
            RopeSimulation other = neighbors.get(n);
            if (other == this) continue;
            if (!boundsOverlap(other, m)) continue;
            // Same as above: skip writing to other's segAabb in parallel; prepare phase did it.
            if (!parallelPhase()) other.refreshSegmentAabbs();
            final double[] bmin = other.segAabb;
            final int bSegs = other.segments;
            // Per-rope full bbox of `other` (already updated by boundsOverlap).
            final double oMinX = other.minX, oMinY = other.minY, oMinZ = other.minZ;
            final double oMaxX = other.maxX, oMaxY = other.maxY, oMaxZ = other.maxZ;
            for (int i = 0; i < aSegs; i++) {
                int oa = i * 6;
                double aMinX = amin[oa],     aMinY = amin[oa + 1], aMinZ = amin[oa + 2];
                double aMaxX = amin[oa + 3], aMaxY = amin[oa + 4], aMaxZ = amin[oa + 5];
                // Row prune: this segment vs other-rope full bbox.
                if (aMaxX + m < oMinX || aMinX - m > oMaxX) continue;
                if (aMaxY + m < oMinY || aMinY - m > oMaxY) continue;
                if (aMaxZ + m < oMinZ || aMinZ - m > oMaxZ) continue;
                for (int j = 0; j < bSegs; j++) {
                    int ob = j * 6;
                    if (aMaxX + m < bmin[ob]     || bmin[ob + 3] + m < aMinX) continue;
                    if (aMaxY + m < bmin[ob + 1] || bmin[ob + 4] + m < aMinY) continue;
                    if (aMaxZ + m < bmin[ob + 2] || bmin[ob + 5] + m < aMinZ) continue;
                    solveSegmentPairNoCheck(other, i, j);
                }
            }
        }
    }

    /** Refresh {@link #segAabb} from current node positions. Cheap O(segments). */
    protected void refreshSegmentAabbs() {
        int needLen = segments * 6;
        if (segAabb == null || segAabb.length < needLen) segAabb = new double[needLen];
        final double[] sa = segAabb;
        for (int i = 0; i < segments; i++) {
            double ax0 = x[i],     ay0 = y[i],     az0 = z[i];
            double ax1 = x[i + 1], ay1 = y[i + 1], az1 = z[i + 1];
            int o = i * 6;
            if (ax0 < ax1) { sa[o]     = ax0; sa[o + 3] = ax1; } else { sa[o]     = ax1; sa[o + 3] = ax0; }
            if (ay0 < ay1) { sa[o + 1] = ay0; sa[o + 4] = ay1; } else { sa[o + 1] = ay1; sa[o + 4] = ay0; }
            if (az0 < az1) { sa[o + 2] = az0; sa[o + 5] = az1; } else { sa[o + 2] = az1; sa[o + 5] = az0; }
        }
    }

    // ============================================================================================
    // Constraint: entity bodies (one-way: entity pushes rope, never the reverse)
    // ============================================================================================
    protected void solveEntityConstraints(List<AABB> entityBoxes) {
        if (entityBoxes.isEmpty()) return;
        double r = ROPE_RADIUS + COLLISION_EPS;
        updateBounds();
        for (int e = 0; e < entityBoxes.size(); e++) {
            AABB box = entityBoxes.get(e);
            if (box.maxX + r < minX || box.minX - r > maxX) continue;
            if (box.maxY + r < minY || box.minY - r > maxY) continue;
            if (box.maxZ + r < minZ || box.minZ - r > maxZ) continue;
            for (int i = 0; i < segments; i++) {
                pushSegmentOutOfEntityBox(i, i + 1, box, r);
            }
        }
    }

    private void pushSegmentOutOfEntityBox(int a, int b, AABB box, double radius) {
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double segLenSqr = ux * ux + uy * uy + uz * uz;
        double s;
        double cpx, cpy, cpz;
        double spx, spy, spz;
        if (segLenSqr < 1.0e-12D) {
            s = 0.0D;
            spx = ax; spy = ay; spz = az;
        } else {
            double mx = (box.minX + box.maxX) * 0.5D - ax;
            double my = (box.minY + box.maxY) * 0.5D - ay;
            double mz = (box.minZ + box.maxZ) * 0.5D - az;
            s = (mx * ux + my * uy + mz * uz) / segLenSqr;
            if (s < 0.0D) s = 0.0D; else if (s > 1.0D) s = 1.0D;
            spx = ax + ux * s; spy = ay + uy * s; spz = az + uz * s;
        }
        for (int it = 0; it < 4; it++) {
            cpx = spx < box.minX ? box.minX : (spx > box.maxX ? box.maxX : spx);
            cpy = spy < box.minY ? box.minY : (spy > box.maxY ? box.maxY : spy);
            cpz = spz < box.minZ ? box.minZ : (spz > box.maxZ ? box.maxZ : spz);
            if (segLenSqr < 1.0e-12D) { spx = ax; spy = ay; spz = az; break; }
            double tx = cpx - ax, ty = cpy - ay, tz = cpz - az;
            double ns = (tx * ux + ty * uy + tz * uz) / segLenSqr;
            if (ns < 0.0D) ns = 0.0D; else if (ns > 1.0D) ns = 1.0D;
            if (Math.abs(ns - s) < 1.0e-6D) { s = ns; spx = ax + ux * s; spy = ay + uy * s; spz = az + uz * s; break; }
            s = ns;
            spx = ax + ux * s; spy = ay + uy * s; spz = az + uz * s;
        }
        cpx = spx < box.minX ? box.minX : (spx > box.maxX ? box.maxX : spx);
        cpy = spy < box.minY ? box.minY : (spy > box.maxY ? box.maxY : spy);
        cpz = spz < box.minZ ? box.minZ : (spz > box.maxZ ? box.maxZ : spz);
        double dx = spx - cpx, dy = spy - cpy, dz = spz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= radius * radius) return;

        double pushLen, nx, ny, nz;
        if (d2 > 1.0e-12D) {
            double d = Math.sqrt(d2);
            pushLen = radius - d;
            double inv = 1.0D / d;
            nx = dx * inv; ny = dy * inv; nz = dz * inv;

            // Budgeted slip-under / slip-over. Default behaviour stays lateral push so the rope
            // shoves the entity around. Only when the rope is jammed against a side face near
            // the top or bottom AND has already absorbed >25% of the entity width worth of
            // horizontal push do we let it ignore that band, so gravity pulls it under (or it
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
            // Segment closest point is inside the box. Choose the smallest-penetration face so
            // a lateral walk-through results in horizontal push (a hard 0,1,0 default makes the
            // rope only ever lift, never get shoved sideways).
            double pxNeg = spx - box.minX;
            double pxPos = box.maxX - spx;
            double pyNeg = spy - box.minY;
            double pyPos = box.maxY - spy;
            double pzNeg = spz - box.minZ;
            double pzPos = box.maxZ - spz;
            double bestPen = pxNeg; nx = -1.0D; ny = 0.0D; nz = 0.0D;
            if (pxPos < bestPen) { bestPen = pxPos; nx = 1.0D; ny = 0.0D; nz = 0.0D; }
            if (pyNeg < bestPen) { bestPen = pyNeg; nx = 0.0D; ny = -1.0D; nz = 0.0D; }
            if (pyPos < bestPen) { bestPen = pyPos; nx = 0.0D; ny = 1.0D; nz = 0.0D; }
            if (pzNeg < bestPen) { bestPen = pzNeg; nx = 0.0D; ny = 0.0D; nz = -1.0D; }
            if (pzPos < bestPen) { bestPen = pzPos; nx = 0.0D; ny = 0.0D; nz = 1.0D; }
            pushLen = bestPen + radius;
        }

        double wa = pinned[a] ? 0.0D : 1.0D;
        double wb = pinned[b] ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - s;
        double denom = wa * oneMinusS * oneMinusS + wb * s * s;
        if (denom < 1.0e-9D) return;
        double k = pushLen / denom;
        double maxStep = 2.0D * pushLen;
        if (wa > 0.0D) {
            double ka = k * wa * oneMinusS;
            if (ka > maxStep) ka = maxStep;
            applyTerrainCorrection(a, nx * ka, ny * ka, nz * ka);
        }
        if (wb > 0.0D) {
            double kb = k * wb * s;
            if (kb > maxStep) kb = maxStep;
            applyTerrainCorrection(b, nx * kb, ny * kb, nz * kb);
        }
    }

    private void solveSegmentPairNoCheck(RopeSimulation other, int i, int j) {
        // In parallel mode every cross-rope read goes through the tick-start snapshot the
        // driver published in preparePhysicsParallel. This downgrades inter-rope coupling to
        // Jacobi (vs. the old Gauss-Seidel where each rope saw its predecessors' final state),
        // which is the only way a parallel solve can stay deterministic and not jitter. Within
        // a single rope the loop is still Gauss-Seidel.
        final double[] oX = parallelPhase() ? other.snapX : other.x;
        final double[] oY = parallelPhase() ? other.snapY : other.y;
        final double[] oZ = parallelPhase() ? other.snapZ : other.z;
        RopeMath.closestSegmentPoints(
                x[i], y[i], z[i], x[i + 1], y[i + 1], z[i + 1],
                oX[j], oY[j], oZ[j], oX[j + 1], oY[j + 1], oZ[j + 1],
                pairScratch);
        if (pairScratch.distSqr >= ROPE_REPEL_DISTANCE * ROPE_REPEL_DISTANCE) return;

        double s = pairScratch.s, t = pairScratch.t;
        double dist = Math.sqrt(pairScratch.distSqr);
        // Layer sign = which rope sits above. We freeze the ordering at the START of the
        // substep (yPrev), so it cannot flip between iterations as the corrections move ropes
        // past each other. Using current y here caused 3-rope stacks to "fight" for the
        // upper slot every iteration. Per-rope priority is only used as the deepest fallback
        // when the substep started with the two ropes essentially co-planar.
        // In parallel mode read the neighbour's layered ordering off the same tick-start snapshot
        // (snapY) used for geometry: yPrev is mutated by the neighbour's own substep concurrently.
        final double[] oYPrev = parallelPhase() ? other.snapY : other.yPrev;
        double yA = yPrev[i] * (1.0D - s) + yPrev[i + 1] * s;
        double yB = oYPrev[j] * (1.0D - t) + oYPrev[j + 1] * t;
        double dyPrev = yA - yB;
        double layerSign;
        if (dyPrev > 1.0e-3D) {
            layerSign = 1.0D;
        } else if (dyPrev < -1.0e-3D) {
            layerSign = -1.0D;
        } else {
            layerSign = layerPriority > other.layerPriority ? 1.0D
                    : (layerPriority < other.layerPriority ? -1.0D
                    : (System.identityHashCode(this) > System.identityHashCode(other) ? 1.0D : -1.0D));
        }

        double nx, ny, nz;
        if (dist < 1.0e-6D) {
            nx = 0.0D; ny = layerSign; nz = 0.0D;
        } else {
            nx = pairScratch.dx / dist;
            ny = pairScratch.dy / dist;
            nz = pairScratch.dz / dist;
            // Soft layer bias: only when the contact is vertically tight enough that the raw
            double yProx = 1.0D - Math.min(1.0D, Math.abs(pairScratch.dy) / LAYER_CAPTURE_HEIGHT);
            if (yProx > 0.0D) {
                double k = LAYER_BLEND_STRENGTH * yProx;
                ny = ny + (layerSign - ny) * k;
                nx *= 1.0D - k * 0.5D;
                nz *= 1.0D - k * 0.5D;
                double m = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (m > 1.0e-9D) { nx /= m; ny /= m; nz /= m; }
            }
        }

        double endpointFade = endpointFade(i, s) * other.endpointFade(j, t);
        if (endpointFade <= 1.0e-4D) return;
        double penetration = ROPE_REPEL_DISTANCE - dist;

        // Inverse mass per contact point: w = (1-t)^2 * w_i + t^2 * w_{i+1}.
        // Important: this per-rope step must not mutate `other`. The client driver steps ropes
        // sequentially, so moving a neighbour here can happen after that neighbour has already
        // rebuilt velocity for this tick, which injects exactly the kind of vertical jitter seen
        // in 3-rope stacks. Within this step `other` is treated as a static collision target, so
        // only this rope's inverse mass belongs in the denominator. Keeping the neighbour's mass
        // here halves the correction and leaves tiny overlaps that never settle in 3-layer stacks.
        double wA = (1.0D - s) * (1.0D - s) * (pinned[i] ? 0.0D : 1.0D)
                + s * s * (pinned[i + 1] ? 0.0D : 1.0D);
        if (wA < 1.0e-9D) return;
        double dlambda = penetration / wA * endpointFade;
        // Parallel mode: cross-rope coupling is Jacobi rather than Gauss-Seidel because every
        // worker reads the tick-start snapshot. Pure Jacobi is famously prone to over-shoot
        // oscillation in 3-way stacks (middle rope sees both neighbours' "stale" positions and
        // double-counts the push). Under-relax to suppress that limit cycle; convergence costs
        // ~1 extra tick on settle but jitter goes away.
        if (parallelPhase()) dlambda *= ROPE_ROPE_PARALLEL_RELAX;

        double cx = nx * dlambda, cy = ny * dlambda, cz = nz * dlambda;
        applyRopeRopeCorrection(i, (1.0D - s) * (pinned[i] ? 0.0D : 1.0D),
                cx, cy, cz);
        applyRopeRopeCorrection(i + 1, s * (pinned[i + 1] ? 0.0D : 1.0D),
                cx, cy, cz);
    }

    private void applyRopeRopeCorrection(int i, double weight, double cx, double cy, double cz) {
        if (weight == 0.0D) return;
        double dx = cx * weight, dy = cy * weight, dz = cz * weight;
        // Support-node Y attenuation: a node already resting on terrain resists being pushed
        // downward (so a rope underneath a stack is not driven into the ground), but can still
        // be lifted upward (so the upper rope is the one that visibly shifts).
        if (supportNode[i] && dy < 0.0D) dy *= SUPPORT_DOWN_INV_MASS;
        x[i] += dx; y[i] += dy; z[i] += dz;
        contactNode[i] = true;
        markBoundsDirty();
    }

    protected double endpointFade(int segment, double t) {
        double ropeT = (segment + t) / segments;
        return Math.min(1.0D, Math.min(ropeT, 1.0D - ropeT) / ENDPOINT_FADE);
    }
}
