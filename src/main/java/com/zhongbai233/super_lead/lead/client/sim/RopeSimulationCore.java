package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import com.zhongbai233.super_lead.lead.client.geom.SegmentHit;
import com.zhongbai233.super_lead.lead.client.geom.SegmentPair;
import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared mutable state and cache plumbing for client rope simulation.
 *
 * <p>
 * The simulation is performance-sensitive and array-backed by design. Prefer
 * extracting cohesive state/cache structs over adding per-node objects or
 * virtual
 * calls inside solver loops.
 */
abstract class RopeSimulationCore {

    // ============================================================================================
    // Topology / geometry — now resolved from RopeTuning
    // ============================================================================================
    protected final double ropeRadius;
    protected final double terrainRadius;
    protected final double ropeRepelDistance;
    protected final double collisionEps;
    protected final double terrainProximityMargin;
    protected final double segmentCornerPushEps;
    protected final double segmentTopSupportEps;
    protected final int minSegments;

    // ============================================================================================
    // Solver — now resolved from RopeTuning
    // ============================================================================================
    protected final double ropeRopeParallelRelax;
    protected final int maxSubsteps;
    protected final double substepSpeedTier1;
    protected final double substepSpeedTier2;
    protected final double substepSpeedTier3;
    protected final double supportDownInvMass;
    protected final double contactPushGain;

    // ============================================================================================
    // Settle / wake — now resolved from RopeTuning
    // ============================================================================================
    protected static final double VISUAL_PUSH_MIN_SLACK = 1.0e-6D;
    protected final int settleThresholdTicks;
    protected final double settleMotionSqr;
    protected final double endpointWakeDistanceSqr;
    protected final long settledBlockHashIntervalTicks;
    protected static final long UNINIT = Long.MIN_VALUE;

    // ============================================================================================
    // Topology / state
    // ============================================================================================
    protected final int segments;
    protected final int nodes;
    protected final long simulationSeed;
    protected RopeTuning tuning;
    protected final Vec3 stableSeparation;

    // Positions (current solver state).
    protected final double[] x;
    protected final double[] y;
    protected final double[] z;
    // Pre-substep positions (for velocity reconstruction at substep end).
    protected final double[] xPrev;
    protected final double[] yPrev;
    protected final double[] zPrev;
    // Tick-aligned previous-end positions (used as render lerp origin).
    protected final double[] xLastTick;
    protected final double[] yLastTick;
    protected final double[] zLastTick;
    // Reusable render snapshot. Filled lazily per frame/partialTick and reused by
    // rendering,
    // picking and particles so stable ropes do not allocate Vec3 nodes every frame.
    protected final double[] renderX;
    protected final double[] renderY;
    protected final double[] renderZ;
    protected final double[] renderLengths;
    // Collision/render proxy curve. This is a smoothed, arc-length-resampled and
    // distance-projected representation of the raw solver nodes. It deliberately
    // keeps the same node count as the physical rope so existing rendering,
    // attachment and particle code can keep indexing through nodeCount().
    protected final double[] proxyX;
    protected final double[] proxyY;
    protected final double[] proxyZ;
    protected final double[] proxyLengths;
    protected final double[] proxySourceX;
    protected final double[] proxySourceY;
    protected final double[] proxySourceZ;
    protected final double[] proxyWorkX;
    protected final double[] proxyWorkY;
    protected final double[] proxyWorkZ;
    protected boolean collisionProxyValid;
    protected double collisionProxyTotalLength;
    /**
     * When true, rendering and player-contact sampling use the smoothed proxy
     * curve. When false (default), rendering uses raw tick-interpolated solver
     * nodes. Set after construction for ropes inside a synced physics zone.
     */
    protected boolean useCollisionProxy;
    protected boolean renderCacheValid;
    protected float renderCachePartialTick;
    protected double renderTotalLength;
    protected boolean renderStable = true;
    // Reusable per-render-frame scratch for LeashBuilder.renderSquare (side / up
    // basis vectors per
    // node, scaled by current thickness). Allocated lazily on first render.
    // Invalidated whenever
    // markBoundsDirty fires or when thickness/pulse hash changes between submit
    // calls.
    protected double[] frameSideX, frameSideY, frameSideZ;
    protected double[] frameUpX, frameUpY, frameUpZ;
    protected boolean frameScratchValid;
    protected double frameScratchThickness;
    protected int frameScratchPulsesHash;
    // Render-side occlusion cache. Refreshed every N frames depending on distance;
    // skipped
    // entirely on cache hit, sparing the level.clip raycasts in RopeVisibility.
    protected long visOcclusionFrame = Long.MIN_VALUE;
    protected boolean visOcclusionResult;
    // Per-segment visibility mask computed by RopeVisibility. Bit s set => segment
    // s is visible
    // (passes both per-segment frustum check and line-of-sight occlusion).
    // Renderers consult
    // this to skip baking/emitting individual stripes, so a partially occluded rope
    // only hides
    // the occluded segments instead of disappearing entirely. Null array == "all
    // visible"
    // fast path. Invalidated by markBoundsDirty.
    protected long[] segVisMask;
    protected int segVisBitCount;
    protected boolean segVisAllVisible = true;
    // ----------------------------------------------------------------------------------------
    // Static baked vertex cache (Pillar A).
    // World-coordinate vertex stream pre-computed when the rope is settled and
    // parameters
    // (light/color/kind/highlight/pulses) match. Render-thread emits these directly
    // to the
    // VertexConsumer, skipping basis-vector math, quad winding, color/light
    // interpolation,
    // and per-segment loops. Auto-invalidated by markBoundsDirty().
    protected float[] bakedX, bakedY, bakedZ;
    protected int[] bakedColor;
    protected int[] bakedLight;
    protected int bakedCount;
    protected boolean bakedValid;
    // Per-segment frame metadata for emit-time view-relative face culling. Each
    // baked
    // square segment writes 16 verts in 4 fixed-order quads (face 0..3); these
    // arrays
    // store the segment midpoint and side/up axes so emitBaked can pick the 2
    // visible
    // faces (8 verts) per segment based on camera position.
    protected float[] bakedSegMidX, bakedSegMidY, bakedSegMidZ;
    protected float[] bakedSegSideX, bakedSegSideY, bakedSegSideZ;
    protected float[] bakedSegUpX, bakedSegUpY, bakedSegUpZ;
    protected int bakedSegmentCount;
    // Cache key fields. Equality across all of these = bake is reusable.
    protected int bakedNodeCount;
    protected boolean bakedRibbonLod;
    protected int bakedBlockA, bakedBlockB, bakedSkyA, bakedSkyB;
    protected int bakedKindOrdinal;
    protected boolean bakedPowered;
    protected int bakedTier;
    protected int bakedPulsesHash;
    protected int bakedColorHash;
    /**
     * Camera-position quantization bin used by ribbon bakes (whose side vector is
     * camera
     * dependent). 0 means "camera-independent" (3D box bake) and matches any
     * camera.
     */
    protected int bakedCameraBin;
    protected double bakedHalfThickness;
    // Explicit velocity.
    protected final double[] entityPushAccum;
    protected final double[] vx;
    protected final double[] vy;
    protected final double[] vz;

    protected final boolean[] pinned;
    /** Set when this node is currently resting on a horizontal terrain surface. */
    protected final boolean[] supportNode;
    /**
     * Set when this node had any terrain or rope-rope contact in the current
     * substep.
     */
    protected final boolean[] contactNode;

    // XPBD lambda accumulators (one per distance segment).
    protected final double[] lambdaDistance;

    // Block AABB cache shared across all iterations of a single step().
    protected final BlockCollisionCache blockCache = new BlockCollisionCache();

    // Reusable scratch.
    protected final SegmentPair pairScratch = new SegmentPair();
    protected final SegmentHit hitScratch = new SegmentHit();
    protected final double[] forceScratch = new double[3];
    protected final double[] distanceCorrectionScratch = new double[6];

    // External (server-broadcast) contact
    protected float contactT = -1.0F;
    protected double contactDx, contactDy, contactDz;
    protected long contactRefreshTick = Long.MIN_VALUE;

    // Precomputed per-segment AABBs (unpadded) for rope-vs-rope broad-phase.
    // Layout: 6 doubles
    // per segment in [minX,minY,minZ,maxX,maxY,maxZ] order. Refreshed at the start
    // of each
    // solveRopeRopeConstraints invocation; corrections within the inner loop may
    // make these
    // slightly stale, but the ROPE_REPEL_DISTANCE margin absorbs the slop
    // (broad-phase is
    // conservative, narrow-phase still uses live positions).
    protected double[] segAabb;

    // ----------------------------------------------------------------------------------------
    // Parallel physics support. Set by the driver around a parallel solve to (a)
    // suppress
    // cross-rope mutating refreshes (workers must never write to neighbours) and
    // (b) tell
    // {@link BlockCollisionCache} to never call {@code Level#getBlockState} on
    // cache miss.
    // The main thread runs {@link #preparePhysicsParallel} on every sim before
    // flipping the
    // flag, which guarantees bounds, segAabb, terrainNearby and blockHash are all
    // fresh.
    // ----------------------------------------------------------------------------------------
    private static final ThreadLocal<Boolean> PARALLEL_PHASE = ThreadLocal.withInitial(() -> false);

    static void beginParallelPhase() {
        PARALLEL_PHASE.set(true);
    }

    static void endParallelPhase() {
        PARALLEL_PHASE.set(false);
    }

    protected static boolean parallelPhase() {
        return PARALLEL_PHASE.get();
    }

    // Set by preparePhysicsParallel; consumed (and cleared) by step().
    protected boolean precomputeReady;
    protected boolean cachedTerrainNearby;
    protected boolean cachedBlockChanged;

    // Tick-start snapshot of node positions, used as "neighbour view" by other
    // workers during
    // a parallel solve. Without this, two workers concurrently mutating their own
    // x[]/y[]/z[]
    // would let cross-rope contacts read torn / inconsistent state, producing
    // perpetual jitter
    // in stacked ropes. Filled by preparePhysicsParallel; read by rope-rope
    // constraints.
    protected double[] snapX, snapY, snapZ;

    // ============================================================================================
    // Bookkeeping
    // ============================================================================================
    protected long lastSteppedTick = UNINIT;
    protected long lastTouchTick = UNINIT;
    protected boolean endpointInit;
    protected double lastAx, lastAy, lastAz, lastBx, lastBy, lastBz;
    protected long lastBlockHash;
    protected long lastBlockHashCheckTick = UNINIT;
    protected boolean blockHashInit;
    protected boolean terrainNearbyLast;
    protected boolean windPhysicsEnabled = true;
    protected int settledTicks;
    protected int quietTicks;

    protected boolean boundsDirty = true;
    protected double minX, maxX, minY, maxY, minZ, maxZ;

    protected int anchorAColX = Integer.MIN_VALUE, anchorAColY = 0, anchorAColZ = 0;
    protected int anchorBColX = Integer.MIN_VALUE, anchorBColY = 0, anchorBColZ = 0;

    protected RopeSimulationCore(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        this.tuning = tuning != null ? tuning : RopeTuning.localDefaults();
        this.simulationSeed = seed;

        // Resolve all physics constants from tuning
        this.ropeRadius = this.tuning.ropeRadius();
        this.terrainRadius = this.tuning.terrainRadius();
        this.ropeRepelDistance = this.tuning.ropeRepelDistance();
        this.collisionEps = this.tuning.collisionEps();
        this.terrainProximityMargin = this.tuning.terrainProximityMargin();
        this.segmentCornerPushEps = this.tuning.ropeRadius() * this.tuning.segmentCornerPushFactor();
        this.segmentTopSupportEps = this.tuning.ropeRadius() * this.tuning.segmentTopSupportFactor();
        this.minSegments = this.tuning.minSegments();
        this.ropeRopeParallelRelax = this.tuning.ropeRopeParallelRelax();
        this.maxSubsteps = this.tuning.maxSubsteps();
        this.substepSpeedTier1 = this.tuning.substepSpeedTier1();
        this.substepSpeedTier2 = this.tuning.substepSpeedTier2();
        this.substepSpeedTier3 = this.tuning.substepSpeedTier3();
        this.supportDownInvMass = this.tuning.supportDownInvMass();
        this.contactPushGain = this.tuning.contactPushGain();
        this.settleThresholdTicks = this.tuning.settleThresholdTicks();
        this.settleMotionSqr = this.tuning.settleMotionSqr();
        this.endpointWakeDistanceSqr = this.tuning.endpointWakeDistanceSqr();
        this.settledBlockHashIntervalTicks = 10L;

        this.segments = segmentCount(a, b, this.tuning);
        this.nodes = segments + 1;
        this.stableSeparation = RopeSagModel.stableUnitVector(seed);

        x = new double[nodes];
        y = new double[nodes];
        z = new double[nodes];
        xPrev = new double[nodes];
        yPrev = new double[nodes];
        zPrev = new double[nodes];
        xLastTick = new double[nodes];
        yLastTick = new double[nodes];
        zLastTick = new double[nodes];
        renderX = new double[nodes];
        renderY = new double[nodes];
        renderZ = new double[nodes];
        renderLengths = new double[nodes];
        proxyX = new double[nodes];
        proxyY = new double[nodes];
        proxyZ = new double[nodes];
        proxyLengths = new double[nodes];
        proxySourceX = new double[nodes];
        proxySourceY = new double[nodes];
        proxySourceZ = new double[nodes];
        proxyWorkX = new double[nodes];
        proxyWorkY = new double[nodes];
        proxyWorkZ = new double[nodes];
        vx = new double[nodes];
        vy = new double[nodes];
        vz = new double[nodes];
        pinned = new boolean[nodes];
        supportNode = new boolean[nodes];
        contactNode = new boolean[nodes];
        entityPushAccum = new double[nodes];
        lambdaDistance = new double[segments];

        RopeSagModel.writeCatenary(a, b, this.tuning.slack(), this.tuning.gravity(), stableSeparation, x, y, z);
        double kickScale = 1.0D - RopeSagModel.tautProjectionWeight(this.tuning.slack());
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
            // Tiny lateral kick so the rope never starts as a perfect line (avoids
            // ambiguous normals).
            double s = Math.sin(Math.PI * t);
            vx[i] = stableSeparation.x * this.tuning.initialVelocityKick() * kickScale * s;
            vy[i] = 0.0D;
            vz[i] = stableSeparation.z * this.tuning.initialVelocityKick() * kickScale * s;
        }
        pinned[0] = true;
        pinned[nodes - 1] = true;
    }

    protected static int segmentCount(Vec3 a, Vec3 b) {
        return segmentCount(a, b, RopeTuning.forMidpoint(a, b));
    }

    protected static int segmentCount(Vec3 a, Vec3 b, RopeTuning tuning) {
        return Math.max(tuning.minSegments(),
                Math.min(tuning.segmentMax(),
                        (int) Math.ceil(a.distanceTo(b) / tuning.segmentLength())));
    }

    // ============================================================================================
    // Public read-only API
    // ============================================================================================
    public int nodeCount() {
        return nodes;
    }

    public boolean matchesLength(Vec3 a, Vec3 b) {
        // Hysteresis: tolerate a one-segment difference so a rope oscillating around a
        // segmentLength multiple (typical when the player is pulled back at max leash)
        // does not trigger a full sim re-creation every tick, which would otherwise
        // wipe
        // velocity / XPBD lambdas and produce visible flicker.
        return Math.abs(segmentCount(a, b) - segments) <= 1;
    }

    public boolean matchesLength(Vec3 a, Vec3 b, RopeTuning tuning) {
        return Math.abs(segmentCount(a, b, tuning) - segments) <= 1;
    }

    public RopeTuning tuning() {
        return tuning;
    }

    protected boolean visualPushEnabled() {
        return tuning != null && tuning.slack() > VISUAL_PUSH_MIN_SLACK;
    }

    public boolean physicsEnabled() {
        return tuning.modePhysics();
    }

    public void setWindPhysicsEnabled(boolean enabled) {
        this.windPhysicsEnabled = enabled;
    }

    public boolean setTuning(RopeTuning tuning) {
        RopeTuning next = tuning != null ? tuning : RopeTuning.localDefaults();
        if (Objects.equals(this.tuning, next))
            return false;
        this.tuning = next;
        settledTicks = 0;
        quietTicks = 0;
        markBoundsDirty();
        double tautWeight = RopeSagModel.tautProjectionWeight(next.slack());
        if (tautWeight > 0.0D) {
            Vec3 a = new Vec3(x[0], y[0], z[0]);
            Vec3 b = new Vec3(x[nodes - 1], y[nodes - 1], z[nodes - 1]);
            applyTautProjection(a, b, tautWeight, false);
            snapPreviousStateToCurrent();
        }
        return true;
    }

    protected void applyTautProjection(Vec3 a, Vec3 b, double weight, boolean preserveContactNodes) {
        if (weight <= 0.0D || nodes < 3) {
            return;
        }
        double clamped = Math.min(1.0D, weight);
        double keepVelocity = 1.0D - clamped;
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        for (int i = 1; i < nodes - 1; i++) {
            if (preserveContactNodes && (contactNode[i] || supportNode[i])) {
                continue;
            }
            double t = i / (double) segments;
            double tx = a.x + dx * t;
            double ty = a.y + dy * t;
            double tz = a.z + dz * t;
            x[i] += (tx - x[i]) * clamped;
            y[i] += (ty - y[i]) * clamped;
            z[i] += (tz - z[i]) * clamped;
            vx[i] *= keepVelocity;
            vy[i] *= keepVelocity;
            vz[i] *= keepVelocity;
        }
        markBoundsDirty();
    }

    private void snapPreviousStateToCurrent() {
        for (int i = 0; i < nodes; i++) {
            xPrev[i] = x[i];
            yPrev[i] = y[i];
            zPrev[i] = z[i];
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
        }
    }

    /**
     * Copy the mutable physics state from another simulation with the same
     * topology.
     * Used by the async client driver to solve on a worker-owned copy, then publish
     * the
     * completed state back to the render-owned live simulation on the main thread.
     */
    public void copyMutableStateFrom(RopeSimulation other) {
        if (other.nodes != nodes || other.segments != segments) {
            throw new IllegalArgumentException("Cannot copy rope state across different topologies");
        }

        this.tuning = other.tuning;
        copy(other.x, x);
        copy(other.y, y);
        copy(other.z, z);
        copy(other.xPrev, xPrev);
        copy(other.yPrev, yPrev);
        copy(other.zPrev, zPrev);
        copy(other.xLastTick, xLastTick);
        copy(other.yLastTick, yLastTick);
        copy(other.zLastTick, zLastTick);
        copy(other.vx, vx);
        copy(other.vy, vy);
        copy(other.vz, vz);
        copy(other.entityPushAccum, entityPushAccum);
        copy(other.pinned, pinned);
        copy(other.supportNode, supportNode);
        copy(other.contactNode, contactNode);
        copy(other.lambdaDistance, lambdaDistance);

        contactT = other.contactT;
        contactDx = other.contactDx;
        contactDy = other.contactDy;
        contactDz = other.contactDz;
        contactRefreshTick = other.contactRefreshTick;

        lastSteppedTick = other.lastSteppedTick;
        lastTouchTick = other.lastTouchTick;
        endpointInit = other.endpointInit;
        lastAx = other.lastAx;
        lastAy = other.lastAy;
        lastAz = other.lastAz;
        lastBx = other.lastBx;
        lastBy = other.lastBy;
        lastBz = other.lastBz;
        lastBlockHash = other.lastBlockHash;
        lastBlockHashCheckTick = other.lastBlockHashCheckTick;
        blockHashInit = other.blockHashInit;
        terrainNearbyLast = other.terrainNearbyLast;
        settledTicks = other.settledTicks;
        quietTicks = other.quietTicks;
        renderStable = other.renderStable;

        precomputeReady = false;
        blockCache.reset();
        this.useCollisionProxy = other.useCollisionProxy;
        markBoundsDirty();
        if (other.segAabb != null) {
            if (segAabb == null || segAabb.length < other.segAabb.length) {
                segAabb = new double[other.segAabb.length];
            }
            System.arraycopy(other.segAabb, 0, segAabb, 0, other.segAabb.length);
        } else {
            segAabb = null;
        }
    }

    private static void copy(double[] src, double[] dst) {
        System.arraycopy(src, 0, dst, 0, dst.length);
    }

    private static void copy(boolean[] src, boolean[] dst) {
        System.arraycopy(src, 0, dst, 0, dst.length);
    }

    public long lastTouchTick() {
        return lastTouchTick;
    }

    public boolean isSettled() {
        return settledTicks >= settleThresholdTicks;
    }

    public int quietTicks() {
        return quietTicks;
    }

    public double maxNodeMotionSqr() {
        double max = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - xLastTick[i];
            double dy = y[i] - yLastTick[i];
            double dz = z[i] - zLastTick[i];
            double m = dx * dx + dy * dy + dz * dz;
            if (m > max)
                max = m;
        }
        return max;
    }

    public Vec3 nodeAt(int i, float partialTick) {
        return new Vec3(
                xLastTick[i] + (x[i] - xLastTick[i]) * partialTick,
                yLastTick[i] + (y[i] - yLastTick[i]) * partialTick,
                zLastTick[i] + (z[i] - zLastTick[i]) * partialTick);
    }

    public double currentX(int i) {
        return x[i];
    }

    public double currentY(int i) {
        return y[i];
    }

    public double currentZ(int i) {
        return z[i];
    }

    protected double prepareCollisionProxy() {
        if (collisionProxyValid) {
            return collisionProxyTotalLength;
        }
        collisionProxyTotalLength = RopeCollisionProxy.rebuild(nodes,
                x, y, z, desiredProxyLength(x[0], y[0], z[0], x[nodes - 1], y[nodes - 1], z[nodes - 1]),
                proxyX, proxyY, proxyZ, proxyLengths,
                proxyWorkX, proxyWorkY, proxyWorkZ);
        collisionProxyValid = true;
        return collisionProxyTotalLength;
    }

    protected double prepareRenderProxy(float partialTick) {
        for (int i = 0; i < nodes; i++) {
            proxySourceX[i] = xLastTick[i] + (x[i] - xLastTick[i]) * partialTick;
            proxySourceY[i] = yLastTick[i] + (y[i] - yLastTick[i]) * partialTick;
            proxySourceZ[i] = zLastTick[i] + (z[i] - zLastTick[i]) * partialTick;
        }
        return RopeCollisionProxy.rebuild(nodes,
                proxySourceX, proxySourceY, proxySourceZ,
                desiredProxyLength(proxySourceX[0], proxySourceY[0], proxySourceZ[0],
                        proxySourceX[nodes - 1], proxySourceY[nodes - 1], proxySourceZ[nodes - 1]),
                renderX, renderY, renderZ, renderLengths,
                proxyWorkX, proxyWorkY, proxyWorkZ);
    }

    private double desiredProxyLength(double ax, double ay, double az, double bx, double by, double bz) {
        Vec3 a = new Vec3(ax, ay, az);
        Vec3 b = new Vec3(bx, by, bz);
        return RopeSagModel.physicsTargetLength(a, b, tuning.slack(), tuning.gravity());
    }

    public boolean boundsOverlap(RopeSimulation other, double margin) {
        updateBounds();
        // In parallel mode workers must not write to neighbours. Prepare phase already
        // refreshed
        // every sim's bounds before the flag was flipped, so reading other's stored
        // bbox is safe.
        if (!parallelPhase())
            other.updateBounds();
        return maxX + margin >= other.minX && other.maxX + margin >= minX
                && maxY + margin >= other.minY && other.maxY + margin >= minY
                && maxZ + margin >= other.minZ && other.maxZ + margin >= minZ;
    }

    public boolean mightContact(RopeSimulation other, double distance) {
        if (other == this || !boundsOverlap(other, distance)) {
            return false;
        }
        double distanceSqr = distance * distance;
        for (int i = 0; i < segments; i++) {
            for (int j = 0; j < other.segments; j++) {
                if (!segmentsAabbOverlap(other, i, j, distance))
                    continue;
                RopeMath.closestSegmentPoints(
                        x[i], y[i], z[i], x[i + 1], y[i + 1], z[i + 1],
                        other.x[j], other.y[j], other.z[j], other.x[j + 1], other.y[j + 1], other.z[j + 1],
                        pairScratch);
                if (pairScratch.distSqr <= distanceSqr) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsAabbOverlap(RopeSimulation other, int i, int j, double margin) {
        double ax0 = x[i], ay0 = y[i], az0 = z[i];
        double ax1 = x[i + 1], ay1 = y[i + 1], az1 = z[i + 1];
        double bx0 = other.x[j], by0 = other.y[j], bz0 = other.z[j];
        double bx1 = other.x[j + 1], by1 = other.y[j + 1], bz1 = other.z[j + 1];
        double aMinX = Math.min(ax0, ax1) - margin;
        double aMaxX = Math.max(ax0, ax1) + margin;
        double aMinY = Math.min(ay0, ay1) - margin;
        double aMaxY = Math.max(ay0, ay1) + margin;
        double aMinZ = Math.min(az0, az1) - margin;
        double aMaxZ = Math.max(az0, az1) + margin;
        return aMaxX >= Math.min(bx0, bx1) && Math.max(bx0, bx1) >= aMinX
                && aMaxY >= Math.min(by0, by1) && Math.max(by0, by1) >= aMinY
                && aMaxZ >= Math.min(bz0, bz1) && Math.max(bz0, bz1) >= aMinZ;
    }

    // ============================================================================================
    // Correction primitives + endpoint pinning
    // ============================================================================================
    protected void applyCorrection(int i, double dx, double dy, double dz) {
        if (pinned[i])
            return;
        if (supportNode[i] && dy < 0.0D)
            dy *= supportDownInvMass;
        x[i] += dx;
        y[i] += dy;
        z[i] += dz;
        markBoundsDirty();
    }

    protected void applyTerrainCorrection(int i, double dx, double dy, double dz) {
        if (pinned[i])
            return;
        x[i] += dx;
        y[i] += dy;
        z[i] += dz;
        contactNode[i] = true;
        if (dy > 0.0D)
            supportNode[i] = true;
        markBoundsDirty();
    }

    protected void pinEndpoints(Vec3 a, Vec3 b) {
        x[0] = a.x;
        y[0] = a.y;
        z[0] = a.z;
        x[nodes - 1] = b.x;
        y[nodes - 1] = b.y;
        z[nodes - 1] = b.z;
    }

    protected void clearContactState() {
        for (int i = 0; i < nodes; i++) {
            contactNode[i] = false;
            supportNode[i] = false;
            entityPushAccum[i] *= 0.85D;
            if (entityPushAccum[i] < 1.0e-4D)
                entityPushAccum[i] = 0.0D;
        }
    }

    // ============================================================================================
    // Endpoint tracking, slack, awake propagation
    // ============================================================================================
    protected boolean rememberEndpointsMoved(Vec3 a, Vec3 b) {
        if (!endpointInit) {
            rememberEndpoints(a, b);
            endpointInit = true;
            return false;
        }
        double daX = a.x - lastAx, daY = a.y - lastAy, daZ = a.z - lastAz;
        double dbX = b.x - lastBx, dbY = b.y - lastBy, dbZ = b.z - lastBz;
        boolean moved = daX * daX + daY * daY + daZ * daZ
                + dbX * dbX + dbY * dbY + dbZ * dbZ > endpointWakeDistanceSqr;
        rememberEndpoints(a, b);
        return moved;
    }

    private void rememberEndpoints(Vec3 a, Vec3 b) {
        lastAx = a.x;
        lastAy = a.y;
        lastAz = a.z;
        lastBx = b.x;
        lastBy = b.y;
        lastBz = b.z;
    }

    protected double slackFactor(Vec3 a, Vec3 b) {
        return RopeSagModel.slackFactor(a, b, tuning.slack(), tuning.gravity());
    }

    protected boolean anyNeighborAwake(List<RopeSimulation> neighbors) {
        for (int i = 0; i < neighbors.size(); i++) {
            RopeSimulation n = neighbors.get(i);
            if (n == this)
                continue;
            if (!n.isSettled() && boundsOverlap(n, ropeRepelDistance))
                return true;
        }
        return false;
    }

    protected void updateSettleState() {
        double maxMotionSqr = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - xLastTick[i];
            double dy = y[i] - yLastTick[i];
            double dz = z[i] - zLastTick[i];
            double m = dx * dx + dy * dy + dz * dz;
            if (m > maxMotionSqr)
                maxMotionSqr = m;
        }
        if (maxMotionSqr < settleMotionSqr)
            settledTicks++;
        else
            settledTicks = 0;
        if (maxMotionSqr < settleMotionSqr)
            quietTicks++;
        else
            quietTicks = 0;
    }

    protected boolean blockHashUnchanged(Level level, long currentTick) {
        if (blockHashInit && isSettled()
                && lastBlockHashCheckTick != UNINIT
                && currentTick - lastBlockHashCheckTick < settledBlockHashIntervalTicks) {
            return true;
        }
        lastBlockHashCheckTick = currentTick;
        long h = computeBlockHash(level);
        if (!blockHashInit) {
            lastBlockHash = h;
            blockHashInit = true;
            return true;
        }
        if (h == lastBlockHash)
            return true;
        lastBlockHash = h;
        return false;
    }

    private long computeBlockHash(Level level) {
        updateBounds();
        int bx0 = (int) Math.floor(minX - ropeRadius) - 1;
        int bx1 = (int) Math.floor(maxX + ropeRadius) + 1;
        int by0 = (int) Math.floor(minY - ropeRadius) - 1;
        int by1 = (int) Math.floor(maxY + ropeRadius) + 1;
        int bz0 = (int) Math.floor(minZ - ropeRadius) - 1;
        int bz1 = (int) Math.floor(maxZ + ropeRadius) + 1;
        long hash = 1469598103934665603L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int by = by0; by <= by1; by++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int bx = bx0; bx <= bx1; bx++) {
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    hash = (hash ^ System.identityHashCode(state)) * 1099511628211L;
                    hash ^= cursor.asLong();
                }
            }
        }
        return hash;
    }

    protected void markBoundsDirty() {
        boundsDirty = true;
        collisionProxyValid = false;
        renderCacheValid = false;
        frameScratchValid = false;
        visOcclusionFrame = Long.MIN_VALUE;
        segVisAllVisible = true;
        segVisBitCount = 0;
        bakedValid = false;
    }

    protected void updateBounds() {
        if (!boundsDirty)
            return;
        double nx0 = x[0], nx1 = x[0];
        double ny0 = y[0], ny1 = y[0];
        double nz0 = z[0], nz1 = z[0];
        for (int i = 1; i < nodes; i++) {
            double vx = x[i];
            if (vx < nx0)
                nx0 = vx;
            else if (vx > nx1)
                nx1 = vx;
            double vy = y[i];
            if (vy < ny0)
                ny0 = vy;
            else if (vy > ny1)
                ny1 = vy;
            double vz = z[i];
            if (vz < nz0)
                nz0 = vz;
            else if (vz > nz1)
                nz1 = vz;
        }
        minX = nx0;
        maxX = nx1;
        minY = ny0;
        maxY = ny1;
        minZ = nz0;
        maxZ = nz1;
        boundsDirty = false;
    }

    public AABB currentBounds() {
        updateBounds();
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
