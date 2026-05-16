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
        // Entity overlap normally wakes the rope so bodies can visually push it. Fully
        // taut ropes have no useful travel budget, so ignore this cosmetic push path at
        // slack=0 instead of waking a few trapped joints every tick.
        boolean entityPushActive = visualPushEnabled() && !entityContacts.isEmpty();
        boolean forceActive = !forceFields.isEmpty();
        boolean windActive = windActive(a, b, currentTick);
        boolean awake = endpointMoved || blockChanged || neighborAwake || entityPushActive || forceActive
                || windActive
                || !isSettled()
                || hasExternalContact(currentTick);
        if (endpointMoved || blockChanged || neighborAwake || entityPushActive || hasExternalContact(currentTick)
                || forceActive || windActive) {
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
                        neighbors, forceFields, entityPushActive ? entityContacts : List.of());
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
            List<RopeEntityContact> entityContacts) {
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
        for (int it = 0; it < iterations; it++) {
            solveDistanceConstraints(targetLen, alphaTilde, (it & 1) == 0);
            if (terrainEnabled)
                solveTerrainConstraints(level);
            if (!neighbors.isEmpty())
                solveRopeRopeConstraints(neighbors);
            if (!entityContacts.isEmpty())
                solveEntityConstraints(entityContacts);
            pinEndpoints(a, b);
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

    private int chooseSubsteps(Vec3 a, Vec3 b) {
        if (!endpointInit)
            return 1;
        double daX = a.x - lastAx, daY = a.y - lastAy, daZ = a.z - lastAz;
        double dbX = b.x - lastBx, dbY = b.y - lastBy, dbZ = b.z - lastBz;
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
        vy[nodeIndex] += force * tuning.windVerticalLift() * h;
    }

    private boolean windActive(Vec3 a, Vec3 b, long tick) {
        if (!windEnabled() || RopeSagModel.tautProjectionWeight(tuning.slack()) >= 0.98D) {
            return false;
        }
        double mx = (a.x + b.x) * 0.5D;
        double mz = (a.z + b.z) * 0.5D;
        return windAt(mx, mz, tick).envelope() > 0.025D;
    }

    private boolean windEnabled() {
        return windPhysicsEnabled
                && tuning.windEnabled()
                && tuning.windStrength() > 0.0D
                && tuning.windWaveLength() > 1.0e-5D
                && tuning.windSpeed() > 0.0D;
    }

    private WindSample windAt(double wx, double wz, long tick) {
        long windSeed = windRegionSeed(wx, wz);
        double baseDirRad = Math.toRadians(tuning.windDirectionDeg());
        double baseDirX = Math.cos(baseDirRad);
        double baseDirZ = Math.sin(baseDirRad);
        double speed = Math.max(1.0e-5D, tuning.windSpeed());
        double baseCycleTicks = Math.max(8.0D, tuning.windWaveLength() / speed);
        double windClock = tick - (wx * baseDirX + wz * baseDirZ) / speed + windSeedPhase(windSeed) * baseCycleTicks;
        long eventIndex = (long) Math.floor(windClock / baseCycleTicks);
        WindSample current = windEventAt(eventIndex, windClock, baseCycleTicks, windSeed);
        if (current.envelope() > 0.0D) {
            return current;
        }
        return windEventAt(eventIndex - 1L, windClock, baseCycleTicks, windSeed);
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

        double directionJitter = tuning.windDirectionJitterDeg();
        double directionOffset = randomSigned(windSeed, eventIndex, 0xBF58476D1CE4E5B9L) * directionJitter;
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

    private long windRegionSeed(double wx, double wz) {
        int cellX = (int) Math.floor(wx / 24.0D);
        int cellZ = (int) Math.floor(wz / 24.0D);
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

    private double random01(long seed, long index, long salt) {
        long mixed = seed ^ salt ^ (index * 0x9E3779B97F4A7C15L);
        mixed ^= (mixed >>> 30);
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= (mixed >>> 27);
        mixed *= 0x94D049BB133111EBL;
        mixed ^= (mixed >>> 31);
        return ((mixed >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53D;
    }

    private double linearTriangleEnvelope(double local, boolean rampingGust) {
        double triangle = local < 0.5D ? local * 2.0D : (1.0D - local) * 2.0D;
        if (!rampingGust) {
            return Math.max(0.0D, triangle);
        }
        double ramp = 0.45D + 0.55D * local;
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
            return lerp(0.0D, firstPeak, local / firstPeakT);
        }
        if (local < dipT) {
            return lerp(firstPeak, dip, (local - firstPeakT) / (dipT - firstPeakT));
        }
        if (local < secondPeakT) {
            return lerp(dip, secondPeak, (local - dipT) / (secondPeakT - dipT));
        }
        return lerp(secondPeak, 0.0D, (local - secondPeakT) / (1.0D - secondPeakT));
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
