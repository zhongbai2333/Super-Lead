package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import com.zhongbai233.super_lead.lead.client.geom.SegmentHit;
import com.zhongbai233.super_lead.lead.client.geom.SegmentPair;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RopeSimulation {

    // ============================================================================================
    // Topology / geometry
    // ============================================================================================
    private static final int MIN_SEGMENTS = 4;
    private static final double ROPE_RADIUS = 0.045D;
    /** Effective half-thickness used for rope-vs-terrain collision. Larger than {@link #ROPE_RADIUS}
     *  so the visual highlight outline does not clip into block faces;
     *  rope-vs-rope spacing keeps using the smaller {@link #ROPE_RADIUS} so stacking stays tight. */
    private static final double TERRAIN_RADIUS = 0.085D;
    private static final double ROPE_REPEL_DISTANCE = 0.06D;
    /** Vertical proximity within which rope-rope normals get biased toward the Y axis for stable layering.
     *  Decoupled from ROPE_REPEL_DISTANCE so very tight stacking still produces a strong layer
     *  bias instead of degenerating into sideways tug-of-war between layers. */
    private static final double LAYER_CAPTURE_HEIGHT = 0.20D;

    // ============================================================================================
    // Solver
    // ============================================================================================
    /** Under-relaxation factor for cross-rope corrections in parallel mode. <1 trades a little
     *  settle latency for stability against Jacobi over-shoot in 3-layer rope stacks. */
    private static final double ROPE_ROPE_PARALLEL_RELAX = 0.6D;
    private static final int MAX_SUBSTEPS = 3;
    private static final double SUBSTEP_SPEED_TIER1 = 0.5D;   // blocks/tick
    private static final double SUBSTEP_SPEED_TIER2 = 1.0D;
    /** Layered repulsion blends the raw normal toward the Y axis when ropes are vertically close.
     *  Near 1.0 = almost pure vertical separation, which is what we want for stacked ropes;
     *  any sideways residue makes the sandwiched middle layer tremble. */
    private static final double LAYER_BLEND_STRENGTH = 0.95D;
    /** Y-axis correction multiplier for nodes resting on terrain. <1 = harder to push downward. */
    private static final double SUPPORT_DOWN_INV_MASS = 1.0D;
    /** Endpoint fade window (fraction of rope) for rope-rope pushes near anchor points. */
    private static final double ENDPOINT_FADE = 0.08D;
    private static final double COLLISION_EPS = 0.015D;
    private static final double TERRAIN_PROXIMITY_MARGIN = 0.35D;
    private static final double SEGMENT_CORNER_PUSH_EPS = ROPE_RADIUS * 0.65D;
    private static final double SEGMENT_TOP_SUPPORT_EPS = ROPE_RADIUS * 1.8D;

    // ============================================================================================
    // Settle / wake
    // ============================================================================================
    private static final int SETTLE_THRESHOLD_TICKS = 4;
    private static final double SETTLE_MOTION_SQR = 1.0e-5D;
    private static final double ENDPOINT_WAKE_DISTANCE_SQR = 1.0e-5D;
    private static final long SETTLED_BLOCK_HASH_INTERVAL_TICKS = 10L;
    private static final long UNINIT = Long.MIN_VALUE;

    // ============================================================================================
    // Topology / state
    // ============================================================================================
    private final int segments;
    private final int nodes;
    private final boolean tight;
    private RopeTuning tuning;
    private final double layerPriority;
    private final Vec3 stableSeparation;

    // Positions (current solver state).
    private final double[] x;
    private final double[] y;
    private final double[] z;
    // Pre-substep positions (for velocity reconstruction at substep end).
    private final double[] xPrev;
    private final double[] yPrev;
    private final double[] zPrev;
    // Tick-aligned previous-end positions (used as render lerp origin).
    private final double[] xLastTick;
    private final double[] yLastTick;
    private final double[] zLastTick;
    // Reusable render snapshot. Filled lazily per frame/partialTick and reused by rendering,
    // picking and particles so stable ropes do not allocate Vec3 nodes every frame.
    private final double[] renderX;
    private final double[] renderY;
    private final double[] renderZ;
    private final double[] renderLengths;
    private boolean renderCacheValid;
    private float renderCachePartialTick;
    private double renderTotalLength;
    private boolean renderStable = true;
    // Reusable per-render-frame scratch for LeashBuilder.renderSquare (side / up basis vectors per
    // node, scaled by current thickness). Allocated lazily on first render. Invalidated whenever
    // markBoundsDirty fires or when thickness/pulse hash changes between submit calls.
    private double[] frameSideX, frameSideY, frameSideZ;
    private double[] frameUpX, frameUpY, frameUpZ;
    private boolean frameScratchValid;
    private double frameScratchThickness;
    private int frameScratchPulsesHash;
    // Render-side occlusion cache. Refreshed every N frames depending on distance; skipped
    // entirely on cache hit, sparing the level.clip raycasts in RopeVisibility.
    private long visOcclusionFrame = Long.MIN_VALUE;
    private boolean visOcclusionResult;
    // Per-segment visibility mask computed by RopeVisibility. Bit s set => segment s is visible
    // (passes both per-segment frustum check and line-of-sight occlusion). Renderers consult
    // this to skip baking/emitting individual stripes, so a partially occluded rope only hides
    // the occluded segments instead of disappearing entirely. Null array == "all visible"
    // fast path. Invalidated by markBoundsDirty.
    private long[] segVisMask;
    private int segVisBitCount;
    private boolean segVisAllVisible = true;
    // ----------------------------------------------------------------------------------------
    // Static baked vertex cache (Pillar A).
    // World-coordinate vertex stream pre-computed when the rope is settled and parameters
    // (light/color/kind/highlight/pulses) match. Render-thread emits these directly to the
    // VertexConsumer, skipping basis-vector math, quad winding, color/light interpolation,
    // and per-segment loops. Auto-invalidated by markBoundsDirty().
    private float[] bakedX, bakedY, bakedZ;
    private int[] bakedColor;
    private int[] bakedLight;
    private int bakedCount;
    private boolean bakedValid;
    // Per-segment frame metadata for emit-time view-relative face culling. Each baked
    // square segment writes 16 verts in 4 fixed-order quads (face 0..3); these arrays
    // store the segment midpoint and side/up axes so emitBaked can pick the 2 visible
    // faces (8 verts) per segment based on camera position.
    private float[] bakedSegMidX, bakedSegMidY, bakedSegMidZ;
    private float[] bakedSegSideX, bakedSegSideY, bakedSegSideZ;
    private float[] bakedSegUpX, bakedSegUpY, bakedSegUpZ;
    private int bakedSegmentCount;
    // Cache key fields. Equality across all of these = bake is reusable.
    private int bakedNodeCount;
    private boolean bakedRibbonLod;
    private int bakedBlockA, bakedBlockB, bakedSkyA, bakedSkyB;
    private int bakedKindOrdinal;
    private boolean bakedPowered;
    private int bakedTier;
    private int bakedPulsesHash;
    /** Camera-position quantization bin used by ribbon bakes (whose side vector is camera
     *  dependent). 0 means "camera-independent" (3D box bake) and matches any camera. */
    private int bakedCameraBin;
    // Explicit velocity.
    private final double[] entityPushAccum;
    private final double[] vx;
    private final double[] vy;
    private final double[] vz;

    private final boolean[] pinned;
    /** Set when this node is currently resting on a horizontal terrain surface. */
    private final boolean[] supportNode;
    /** Set when this node had any terrain or rope-rope contact in the current substep. */
    private final boolean[] contactNode;

    // XPBD lambda accumulators (one per distance segment).
    private final double[] lambdaDistance;

    // Block AABB cache shared across all iterations of a single step().
    private final BlockCollisionCache blockCache = new BlockCollisionCache();

    // Reusable scratch.
    private final SegmentPair pairScratch = new SegmentPair();
    private final SegmentHit hitScratch = new SegmentHit();
    private final double[] forceScratch = new double[3];

    // External (server-broadcast) contact: a single horizontal displacement target along the
    // rope, applied as a soft push once per step() and as an additive bend in the LOD-off
    // visual path so all observers see the same deflection. Disabled when contactT < 0.
    private float contactT = -1.0F;
    private double contactDx, contactDy, contactDz;
    private long contactRefreshTick = Long.MIN_VALUE;
    private static final double CONTACT_PUSH_GAIN = 0.45D;

    // Server-broadcast Verlet snapshot used as a soft target. Each tick the corresponding
    // server interior nodes are sampled along the chord and each client interior node is
    // nudged toward its target by SERVER_BLEND_ALPHA — small enough that local physics still
    // refines the shape, large enough that all clients converge on the server's geometry.
    // Cleared / made inactive by setting serverNodesSegments <= 0.
    private int serverNodesSegments = 0;            // server segment count (e.g. 8). 0 = none.
    private float[] serverInterior;                  // length = (segments-1)*3
    private long serverNodesRefreshTick = Long.MIN_VALUE;
    private static final double SERVER_BLEND_ALPHA = 0.20D;
    private static final long SERVER_BLEND_STALE_TICKS = 6L;

    // Precomputed per-segment AABBs (unpadded) for rope-vs-rope broad-phase. Layout: 6 doubles
    // per segment in [minX,minY,minZ,maxX,maxY,maxZ] order. Refreshed at the start of each
    // solveRopeRopeConstraints invocation; corrections within the inner loop may make these
    // slightly stale, but the ROPE_REPEL_DISTANCE margin absorbs the slop (broad-phase is
    // conservative, narrow-phase still uses live positions).
    private double[] segAabb;

    // ----------------------------------------------------------------------------------------
    // Parallel physics support. Set by the driver around a parallel solve to (a) suppress
    // cross-rope mutating refreshes (workers must never write to neighbours) and (b) tell
    // {@link BlockCollisionCache} to never call {@code Level#getBlockState} on cache miss.
    // The main thread runs {@link #preparePhysicsParallel} on every sim before flipping the
    // flag, which guarantees bounds, segAabb, terrainNearby and blockHash are all fresh.
    // ----------------------------------------------------------------------------------------
    private static final ThreadLocal<Boolean> PARALLEL_PHASE = ThreadLocal.withInitial(() -> false);
    public static void beginParallelPhase() { PARALLEL_PHASE.set(true); }
    public static void endParallelPhase()   { PARALLEL_PHASE.set(false); }
    private static boolean parallelPhase()  { return PARALLEL_PHASE.get(); }

    // Set by preparePhysicsParallel; consumed (and cleared) by step().
    private boolean precomputeReady;
    private boolean cachedTerrainNearby;
    private boolean cachedBlockChanged;

    // Tick-start snapshot of node positions, used as "neighbour view" by other workers during
    // a parallel solve. Without this, two workers concurrently mutating their own x[]/y[]/z[]
    // would let cross-rope contacts read torn / inconsistent state, producing perpetual jitter
    // in stacked ropes. Filled by preparePhysicsParallel; read via {@link #snapXAt} family.
    private double[] snapX, snapY, snapZ;

    // ============================================================================================
    // Bookkeeping
    // ============================================================================================
    private long lastSteppedTick = UNINIT;
    private long lastTouchTick = UNINIT;
    private boolean endpointInit;
    private double lastAx, lastAy, lastAz, lastBx, lastBy, lastBz;
    private long lastBlockHash;
    private long lastBlockHashCheckTick = UNINIT;
    private boolean blockHashInit;
    private boolean terrainNearbyLast;
    private int settledTicks;
    private int quietTicks;

    private boolean boundsDirty = true;
    private double minX, maxX, minY, maxY, minZ, maxZ;

    // ============================================================================================
    // Construction
    // ============================================================================================
    public RopeSimulation(Vec3 a, Vec3 b) {
        this(a, b, 0L, false, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, boolean tight) {
        this(a, b, seed, tight, RopeTuning.forMidpoint(a, b));
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, boolean tight, RopeTuning tuning) {
        this.tight = tight;
        this.tuning = tuning != null ? tuning : RopeTuning.localDefaults();
        this.segments = segmentCount(a, b, this.tuning);
        this.nodes = segments + 1;
        this.layerPriority = RopeMath.stableLayerPriority(seed);
        this.stableSeparation = RopeMath.stableUnitVector(seed);

        x = new double[nodes];      y = new double[nodes];      z = new double[nodes];
        xPrev = new double[nodes];  yPrev = new double[nodes];  zPrev = new double[nodes];
        xLastTick = new double[nodes]; yLastTick = new double[nodes]; zLastTick = new double[nodes];
        renderX = new double[nodes]; renderY = new double[nodes]; renderZ = new double[nodes];
        renderLengths = new double[nodes];
        vx = new double[nodes];     vy = new double[nodes];     vz = new double[nodes];
        pinned = new boolean[nodes];
        supportNode = new boolean[nodes];
        contactNode = new boolean[nodes];
        entityPushAccum = new double[nodes];
        lambdaDistance = new double[segments];

        Vec3 dir = b.subtract(a);
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double nx = a.x + dir.x * t;
            double ny = a.y + dir.y * t;
            double nz = a.z + dir.z * t;
            x[i] = nx; y[i] = ny; z[i] = nz;
            xPrev[i] = nx; yPrev[i] = ny; zPrev[i] = nz;
            xLastTick[i] = nx; yLastTick[i] = ny; zLastTick[i] = nz;
            // Tiny lateral kick so the rope never starts as a perfect line (avoids ambiguous normals).
            double s = Math.sin(Math.PI * t);
            vx[i] = stableSeparation.x * 0.06D * s;
            vy[i] = -0.035D * s;
            vz[i] = stableSeparation.z * 0.06D * s;
        }
        pinned[0] = true;
        pinned[nodes - 1] = true;
    }

    public static RopeSimulation visualLeash(Vec3 a, Vec3 b) {
        RopeSimulation sim = new RopeSimulation(a, b);
        sim.setCatenary(a, b, 0.08D);
        return sim;
    }

    public void resetCatenary(Vec3 a, Vec3 b, double sagFactor) {
        setCatenary(a, b, sagFactor);
    }

    private static int segmentCount(Vec3 a, Vec3 b) {
        return segmentCount(a, b, RopeTuning.forMidpoint(a, b));
    }

    private static int segmentCount(Vec3 a, Vec3 b, RopeTuning tuning) {
        return Math.max(MIN_SEGMENTS,
                Math.min(tuning.segmentMax(),
                        (int) Math.ceil(a.distanceTo(b) / tuning.segmentLength())));
    }

    // ============================================================================================
    // Public read-only API
    // ============================================================================================
    public int nodeCount() { return nodes; }

    public boolean matchesLength(Vec3 a, Vec3 b) {
        return segmentCount(a, b) == segments;
    }

    public boolean matchesLength(Vec3 a, Vec3 b, RopeTuning tuning) {
        return segmentCount(a, b, tuning) == segments;
    }

    public RopeTuning tuning() { return tuning; }

    public boolean physicsEnabled() { return tuning.modePhysics(); }

    public boolean setTuning(RopeTuning tuning) {
        RopeTuning next = tuning != null ? tuning : RopeTuning.localDefaults();
        if (Objects.equals(this.tuning, next)) return false;
        this.tuning = next;
        settledTicks = 0;
        quietTicks = 0;
        markBoundsDirty();
        return true;
    }

    /**
     * Copy the mutable physics state from another simulation with the same topology.
     * Used by the async client driver to solve on a worker-owned copy, then publish the
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
        serverNodesSegments = other.serverNodesSegments;
        if (other.serverInterior == null) {
            serverInterior = null;
        } else {
            serverInterior = java.util.Arrays.copyOf(other.serverInterior, other.serverInterior.length);
        }
        serverNodesRefreshTick = other.serverNodesRefreshTick;

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
        markBoundsDirty();
        if (other.segAabb != null) {
            if (segAabb == null || segAabb.length < other.segAabb.length) {
                segAabb = new double[other.segAabb.length];
            }
            System.arraycopy(other.segAabb, 0, segAabb, 0, other.segAabb.length);
        }
    }

    private static void copy(double[] src, double[] dst) {
        System.arraycopy(src, 0, dst, 0, dst.length);
    }

    private static void copy(boolean[] src, boolean[] dst) {
        System.arraycopy(src, 0, dst, 0, dst.length);
    }

    public long lastTouchTick() { return lastTouchTick; }

    public boolean isSettled() { return settledTicks >= SETTLE_THRESHOLD_TICKS; }

    public int quietTicks() { return quietTicks; }

    public double maxNodeMotionSqr() {
        double max = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - xLastTick[i];
            double dy = y[i] - yLastTick[i];
            double dz = z[i] - zLastTick[i];
            double m = dx * dx + dy * dy + dz * dz;
            if (m > max) max = m;
        }
        return max;
    }

    public Vec3 nodeAt(int i, float partialTick) {
        return new Vec3(
                xLastTick[i] + (x[i] - xLastTick[i]) * partialTick,
                yLastTick[i] + (y[i] - yLastTick[i]) * partialTick,
                zLastTick[i] + (z[i] - zLastTick[i]) * partialTick);
    }

    /** Prepare and cache interpolated render nodes. Returns cumulative rope length. */
    public double prepareRender(float partialTick) {
        if (renderCacheValid
                && (isSettled() || Float.floatToIntBits(renderCachePartialTick) == Float.floatToIntBits(partialTick))) {
            return renderTotalLength;
        }
        renderX[0] = xLastTick[0] + (x[0] - xLastTick[0]) * partialTick;
        renderY[0] = yLastTick[0] + (y[0] - yLastTick[0]) * partialTick;
        renderZ[0] = zLastTick[0] + (z[0] - zLastTick[0]) * partialTick;
        renderLengths[0] = 0.0D;
        for (int i = 1; i < nodes; i++) {
            double rx = xLastTick[i] + (x[i] - xLastTick[i]) * partialTick;
            double ry = yLastTick[i] + (y[i] - yLastTick[i]) * partialTick;
            double rz = zLastTick[i] + (z[i] - zLastTick[i]) * partialTick;
            renderX[i] = rx;
            renderY[i] = ry;
            renderZ[i] = rz;
            double dx = rx - renderX[i - 1];
            double dy = ry - renderY[i - 1];
            double dz = rz - renderZ[i - 1];
            renderLengths[i] = renderLengths[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        renderTotalLength = renderLengths[nodes - 1];
        renderCachePartialTick = partialTick;
        renderCacheValid = true;
        // Render positions just changed; basis-vector scratch and occlusion cache are stale.
        frameScratchValid = false;
        visOcclusionFrame = Long.MIN_VALUE;
        return renderTotalLength;
    }

    public double renderX(int i) { return renderX[i]; }
    public double renderY(int i) { return renderY[i]; }
    public double renderZ(int i) { return renderZ[i]; }
    public double renderLength(int i) { return renderLengths[i]; }
    public AABB renderBounds(float partialTick) {
        prepareRender(partialTick);
        double nx0 = renderX[0], nx1 = renderX[0];
        double ny0 = renderY[0], ny1 = renderY[0];
        double nz0 = renderZ[0], nz1 = renderZ[0];
        for (int i = 1; i < nodes; i++) {
            double vx = renderX[i]; if (vx < nx0) nx0 = vx; else if (vx > nx1) nx1 = vx;
            double vy = renderY[i]; if (vy < ny0) ny0 = vy; else if (vy > ny1) ny1 = vy;
            double vz = renderZ[i]; if (vz < nz0) nz0 = vz; else if (vz > nz1) nz1 = vz;
        }
        return new AABB(nx0, ny0, nz0, nx1, ny1, nz1);
    }
    public double currentX(int i) { return x[i]; }
    public double currentY(int i) { return y[i]; }
    public double currentZ(int i) { return z[i]; }

    public boolean boundsOverlap(RopeSimulation other, double margin) {
        updateBounds();
        // In parallel mode workers must not write to neighbours. Prepare phase already refreshed
        // every sim's bounds before the flag was flipped, so reading other's stored bbox is safe.
        if (!parallelPhase()) other.updateBounds();
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
                if (!segmentsAabbOverlap(other, i, j, distance)) continue;
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
    // Visual / cosmetic paths (no physics)
    // ============================================================================================
    public void updateVisualLeash(Vec3 a, Vec3 b, long currentTick, float smoothing) {
        lastTouchTick = currentTick;
        double sag = Math.min(0.55D, a.distanceTo(b) * 0.055D);
        boolean bend = contactT >= 0.0F;
        double bendWindow = 0.28D;
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double tx = a.x + (b.x - a.x) * t;
            double ty = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double tz = a.z + (b.z - a.z) * t;
            if (bend) {
                double dist = Math.abs(t - contactT);
                if (dist < bendWindow) {
                    double w = 0.5D * (1.0D + Math.cos(Math.PI * dist / bendWindow));
                    tx += contactDx * w;
                    ty += contactDy * w;
                    tz += contactDz * w;
                }
            }
            xLastTick[i] = x[i]; yLastTick[i] = y[i]; zLastTick[i] = z[i];
            x[i] += (tx - x[i]) * smoothing;
            y[i] += (ty - y[i]) * smoothing;
            z[i] += (tz - z[i]) * smoothing;
            vx[i] = vy[i] = vz[i] = 0.0D;
        }
        renderStable = false;
        markBoundsDirty();
    }

    private void setCatenary(Vec3 a, Vec3 b, double sagFactor) {
        double sag = Math.min(0.55D, a.distanceTo(b) * sagFactor);
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double nx = a.x + (b.x - a.x) * t;
            double ny = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double nz = a.z + (b.z - a.z) * t;
            x[i] = nx; y[i] = ny; z[i] = nz;
            xLastTick[i] = nx; yLastTick[i] = ny; zLastTick[i] = nz;
            vx[i] = vy[i] = vz[i] = 0.0D;
        }
        markBoundsDirty();
    }

    // ============================================================================================
    // External impulse hooks (reserved for future interactions)
    // ============================================================================================
    public void disturb(Vec3 dir, double strength) {
        for (int i = 1; i < nodes - 1; i++) {
            double s = Math.sin(Math.PI * i / (double) (nodes - 1)) * strength;
            vx[i] += dir.x * s;
            vy[i] += dir.y * s;
            vz[i] += dir.z * s;
        }
    }

    /** Add a falloff-weighted velocity impulse around a world position. Useful for "rope hit" effects. */
    public void applyImpulseAt(Vec3 worldPos, Vec3 impulse, double radius) {
        if (radius <= 0.0D) return;
        double r2 = radius * radius;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - worldPos.x;
            double dy = y[i] - worldPos.y;
            double dz = z[i] - worldPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;
            double falloff = 1.0D - Math.sqrt(d2) / radius;
            vx[i] += impulse.x * falloff;
            vy[i] += impulse.y * falloff;
            vz[i] += impulse.z * falloff;
        }
    }

    // ============================================================================================
    // External contact (server-broadcast push from a player walking into the rope)
    // ============================================================================================
    /** Set or refresh the contact for this rope. Pass {@code t < 0} to clear. */
    public void setExternalContact(long currentTick, float t, double dx, double dy, double dz) {
        if (t < 0.0F) {
            contactT = -1.0F;
            return;
        }
        contactT = t;
        contactDx = dx;
        contactDy = dy;
        contactDz = dz;
        contactRefreshTick = currentTick;
    }

    public void clearExternalContact() {
        contactT = -1.0F;
    }

    /** Active iff a contact was set within the last few ticks (handles dropped packets). */
    public boolean hasExternalContact(long currentTick) {
        return contactT >= 0.0F && (currentTick - contactRefreshTick) <= 5L;
    }

    // ============================================================================================
    // Server Verlet snapshot (coarse-to-fine sync)
    // ============================================================================================
    /** Push the latest server-side Verlet shape for this rope. {@code interior} is the
     *  interleaved xyz triples of the server's interior nodes (length must be
     *  {@code (segments-1)*3}). Pass {@code segments <= 0} or null array to disable. */
    public void setServerNodes(long currentTick, int segments, float[] interior) {
        if (segments <= 0 || interior == null || interior.length != Math.max(0, segments - 1) * 3) {
            serverNodesSegments = 0;
            serverInterior = null;
            return;
        }
        serverNodesSegments = segments;
        serverInterior = interior;
        serverNodesRefreshTick = currentTick;
    }

    public void clearServerNodes() {
        serverNodesSegments = 0;
        serverInterior = null;
    }

    private boolean hasFreshServerNodes(long currentTick) {
        return serverNodesSegments > 0
                && serverInterior != null
                && (currentTick - serverNodesRefreshTick) <= SERVER_BLEND_STALE_TICKS;
    }

    /** Soft pull of every interior node toward its corresponding point on the server polyline.
     *  Called once per game tick; XPBD constraints subsequently smooth and re-tighten the chain. */
    private void applyServerNodeBlend(Vec3 a, Vec3 b, long currentTick) {
        if (!hasFreshServerNodes(currentTick)) return;
        int sSeg = serverNodesSegments;
        // Walk client interior nodes 1..nodes-2.
        for (int j = 1; j < nodes - 1; j++) {
            if (pinned[j]) continue;
            double tj = j / (double) segments;
            // Locate the server segment containing tj.
            double sPos = tj * sSeg;
            int sIdx = (int) Math.floor(sPos);
            if (sIdx < 0) sIdx = 0;
            if (sIdx > sSeg - 1) sIdx = sSeg - 1;
            double frac = sPos - sIdx;
            // Server polyline points: P0 = a, P1..P_{sSeg-1} = interior, P_{sSeg} = b.
            double p0x, p0y, p0z, p1x, p1y, p1z;
            if (sIdx == 0) {
                p0x = a.x; p0y = a.y; p0z = a.z;
            } else {
                int o = (sIdx - 1) * 3;
                p0x = serverInterior[o]; p0y = serverInterior[o + 1]; p0z = serverInterior[o + 2];
            }
            int next = sIdx + 1;
            if (next >= sSeg) {
                p1x = b.x; p1y = b.y; p1z = b.z;
            } else {
                int o = (next - 1) * 3;
                p1x = serverInterior[o]; p1y = serverInterior[o + 1]; p1z = serverInterior[o + 2];
            }
            double tx = p0x + (p1x - p0x) * frac;
            double ty = p0y + (p1y - p0y) * frac;
            double tz = p0z + (p1z - p0z) * frac;
            x[j] += (tx - x[j]) * SERVER_BLEND_ALPHA;
            y[j] += (ty - y[j]) * SERVER_BLEND_ALPHA;
            z[j] += (tz - z[j]) * SERVER_BLEND_ALPHA;
        }
    }

    /** Apply the contact as a soft pull on the segment containing {@code contactT}.
     *  Called once per game-tick from {@link #step}; XPBD distance constraints subsequently
     *  propagate the deformation along the rope. */
    private void applyExternalContactPush() {
        if (contactT < 0.0F) return;
        float ct = contactT < 0.0F ? 0.0F : (contactT > 1.0F ? 1.0F : contactT);
        int seg = (int) Math.floor(ct * segments);
        if (seg >= segments) seg = segments - 1;
        if (seg < 0) seg = 0;
        double frac = ct * segments - seg;
        int i = seg, j = seg + 1;
        double wi = 1.0D - frac, wj = frac;
        if (!pinned[i]) {
            x[i] += contactDx * wi * CONTACT_PUSH_GAIN;
            y[i] += contactDy * wi * CONTACT_PUSH_GAIN;
            z[i] += contactDz * wi * CONTACT_PUSH_GAIN;
        }
        if (!pinned[j]) {
            x[j] += contactDx * wj * CONTACT_PUSH_GAIN;
            y[j] += contactDy * wj * CONTACT_PUSH_GAIN;
            z[j] += contactDz * wj * CONTACT_PUSH_GAIN;
        }
    }

    // ============================================================================================
    // Main entry: legacy single-rope wrapper + full driver call
    // ============================================================================================
    /** Backwards-compatible single-rope step (no neighbours, no force fields, no entities). */
    public boolean stepUpTo(Level level, Vec3 a, Vec3 b, long currentTick) {
        return step(level, a, b, currentTick, List.of(), List.of(), List.of());
    }

    /**
     * Main-thread preparation for an upcoming parallel {@link #step}. Reads {@code level},
     * pre-fills {@link #blockCache} for the rope's bbox, refreshes own bounds and segment
     * AABBs, and snapshots {@code terrainNearby} / {@code blockHashChanged}. Once every sim
     * scheduled for this tick has been prepared, the driver flips
     * {@link #beginParallelPhase()} and submits {@code step} calls to a worker pool. Workers
     * never touch {@code level} thereafter.
     */
    public void preparePhysicsParallel(Level level, Vec3 a, Vec3 b, long currentTick) {
        // 1. Refresh self bounds + segment AABBs (workers read these on neighbours, write on self).
        updateBounds();
        refreshSegmentAabbs();
        // 1b. Take a complete tick-start snapshot of node positions so neighbour workers see a
        //     consistent view that does not change while we mutate our own x[]/y[]/z[] in step.
        if (snapX == null || snapX.length < nodes) {
            snapX = new double[nodes];
            snapY = new double[nodes];
            snapZ = new double[nodes];
        }
        System.arraycopy(x, 0, snapX, 0, nodes);
        System.arraycopy(y, 0, snapY, 0, nodes);
        System.arraycopy(z, 0, snapZ, 0, nodes);
        // 2. terrainNearby: side-effect resets blockCache and fills it for the proximity bbox.
        cachedTerrainNearby = hasTerrainNearby(level, a, b);
        // 3. Snapshot block-hash diff for this tick (mutates lastBlockHash exactly once).
        cachedBlockChanged = cachedTerrainNearby && !blockHashUnchanged(level, currentTick);
        // 4. Re-prefetch with a generous margin covering every level-touching query inside step
        //    (segment-vs-terrain capsule, node MTV, sweep test). Without this the cache only
        //    holds the proximity-only bbox from hasTerrainNearby which is too small.
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
     * Advance one logical tick. Driver supplies neighbours (already pre-filtered by bounds),
     * external force fields, and a list of nearby entity bounding boxes that push the rope.
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
        if (delta <= 0) return false;
        if (delta > 2) delta = 2;

        boolean endpointMoved = rememberEndpointsMoved(a, b);
        boolean terrainNearby;
        boolean blockHashChangedNow;
        if (precomputeReady) {
            terrainNearby = cachedTerrainNearby;
            blockHashChangedNow = cachedBlockChanged;
            // Workers must not call level.getBlockState; the main-thread prefetch covered the bbox.
            blockCache.setReadOnly(true);
        } else {
            terrainNearby = hasTerrainNearby(level, a, b);
            blockHashChangedNow = terrainNearby && !blockHashUnchanged(level, currentTick);
        }
        boolean terrainStateChanged = terrainNearby != terrainNearbyLast;
        terrainNearbyLast = terrainNearby;
        boolean blockChanged = terrainStateChanged || (terrainNearby && blockHashChangedNow);
        boolean neighborAwake = anyNeighborAwake(neighbors);
        // Entity overlap forces a wake-up: a frozen airborne rope would otherwise let the player
        // walk through it because no constraint loop runs at all.
        boolean entityNearby = !entityBoxes.isEmpty();
        boolean serverPullActive = hasFreshServerNodes(currentTick);
        boolean awake = endpointMoved || blockChanged || neighborAwake || entityNearby || !isSettled() || hasExternalContact(currentTick) || serverPullActive;
        if (endpointMoved || blockChanged || neighborAwake || entityNearby || hasExternalContact(currentTick) || serverPullActive) {
            settledTicks = 0;
        }

        // Snapshot tick-start positions for render lerp + settle measurement.
        for (int i = 0; i < nodes; i++) {
            xLastTick[i] = x[i]; yLastTick[i] = y[i]; zLastTick[i] = z[i];
        }

        if (!awake) {
            for (int i = 0; i < nodes; i++) {
                vx[i] = vy[i] = vz[i] = 0.0D;
            }
            lastSteppedTick = currentTick;
            renderStable = true;
            return false;
        }

        for (int t = 0; t < delta; t++) {
            applyExternalContactPush();
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
        if (precomputeReady) {
            blockCache.setReadOnly(false);
            precomputeReady = false;
        }
        return true;
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
            // In parallel mode the main-thread prefetch covers the whole step's bbox; clearing
            // through the (still warm) cache.
            if (!precomputeReady) blockCache.reset();
            detectAnchorBlocks(level);
        } else {
            clearAnchorColumns();
        }
        clearContactState();

        double dampingPerSubstep = Math.pow(tuning.damping(), h);
        for (int i = 0; i < nodes; i++) {
            xPrev[i] = x[i]; yPrev[i] = y[i]; zPrev[i] = z[i];
            if (pinned[i]) continue;
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
        for (int i = 0; i < segments; i++) lambdaDistance[i] = 0.0D;

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
        if (iterations < minPasses) iterations = minPasses;
        for (int it = 0; it < iterations; it++) {
            solveDistanceConstraints(targetLen, alphaTilde, (it & 1) == 0);
            if (terrainEnabled) solveTerrainConstraints(level);
            if (!neighbors.isEmpty()) solveRopeRopeConstraints(neighbors);
            if (!entityBoxes.isEmpty()) solveEntityConstraints(entityBoxes);
            pinEndpoints(a, b);
        }

        // 4. Reconstruct velocity from position delta
        for (int i = 0; i < nodes; i++) {
            if (pinned[i]) continue;
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
        if (!endpointInit) return 1;
        double daX = a.x - lastAx, daY = a.y - lastAy, daZ = a.z - lastAz;
        double dbX = b.x - lastBx, dbY = b.y - lastBy, dbZ = b.z - lastBz;
        double aSpeedSqr = daX * daX + daY * daY + daZ * daZ;
        double bSpeedSqr = dbX * dbX + dbY * dbY + dbZ * dbZ;
        double s2 = Math.max(aSpeedSqr, bSpeedSqr);
        if (s2 < SUBSTEP_SPEED_TIER1 * SUBSTEP_SPEED_TIER1) return 1;
        if (s2 < SUBSTEP_SPEED_TIER2 * SUBSTEP_SPEED_TIER2) return 2;
        return MAX_SUBSTEPS;
    }

    // ============================================================================================
    // Constraint: distance (XPBD)
    // ============================================================================================
    private void solveDistanceConstraints(double targetLen, double alphaTilde, boolean forward) {
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
    // Constraint: terrain (block AABBs)
    // ============================================================================================
    private boolean hasTerrainNearby(Level level, Vec3 a, Vec3 b) {
        updateBounds();
        blockCache.reset();
        double r = TERRAIN_RADIUS + COLLISION_EPS + TERRAIN_PROXIMITY_MARGIN;
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

    private void solveTerrainConstraints(Level level) {
        for (int i = 1; i < nodes - 1; i++) {
            resolveNodeAgainstTerrain(level, i);
        }
        // a block (e.g. one on top, one beside), the straight line connecting them can still cut
        // through the block's edge. Sampling the segment as a capsule and pushing it out fixes the
        // visual "rope clipping into the block" without re-introducing the old corner jitter,
        // because the same spherical normal as the node pass is used.
        for (int i = 0; i < segments; i++) {
            resolveSegmentAgainstTerrain(level, i, i + 1);
        }
        // Tunneling protection: run a true segment sweep when an endpoint moved fast enough to
        // potentially skip past a whole block in a single substep.
        final double tunnelThresholdSqr = 0.25D; // 0.5 block movement
        for (int i = 0; i < segments; i++) {
            int a = i, b = i + 1;
            double dax = x[a] - xPrev[a], day = y[a] - yPrev[a], daz = z[a] - zPrev[a];
            double dbx = x[b] - xPrev[b], dby = y[b] - yPrev[b], dbz = z[b] - zPrev[b];
            double maxMoveSqr = Math.max(
                    dax * dax + day * day + daz * daz,
                    dbx * dbx + dby * dby + dbz * dbz);
            if (maxMoveSqr < tunnelThresholdSqr) continue;
            resolveSegmentSweep(level, a, b);
        }
    }

    /** Treat segment [a,b] as a capsule of radius ROPE_RADIUS and push it out of any block AABB
     *  it currently overlaps. The push direction comes from the closest-point-on-AABB normal,
     *  identical to the node pass, so corner contacts use a smooth diagonal normal. */
    private void resolveSegmentAgainstTerrain(Level level, int a, int b) {
        if (pinned[a] && pinned[b]) return;
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        double r = TERRAIN_RADIUS + COLLISION_EPS;
        int bxMin = (int) Math.floor(Math.min(ax, bx) - r) - 1;
        int bxMax = (int) Math.floor(Math.max(ax, bx) + r) + 1;
        int byMin = (int) Math.floor(Math.min(ay, by) - r) - 1;
        int byMax = (int) Math.floor(Math.max(ay, by) + r) + 1;
        int bzMin = (int) Math.floor(Math.min(az, bz) - r) - 1;
        int bzMax = (int) Math.floor(Math.max(az, bz) + r) + 1;
        for (int cx = bxMin; cx <= bxMax; cx++) {
            for (int cy = byMin; cy <= byMax; cy++) {
                for (int cz = bzMin; cz <= bzMax; cz++) {
                    if (isAnchorColumn(cx, cy, cz)) continue;
                    for (AABB box : blockCache.aabbsAt(level, cx, cy, cz)) {
                        pushSegmentOutOfBox(a, b, box, TERRAIN_RADIUS + COLLISION_EPS);
                    }
                }
            }
        }
    }

    /** Find the point on segment [a,b] closest to {@code box}; if that point is within the
     *  capsule radius of the box, push both endpoints (weighted by 1-s and s) along the contact
     *  normal so the whole capsule slides out. */
    private void pushSegmentOutOfBox(int a, int b, AABB box, double radius) {
        double ax = x[a], ay = y[a], az = z[a];
        double bx = x[b], by = y[b], bz = z[b];
        // Iterative closest-point: alternate (clamp on segment) and (clamp on AABB) a few times.
        // Converges very fast for axis-aligned boxes; 4 iterations is overkill but cheap.
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double segLenSqr = ux * ux + uy * uy + uz * uz;
        double s;
        double cpx, cpy, cpz; // closest point on box
        double spx, spy, spz; // closest point on segment
        if (segLenSqr < 1.0e-12D) {
            s = 0.0D;
            spx = ax; spy = ay; spz = az;
        } else {
            // initial s = projection of box centre on segment
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
            if (segLenSqr < 1.0e-12D) {
                spx = ax; spy = ay; spz = az;
                break;
            }
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
        } else {
            // Segment closest point is on the box surface or inside; push upward as a safe default.
            pushLen = radius;
            nx = 0.0D; ny = 1.0D; nz = 0.0D;
        }
        // Distribute correction. For a capsule the contact authority at parameter s is split
        // (1-s) to endpoint a and s to endpoint b. We scale by 1/(w_a*(1-s)^2 + w_b*s^2) so the
        // closest point actually moves by pushLen, matching the node pass behaviour.
        double wa = pinned[a] ? 0.0D : 1.0D;
        double wb = pinned[b] ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - s;
        double denom = wa * oneMinusS * oneMinusS + wb * s * s;
        if (denom < 1.0e-9D) return;
        double k = pushLen / denom;
        // Per-endpoint magnitude cap. The geometric scaling above is correct, but when one end
        // is pinned and the contact sits near that end (small effective lever arm), the free
        // followed by the distance constraint yanking the rope back. Capping the per-step move
        // to 2x pushLen sacrifices nothing physical (the next iteration finishes the resolve)
        // and kills the snap cleanly.
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

    private void resolveNodeAgainstTerrain(Level level, int node) {
        // Up to 2 passes: a node pushed out of one box may end up in an adjacent inflated box.
        for (int pass = 0; pass < 2; pass++) {
            boolean moved = false;
            int bxMin = (int) Math.floor(x[node] - TERRAIN_RADIUS) - 1;
            int bxMax = (int) Math.floor(x[node] + TERRAIN_RADIUS) + 1;
            int byMin = (int) Math.floor(y[node] - TERRAIN_RADIUS) - 1;
            int byMax = (int) Math.floor(y[node] + TERRAIN_RADIUS) + 1;
            int bzMin = (int) Math.floor(z[node] - TERRAIN_RADIUS) - 1;
            int bzMax = (int) Math.floor(z[node] + TERRAIN_RADIUS) + 1;
            for (int bx = bxMin; bx <= bxMax; bx++) {
                for (int by = byMin; by <= byMax; by++) {
                    for (int bz = bzMin; bz <= bzMax; bz++) {
                        AABB[] boxes = blockCache.aabbsAt(level, bx, by, bz);
                        if (isAnchorColumn(bx, by, bz)) continue;
                        for (AABB box : boxes) {
                            if (projectNodeOutOfBox(level, node, box)) moved = true;
                        }
                    }
                }
            }
            if (!moved) return;
        }
    }

    private boolean projectNodeOutOfBox(Level level, int node, AABB original) {
        // Treat the rope node as a sphere of radius ROPE_RADIUS and the block as the original
        // (un-inflated) AABB. The contact normal is the unit vector from the closest point on the
        // AABB to the node. Faces give axis-aligned normals; edges and corners give the proper
        // diagonal normal, so a rope draped over a block edge wraps smoothly instead of having
        // each node snap to a different face (which used to produce visible kinks at corners).
        double px = x[node], py = y[node], pz = z[node];
        double cpx = px < original.minX ? original.minX : (px > original.maxX ? original.maxX : px);
        double cpy = py < original.minY ? original.minY : (py > original.maxY ? original.maxY : py);
        double cpz = pz < original.minZ ? original.minZ : (pz > original.maxZ ? original.maxZ : pz);
        double dx = px - cpx, dy = py - cpy, dz = pz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;
        double radius = TERRAIN_RADIUS + COLLISION_EPS;
        if (d2 > 1.0e-12D) {
            if (d2 >= radius * radius) return false;
            double d = Math.sqrt(d2);
            double pushLen = radius - d;
            double inv = 1.0D / d;
            applyTerrainCorrection(node, dx * inv * pushLen, dy * inv * pushLen, dz * inv * pushLen);
            return true;
        }
        // Strictly inside the block volume (e.g. tunnelled in). Escape via the smallest axis of
        // the inflated box, falling back to an upward push if every direction lands inside another
        // block.
        AABB box = original.inflate(TERRAIN_RADIUS);
        double dxMin = box.minX - COLLISION_EPS - px;
        double dxMax = box.maxX + COLLISION_EPS - px;
        double dyMin = box.minY - COLLISION_EPS - py;
        double dyMax = box.maxY + COLLISION_EPS - py;
        double dzMin = box.minZ - COLLISION_EPS - pz;
        double dzMax = box.maxZ + COLLISION_EPS - pz;
        double[] candDelta = {dxMin, dxMax, dyMin, dyMax, dzMin, dzMax};
        int[] candAxis    = {0, 0, 1, 1, 2, 2};
        sortBySmallestAbs(candDelta, candAxis);
        for (int k = 0; k < 6; k++) {
            double delta = candDelta[k];
            int axis = candAxis[k];
            double tx = px, ty = py, tz = pz;
            if (axis == 0) tx += delta;
            else if (axis == 1) ty += delta;
            else tz += delta;
            if (!isInsideAnyInflatedBox(level, tx, ty, tz)) {
                if (axis == 0) applyTerrainCorrection(node, delta, 0.0D, 0.0D);
                else if (axis == 1) applyTerrainCorrection(node, 0.0D, delta, 0.0D);
                else applyTerrainCorrection(node, 0.0D, 0.0D, delta);
                return true;
            }
        }
        applyTerrainCorrection(node, 0.0D, COLLISION_EPS + box.maxY - py, 0.0D);
        return true;
    }

    private static void sortBySmallestAbs(double[] vals, int[] axes) {
        for (int i = 0; i < 5; i++) {
            int min = i;
            for (int j = i + 1; j < 6; j++) {
                if (Math.abs(vals[j]) < Math.abs(vals[min])) min = j;
            }
            if (min != i) {
                double tv = vals[i]; vals[i] = vals[min]; vals[min] = tv;
                int ta = axes[i]; axes[i] = axes[min]; axes[min] = ta;
            }
        }
    }

    private boolean isInsideAnyInflatedBox(Level level, double wx, double wy, double wz) {
        int bxMin = (int) Math.floor(wx - TERRAIN_RADIUS) - 1;
        int bxMax = (int) Math.floor(wx + TERRAIN_RADIUS) + 1;
        int byMin = (int) Math.floor(wy - TERRAIN_RADIUS) - 1;
        int byMax = (int) Math.floor(wy + TERRAIN_RADIUS) + 1;
        int bzMin = (int) Math.floor(wz - TERRAIN_RADIUS) - 1;
        int bzMax = (int) Math.floor(wz + TERRAIN_RADIUS) + 1;
        for (int bx = bxMin; bx <= bxMax; bx++) {
            for (int by = byMin; by <= byMax; by++) {
                for (int bz = bzMin; bz <= bzMax; bz++) {
                    if (isAnchorColumn(bx, by, bz)) continue;
                    for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
                        if (RopeMath.containsInclusive(box.inflate(TERRAIN_RADIUS), wx, wy, wz)) return true;
                    }
                }
            }
        }
        return false;
    }

    private void resolveSegmentSweep(Level level, int a, int b) {
        double fx = x[a], fy = y[a], fz = z[a];
        double tx = x[b], ty = y[b], tz = z[b];
        double r = TERRAIN_RADIUS + COLLISION_EPS;
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
                    if (isAnchorColumn(bx, by, bz)) continue;
                    for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
                        AABB inflated = box.inflate(TERRAIN_RADIUS);
                        if (RopeMath.intersectSegmentAabb(fx, fy, fz, tx, ty, tz, inflated,
                                SEGMENT_CORNER_PUSH_EPS, SEGMENT_TOP_SUPPORT_EPS, hitScratch)) {
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
        if (bestT == Double.POSITIVE_INFINITY) return;
        // Push by MTV without amplification. The previous (1/w) scale up to 3x was meant to
        // fully resolve a contact in one shot when one endpoint was pinned, but it overshoots
        // and fights the distance constraint, producing visible single-rope corner jitter.
        // The 8 constraint iterations + sweep re-running each iteration converge fine without
        // the amplification.
        if (!pinned[a]) {
            applyTerrainCorrection(a, bestDx, bestDy, bestDz);
        }
        if (!pinned[b]) {
            applyTerrainCorrection(b, bestDx, bestDy, bestDz);
        }
    }

    private int anchorAColX = Integer.MIN_VALUE, anchorAColY = 0, anchorAColZ = 0;
    private int anchorBColX = Integer.MIN_VALUE, anchorBColY = 0, anchorBColZ = 0;

    private void detectAnchorBlocks(Level level) {
        clearAnchorColumns();
        int last = nodes - 1;
        detectAnchorAt(level, x[0], y[0], z[0], true);
        detectAnchorAt(level, x[last], y[last], z[last], false);
    }

    private void clearAnchorColumns() {
        anchorAColX = Integer.MIN_VALUE;
        anchorBColX = Integer.MIN_VALUE;
    }

    private void detectAnchorAt(Level level, double px, double py, double pz, boolean isA) {
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        for (AABB box : blockCache.aabbsAt(level, bx, by, bz)) {
            if (strictlyContains(box, px, py, pz)) {
                if (isA) { anchorAColX = bx; anchorAColY = by; anchorAColZ = bz; }
                else     { anchorBColX = bx; anchorBColY = by; anchorBColZ = bz; }
                return;
            }
        }
    }

    private boolean isAnchorColumn(int bx, int by, int bz) {
        if (anchorAColX != Integer.MIN_VALUE
                && bx == anchorAColX && bz == anchorAColZ
                && (by == anchorAColY || by == anchorAColY - 1)) return true;
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

    // ============================================================================================
    // Constraint: rope-rope (with soft layer bias)
    // ============================================================================================
    private void solveRopeRopeConstraints(List<RopeSimulation> neighbors) {
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
    private void refreshSegmentAabbs() {
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
    private void solveEntityConstraints(List<AABB> entityBoxes) {
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

    private double endpointFade(int segment, double t) {
        double ropeT = (segment + t) / segments;
        return Math.min(1.0D, Math.min(ropeT, 1.0D - ropeT) / ENDPOINT_FADE);
    }

    // ============================================================================================
    // Correction primitives + endpoint pinning
    // ============================================================================================
    private void applyCorrection(int i, double dx, double dy, double dz) {
        if (pinned[i]) return;
        if (supportNode[i] && dy < 0.0D) dy *= SUPPORT_DOWN_INV_MASS;
        x[i] += dx; y[i] += dy; z[i] += dz;
        markBoundsDirty();
    }

    private void applyTerrainCorrection(int i, double dx, double dy, double dz) {
        if (pinned[i]) return;
        x[i] += dx; y[i] += dy; z[i] += dz;
        contactNode[i] = true;
        if (dy > 0.0D) supportNode[i] = true;
        markBoundsDirty();
    }

    private void pinEndpoints(Vec3 a, Vec3 b) {
        x[0] = a.x; y[0] = a.y; z[0] = a.z;
        x[nodes - 1] = b.x; y[nodes - 1] = b.y; z[nodes - 1] = b.z;
    }

    private void clearContactState() {
        for (int i = 0; i < nodes; i++) {
            contactNode[i] = false;
            supportNode[i] = false;
            entityPushAccum[i] *= 0.85D;
            if (entityPushAccum[i] < 1.0e-4D) entityPushAccum[i] = 0.0D;
        }
    }

    // ============================================================================================
    // Endpoint tracking, slack, awake propagation
    // ============================================================================================
    private boolean rememberEndpointsMoved(Vec3 a, Vec3 b) {
        if (!endpointInit) {
            rememberEndpoints(a, b);
            endpointInit = true;
            return false;
        }
        double daX = a.x - lastAx, daY = a.y - lastAy, daZ = a.z - lastAz;
        double dbX = b.x - lastBx, dbY = b.y - lastBy, dbZ = b.z - lastBz;
        boolean moved = daX * daX + daY * daY + daZ * daZ
                + dbX * dbX + dbY * dbY + dbZ * dbZ > ENDPOINT_WAKE_DISTANCE_SQR;
        rememberEndpoints(a, b);
        return moved;
    }

    private void rememberEndpoints(Vec3 a, Vec3 b) {
        lastAx = a.x; lastAy = a.y; lastAz = a.z;
        lastBx = b.x; lastBy = b.y; lastBz = b.z;
    }

    private double slackFactor(Vec3 a, Vec3 b) {
        // With no gravity the physically expected zone-preset behaviour is a taut straight rope.
        // Keeping slack > 1 here would force the solver to invent sideways/downward bends just to
        // spend the extra length, making a gravity=0 preset still look sagged.
        if (Math.abs(tuning.gravity()) < 1.0e-9D) return 1.0D;
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double dist = a.distanceTo(b);
        if (dist < 1e-6) return 1.0D;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double t = Math.min(1.0D, horizontal / (dist * 0.45D));
        double maxSlackFactor = tight ? tuning.slackTight() : tuning.slackLoose();
        return 1.0D + (maxSlackFactor - 1.0D) * t;
    }

    private boolean anyNeighborAwake(List<RopeSimulation> neighbors) {
        for (int i = 0; i < neighbors.size(); i++) {
            RopeSimulation n = neighbors.get(i);
            if (n == this) continue;
            if (!n.isSettled() && boundsOverlap(n, ROPE_REPEL_DISTANCE)) return true;
        }
        return false;
    }

    private void updateSettleState() {
        double maxMotionSqr = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - xLastTick[i];
            double dy = y[i] - yLastTick[i];
            double dz = z[i] - zLastTick[i];
            double m = dx * dx + dy * dy + dz * dz;
            if (m > maxMotionSqr) maxMotionSqr = m;
        }
        if (maxMotionSqr < SETTLE_MOTION_SQR) settledTicks++;
        else settledTicks = 0;
        if (maxMotionSqr < SETTLE_MOTION_SQR) quietTicks++;
        else quietTicks = 0;
    }

    private boolean blockHashUnchanged(Level level, long currentTick) {
        if (blockHashInit && isSettled()
                && lastBlockHashCheckTick != UNINIT
                && currentTick - lastBlockHashCheckTick < SETTLED_BLOCK_HASH_INTERVAL_TICKS) {
            return true;
        }
        lastBlockHashCheckTick = currentTick;
        long h = computeBlockHash(level);
        if (!blockHashInit) {
            lastBlockHash = h;
            blockHashInit = true;
            return true;
        }
        if (h == lastBlockHash) return true;
        lastBlockHash = h;
        return false;
    }

    private long computeBlockHash(Level level) {
        updateBounds();
        int bx0 = (int) Math.floor(minX - ROPE_RADIUS) - 1;
        int bx1 = (int) Math.floor(maxX + ROPE_RADIUS) + 1;
        int by0 = (int) Math.floor(minY - ROPE_RADIUS) - 1;
        int by1 = (int) Math.floor(maxY + ROPE_RADIUS) + 1;
        int bz0 = (int) Math.floor(minZ - ROPE_RADIUS) - 1;
        int bz1 = (int) Math.floor(maxZ + ROPE_RADIUS) + 1;
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

    private void markBoundsDirty() {
        boundsDirty = true;
        renderCacheValid = false;
        frameScratchValid = false;
        visOcclusionFrame = Long.MIN_VALUE;
        segVisAllVisible = true;
        segVisBitCount = 0;
        bakedValid = false;
    }

    // Render-side scratch accessors --------------------------------------------------------------
    public double[] frameSideX() { return frameSideX; }
    public double[] frameSideY() { return frameSideY; }
    public double[] frameSideZ() { return frameSideZ; }
    public double[] frameUpX() { return frameUpX; }
    public double[] frameUpY() { return frameUpY; }
    public double[] frameUpZ() { return frameUpZ; }

    /**
     * Lazily allocate / validate per-frame basis-vector scratch arrays. Returns {@code true} if
     * the caller must (re)build them, {@code false} if the previously-built scratch is still
     * valid for these parameters and can be reused.
     */
    public boolean acquireFrameScratch(double baseThickness, int pulsesHash) {
        if (frameSideX == null || frameSideX.length < nodes) {
            frameSideX = new double[nodes]; frameSideY = new double[nodes]; frameSideZ = new double[nodes];
            frameUpX = new double[nodes];   frameUpY = new double[nodes];   frameUpZ = new double[nodes];
            frameScratchValid = false;
        }
        if (frameScratchValid
                && frameScratchThickness == baseThickness
                && frameScratchPulsesHash == pulsesHash) {
            return false;
        }
        frameScratchThickness = baseThickness;
        frameScratchPulsesHash = pulsesHash;
        frameScratchValid = true;
        return true;
    }

    // Render-side occlusion cache accessors ------------------------------------------------------
    public long visOcclusionFrame() { return visOcclusionFrame; }
    public boolean visOcclusionResult() { return visOcclusionResult; }
    public void setVisOcclusionCache(long frame, boolean result) {
        this.visOcclusionFrame = frame;
        this.visOcclusionResult = result;
    }

    /**
     * Reset the per-segment visibility mask to "all visible" and ensure the backing array can
     * hold {@code segmentCount} bits. Call before populating with {@link #setSegmentVisible}.
     */
    public void beginSegmentVisibility(int segmentCount) {
        segVisBitCount = segmentCount;
        segVisAllVisible = true;
        if (segmentCount <= 0) {
            return;
        }
        int words = (segmentCount + 63) >>> 6;
        if (segVisMask == null || segVisMask.length < words) {
            segVisMask = new long[words];
        } else {
            for (int i = 0; i < words; i++) segVisMask[i] = 0L;
        }
    }

    /** Mark segment {@code s} (0-based) as visible/hidden. */
    public void setSegmentVisible(int s, boolean visible) {
        if (s < 0 || s >= segVisBitCount) return;
        if (visible) {
            segVisMask[s >>> 6] |= 1L << (s & 63);
        } else {
            segVisAllVisible = false;
        }
    }

    /** True if every segment in the current mask is visible (or no mask was computed). */
    public boolean segVisAllVisible() { return segVisAllVisible; }

    /** True if segment {@code s} is visible. Returns true when no mask is active. */
    public boolean isSegmentVisible(int s) {
        if (segVisAllVisible) return true;
        if (s < 0 || s >= segVisBitCount) return true;
        return (segVisMask[s >>> 6] & (1L << (s & 63))) != 0L;
    }

    // Static baked vertex cache (Pillar A) ------------------------------------------------------
    /** Returns true if the cache is populated and matches the supplied key. The cache is
     *  auto-invalidated by {@link #markBoundsDirty()} (called at the end of any non-skipped
     *  {@code step()}), so cache-hit always means "positions, light and material identical
     *  to the last bake". */
    public boolean tryUseBake(int nodeCountKey, boolean ribbonLodKey,
            int blockA, int blockB, int skyA, int skyB,
            int kindOrdinal, boolean powered, int tier, int pulsesHash, int cameraBin) {
        return bakedValid
                && renderStable
                && bakedNodeCount == nodeCountKey
                && bakedRibbonLod == ribbonLodKey
                && bakedBlockA == blockA && bakedBlockB == blockB
                && bakedSkyA == skyA && bakedSkyB == skyB
                && bakedKindOrdinal == kindOrdinal
                && bakedPowered == powered
                && bakedTier == tier
                && bakedPulsesHash == pulsesHash
                && bakedCameraBin == cameraBin;
    }

    /** Begin a fresh bake. Re-allocates only when the current arrays are too small. */
    public void beginBake(int expectedVertices) {
        if (bakedX == null || bakedX.length < expectedVertices) {
            int n = Math.max(64, expectedVertices);
            bakedX = new float[n];
            bakedY = new float[n];
            bakedZ = new float[n];
            bakedColor = new int[n];
            bakedLight = new int[n];
        }
        int expectedSegs = Math.max(8, expectedVertices / 16 + 4);
        if (bakedSegMidX == null || bakedSegMidX.length < expectedSegs) {
            bakedSegMidX = new float[expectedSegs];
            bakedSegMidY = new float[expectedSegs];
            bakedSegMidZ = new float[expectedSegs];
            bakedSegSideX = new float[expectedSegs];
            bakedSegSideY = new float[expectedSegs];
            bakedSegSideZ = new float[expectedSegs];
            bakedSegUpX = new float[expectedSegs];
            bakedSegUpY = new float[expectedSegs];
            bakedSegUpZ = new float[expectedSegs];
        }
        bakedCount = 0;
        bakedSegmentCount = 0;
        bakedValid = false;
    }

    /** Append per-segment frame info before that segment's 16 verts are written. */
    public void appendBakedSegment(double mx, double my, double mz,
            double sx, double sy, double sz,
            double ux, double uy, double uz) {
        if (bakedSegmentCount >= bakedSegMidX.length) {
            int n = bakedSegMidX.length * 2;
            bakedSegMidX = java.util.Arrays.copyOf(bakedSegMidX, n);
            bakedSegMidY = java.util.Arrays.copyOf(bakedSegMidY, n);
            bakedSegMidZ = java.util.Arrays.copyOf(bakedSegMidZ, n);
            bakedSegSideX = java.util.Arrays.copyOf(bakedSegSideX, n);
            bakedSegSideY = java.util.Arrays.copyOf(bakedSegSideY, n);
            bakedSegSideZ = java.util.Arrays.copyOf(bakedSegSideZ, n);
            bakedSegUpX = java.util.Arrays.copyOf(bakedSegUpX, n);
            bakedSegUpY = java.util.Arrays.copyOf(bakedSegUpY, n);
            bakedSegUpZ = java.util.Arrays.copyOf(bakedSegUpZ, n);
        }
        int i = bakedSegmentCount++;
        bakedSegMidX[i] = (float) mx;
        bakedSegMidY[i] = (float) my;
        bakedSegMidZ[i] = (float) mz;
        bakedSegSideX[i] = (float) sx;
        bakedSegSideY[i] = (float) sy;
        bakedSegSideZ[i] = (float) sz;
        bakedSegUpX[i] = (float) ux;
        bakedSegUpY[i] = (float) uy;
        bakedSegUpZ[i] = (float) uz;
    }

    /** Append one vertex (world-coord) to the bake stream. */
    public void appendBakedVertex(double wx, double wy, double wz, int color, int light) {
        if (bakedCount >= bakedX.length) {
            int n = bakedX.length * 2;
            bakedX = java.util.Arrays.copyOf(bakedX, n);
            bakedY = java.util.Arrays.copyOf(bakedY, n);
            bakedZ = java.util.Arrays.copyOf(bakedZ, n);
            bakedColor = java.util.Arrays.copyOf(bakedColor, n);
            bakedLight = java.util.Arrays.copyOf(bakedLight, n);
        }
        bakedX[bakedCount] = (float) wx;
        bakedY[bakedCount] = (float) wy;
        bakedZ[bakedCount] = (float) wz;
        bakedColor[bakedCount] = color;
        bakedLight[bakedCount] = light;
        bakedCount++;
    }

    /** Finalise bake by stamping the cache key fields. */
    public void completeBake(int nodeCountKey, boolean ribbonLodKey,
            int blockA, int blockB, int skyA, int skyB,
            int kindOrdinal, boolean powered, int tier, int pulsesHash, int cameraBin) {
        bakedNodeCount = nodeCountKey;
        bakedRibbonLod = ribbonLodKey;
        bakedBlockA = blockA; bakedBlockB = blockB;
        bakedSkyA = skyA; bakedSkyB = skyB;
        bakedKindOrdinal = kindOrdinal;
        bakedPowered = powered;
        bakedTier = tier;
        bakedPulsesHash = pulsesHash;
        bakedCameraBin = cameraBin;
        bakedValid = true;
    }

    public int bakedCount() { return bakedCount; }
    public float[] bakedX() { return bakedX; }
    public float[] bakedY() { return bakedY; }
    public float[] bakedZ() { return bakedZ; }
    public int[] bakedColor() { return bakedColor; }
    public int[] bakedLight() { return bakedLight; }
    public int bakedSegmentCount() { return bakedSegmentCount; }
    public float[] bakedSegMidX() { return bakedSegMidX; }
    public float[] bakedSegMidY() { return bakedSegMidY; }
    public float[] bakedSegMidZ() { return bakedSegMidZ; }
    public float[] bakedSegSideX() { return bakedSegSideX; }
    public float[] bakedSegSideY() { return bakedSegSideY; }
    public float[] bakedSegSideZ() { return bakedSegSideZ; }
    public float[] bakedSegUpX() { return bakedSegUpX; }
    public float[] bakedSegUpY() { return bakedSegUpY; }
    public float[] bakedSegUpZ() { return bakedSegUpZ; }

    private void updateBounds() {
        if (!boundsDirty) return;
        double nx0 = x[0], nx1 = x[0];
        double ny0 = y[0], ny1 = y[0];
        double nz0 = z[0], nz1 = z[0];
        for (int i = 1; i < nodes; i++) {
            double vx = x[i]; if (vx < nx0) nx0 = vx; else if (vx > nx1) nx1 = vx;
            double vy = y[i]; if (vy < ny0) ny0 = vy; else if (vy > ny1) ny1 = vy;
            double vz = z[i]; if (vz < nz0) nz0 = vz; else if (vz > nz1) nz1 = vz;
        }
        minX = nx0; maxX = nx1; minY = ny0; maxY = ny1; minZ = nz0; maxZ = nz1;
        boundsDirty = false;
    }

    public AABB currentBounds() {
        updateBounds();
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
