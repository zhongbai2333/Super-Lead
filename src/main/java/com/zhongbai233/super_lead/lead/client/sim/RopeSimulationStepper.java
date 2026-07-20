package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Verlet-style integration and constraint stepping for dynamic rope particles.
 *
 * <p>
 * This layer owns time-step order: integrate, apply length/endpoint locks,
 * resolve terrain/entity contacts, then refresh render caches. Keep gameplay
 * side effects outside this class; it should only mutate simulation state.
 */
abstract class RopeSimulationStepper extends RopeSimulationContactConstraints {
    private static final int MAX_CROWDED_SUBSTEPS = 2;
    private static final int MAX_CROWDED_SOLVER_ITERATIONS = 12;
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
            double r = terrainRadius + collisionEps + terrainProximityMargin + 1.0D;
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
     * external force fields, and a list of nearby entity body snapshots that push
     * the rope.
     */
    public boolean step(
            Level level, Vec3 a, Vec3 b, long currentTick,
            List<RopeSimulation> neighbors,
            List<RopeForceField> forceFields,
            List<RopeEntityContact> entityContacts) {
        lastTouchTick = currentTick;

        if (lastSteppedTick == UNINIT) {
            lastSteppedTick = currentTick - 1;
        }
        long delta = currentTick - lastSteppedTick;
        if (delta <= 0) {
            finishPrecompute();
            return false;
        }
        if (delta > tuning.maxTickDelta())
            delta = tuning.maxTickDelta();

        // Preserve the previous stepped endpoints before rememberEndpointsMoved()
        // publishes the current ones. Substep selection and endpoint interpolation
        // both need the old values; reading lastA/lastB afterwards silently reports
        // zero endpoint speed and collapses every interpolation onto the new endpoint.
        boolean hadEndpointHistory = endpointInit;
        double previousAx = hadEndpointHistory ? lastAx : a.x;
        double previousAy = hadEndpointHistory ? lastAy : a.y;
        double previousAz = hadEndpointHistory ? lastAz : a.z;
        double previousBx = hadEndpointHistory ? lastBx : b.x;
        double previousBy = hadEndpointHistory ? lastBy : b.y;
        double previousBz = hadEndpointHistory ? lastBz : b.z;
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
        boolean ropeStackContact = !neighbors.isEmpty();
        if (ropeStackContact && delta > 1L) {
            // Catch-up multiplies every rope-rope pair solve. In a dense stack it is
            // safer to drop stale simulation time than to let one delayed frame create
            // an even larger catch-up frame and enter a positive feedback loop.
            delta = 1L;
        }
        boolean neighborAwake = anyNeighborAwake(neighbors);
        // Entity overlap normally wakes the rope so bodies can visually push it. Fully
        // taut ropes have no useful travel budget, so ignore this cosmetic push path at
        // slack=0 instead of waking a few trapped joints every tick.
        boolean entityPushActive = visualPushEnabled() && !entityContacts.isEmpty();
        boolean forceActive = !forceFields.isEmpty();
        boolean windActive = windActive(a, b, currentTick);
        if (windActive) {
            lastWindActiveTick = currentTick;
        }
        boolean awake = endpointMoved || blockChanged || neighborAwake || entityPushActive || forceActive
                || windActive
                || !isSettled()
                || hasExternalContact(currentTick);
        if (endpointMoved || blockChanged || (neighborAwake && ropeStackQuietTicks < settleThresholdTicks)
            || entityPushActive || hasExternalContact(currentTick)
                || forceActive || windActive) {
            settledTicks = 0;
        }

        if (!awake) {
            snapshotRenderOrigin();
            for (int i = 0; i < nodes; i++) {
                vx[i] = vy[i] = vz[i] = 0.0D;
            }
            lastSteppedTick = currentTick;
            renderStable = true;
            finishPrecompute();
            return false;
        }

        for (int t = 0; t < delta; t++) {
            if (isFinalCatchUpTick(t, delta)) {
                // Render interpolation must cover only the final logical tick. When a
                // low-FPS frame catches up four ticks, snapshotting before the whole
                // loop makes partialTick interpolate tick 0 -> tick 4 and visibly lag
                // before snapping forward. The final-tick origin produces the same
                // tick 3 -> tick 4 interpolation used during normal 20 TPS rendering.
                // Settle measurement also becomes independent of catch-up length.
                snapshotRenderOrigin();
            }
            long simulationTick = catchUpSimulationTick(currentTick, delta, t);
            prepareWindCache(simulationTick);
            applyExternalContactPush(simulationTick);
            int substeps = chooseSubsteps(a, b,
                    previousAx, previousAy, previousAz,
                    previousBx, previousBy, previousBz,
                    delta, hadEndpointHistory);
            if (ropeStackContact) {
                substeps = Math.min(substeps, MAX_CROWDED_SUBSTEPS);
            }
            double h = 1.0D / substeps;
            for (int sub = 0; sub < substeps; sub++) {
                // Spread one endpoint move over the entire catch-up interval. The old
                // formula restarted at 1/substeps for every logical tick, so a 4-tick
                // catch-up moved old->new four times and teleported the pinned ends
                // backwards between iterations. Constraint velocity reconstruction
                // turned those teleports into the violent whipping most visible when
                // low gravity cannot damp the injected transverse energy.
                double frac = logicalSubstepFraction(t, sub, substeps, delta);
                Vec3 aInterp = new Vec3(
                        previousAx + (a.x - previousAx) * frac,
                        previousAy + (a.y - previousAy) * frac,
                        previousAz + (a.z - previousAz) * frac);
                Vec3 bInterp = new Vec3(
                        previousBx + (b.x - previousBx) * frac,
                        previousBy + (b.y - previousBy) * frac,
                        previousBz + (b.z - previousBz) * frac);
                substep(level, aInterp, bInterp, h, simulationTick, terrainNearby,
                        neighbors, forceFields, entityPushActive ? entityContacts : List.of());
            }
        }

        updateSettleState(ropeStackContact);
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

    private void snapshotRenderOrigin() {
        for (int i = 0; i < nodes; i++) {
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
        }
    }

    // ============================================================================================
    // Substep
    // ============================================================================================
    private void substep(
            Level level, Vec3 a, Vec3 b, double h, long tick, boolean terrainEnabled,
            List<RopeSimulation> neighbors,
            List<RopeForceField> forceFields,
            List<RopeEntityContact> entityContacts) {
        if (terrainEnabled) {
            // hasTerrainNearby() resets the per-step cache before the outer solve.
            // Keep it warm across all substeps and constraint iterations; misses still
            // load newly reached BlockPos values, while repeated terrain queries avoid
            // rebuilding the same VoxelShape AABBs.
            detectAnchorBlocks(level);
        } else {
            clearAnchorColumns();
        }
        clearContactState();

        double dampingPerSubstep = Math.pow(tuning.damping(), h);
        double tautWeight = RopeSagModel.tautProjectionWeight(tuning.slack());
        double gravityScale = 1.0D - tautWeight;
        for (int i = 0; i < nodes; i++) {
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
            if (pinned[i])
                continue;
            vx[i] *= dampingPerSubstep;
            vy[i] *= dampingPerSubstep;
            vz[i] *= dampingPerSubstep;
            vy[i] += tuning.gravity() * h * gravityScale;
            applyWind(i, tick, h, gravityScale);
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
        double targetLen = RopeSagModel.physicsTargetLength(a, b, tuning.slack(), tuning.gravity()) / segments;
        double alphaTilde = tuning.compliance() / (h * h);
        int iterations;
        if (terrainEnabled || !entityContacts.isEmpty() || !forceFields.isEmpty()) {
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
        int minPasses = Math.max((segments + 1) / 2,
                (int) Math.ceil(segments * (1.0D + tautWeight * 3.0D)));
        if (iterations < minPasses)
            iterations = minPasses;
        if (!neighbors.isEmpty()) {
            iterations = Math.min(iterations, MAX_CROWDED_SOLVER_ITERATIONS);
        }
        boolean tensionContact = hasDominantTensionContactTarget();
        boolean canEarlyExitDistanceSolve = !terrainEnabled && entityContacts.isEmpty()
                && forceFields.isEmpty() && neighbors.isEmpty() && !hasExternalContact(tick);
        double distanceConvergedError = Math.max(1.0e-5D, targetLen * 2.0e-3D);
        for (int it = 0; it < iterations; it++) {
            double maxDistanceError = solveDistanceConstraints(targetLen, alphaTilde, (it & 1) == 0);
            // Entity pushes run before terrain so blocks always win:
            // when a player pushes a rope against a wall the rope slides into the
            // player's collision box instead of clipping into the block.
            if (!tensionContact && !entityContacts.isEmpty())
                solveEntityConstraints(entityContacts);
            if (!tensionContact && terrainEnabled)
                solveTerrainConstraints(level);
            if (!neighbors.isEmpty())
                solveRopeRopeConstraints(neighbors);
            pinEndpoints(a, b);
            if (canEarlyExitDistanceSolve && it + 1 >= minPasses && maxDistanceError <= distanceConvergedError) {
                break;
            }
        }

        if (!terrainEnabled && entityContacts.isEmpty() && !hasExternalContact(tick)) {
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
                vx[i] *= tuning.contactNodeDamping();
                vy[i] *= tuning.contactNodeDamping();
                vz[i] *= tuning.contactNodeDamping();
            }
        }
        if (tautWeight > 0.0D) {
            applyTautProjection(a, b, tautWeight, true);
        }
    }

    private int chooseSubsteps(Vec3 a, Vec3 b,
            double previousAx, double previousAy, double previousAz,
            double previousBx, double previousBy, double previousBz,
            long logicalTicks, boolean hadEndpointHistory) {
        if (!hadEndpointHistory)
            return 1;
        double invTicks = endpointSpeedScale(logicalTicks);
        double daX = (a.x - previousAx) * invTicks;
        double daY = (a.y - previousAy) * invTicks;
        double daZ = (a.z - previousAz) * invTicks;
        double dbX = (b.x - previousBx) * invTicks;
        double dbY = (b.y - previousBy) * invTicks;
        double dbZ = (b.z - previousBz) * invTicks;
        double aSpeedSqr = daX * daX + daY * daY + daZ * daZ;
        double bSpeedSqr = dbX * dbX + dbY * dbY + dbZ * dbZ;
        double s2 = Math.max(Math.max(aSpeedSqr, bSpeedSqr), maxInteriorSpeedSqr());
        if (s2 < substepSpeedTier1 * substepSpeedTier1)
            return 1;
        if (s2 < substepSpeedTier2 * substepSpeedTier2)
            return 2;
        if (s2 < substepSpeedTier3 * substepSpeedTier3)
            return 3;
        return maxSubsteps;
    }

    static double endpointSpeedScale(long logicalTicks) {
        return 1.0D / Math.max(1L, logicalTicks);
    }

    static boolean isFinalCatchUpTick(int logicalTickIndex, long logicalTicks) {
        return logicalTickIndex == Math.max(1L, logicalTicks) - 1L;
    }

    static long catchUpSimulationTick(long currentTick, long logicalTicks, int logicalTickIndex) {
        long safeLogicalTicks = Math.max(1L, logicalTicks);
        long clampedIndex = Math.max(0L, Math.min(safeLogicalTicks - 1L, logicalTickIndex));
        return currentTick - safeLogicalTicks + 1L + clampedIndex;
    }

    static double logicalSubstepFraction(int logicalTick, int substep, int substeps, long logicalTicks) {
        int safeSubsteps = Math.max(1, substeps);
        long safeLogicalTicks = Math.max(1L, logicalTicks);
        double completedTicks = Math.max(0, logicalTick)
                + (Math.max(0, substep) + 1.0D) / safeSubsteps;
        return Math.min(1.0D, completedTicks / safeLogicalTicks);
    }

    private void applyWind(int nodeIndex, long tick, double h, double gravityScale) {
        if (!windEnabled()) {
            return;
        }
        WindSample wind = windAt(x[nodeIndex], z[nodeIndex], tick);
        if (wind.envelope() <= 1.0e-5D) {
            return;
        }
        double t = nodeIndex / (double) segments;
        double nodeWeight = Math.sin(Math.PI * t);
        if (nodeWeight <= 1.0e-5D) {
            return;
        }
        double force = tuning.windStrength() * wind.strengthScale() * wind.envelope() * nodeWeight * gravityScale;
        vx[nodeIndex] += wind.dirX() * force * h;
        vz[nodeIndex] += wind.dirZ() * force * h;
        // A constant positive lift on every interior node gives the rope a net
        // vertical force, so long spans rise and fall as one object when a gust
        // starts or ends. Keep vertical wind as a local shape disturbance instead:
        // this profile has zero weighted sum across the span and therefore cannot
        // levitate the rope's center of mass.
        vy[nodeIndex] += force * tuning.windVerticalLift()
                * verticalWindProfile(nodeIndex, segments) * h;
    }

    static double verticalWindProfile(int nodeIndex, int segmentCount) {
        if (segmentCount <= 1 || nodeIndex <= 0 || nodeIndex >= segmentCount) {
            return 0.0D;
        }
        double t = nodeIndex / (double) segmentCount;
        return Math.cos(Math.PI * t);
    }

    private boolean windActive(Vec3 a, Vec3 b, long tick) {
        if (!windEnabled() || RopeSagModel.tautProjectionWeight(tuning.slack()) >= 0.98D) {
            return false;
        }
        double mx = (a.x + b.x) * 0.5D;
        double mz = (a.z + b.z) * 0.5D;
        return windAt(mx, mz, tick).envelope() > 0.025D;
    }

    /**
     * Cheap scheduler-side wake check for wind. Without this, settled ropes can be
     * skipped before {@link #step} gets a chance to sample wind and clear their
     * settled state, which makes only already-awake ropes visibly sway.
     */
    public boolean hasActiveWind(Vec3 a, Vec3 b, long tick) {
        prepareWindCache(tick);
        return windActive(a, b, tick);
    }

    // ============================================================================================
    // Wind — cached per-tick cell-center sampling with bilinear interpolation
    // ============================================================================================

    private static final double WIND_CELL_SIZE = 24.0;

    /**
     * Per-step cell-center wind cache. Key = ((long)cellX << 32) | (cellZ &
     * 0xFFFFFFFFL).
     */
    private final java.util.HashMap<Long, WindSample> windCellCache = new java.util.HashMap<>(16);
    private long windCacheTick = Long.MIN_VALUE;
    /**
     * Shared constants precomputed once per step to avoid repeated trig & division.
     */
    private double windSharedDirX, windSharedDirZ, windSharedSpeed, windSharedCycleTicks;
    private boolean windSharedReady;

    /**
     * Clear the per-tick wind cache and invalidate shared constants. Called before
     * substep loop.
     */
    private void prepareWindCache(long tick) {
        if (windCacheTick != tick) {
            windCellCache.clear();
            windCacheTick = tick;
        }
        windSharedReady = false;
    }

    private void ensureWindShared() {
        if (windSharedReady)
            return;
        double baseDirRad = Math.toRadians(tuning.windDirectionDeg());
        windSharedDirX = Math.cos(baseDirRad);
        windSharedDirZ = Math.sin(baseDirRad);
        windSharedSpeed = Math.max(1.0e-5D, tuning.windSpeed());
        windSharedCycleTicks = Math.max(8.0D, tuning.windWaveLength() / windSharedSpeed);
        windSharedReady = true;
    }

    private boolean windEnabled() {
        return windPhysicsEnabled
                && tuning.windEnabled()
                && tuning.windStrength() > 0.0D
                && tuning.windWaveLength() > 1.0e-5D
                && tuning.windSpeed() > 0.0D;
    }

    /**
     * Sample wind at world position (wx, wz).
     * <p>
     * Uses bilinear interpolation of the 4 surrounding cell-center samples.
     * Cell indices use the same floor(wx/24) scheme as the original fast-path,
     * so every position maps to the correct seed-based wind cell. Cell-center
     * results are cached per tick — nodes sharing cells avoid recomputation.
     */
    private WindSample windAt(double wx, double wz, long tick) {
        ensureWindShared();

        double ccx = wx / WIND_CELL_SIZE - 0.5D;
        double ccz = wz / WIND_CELL_SIZE - 0.5D;
        int ix = (int) Math.floor(ccx);
        int iz = (int) Math.floor(ccz);
        double tx = ccx - ix;
        double tz = ccz - iz;

        WindSample s00 = windAtCellCenterCached(ix, iz, tick);
        WindSample s10 = windAtCellCenterCached(ix + 1, iz, tick);
        WindSample s01 = windAtCellCenterCached(ix, iz + 1, tick);
        WindSample s11 = windAtCellCenterCached(ix + 1, iz + 1, tick);

        return bilinearBlendWind(s00, s10, s01, s11, tx, tz);
    }

    /**
     * Return the wind sample at the center of cell ({@code cellX}, {@code cellZ}),
     * using a per-tick cache so that the expensive event computation (random
     * hashing, trig, envelope evaluation) is done at most once per cell per tick.
     */
    private WindSample windAtCellCenterCached(int cellX, int cellZ, long tick) {
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        WindSample cached = windCellCache.get(key);
        if (cached != null)
            return cached;

        long windSeed = windSeedFromCell(cellX, cellZ);
        double cx = (cellX + 0.5) * WIND_CELL_SIZE;
        double cz = (cellZ + 0.5) * WIND_CELL_SIZE;
        double windClock = tick - (cx * windSharedDirX + cz * windSharedDirZ) / windSharedSpeed
                + windSeedPhase(windSeed) * windSharedCycleTicks;
        long eventIndex = (long) Math.floor(windClock / windSharedCycleTicks);
        WindSample current = windEventAt(eventIndex, windClock, windSharedCycleTicks, windSeed);
        if (current.envelope() > 0.0D) {
            windCellCache.put(key, current);
            return current;
        }
        WindSample prev = windEventAt(eventIndex - 1L, windClock, windSharedCycleTicks, windSeed);
        windCellCache.put(key, prev);
        return prev;
    }

    /**
     * Bilinearly interpolate wind samples from 4 cell centers. Force vectors
     * (direction × envelope × strength) are interpolated, then direction and
     * strength are reconstructed from the blended force, producing smooth
     * transitions across cell boundaries.
     */
    private WindSample bilinearBlendWind(
            WindSample s00, WindSample s10, WindSample s01, WindSample s11,
            double tx, double tz) {
        double f00 = s00.envelope() * s00.strengthScale();
        double f10 = s10.envelope() * s10.strengthScale();
        double f01 = s01.envelope() * s01.strengthScale();
        double f11 = s11.envelope() * s11.strengthScale();

        double fx00 = s00.dirX() * f00, fz00 = s00.dirZ() * f00;
        double fx10 = s10.dirX() * f10, fz10 = s10.dirZ() * f10;
        double fx01 = s01.dirX() * f01, fz01 = s01.dirZ() * f01;
        double fx11 = s11.dirX() * f11, fz11 = s11.dirZ() * f11;

        double blendedFX = lerp(lerp(fx00, fx10, tx), lerp(fx01, fx11, tx), tz);
        double blendedFZ = lerp(lerp(fz00, fz10, tx), lerp(fz01, fz11, tx), tz);
        double blendedEnv = lerp(lerp(s00.envelope(), s10.envelope(), tx),
                lerp(s01.envelope(), s11.envelope(), tx), tz);

        if (blendedEnv <= 1.0e-5) {
            return new WindSample(0.0D, windSharedDirX, windSharedDirZ, 0.0D);
        }

        double forceMag = Math.sqrt(blendedFX * blendedFX + blendedFZ * blendedFZ);
        double dirX, dirZ, strength;
        if (forceMag > 1.0e-9) {
            dirX = blendedFX / forceMag;
            dirZ = blendedFZ / forceMag;
            strength = forceMag / blendedEnv;
        } else {
            dirX = s00.dirX();
            dirZ = s00.dirZ();
            strength = 0.0;
        }

        return new WindSample(blendedEnv, dirX, dirZ, Math.max(0.22, strength));
    }

    private WindSample windEventAt(long eventIndex, double windClock, double baseCycleTicks, long windSeed) {
        double duty = Math.max(0.05D, Math.min(0.95D, tuning.windDuty()));
        double pauseRoom = baseCycleTicks * (1.0D - duty);
        double startOffset = randomSigned(windSeed, eventIndex, 0x632BE59BD9B4E019L)
                * pauseRoom * Math.max(0.0D, Math.min(1.0D, tuning.windPauseJitter())) * 0.55D;
        double eventStart = eventIndex * baseCycleTicks + startOffset;

        double activeScale = jitterScale(windSeed, eventIndex, 0x41C64E6DL, tuning.windDurationJitter());
        double activeTicks = Math.max(2.0D, baseCycleTicks * duty * activeScale);
        double cyclePos = windClock - eventStart;
        if (cyclePos < 0.0D || cyclePos >= activeTicks) {
            return WindSample.NONE;
        }

        double local = cyclePos / activeTicks;
        boolean rampingGust = random01(windSeed, eventIndex, 0xD1B54A32D192ED03L) < tuning.windRampBias();
        boolean doubleSwell = random01(windSeed, eventIndex, 0xA24BAED4963EE407L) < 0.58D;
        double envelope = doubleSwell
                ? doubleSwellEnvelope(local, eventIndex, rampingGust, windSeed)
                : linearTriangleEnvelope(local, rampingGust);
        if (envelope <= 1.0e-5D) {
            return WindSample.NONE;
        }

        double strengthJitter = Math.max(0.0D, Math.min(1.0D, tuning.windStrengthJitter()));
        double strengthNoise = randomSigned(windSeed, eventIndex, 0x94D049BB133111EBL);
        double strengthScale = 1.0D + strengthNoise * strengthJitter * (strengthNoise >= 0.0D ? 1.30D : 0.75D);
        strengthScale = Math.max(0.22D, strengthScale);

        double cellDirOffset = cellDirectionOffset(windSeed);
        double directionJitter = tuning.windDirectionJitterDeg();
        double directionOffset = cellDirOffset
                + randomSigned(windSeed, eventIndex, 0xBF58476D1CE4E5B9L) * directionJitter;
        double dirRad = Math.toRadians(tuning.windDirectionDeg() + directionOffset);
        return new WindSample(envelope, Math.cos(dirRad), Math.sin(dirRad), strengthScale);
    }

    private double windSeedPhase(long seed) {
        long mixed = seed ^ (seed >>> 33) ^ 0x9E3779B97F4A7C15L;
        mixed ^= (mixed << 13);
        mixed ^= (mixed >>> 7);
        mixed ^= (mixed << 17);
        return (mixed & 0xFFFFL) / 65536.0D;
    }

    private double cellDirectionOffset(long windSeed) {
        double spread = Math.max(0.0D, tuning.windCellDirectionSpreadDeg());
        if (spread <= 1.0e-5)
            return 0.0D;
        return randomSigned(windSeed, 0x4B6F2D1AL, 0xC3A8915EL) * spread;
    }

    private long windSeedFromCell(int cellX, int cellZ) {
        long mixed = 0x6A09E667F3BCC909L;
        mixed ^= (long) cellX * 0xBF58476D1CE4E5B9L;
        mixed ^= (long) cellZ * 0x94D049BB133111EBL;
        mixed ^= ((long) Math.floor(tuning.windDirectionDeg() * 8.0D)) * 0x9E3779B97F4A7C15L;
        return mixed;
    }

    private double jitterScale(long seed, long eventIndex, long salt, double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        return Math.max(0.2D, 1.0D + randomSigned(seed, eventIndex, salt) * clamped);
    }

    private double randomSigned(long seed, long index, long salt) {
        return random01(seed, index, salt) * 2.0D - 1.0D;
    }

    /**
     * Fast deterministic hash returning a uniform double in [0, 1).
     * Uses a 2-round MurmurHash3-style finalizer — ~30% fewer operations than
     * the previous splitmix64 variant while retaining good avalanche for wind.
     */
    private static double random01(long seed, long index, long salt) {
        long h = seed ^ salt;
        h ^= index * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h = h ^ (h >>> 33);
        return (h & 0x7FFFFFFFFFFFFFFFL) * 0x1.0p-63;
    }

    private double linearTriangleEnvelope(double local, boolean rampingGust) {
        double triangle = local < 0.5D ? local * 2.0D : (1.0D - local) * 2.0D;
        triangle = smooth01(triangle);
        if (!rampingGust) {
            return Math.max(0.0D, triangle);
        }
        double ramp = 0.45D + 0.55D * smooth01(local);
        return Math.max(0.0D, triangle * ramp);
    }

    private double doubleSwellEnvelope(double local, long eventIndex, boolean rampingGust, long windSeed) {
        double firstPeakT = 0.24D + randomSigned(windSeed, eventIndex, 0xC6A4A7935BD1E995L) * 0.08D;
        double dipT = 0.50D + randomSigned(windSeed, eventIndex, 0x165667B19E3779F9L) * 0.08D;
        double secondPeakT = 0.73D + randomSigned(windSeed, eventIndex, 0x85EBCA77C2B2AE63L) * 0.08D;
        firstPeakT = clamp(firstPeakT, 0.14D, 0.36D);
        dipT = clamp(dipT, firstPeakT + 0.10D, 0.68D);
        secondPeakT = clamp(secondPeakT, dipT + 0.08D, 0.90D);

        double firstPeak = rampingGust ? 0.42D : 0.66D;
        firstPeak += random01(windSeed, eventIndex, 0x27D4EB2F165667C5L) * 0.18D;
        double dip = 0.18D + random01(windSeed, eventIndex, 0x9E3779B185EBCA87L) * 0.26D;
        double secondPeak = 0.82D + random01(windSeed, eventIndex, 0xD1B54A32D192ED03L) * 0.38D;

        if (local < firstPeakT) {
            return lerp(0.0D, firstPeak, smooth01(local / firstPeakT));
        }
        if (local < dipT) {
            return lerp(firstPeak, dip, smooth01((local - firstPeakT) / (dipT - firstPeakT)));
        }
        if (local < secondPeakT) {
            return lerp(dip, secondPeak, smooth01((local - dipT) / (secondPeakT - dipT)));
        }
        return lerp(secondPeak, 0.0D, smooth01((local - secondPeakT) / (1.0D - secondPeakT)));
    }

    private static double smooth01(double value) {
        double t = clamp(value, 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp(t, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record WindSample(double envelope, double dirX, double dirZ, double strengthScale) {
        static final WindSample NONE = new WindSample(0.0D, 0.0D, 0.0D, 0.0D);
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
