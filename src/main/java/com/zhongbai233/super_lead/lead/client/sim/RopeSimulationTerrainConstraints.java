package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationTerrainConstraints extends RopeSimulationVisualState {
    protected RopeSimulationTerrainConstraints(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    // ============================================================================================
    // Constraint: terrain (block AABBs)
    // ============================================================================================
    protected boolean hasTerrainNearby(Level level, Vec3 a, Vec3 b) {
        updateBounds();
        blockCache.reset();
        double r = terrainRadius + collisionEps + terrainProximityMargin;
        double qMinX = Math.min(Math.min(minX, a.x), b.x) - r;
        double qMinY = Math.min(Math.min(minY, a.y), b.y) - r;
        double qMinZ = Math.min(Math.min(minZ, a.z), b.z) - r;
        double qMaxX = Math.max(Math.max(maxX, a.x), b.x) + r;
        double qMaxY = Math.max(Math.max(maxY, a.y), b.y) + r;
        double qMaxZ = Math.max(Math.max(maxZ, a.z), b.z) + r;
        int bx0 = (int) Math.floor(qMinX);
        int bx1 = (int) Math.floor(qMaxX);
        int by0 = (int) Math.floor(qMinY);
        int by1 = (int) Math.floor(qMaxY);
        int bz0 = (int) Math.floor(qMinZ);
        int bz1 = (int) Math.floor(qMaxZ);
        for (int by = by0; by <= by1; by++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int bx = bx0; bx <= bx1; bx++) {
                    if (blockCache.aabbsAt(level, bx, by, bz).length > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected void solveTerrainConstraints(Level level) {
        for (int i = 1; i < nodes - 1; i++) {
            resolveNodeAgainstTerrain(level, i);
        }
        // a block (e.g. one on top, one beside), the straight line connecting them can
        // still cut
        // through the block's edge. Sampling the segment as a capsule and pushing it
        // out fixes the
        // visual "rope clipping into the block" without re-introducing the old corner
        // jitter,
        // because the same spherical normal as the node pass is used.
        for (int i = 0; i < segments; i++) {
            resolveSegmentAgainstTerrain(level, i, i + 1);
        }
        // Tunneling protection: run a true segment sweep when an endpoint moved fast
        // enough to
        // potentially skip past a whole block in a single substep.
        final double tunnelThresholdSqr = tuning.tunnelThresholdSqr();
        for (int i = 0; i < segments; i++) {
            int a = i, b = i + 1;
            double dax = x[a] - xPrev[a], day = y[a] - yPrev[a], daz = z[a] - zPrev[a];
            double dbx = x[b] - xPrev[b], dby = y[b] - yPrev[b], dbz = z[b] - zPrev[b];
            double maxMoveSqr = Math.max(
                    dax * dax + day * day + daz * daz,
                    dbx * dbx + dby * dby + dbz * dbz);
            if (maxMoveSqr < tunnelThresholdSqr)
                continue;
            resolveSegmentSweep(level, a, b);
        }
    }

    /**
     * Treat segment [a,b] as a capsule of radius ROPE_RADIUS and push it out of any
     * block AABB
     * it currently overlaps. The push direction comes from the
     * closest-point-on-AABB normal,
     * identical to the node pass, so corner contacts use a smooth diagonal normal.
     */
    private void resolveSegmentAgainstTerrain(Level level, int a, int b) {
        if (pinned[a] && pinned[b])
            return;
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        double r = terrainRadius + collisionEps;
        int bxMin = (int) Math.floor(Math.min(ax, bx) - r) - 1;
        int bxMax = (int) Math.floor(Math.max(ax, bx) + r) + 1;
        int byMin = (int) Math.floor(Math.min(ay, by) - r) - 1;
        int byMax = (int) Math.floor(Math.max(ay, by) + r) + 1;
        int bzMin = (int) Math.floor(Math.min(az, bz) - r) - 1;
        int bzMax = (int) Math.floor(Math.max(az, bz) + r) + 1;
        for (int cx = bxMin; cx <= bxMax; cx++) {
            for (int cy = byMin; cy <= byMax; cy++) {
                for (int cz = bzMin; cz <= bzMax; cz++) {
                    if (isAnchorColumn(cx, cy, cz))
                        continue;
                    for (AABB box : blockCache.aabbsAt(level, cx, cy, cz)) {
                        pushSegmentOutOfBox(a, b, box, terrainRadius + collisionEps);
                    }
                }
            }
        }
    }

    /**
     * Find the point on segment [a,b] closest to {@code box}; if that point is
     * within the
     * capsule radius of the box, push both endpoints (weighted by 1-s and s) along
     * the contact
     * normal so the whole capsule slides out.
     */
    private void pushSegmentOutOfBox(int a, int b, AABB box, double radius) {
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        // Iterative closest-point: alternate (clamp on segment) and (clamp on AABB) a
        // few times.
        // Converges very fast for axis-aligned boxes; 4 iterations is overkill but
        // cheap.
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double segLenSqr = ux * ux + uy * uy + uz * uz;
        double s;
        double cpx, cpy, cpz; // closest point on box
        double spx, spy, spz; // closest point on segment
        if (segLenSqr < 1.0e-12D) {
            s = 0.0D;
            spx = ax;
            spy = ay;
            spz = az;
        } else {
            // initial s = projection of box centre on segment
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
        double dx = spx - cpx, dy = spy - cpy, dz = spz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= radius * radius)
            return;
        double pushLen, nx, ny, nz;
        if (d2 > 1.0e-12D) {
            double d = Math.sqrt(d2);
            pushLen = radius - d;
            double inv = 1.0D / d;
            nx = dx * inv;
            ny = dy * inv;
            nz = dz * inv;
        } else {
            // Segment closest point is on the box surface or inside; push upward as a safe
            // default.
            pushLen = radius;
            nx = 0.0D;
            ny = 1.0D;
            nz = 0.0D;
        }
        // Distribute correction. For a capsule the contact authority at parameter s is
        // split
        // (1-s) to endpoint a and s to endpoint b. We scale by 1/(w_a*(1-s)^2 +
        // w_b*s^2) so the
        // closest point actually moves by pushLen, matching the node pass behaviour.
        double wa = pinned[a] ? 0.0D : 1.0D;
        double wb = pinned[b] ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - s;
        double denom = wa * oneMinusS * oneMinusS + wb * s * s;
        if (denom < 1.0e-9D)
            return;
        double k = pushLen / denom;
        // Per-endpoint magnitude cap. The geometric scaling above is correct, but when
        // one end
        // is pinned and the contact sits near that end (small effective lever arm), the
        // free
        // followed by the distance constraint yanking the rope back. Capping the
        // per-step move
        // to 2x pushLen sacrifices nothing physical (the next iteration finishes the
        // resolve)
        // and kills the snap cleanly.
        double maxStep = 2.0D * pushLen;
        if (wa > 0.0D) {
            double ka = k * wa * oneMinusS;
            if (ka > maxStep)
                ka = maxStep;
            applyTerrainCorrection(a, nx * ka, ny * ka, nz * ka);
        }
        if (wb > 0.0D) {
            double kb = k * wb * s;
            if (kb > maxStep)
                kb = maxStep;
            applyTerrainCorrection(b, nx * kb, ny * kb, nz * kb);
        }
    }

    private void resolveNodeAgainstTerrain(Level level, int node) {
        // Up to 2 passes: a node pushed out of one box may end up in an adjacent
        // inflated box.
        for (int pass = 0; pass < 2; pass++) {
            boolean moved = false;
            int bxMin = (int) Math.floor(x[node] - terrainRadius) - 1;
            int bxMax = (int) Math.floor(x[node] + terrainRadius) + 1;
            int byMin = (int) Math.floor(y[node] - terrainRadius) - 1;
            int byMax = (int) Math.floor(y[node] + terrainRadius) + 1;
            int bzMin = (int) Math.floor(z[node] - terrainRadius) - 1;
            int bzMax = (int) Math.floor(z[node] + terrainRadius) + 1;
            for (int bx = bxMin; bx <= bxMax; bx++) {
                for (int by = byMin; by <= byMax; by++) {
                    for (int bz = bzMin; bz <= bzMax; bz++) {
                        AABB[] boxes = blockCache.aabbsAt(level, bx, by, bz);
                        if (isAnchorColumn(bx, by, bz))
                            continue;
                        for (AABB box : boxes) {
                            if (projectNodeOutOfBox(level, node, box))
                                moved = true;
                        }
                    }
                }
            }
            if (!moved)
                return;
        }
    }

    private boolean projectNodeOutOfBox(Level level, int node, AABB original) {
        // Treat the rope node as a sphere of radius ROPE_RADIUS and the block as the
        // original
        // (un-inflated) AABB. The contact normal is the unit vector from the closest
        // point on the
        // AABB to the node. Faces give axis-aligned normals; edges and corners give the
        // proper
        // diagonal normal, so a rope draped over a block edge wraps smoothly instead of
        // having
        // each node snap to a different face (which used to produce visible kinks at
        // corners).
        double px = x[node], py = y[node], pz = z[node];
        double cpx = px < original.minX ? original.minX : (px > original.maxX ? original.maxX : px);
        double cpy = py < original.minY ? original.minY : (py > original.maxY ? original.maxY : py);
        double cpz = pz < original.minZ ? original.minZ : (pz > original.maxZ ? original.maxZ : pz);
        double dx = px - cpx, dy = py - cpy, dz = pz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;
        double radius = terrainRadius + collisionEps;
        if (d2 > 1.0e-12D) {
            if (d2 >= radius * radius)
                return false;
            double d = Math.sqrt(d2);
            double pushLen = radius - d;
            double inv = 1.0D / d;
            applyTerrainCorrection(node, dx * inv * pushLen, dy * inv * pushLen, dz * inv * pushLen);
            return true;
        }
        // Strictly inside the block volume (e.g. tunnelled in). Escape via the smallest
        // axis of
        // the inflated box, falling back to an upward push if every direction lands
        // inside another
        // block.
        AABB box = original.inflate(terrainRadius);
        double dxMin = box.minX - collisionEps - px;
        double dxMax = box.maxX + collisionEps - px;
        double dyMin = box.minY - collisionEps - py;
        double dyMax = box.maxY + collisionEps - py;
        double dzMin = box.minZ - collisionEps - pz;
        double dzMax = box.maxZ + collisionEps - pz;
        double[] candDelta = { dxMin, dxMax, dyMin, dyMax, dzMin, dzMax };
        int[] candAxis = { 0, 0, 1, 1, 2, 2 };
        sortBySmallestAbs(candDelta, candAxis);
        for (int k = 0; k < 6; k++) {
            double delta = candDelta[k];
            int axis = candAxis[k];
            double tx = px, ty = py, tz = pz;
            if (axis == 0)
                tx += delta;
            else if (axis == 1)
                ty += delta;
            else
                tz += delta;
            if (!isInsideAnyInflatedBox(level, tx, ty, tz)) {
                if (axis == 0)
                    applyTerrainCorrection(node, delta, 0.0D, 0.0D);
                else if (axis == 1)
                    applyTerrainCorrection(node, 0.0D, delta, 0.0D);
                else
                    applyTerrainCorrection(node, 0.0D, 0.0D, delta);
                return true;
            }
        }
        applyTerrainCorrection(node, 0.0D, collisionEps + box.maxY - py, 0.0D);
        return true;
    }

    private static void sortBySmallestAbs(double[] vals, int[] axes) {
        for (int i = 0; i < 5; i++) {
            int min = i;
            for (int j = i + 1; j < 6; j++) {
                if (Math.abs(vals[j]) < Math.abs(vals[min]))
                    min = j;
            }
            if (min != i) {
                double tv = vals[i];
                vals[i] = vals[min];
                vals[min] = tv;
                int ta = axes[i];
                axes[i] = axes[min];
                axes[min] = ta;
            }
        }
    }

    private boolean isInsideAnyInflatedBox(Level level, double wx, double wy, double wz) {
        int bxMin = (int) Math.floor(wx - terrainRadius) - 1;
        int bxMax = (int) Math.floor(wx + terrainRadius) + 1;
        int byMin = (int) Math.floor(wy - terrainRadius) - 1;
        int byMax = (int) Math.floor(wy + terrainRadius) + 1;
        int bzMin = (int) Math.floor(wz - terrainRadius) - 1;
        int bzMax = (int) Math.floor(wz + terrainRadius) + 1;
        for (int bx = bxMin; bx <= bxMax; bx++) {
            for (int by = byMin; by <= byMax; by++) {
                for (int bz = bzMin; bz <= bzMax; bz++) {
                    if (isAnchorColumn(bx, by, bz))
                        continue;
                    for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
                        if (RopeMath.containsInclusive(box.inflate(terrainRadius), wx, wy, wz))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private void resolveSegmentSweep(Level level, int a, int b) {
        double fx = x[a], fy = y[a], fz = z[a];
        double tx = x[b], ty = y[b], tz = z[b];
        double r = terrainRadius + collisionEps;
        int bxMin = (int) Math.floor(Math.min(fx, tx) - r) - 1;
        int bxMax = (int) Math.floor(Math.max(fx, tx) + r) + 1;
        int byMin = (int) Math.floor(Math.min(fy, ty) - r) - 1;
        int byMax = (int) Math.floor(Math.max(fy, ty) + r) + 1;
        int bzMin = (int) Math.floor(Math.min(fz, tz) - r) - 1;
        int bzMax = (int) Math.floor(Math.max(fz, tz) + r) + 1;
        double bestT = Double.POSITIVE_INFINITY;
        double bestDx = 0, bestDy = 0, bestDz = 0;
        for (int bx = bxMin; bx <= bxMax; bx++) {
            for (int by = byMin; by <= byMax; by++) {
                for (int bz = bzMin; bz <= bzMax; bz++) {
                    if (isAnchorColumn(bx, by, bz))
                        continue;
                    for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
                        AABB inflated = box.inflate(terrainRadius);
                        if (RopeMath.intersectSegmentAabb(fx, fy, fz, tx, ty, tz, inflated,
                                segmentCornerPushEps, segmentTopSupportEps, hitScratch)) {
                            if (hitScratch.t < bestT) {
                                bestT = hitScratch.t;
                                bestDx = hitScratch.dx;
                                bestDy = hitScratch.dy;
                                bestDz = hitScratch.dz;
                            }
                        }
                    }
                }
            }
        }
        if (bestT == Double.POSITIVE_INFINITY)
            return;
        // Push by MTV without amplification. The previous (1/w) scale up to 3x was
        // meant to
        // fully resolve a contact in one shot when one endpoint was pinned, but it
        // overshoots
        // and fights the distance constraint, producing visible single-rope corner
        // jitter.
        // The 8 constraint iterations + sweep re-running each iteration converge fine
        // without
        // the amplification.
        if (!pinned[a]) {
            applyTerrainCorrection(a, bestDx, bestDy, bestDz);
        }
        if (!pinned[b]) {
            applyTerrainCorrection(b, bestDx, bestDy, bestDz);
        }
    }

    protected void detectAnchorBlocks(Level level) {
        clearAnchorColumns();
        int last = nodes - 1;
        detectAnchorAt(level, x[0], y[0], z[0], true);
        detectAnchorAt(level, x[last], y[last], z[last], false);
    }

    protected void clearAnchorColumns() {
        anchorAColX = Integer.MIN_VALUE;
        anchorBColX = Integer.MIN_VALUE;
    }

    private void detectAnchorAt(Level level, double px, double py, double pz, boolean isA) {
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
            if (strictlyContains(box, px, py, pz)) {
                if (isA) {
                    anchorAColX = bx;
                    anchorAColY = by;
                    anchorAColZ = bz;
                } else {
                    anchorBColX = bx;
                    anchorBColY = by;
                    anchorBColZ = bz;
                }
                return;
            }
        }
    }

    private boolean isAnchorColumn(int bx, int by, int bz) {
        if (anchorAColX != Integer.MIN_VALUE
                && bx == anchorAColX && bz == anchorAColZ
                && (by == anchorAColY || by == anchorAColY - 1))
            return true;
        return anchorBColX != Integer.MIN_VALUE
                && bx == anchorBColX && bz == anchorBColZ
                && (by == anchorBColY || by == anchorBColY - 1);
    }

    private static boolean strictlyContains(AABB box, double px, double py, double pz) {
        final double eps = 1.0e-4D;
        return px > box.minX + eps && px < box.maxX - eps
                && py > box.minY + eps && py < box.maxY - eps
                && pz > box.minZ + eps && pz < box.maxZ - eps;
    }
}
