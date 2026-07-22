package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Terrain collision layer for the rope particle simulation.
 *
 * <p>
 * It pushes rope particles out of block collision shapes while preserving the
 * endpoint and length constraints handled by other layers. Keep this code
 * allocation-light; it runs frequently for every visible dynamic rope.
 */
abstract class RopeSimulationTerrainConstraints extends RopeSimulationVisualState {
    private static final double ANCHOR_FACE_DETECT_MARGIN = 0.14D;
    private static final int ANCHOR_COLLISION_SKIP_SEGMENTS = 1;

    private final SegmentBoxContact terrainSegmentBoxContact = new SegmentBoxContact();
    protected final double[] segmentEndpointCorrectionScratch = new double[2];

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
                    if (isAnchorColumnForSegment(cx, cy, cz, a, b))
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
        SegmentBoxContact contact = terrainSegmentBoxContact.compute(ax, ay, az, bx, by, bz, box);
        if (contact.distSqr >= radius * radius)
            return;
        double pushLen, nx, ny, nz;
        if (contact.distSqr > 1.0e-12D) {
            double d = Math.sqrt(contact.distSqr);
            pushLen = radius - d;
            double inv = 1.0D / d;
            nx = contact.dx * inv;
            ny = contact.dy * inv;
            nz = contact.dz * inv;
        } else {
            // Segment closest point is on the box surface or inside; push upward as a safe
            // default.
            pushLen = radius;
            nx = 0.0D;
            ny = 1.0D;
            nz = 0.0D;
        }
        if (!SegmentEndpointCorrection.compute(
                contact.s, pinned[a], pinned[b], pushLen, segmentEndpointCorrectionScratch)) {
            return;
        }
        if (segmentEndpointCorrectionScratch[0] > 0.0D) {
            applyTerrainCorrection(a, nx * segmentEndpointCorrectionScratch[0],
                    ny * segmentEndpointCorrectionScratch[0], nz * segmentEndpointCorrectionScratch[0]);
        }
        if (segmentEndpointCorrectionScratch[1] > 0.0D) {
            applyTerrainCorrection(b, nx * segmentEndpointCorrectionScratch[1],
                    ny * segmentEndpointCorrectionScratch[1], nz * segmentEndpointCorrectionScratch[1]);
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
                        if (isAnchorColumnForNode(bx, by, bz, node))
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
        if (wallRopeNormalValid) {
            double wallDelta = wallEscapeDelta(px, pz, box);
            if (Math.abs(wallDelta) > 1.0e-7D
                    && !isInsideAnyInflatedBox(level, px + wallRopeNormalX * wallDelta, py,
                            pz + wallRopeNormalZ * wallDelta)) {
                applyTerrainCorrection(node, wallRopeNormalX * wallDelta, 0.0D, wallRopeNormalZ * wallDelta);
                return true;
            }
        }
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

    private double wallEscapeDelta(double px, double pz, AABB box) {
        if (wallRopeNormalX > 0.0D) {
            return box.maxX + collisionEps - px;
        }
        if (wallRopeNormalX < 0.0D) {
            return px - box.minX + collisionEps;
        }
        if (wallRopeNormalZ > 0.0D) {
            return box.maxZ + collisionEps - pz;
        }
        if (wallRopeNormalZ < 0.0D) {
            return pz - box.minZ + collisionEps;
        }
        return 0.0D;
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
                    if (wallRopeNormalValid && isAnchorColumn(bx, by, bz))
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
                    if (isAnchorColumnForSegment(bx, by, bz, a, b))
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
        updateWallRopeNormal();
    }

    protected void clearAnchorColumns() {
        anchorAColX = Integer.MIN_VALUE;
        anchorBColX = Integer.MIN_VALUE;
        wallRopeNormalValid = false;
    }

    private void updateWallRopeNormal() {
        wallRopeNormalValid = false;
        if (anchorAColX == Integer.MIN_VALUE || anchorBColX == Integer.MIN_VALUE) {
            return;
        }
        double dx = x[nodes - 1] - x[0];
        double dy = y[nodes - 1] - y[0];
        double dz = z[nodes - 1] - z[0];
        double horizontalSqr = dx * dx + dz * dz;
        if (dy * dy < 1.0e-4D || horizontalSqr > 0.20D * 0.20D) {
            return;
        }
        double ax = x[0] - (anchorAColX + 0.5D);
        double az = z[0] - (anchorAColZ + 0.5D);
        double bx = x[nodes - 1] - (anchorBColX + 0.5D);
        double bz = z[nodes - 1] - (anchorBColZ + 0.5D);
        double nx = Math.abs(ax) >= Math.abs(az) ? Math.copySign(1.0D, ax) : 0.0D;
        double nz = nx == 0.0D ? Math.copySign(1.0D, az) : 0.0D;
        double n2x = Math.abs(bx) >= Math.abs(bz) ? Math.copySign(1.0D, bx) : 0.0D;
        double n2z = n2x == 0.0D ? Math.copySign(1.0D, bz) : 0.0D;
        if (nx * n2x + nz * n2z < 0.5D) {
            return;
        }
        wallRopeNormalX = nx;
        wallRopeNormalZ = nz;
        wallRopeNormalValid = true;
    }

    private void detectAnchorAt(Level level, double px, double py, double pz, boolean isA) {
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        for (int cx = bx - 1; cx <= bx + 1; cx++) {
            for (int cy = by - 1; cy <= by + 1; cy++) {
                for (int cz = bz - 1; cz <= bz + 1; cz++) {
                    for (AABB box : blockCache.aabbsAt(level, cx, cy, cz)) {
                        if (isAnchorAttachmentBox(box, px, py, pz)) {
                            setAnchorColumn(cx, cy, cz, isA);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void setAnchorColumn(int bx, int by, int bz, boolean isA) {
        if (isA) {
            anchorAColX = bx;
            anchorAColY = by;
            anchorAColZ = bz;
        } else {
            anchorBColX = bx;
            anchorBColY = by;
            anchorBColZ = bz;
        }
    }

    private boolean isAnchorAttachmentBox(AABB box, double px, double py, double pz) {
        if (strictlyContains(box, px, py, pz)) {
            return true;
        }
        double margin = Math.max(ANCHOR_FACE_DETECT_MARGIN, terrainRadius + collisionEps + 0.06D);
        if (!RopeMath.containsInclusive(box.inflate(margin), px, py, pz)) {
            return false;
        }
        double dx = distanceOutside(px, box.minX, box.maxX);
        double dy = distanceOutside(py, box.minY, box.maxY);
        double dz = distanceOutside(pz, box.minZ, box.maxZ);
        double outsideSqr = dx * dx + dy * dy + dz * dz;
        return outsideSqr <= margin * margin;
    }

    private static double distanceOutside(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
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

    private boolean isAnchorColumnForNode(int bx, int by, int bz, int node) {
        if (!wallRopeNormalValid) {
            return false;
        }
        if (!isAnchorColumn(bx, by, bz)) {
            return false;
        }
        int last = nodes - 1;
        return node <= ANCHOR_COLLISION_SKIP_SEGMENTS
                || node >= last - ANCHOR_COLLISION_SKIP_SEGMENTS;
    }

    private boolean isAnchorColumnForSegment(int bx, int by, int bz, int a, int b) {
        if (!wallRopeNormalValid) {
            return false;
        }
        if (!isAnchorColumn(bx, by, bz)) {
            return false;
        }
        int last = nodes - 1;
        return a <= ANCHOR_COLLISION_SKIP_SEGMENTS
                || b >= last - ANCHOR_COLLISION_SKIP_SEGMENTS;
    }

    private static boolean strictlyContains(AABB box, double px, double py, double pz) {
        final double eps = 1.0e-4D;
        return px > box.minX + eps && px < box.maxX - eps
                && py > box.minY + eps && py < box.maxY - eps
                && pz > box.minZ + eps && pz < box.maxZ - eps;
    }
}
