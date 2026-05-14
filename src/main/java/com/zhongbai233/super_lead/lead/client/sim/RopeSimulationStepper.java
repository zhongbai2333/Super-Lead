package com.zhongbai233.super_lead.lead.client.sim;

import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationStepper extends RopeSimulationContactConstraints {
    protected RopeSimulationStepper(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    // ============================================================================================
    // Main entry: legacy single-rope wrapper + full driver call
    // ============================================================================================
    /**
     * Backwards-compatible single-rope step (no neighbours, no force fields, no
     * entities).
     */
    public boolean stepUpTo(Level level, Vec3 a, Vec3 b, long currentTick) {
        return step(level, a, b, currentTick, List.of(), List.of(), List.of());
    }

    /**
     * Main-thread preparation for an upcoming parallel {@link #step}. Reads
     * {@code level},
     * pre-fills {@link #blockCache} for the rope's bbox, refreshes own bounds and
     * segment
     * AABBs, and snapshots {@code terrainNearby} / {@code blockHashChanged}. Once
     * every sim
     * scheduled for this tick has been prepared, the driver flips
     * {@link #beginParallelPhase()} and submits {@code step} calls to a worker
     * pool. Workers
     * never touch {@code level} thereafter.
     */
    public void preparePhysicsParallel(Level level, Vec3 a, Vec3 b, long currentTick) {
        // 1. Refresh self bounds + segment AABBs (workers read these on neighbours,
        // write on self).
        updateBounds();
        refreshSegmentAabbs();
        // 1b. Take a complete tick-start snapshot of node positions so neighbour
        // workers see a
        // consistent view that does not change while we mutate our own x[]/y[]/z[] in
        // step.
        if (snapX == null || snapX.length < nodes) {
            snapX = new double[nodes];
            snapY = new double[nodes];
            snapZ = new double[nodes];
        }
        System.arraycopy(x, 0, snapX, 0, nodes);
        System.arraycopy(y, 0, snapY, 0, nodes);
        System.arraycopy(z, 0, snapZ, 0, nodes);
        // 2. terrainNearby: side-effect resets blockCache and fills it for the
        // proximity bbox.
        cachedTerrainNearby = hasTerrainNearby(level, a, b);
        // 3. Snapshot block-hash diff for this tick (mutates lastBlockHash exactly
        // once).
        cachedBlockChanged = cachedTerrainNearby && !blockHashUnchanged(level, currentTick);
        // 4. Re-prefetch with a generous margin covering every level-touching query
        // inside step
        // (segment-vs-terrain capsule, node MTV, sweep test). Without this the cache
        // only
        // holds the proximity-only bbox from hasTerrainNearby which is too small.
        if (cachedTerrainNearby) {
            double r = TERRAIN_RADIUS + COLLISION_EPS + TERRAIN_PROXIMITY_MARGIN + 1.0D;
            int bx0 = (int) Math.floor(Math.min(Math.min(minX, a.x), b.x) - r) - 1;
            int by0 = (int) Math.floor(Math.min(Math.min(minY, a.y), b.y) - r) - 1;
            int bz0 = (int) Math.floor(Math.min(Math.min(minZ, a.z), b.z) - r) - 1;
            int bx1 = (int) Math.floor(Math.max(Math.max(maxX, a.x), b.x) + r) + 1;
            int by1 = (int) Math.floor(Math.max(Math.max(maxY, a.y), b.y) + r) + 1;
            int bz1 = (int) Math.floor(Math.max(Math.max(maxZ, a.z), b.z) + r) + 1;
            blockCache.prefetch(level, bx0, by0, bz0, bx1, by1, bz1);
        }
        precomputeReady = true;
    }

    /**
     * Advance one logical tick. Driver supplies neighbours (already pre-filtered by
     * bounds),
     * external force fields, and a list of nearby entity bounding boxes that push
     * the rope.
     */
    public boolean step(
            Level level, Vec3 a, Vec3 b, long currentTick,
            List<RopeSimulation> neighbors,
            List<RopeForceField> forceFields,
            List<AABB> entityBoxes) {
        lastTouchTick = currentTick;

        if (lastSteppedTick == UNINIT) {
            lastSteppedTick = currentTick - 1;
        }
        long delta = currentTick - lastSteppedTick;
        if (delta <= 0) {
            finishPrecompute();
            return false;
        }
        if (delta > 2)
            delta = 2;

        boolean endpointMoved = rememberEndpointsMoved(a, b);
        boolean terrainNearby;
        boolean blockHashChangedNow;
        if (precomputeReady) {
            terrainNearby = cachedTerrainNearby;
            blockHashChangedNow = cachedBlockChanged;
            // Workers must not call level.getBlockState; the main-thread prefetch covered
            // the bbox.
            blockCache.setReadOnly(true);
        } else {
            terrainNearby = hasTerrainNearby(level, a, b);
            blockHashChangedNow = terrainNearby && !blockHashUnchanged(level, currentTick);
        }
        boolean terrainStateChanged = terrainNearby != terrainNearbyLast;
        terrainNearbyLast = terrainNearby;
        boolean blockChanged = terrainStateChanged || (terrainNearby && blockHashChangedNow);
        boolean neighborAwake = anyNeighborAwake(neighbors);
        // Entity overlap forces a wake-up: a frozen airborne rope would otherwise let
        // the player
        // walk through it because no constraint loop runs at all.
        boolean entityNearby = !entityBoxes.isEmpty();
        boolean serverPullActive = hasFreshServerNodes(currentTick);
        boolean forceActive = !forceFields.isEmpty();
        boolean awake = endpointMoved || blockChanged || neighborAwake || entityNearby || forceActive || !isSettled()
                || hasExternalContact(currentTick) || serverPullActive;
        if (endpointMoved || blockChanged || neighborAwake || entityNearby || hasExternalContact(currentTick)
                || serverPullActive || forceActive) {
            settledTicks = 0;
        }

        // Snapshot tick-start positions for render lerp + settle measurement.
        for (int i = 0; i < nodes; i++) {
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
        }

        if (!awake) {
            for (int i = 0; i < nodes; i++) {
                vx[i] = vy[i] = vz[i] = 0.0D;
            }
            lastSteppedTick = currentTick;
            renderStable = true;
            finishPrecompute();
            return false;
        }

        for (int t = 0; t < delta; t++) {
            applyExternalContactPush(currentTick);
            applyServerNodeBlend(a, b, currentTick);
            int substeps = chooseSubsteps(a, b);
            double h = 1.0D / substeps;
            for (int sub = 0; sub < substeps; sub++) {
                double frac = (sub + 1) / (double) substeps;
                Vec3 aInterp = new Vec3(
                        lastAx + (a.x - lastAx) * frac,
                        lastAy + (a.y - lastAy) * frac,
                        lastAz + (a.z - lastAz) * frac);
                Vec3 bInterp = new Vec3(
                        lastBx + (b.x - lastBx) * frac,
                        lastBy + (b.y - lastBy) * frac,
                        lastBz + (b.z - lastBz) * frac);
                substep(level, aInterp, bInterp, h, currentTick, terrainNearby,
                        neighbors, forceFields, entityBoxes);
            }
        }

        updateSettleState();
        lastSteppedTick = currentTick;
        renderStable = false;
        markBoundsDirty();
        finishPrecompute();
        return true;
    }

    private void finishPrecompute() {
        if (precomputeReady) {
            blockCache.setReadOnly(false);
            precomputeReady = false;
        }
    }

    // ============================================================================================
    // Substep
    // ============================================================================================
    private void substep(
            Level level, Vec3 a, Vec3 b, double h, long tick, boolean terrainEnabled,
            List<RopeSimulation> neighbors,
            List<RopeForceField> forceFields,
            List<AABB> entityBoxes) {
        if (terrainEnabled) {
            // In parallel mode the main-thread prefetch covers the whole step's bbox;
            // clearing
            // through the (still warm) cache.
            if (!precomputeReady)
                blockCache.reset();
            detectAnchorBlocks(level);
        } else {
            clearAnchorColumns();
        }
        clearContactState();

        double dampingPerSubstep = Math.pow(tuning.damping(), h);
        for (int i = 0; i < nodes; i++) {
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
            if (pinned[i])
                continue;
            vx[i] *= dampingPerSubstep;
            vy[i] *= dampingPerSubstep;
            vz[i] *= dampingPerSubstep;
            vy[i] += tuning.gravity() * h;
            if (!forceFields.isEmpty()) {
                forceScratch[0] = forceScratch[1] = forceScratch[2] = 0.0D;
                for (int k = 0; k < forceFields.size(); k++) {
                    forceFields.get(k).sample(x[i], y[i], z[i], tick, forceScratch);
                }
                vx[i] += forceScratch[0] * h;
                vy[i] += forceScratch[1] * h;
                vz[i] += forceScratch[2] * h;
            }
            x[i] += vx[i] * h;
            y[i] += vy[i] * h;
            z[i] += vz[i] * h;
        }
        pinEndpoints(a, b);

        // 2. Reset XPBD lambdas at substep start
        for (int i = 0; i < segments; i++)
            lambdaDistance[i] = 0.0D;

        // 3. Unified constraint loop
        double targetLen = a.distanceTo(b) * slackFactor(a, b) / segments;
        double alphaTilde = tuning.compliance() / (h * h);
        int iterations;
        if (terrainEnabled || !entityBoxes.isEmpty() || !forceFields.isEmpty()) {
            iterations = tuning.iterContact();
        } else if (!neighbors.isEmpty()) {
            iterations = tuning.iterRope();
        } else {
            iterations = tuning.iterAir();
        }
        // Each Gauss-Seidel pass over the distance constraints only propagates the
        // correction one segment along the chain, so a long air-hung rope with too
        // few iterations stays visibly stretched (gravity perturbation in the
        // middle segments never reaches a pinned end). Ensure we run at least
        // ceil(segments/2) passes so corrections from both ends meet in the middle.
        int minPasses = (segments + 1) / 2;
        if (iterations < minPasses)
            iterations = minPasses;
        for (int it = 0; it < iterations; it++) {
            solveDistanceConstraints(targetLen, alphaTilde, (it & 1) == 0);
            if (terrainEnabled)
                solveTerrainConstraints(level);
            if (!neighbors.isEmpty())
                solveRopeRopeConstraints(neighbors);
            if (!entityBoxes.isEmpty())
                solveEntityConstraints(entityBoxes);
            pinEndpoints(a, b);
        }

        if (!terrainEnabled && entityBoxes.isEmpty() && !hasExternalContact(tick)) {
            solveDistanceConstraints(targetLen, 0.0D, true);
            solveDistanceConstraints(targetLen, 0.0D, false);
            pinEndpoints(a, b);
        }

        // 4. Reconstruct velocity from position delta
        for (int i = 0; i < nodes; i++) {
            if (pinned[i])
                continue;
            vx[i] = (x[i] - xPrev[i]) / h;
            vy[i] = (y[i] - yPrev[i]) / h;
            vz[i] = (z[i] - zPrev[i]) / h;
            // Light contact damping: bleed off velocity along contact normal-ish at rest.
            if (contactNode[i]) {
                vx[i] *= 0.5D;
                vy[i] *= 0.5D;
                vz[i] *= 0.5D;
            }
        }
    }

    private int chooseSubsteps(Vec3 a, Vec3 b) {
        if (!endpointInit)
            return 1;
        double daX = a.x - lastAx, daY = a.y - lastAy, daZ = a.z - lastAz;
        double dbX = b.x - lastBx, dbY = b.y - lastBy, dbZ = b.z - lastBz;
        double aSpeedSqr = daX * daX + daY * daY + daZ * daZ;
        double bSpeedSqr = dbX * dbX + dbY * dbY + dbZ * dbZ;
        double s2 = Math.max(Math.max(aSpeedSqr, bSpeedSqr), maxInteriorSpeedSqr());
        if (s2 < SUBSTEP_SPEED_TIER1 * SUBSTEP_SPEED_TIER1)
            return 1;
        if (s2 < SUBSTEP_SPEED_TIER2 * SUBSTEP_SPEED_TIER2)
            return 2;
        if (s2 < SUBSTEP_SPEED_TIER3 * SUBSTEP_SPEED_TIER3)
            return 3;
        return MAX_SUBSTEPS;
    }

    private double maxInteriorSpeedSqr() {
        double max = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double s2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (s2 > max)
                max = s2;
        }
        return max;
    }
}
