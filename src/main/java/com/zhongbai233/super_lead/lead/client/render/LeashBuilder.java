package com.zhongbai233.super_lead.lead.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Render-thread rope mesh builder and batching queue.
 *
 * <p>
 * The class owns the hot path from {@link RopeJob} to emitted vertices. Keep
 * allocations and abstraction overhead low here; helper extraction should focus
 * on stateless math/color/baked-emission routines that do not add per-segment
 * objects.
 */
public final class LeashBuilder {
    public static final int NO_HIGHLIGHT = 0;
    public static final int DEFAULT_HIGHLIGHT = 0x66FFEE84;

    private static final double HIGHLIGHT_RIBBON_DISTANCE_SQR = 24.0D * 24.0D;
    private static final double STEEP_RENDER_VERTICALITY = 0.984807753012208D; // sin(80deg)
    private static final double SHARP_JOINT_DOT = 0.7071067811865476D; // cos(45deg)

    private static double halfThickness(RopeTuning tuning) {
        return effectiveTuning(tuning).halfThickness();
    }

    private static double highlightHalfThickness(RopeTuning tuning) {
        return halfThickness(tuning) * 2.45D;
    }

    private static double ribbonHalfWidth(RopeTuning tuning) {
        RopeTuning effective = effectiveTuning(tuning);
        return effective.halfThickness() * effective.ribbonWidthFactor();
    }

    private static double highlightRibbonHalfWidth(RopeTuning tuning) {
        return highlightHalfThickness(tuning) * 1.35D;
    }

    private static RopeTuning effectiveTuning(RopeTuning tuning) {
        if (tuning != null) {
            return tuning;
        }
        return activeColorTuning != null ? activeColorTuning : RopeTuning.localDefaults();
    }

    private static double ribbonLodDistanceSqr() {
        double d = ClientTuning.LOD_RIBBON_DISTANCE.get();
        return d * d;
    }

    private static double stride2DistanceSqr() {
        double d = ClientTuning.LOD_STRIDE2_DISTANCE.get();
        return d * d;
    }

    private static double stride4DistanceSqr() {
        double d = ClientTuning.LOD_STRIDE4_DISTANCE.get();
        return d * d;
    }

    private static final float[] EMPTY_PULSES = new float[0];
    private static final double PULSE_SIGMA = 0.07D;
    private static final double PULSE_AMPLITUDE = 1.6D;
    private static final double FIRST_SEGMENT_AMPLITUDE = 0.9D;
    private static final double FIRST_SEGMENT_LENGTH = 0.45D;

    private LeashBuilder() {
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            boolean highlighted) {
        submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlighted ? DEFAULT_HIGHLIGHT : NO_HIGHLIGHT, LeadKind.NORMAL, false, 0);
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            boolean highlighted,
            LeadKind kind,
            boolean powered) {
        submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlighted ? DEFAULT_HIGHLIGHT : NO_HIGHLIGHT, kind, powered, 0);
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier) {
        submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlightColor, kind, powered, tier, EMPTY_PULSES, 0);
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        java.util.List<RopeJob> single = java.util.List.of(new RopeJob(
                sim, blockA, blockB, skyA, skyB, highlightColor, kind, powered, tier,
                pulsePositions, extractEnd));
        flush(collector, cameraPos, partialTick, single);
    }

    public static RopeJob collect(
            RopeSimulation sim,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        return collect(sim, blockA, blockB, skyA, skyB, highlightColor,
                kind, powered, tier, pulsePositions, extractEnd, false);
    }

    public static RopeJob collect(
            RopeSimulation sim,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd,
            boolean chunkMeshActive) {
        return new RopeJob(sim, blockA, blockB, skyA, skyB, highlightColor,
                kind, powered, tier, pulsePositions, extractEnd, chunkMeshActive);
    }

    private static final PoseStack BATCH_POSE = new PoseStack();
    /**
     * Render-thread-only. When non-null, all subsequent vertex() calls are diverted
     * into this
     * sim's baked vertex stream instead of being written to the VertexConsumer.
     * World coords
     * are baked because cameraPos is forced to origin during bake.
     */
    private static RopeSimulation activeBakeSim = null;
    private static RopeTuning activeColorTuning = null;
    private static double bakeCamOffsetX, bakeCamOffsetY, bakeCamOffsetZ;

    // Diagnostic counters (reset by the caller's per-frame stat collector). Useful
    // for
    // verifying whether the static bake cache is actually engaging in stress tests.
    public static int cacheHits;
    public static int cacheMisses;
    public static int verticesEmitted;

    public static void resetStats() {
        cacheHits = 0;
        cacheMisses = 0;
        verticesEmitted = 0;
    }

    /**
     * Submit all collected rope jobs in a single {@code submitCustomGeometry} call.
     * Collapsing
     * N per-rope submissions into one removes per-call render-node bookkeeping
     * (sort key, lambda
     * dispatch, pose snapshot) and lets the driver upload one large vertex stream.
     */
    public static void flush(SubmitNodeCollector collector, Vec3 cameraPos, float partialTick,
            java.util.List<RopeJob> jobs) {
        if (jobs.isEmpty())
            return;
        // Pre-prepare every sim's render snapshot OUTSIDE the lambda so prepareRender
        // side
        // effects (basis-vector scratch invalidation) happen once per rope per frame.
        double[] totalLengths = new double[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            totalLengths[i] = Math.max(1.0e-6D, jobs.get(i).sim.prepareRender(partialTick));
        }
        collector.submitCustomGeometry(BATCH_POSE, RenderTypes.textBackground(), (poseState, buffer) -> {
            for (int i = 0; i < jobs.size(); i++) {
                renderJob(buffer, poseState, cameraPos, jobs.get(i), totalLengths[i]);
            }
        });
    }

    private static void renderJob(VertexConsumer buffer, PoseStack.Pose poseState, Vec3 cameraPos, RopeJob job,
            double totalLength) {
        RopeTuning previousTuning = activeColorTuning;
        RopeTuning tuning = job.sim.tuning();
        activeColorTuning = tuning;
        try {
            renderJobWithPalette(buffer, poseState, cameraPos, job, totalLength, tuning);
        } finally {
            activeColorTuning = previousTuning;
        }
    }

    private static void renderJobWithPalette(VertexConsumer buffer, PoseStack.Pose poseState, Vec3 cameraPos,
            RopeJob job, double totalLength, RopeTuning tuning) {
        RopeSimulation sim = job.sim;
        boolean glow = job.powered && (job.kind == LeadKind.REDSTONE || job.kind == LeadKind.ENERGY);
        int effectiveBlockA = glow ? 15 : job.blockA;
        int effectiveBlockB = glow ? 15 : job.blockB;

        int nodeCount = sim.nodeCount();
        int mid = nodeCount / 2;
        double midDx = sim.renderX(mid) - cameraPos.x;
        double midDy = sim.renderY(mid) - cameraPos.y;
        double midDz = sim.renderZ(mid) - cameraPos.z;
        double midDistSqr = midDx * midDx + midDy * midDy + midDz * midDz;
        // Global "flat 2D" toggle for low-end machines: forces the cheap ribbon path
        // regardless
        // of distance, mirroring vanilla's flat-leash look. Skips the static-bake fast
        // path
        // because ribbon is already only a few verts per segment.
        boolean force2D = !ClientTuning.MODE_RENDER3D.get();
        boolean ribbonLod = force2D || midDistSqr > ribbonLodDistanceSqr();
        boolean cheapHighlight = ribbonLod || midDistSqr > HIGHLIGHT_RIBBON_DISTANCE_SQR;
        // Pick render stride: 1 close, 2 medium, 4 far. Caps so ropes always have at
        // least 4
        // segments rendered (otherwise sharp ropes look like polylines).
        int stride = midDistSqr > stride4DistanceSqr() ? 4
                : midDistSqr > stride2DistanceSqr() ? 2
                        : 1;
        if (stride > 1) {
            int maxStride = Math.max(1, (nodeCount - 1) / 4);
            if (stride > maxStride)
                stride = maxStride;
        }

        // Static bake fast-path: the opaque base rope (3D box OR flat ribbon)
        // reuses
        // baked world-space vertices until positions, light or material change. Ribbon
        // bakes additionally key on a coarse camera bin since the ribbon's side vector
        // depends on view direction; bin size of 4 blocks keeps re-bakes rare during
        // normal walking while drift stays visually negligible.
        boolean hasHighlight = job.highlightColor != NO_HIGHLIGHT;
        boolean skipBasePass = hasHighlight && job.chunkMeshActive;
        boolean canBake = !skipBasePass;
        if (canBake) {
            int pulsesHash = pulsesHash(job.pulsePositions, job.extractEnd);
            int kindOrd = job.kind.ordinal()
                    | (stride << 8)
                    | (ribbonLod ? (1 << 16) : 0);
            int colorHash = tuning.colorHashFor(job.kind);
            int cameraBin = ribbonLod ? cameraBin(cameraPos) : 0;
            double curHalfThickness = ribbonLod ? ribbonHalfWidth(tuning) : halfThickness(tuning);
            if (sim.tryUseBake(nodeCount, ribbonLod,
                    effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                    kindOrd, job.powered, job.tier, pulsesHash, colorHash, cameraBin,
                    curHalfThickness)) {
                cacheHits++;
                emitBaked(buffer, poseState, cameraPos, sim);
                renderHighlightPass(buffer, poseState, cameraPos, job, sim, nodeCount, totalLength,
                        effectiveBlockA, effectiveBlockB, ribbonLod, cheapHighlight, stride);
                return;
            }
            cacheMisses++;
            int approxVerts = ribbonLod
                    ? Math.max(64, (nodeCount - 1) * 4)
                    : Math.max(64, (nodeCount - 1) * 16);
            sim.beginBake(approxVerts);
            activeBakeSim = sim;
            try {
                if (ribbonLod) {
                    bakeCamOffsetX = cameraPos.x;
                    bakeCamOffsetY = cameraPos.y;
                    bakeCamOffsetZ = cameraPos.z;
                    renderRibbon(null, null, cameraPos, sim, nodeCount, totalLength,
                            effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                            NO_HIGHLIGHT, job.kind, job.powered, job.tier,
                            job.pulsePositions, job.extractEnd);
                } else {
                    renderSquare(null, null, Vec3.ZERO, sim, nodeCount, totalLength,
                            effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                            NO_HIGHLIGHT, job.kind, job.powered, job.tier,
                            job.pulsePositions, job.extractEnd, stride);
                }
            } finally {
                activeBakeSim = null;
                bakeCamOffsetX = bakeCamOffsetY = bakeCamOffsetZ = 0.0D;
            }
            sim.completeBake(nodeCount, ribbonLod,
                    effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                    kindOrd, job.powered, job.tier, pulsesHash, colorHash, cameraBin,
                    curHalfThickness);
            emitBaked(buffer, poseState, cameraPos, sim);
            renderHighlightPass(buffer, poseState, cameraPos, job, sim, nodeCount, totalLength,
                    effectiveBlockA, effectiveBlockB, ribbonLod, cheapHighlight, stride);
            return;
        }

        renderHighlightPass(buffer, poseState, cameraPos, job, sim, nodeCount, totalLength,
                effectiveBlockA, effectiveBlockB, ribbonLod, cheapHighlight, stride);
    }

    private static void renderHighlightPass(VertexConsumer buffer, PoseStack.Pose poseState, Vec3 cameraPos,
            RopeJob job, RopeSimulation sim, int nodeCount, double totalLength,
            int effectiveBlockA, int effectiveBlockB, boolean ribbonLod, boolean cheapHighlight, int stride) {
        if (job.highlightColor == NO_HIGHLIGHT) {
            return;
        }
        if (ribbonLod || cheapHighlight) {
            renderRibbon(buffer, poseState, cameraPos, sim, nodeCount, totalLength,
                    effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                    job.highlightColor, job.kind, job.powered, job.tier, job.pulsePositions, job.extractEnd);
        } else {
            renderSquare(buffer, poseState, cameraPos, sim, nodeCount, totalLength,
                    effectiveBlockA, effectiveBlockB, job.skyA, job.skyB,
                    job.highlightColor, job.kind, job.powered, job.tier, job.pulsePositions, job.extractEnd,
                    stride);
        }
    }

    /** Walk the bake stream and stream world-coord vertices into the consumer. */
    private static void emitBaked(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, RopeSimulation sim) {
        int totalVerts = sim.bakedCount();
        int segCount = sim.bakedSegmentCount();
        float[] bx = sim.bakedX();
        float[] by = sim.bakedY();
        float[] bz = sim.bakedZ();
        int[] bc = sim.bakedColor();
        int[] bl = sim.bakedLight();
        float camX = (float) cameraPos.x;
        float camY = (float) cameraPos.y;
        float camZ = (float) cameraPos.z;
        // Vector3f allocation + 4x4 matmul. Plus per-segment view-relative face
        // culling:
        // each baked square segment wrote 16 verts in 4 fixed-order quads (face 0
        // +side,
        // 1 -up, 2 -side, 3 +up); we emit only the 2 faces whose outward normal points
        // toward the camera, halving emit-side addVertex calls.
        if (segCount > 0 && segCount * 16 == totalVerts) {
            float[] smx = sim.bakedSegMidX();
            float[] smy = sim.bakedSegMidY();
            float[] smz = sim.bakedSegMidZ();
            float[] ssx = sim.bakedSegSideX();
            float[] ssy = sim.bakedSegSideY();
            float[] ssz = sim.bakedSegSideZ();
            float[] sux = sim.bakedSegUpX();
            float[] suy = sim.bakedSegUpY();
            float[] suz = sim.bakedSegUpZ();
            int emitted = 0;
            for (int s = 0; s < segCount; s++) {
                if (!sim.isSegmentVisible(s)) {
                    continue;
                }
                int base = s * 16;
                if (needsFullRenderSegment(sim, s)) {
                    emitBakedFace(buffer, base, bx, by, bz, bc, bl, camX, camY, camZ);
                    emitBakedFace(buffer, base + 4, bx, by, bz, bc, bl, camX, camY, camZ);
                    emitBakedFace(buffer, base + 8, bx, by, bz, bc, bl, camX, camY, camZ);
                    emitBakedFace(buffer, base + 12, bx, by, bz, bc, bl, camX, camY, camZ);
                    emitted += 16;
                    continue;
                }
                float dx = smx[s] - camX;
                float dy = smy[s] - camY;
                float dz = smz[s] - camZ;
                float dotS = dx * ssx[s] + dy * ssy[s] + dz * ssz[s];
                float dotU = dx * sux[s] + dy * suy[s] + dz * suz[s];
                int sideBase = dotS < 0f ? base : base + 8; // face 0 (+side) or face 2 (-side)
                int upBase = dotU < 0f ? base + 12 : base + 4; // face 3 (+up) or face 1 (-up)
                emitBakedFace(buffer, sideBase, bx, by, bz, bc, bl, camX, camY, camZ);
                emitBakedFace(buffer, upBase, bx, by, bz, bc, bl, camX, camY, camZ);
                emitted += 8;
            }
            verticesEmitted += emitted;
            return;
        }
        verticesEmitted += totalVerts;
        for (int i = 0; i < totalVerts; i++) {
            buffer.addVertex(bx[i] - camX, by[i] - camY, bz[i] - camZ)
                    .setColor(bc[i])
                    .setLight(bl[i]);
        }
    }

    private static void emitBakedFace(VertexConsumer buffer, int base,
            float[] bx, float[] by, float[] bz, int[] bc, int[] bl,
            float camX, float camY, float camZ) {
        for (int k = 0; k < 4; k++) {
            int i = base + k;
            buffer.addVertex(bx[i] - camX, by[i] - camY, bz[i] - camZ)
                    .setColor(bc[i])
                    .setLight(bl[i]);
        }
    }

    private static boolean isSteepRenderSegment(RopeSimulation sim, int segment) {
        if (segment < 0 || segment + 1 >= sim.nodeCount()) {
            return false;
        }
        return isSteepRenderSegment(
                sim.renderX(segment), sim.renderY(segment), sim.renderZ(segment),
                sim.renderX(segment + 1), sim.renderY(segment + 1), sim.renderZ(segment + 1));
    }

    private static boolean isSteepRenderSegment(
            double sx, double sy, double sz,
            double ex, double ey, double ez) {
        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double lenSqr = dx * dx + dy * dy + dz * dz;
        return lenSqr > 1.0e-12D && Math.abs(dy) / Math.sqrt(lenSqr) >= STEEP_RENDER_VERTICALITY;
    }

    private static boolean needsFullRenderSegment(RopeSimulation sim, int segment) {
        return isSteepRenderSegment(sim, segment)
                || isSharpRenderJoint(sim, segment)
                || isSharpRenderJoint(sim, segment + 1);
    }

    private static boolean isSharpRenderJoint(RopeSimulation sim, int node) {
        if (node <= 0 || node + 1 >= sim.nodeCount()) {
            return false;
        }
        double ax = sim.renderX(node) - sim.renderX(node - 1);
        double ay = sim.renderY(node) - sim.renderY(node - 1);
        double az = sim.renderZ(node) - sim.renderZ(node - 1);
        double bx = sim.renderX(node + 1) - sim.renderX(node);
        double by = sim.renderY(node + 1) - sim.renderY(node);
        double bz = sim.renderZ(node + 1) - sim.renderZ(node);
        double aLenSqr = ax * ax + ay * ay + az * az;
        double bLenSqr = bx * bx + by * by + bz * bz;
        if (aLenSqr <= 1.0e-12D || bLenSqr <= 1.0e-12D) {
            return false;
        }
        double dot = (ax * bx + ay * by + az * bz) / Math.sqrt(aLenSqr * bLenSqr);
        return dot < SHARP_JOINT_DOT;
    }

    private static void renderSquare(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 cameraPos,
            RopeSimulation sim,
            int nodeCount,
            double totalLength,
            int blockA,
            int blockB,
            int skyA,
            int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd,
            int stride) {
        double baseThickness = highlightColor != NO_HIGHLIGHT
                ? highlightHalfThickness(activeColorTuning)
                : halfThickness(activeColorTuning);
        int pulsesHash = pulsesHash(pulsePositions, extractEnd);
        boolean rebuild = sim.acquireFrameScratch(baseThickness, pulsesHash);
        double[] sideX = sim.frameSideX();
        double[] sideY = sim.frameSideY();
        double[] sideZ = sim.frameSideZ();
        double[] upX = sim.frameUpX();
        double[] upY = sim.frameUpY();
        double[] upZ = sim.frameUpZ();
        if (rebuild) {
            buildNodeFrames(sim, nodeCount, totalLength, baseThickness,
                    pulsePositions, extractEnd, sideX, sideY, sideZ, upX, upY, upZ);
        }

        int last = nodeCount - 1;
        RopeSimulation bakeSim = activeBakeSim;
        // When baking, we must emit every stripe so the cached vertex stream is
        // complete and
        // can be filtered at emit-time by the per-segment visibility mask. When NOT
        // baking
        // (e.g. highlight rendering), skip stripes the visibility pass already marked
        // hidden.
        boolean filterByMask = bakeSim == null && !sim.segVisAllVisible();
        int stripeIdx = 0;
        // Emit one stripe per node-step (not per stride-step) so the alternating
        // dark/light
        // color pattern is invariant under LOD: stride=2 produces two stripes per
        // geometric
        // segment, stride=4 produces four. Frames are linearly interpolated between i
        // and j;
        // we still save physics work by computing only stride-anchored frames upstream.
        for (int i = 0; i < last; i += stride) {
            int j = Math.min(i + stride, last);
            int sub = Math.max(1, j - i);
            for (int k = 0; k < sub; k++) {
                int currentStripe = stripeIdx++;
                if (filterByMask && !sim.isSegmentVisible(currentStripe)) {
                    continue;
                }
                double a = k / (double) sub;
                double b = (k + 1) / (double) sub;
                emitBoxStripSubSegment(buffer, pose, cameraPos, sim, last,
                        sideX, sideY, sideZ, upX, upY, upZ,
                        i, j, a, b, blockA, blockB, skyA, skyB,
                        currentStripe, highlightColor, kind, powered, tier, bakeSim);
            }
        }
    }

    private static void emitBoxStripSubSegment(
            VertexConsumer buffer, PoseStack.Pose pose, Vec3 cameraPos, RopeSimulation sim,
            int last,
            double[] sideX, double[] sideY, double[] sideZ,
            double[] upX, double[] upY, double[] upZ,
            int i, int j, double a, double b,
            int blockA, int blockB, int skyA, int skyB,
            int stripe, int highlightColor, LeadKind kind, boolean powered, int tier,
            RopeSimulation bakeSim) {
        double span = j - i;
        float t0 = (float) ((i + a * span) / (double) last);
        float t1 = (float) ((i + b * span) / (double) last);
        int light0 = LightCoordsUtil.pack((int) lerp(t0, blockA, blockB), (int) lerp(t0, skyA, skyB));
        int light1 = LightCoordsUtil.pack((int) lerp(t1, blockA, blockB), (int) lerp(t1, skyA, skyB));

        double sxw = sim.renderX(i) + (sim.renderX(j) - sim.renderX(i)) * a;
        double syw = sim.renderY(i) + (sim.renderY(j) - sim.renderY(i)) * a;
        double szw = sim.renderZ(i) + (sim.renderZ(j) - sim.renderZ(i)) * a;
        double exw = sim.renderX(i) + (sim.renderX(j) - sim.renderX(i)) * b;
        double eyw = sim.renderY(i) + (sim.renderY(j) - sim.renderY(i)) * b;
        double ezw = sim.renderZ(i) + (sim.renderZ(j) - sim.renderZ(i)) * b;

        double sSideX = sideX[i] + (sideX[j] - sideX[i]) * a;
        double sSideY = sideY[i] + (sideY[j] - sideY[i]) * a;
        double sSideZ = sideZ[i] + (sideZ[j] - sideZ[i]) * a;
        double sUpX = upX[i] + (upX[j] - upX[i]) * a;
        double sUpY = upY[i] + (upY[j] - upY[i]) * a;
        double sUpZ = upZ[i] + (upZ[j] - upZ[i]) * a;
        double eSideX = sideX[i] + (sideX[j] - sideX[i]) * b;
        double eSideY = sideY[i] + (sideY[j] - sideY[i]) * b;
        double eSideZ = sideZ[i] + (sideZ[j] - sideZ[i]) * b;
        double eUpX = upX[i] + (upX[j] - upX[i]) * b;
        double eUpY = upY[i] + (upY[j] - upY[i]) * b;
        double eUpZ = upZ[i] + (upZ[j] - upZ[i]) * b;

        if (bakeSim != null) {
            bakeSim.appendBakedSegment(
                    (sxw + exw) * 0.5D,
                    (syw + eyw) * 0.5D,
                    (szw + ezw) * 0.5D,
                    (sSideX + eSideX) * 0.5D,
                    (sSideY + eSideY) * 0.5D,
                    (sSideZ + eSideZ) * 0.5D,
                    (sUpX + eUpX) * 0.5D,
                    (sUpY + eUpY) * 0.5D,
                    (sUpZ + eUpZ) * 0.5D);
        }
        renderBoxStrip(buffer, pose,
                sxw - cameraPos.x,
                syw - cameraPos.y,
                szw - cameraPos.z,
                exw - cameraPos.x,
                eyw - cameraPos.y,
                ezw - cameraPos.z,
                sSideX, sSideY, sSideZ, sUpX, sUpY, sUpZ,
                eSideX, eSideY, eSideZ, eUpX, eUpY, eUpZ,
                stripe, light0, light1, highlightColor, kind, powered, tier,
                needsFullRenderSegment(sim, stripe));
    }

    private static void buildNodeFrames(
            RopeSimulation sim,
            int nodeCount,
            double totalLength,
            double baseThickness,
            float[] pulsePositions,
            int extractEnd,
            double[] sideX,
            double[] sideY,
            double[] sideZ,
            double[] upX,
            double[] upY,
            double[] upZ) {
        double prevSideX = 0.0D;
        double prevSideY = 0.0D;
        double prevSideZ = 0.0D;
        boolean hasPrevSide = false;
        for (int i = 0; i < nodeCount; i++) {
            double tx;
            double ty;
            double tz;
            if (i == 0) {
                tx = sim.renderX(1) - sim.renderX(0);
                ty = sim.renderY(1) - sim.renderY(0);
                tz = sim.renderZ(1) - sim.renderZ(0);
            } else if (i == nodeCount - 1) {
                tx = sim.renderX(i) - sim.renderX(i - 1);
                ty = sim.renderY(i) - sim.renderY(i - 1);
                tz = sim.renderZ(i) - sim.renderZ(i - 1);
            } else {
                double px = sim.renderX(i) - sim.renderX(i - 1);
                double py = sim.renderY(i) - sim.renderY(i - 1);
                double pz = sim.renderZ(i) - sim.renderZ(i - 1);
                double nx = sim.renderX(i + 1) - sim.renderX(i);
                double ny = sim.renderY(i + 1) - sim.renderY(i);
                double nz = sim.renderZ(i + 1) - sim.renderZ(i);
                double pLen = Math.sqrt(px * px + py * py + pz * pz);
                double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (pLen > 1.0e-6D) {
                    px /= pLen;
                    py /= pLen;
                    pz /= pLen;
                }
                if (nLen > 1.0e-6D) {
                    nx /= nLen;
                    ny /= nLen;
                    nz /= nLen;
                }
                tx = px + nx;
                ty = py + ny;
                tz = pz + nz;
                if (tx * tx + ty * ty + tz * tz < 1.0e-8D) {
                    tx = nx;
                    ty = ny;
                    tz = nz;
                }
            }

            double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tLen < 1.0e-6D) {
                tx = 1.0D;
                ty = 0.0D;
                tz = 0.0D;
            } else {
                tx /= tLen;
                ty /= tLen;
                tz /= tLen;
            }

            double sx;
            double sy;
            double sz;
            if (hasPrevSide) {
                double along = prevSideX * tx + prevSideY * ty + prevSideZ * tz;
                sx = prevSideX - tx * along;
                sy = prevSideY - ty * along;
                sz = prevSideZ - tz * along;
                double sLenSqr = sx * sx + sy * sy + sz * sz;
                if (sLenSqr < 1.0e-8D) {
                    sx = -tz;
                    sy = 0.0D;
                    sz = tx;
                    sLenSqr = sx * sx + sz * sz;
                }
                if (sLenSqr < 1.0e-8D) {
                    sx = 1.0D;
                    sy = 0.0D;
                    sz = 0.0D;
                    double fallbackAlong = sx * tx + sy * ty + sz * tz;
                    sx -= tx * fallbackAlong;
                    sy -= ty * fallbackAlong;
                    sz -= tz * fallbackAlong;
                    sLenSqr = sx * sx + sy * sy + sz * sz;
                    if (sLenSqr < 1.0e-8D) {
                        sx = 0.0D;
                        sy = 0.0D;
                        sz = 1.0D;
                        fallbackAlong = sx * tx + sy * ty + sz * tz;
                        sx -= tx * fallbackAlong;
                        sy -= ty * fallbackAlong;
                        sz -= tz * fallbackAlong;
                        sLenSqr = sx * sx + sy * sy + sz * sz;
                    }
                }
                double invSide = 1.0D / Math.sqrt(sLenSqr);
                sx *= invSide;
                sy *= invSide;
                sz *= invSide;
            } else {
                sx = -tz;
                sy = 0.0D;
                sz = tx;
                double sLenSqr = sx * sx + sz * sz;
                if (sLenSqr < 1.0e-8D) {
                    sx = 1.0D;
                    sy = 0.0D;
                    sz = 0.0D;
                    double fallbackAlong = sx * tx + sy * ty + sz * tz;
                    sx -= tx * fallbackAlong;
                    sy -= ty * fallbackAlong;
                    sz -= tz * fallbackAlong;
                    sLenSqr = sx * sx + sy * sy + sz * sz;
                    if (sLenSqr < 1.0e-8D) {
                        sx = 0.0D;
                        sy = 0.0D;
                        sz = 1.0D;
                        fallbackAlong = sx * tx + sy * ty + sz * tz;
                        sx -= tx * fallbackAlong;
                        sy -= ty * fallbackAlong;
                        sz -= tz * fallbackAlong;
                        sLenSqr = sx * sx + sy * sy + sz * sz;
                    }
                }
                double invSide = 1.0D / Math.sqrt(sLenSqr);
                sx *= invSide;
                sy *= invSide;
                sz *= invSide;
            }

            double ux = sy * tz - sz * ty;
            double uy = sz * tx - sx * tz;
            double uz = sx * ty - sy * tx;
            double invUp = 1.0D / Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux *= invUp;
            uy *= invUp;
            uz *= invUp;

            if (hasPrevSide && sx * prevSideX + sy * prevSideY + sz * prevSideZ < 0.0D) {
                sx = -sx;
                sy = -sy;
                sz = -sz;
                ux = -ux;
                uy = -uy;
                uz = -uz;
            }

            prevSideX = sx;
            prevSideY = sy;
            prevSideZ = sz;
            hasPrevSide = true;

            double normalized = sim.renderLength(i) / totalLength;
            double thickness = baseThickness * thicknessMultiplier(normalized, pulsePositions, extractEnd);
            sideX[i] = sx * thickness;
            sideY[i] = sy * thickness;
            sideZ[i] = sz * thickness;
            upX[i] = ux * thickness;
            upY[i] = uy * thickness;
            upZ[i] = uz * thickness;
        }
    }

    private static void renderRibbon(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 cameraPos,
            RopeSimulation sim,
            int nodeCount,
            double totalLength,
            int blockA,
            int blockB,
            int skyA,
            int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        for (int i = 0; i < nodeCount - 1; i++) {
            float t0 = i / (float) (nodeCount - 1);
            float t1 = (i + 1) / (float) (nodeCount - 1);
            int light0 = LightCoordsUtil.pack((int) lerp(t0, blockA, blockB), (int) lerp(t0, skyA, skyB));
            int light1 = LightCoordsUtil.pack((int) lerp(t1, blockA, blockB), (int) lerp(t1, skyA, skyB));
            renderRibbonSegment(buffer, pose,
                    sim.renderX(i) - cameraPos.x,
                    sim.renderY(i) - cameraPos.y,
                    sim.renderZ(i) - cameraPos.z,
                    sim.renderX(i + 1) - cameraPos.x,
                    sim.renderY(i + 1) - cameraPos.y,
                    sim.renderZ(i + 1) - cameraPos.z,
                    sim.renderLength(i), sim.renderLength(i + 1), totalLength,
                    i, light0, light1, highlightColor, kind, powered, tier, pulsePositions, extractEnd);
        }
    }

    private static double lerp(float t, int a, int b) {
        return a + (b - a) * t;
    }

    private static void renderRibbonSegment(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            double sx,
            double sy,
            double sz,
            double ex,
            double ey,
            double ez,
            double lengthStart,
            double lengthEnd,
            double totalLength,
            int stripe,
            int light0,
            int light1,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6D)
            return;
        double invLength = 1.0D / length;
        double dirX = dx * invLength;
        double dirY = dy * invLength;
        double dirZ = dz * invLength;

        double viewX = (sx + ex) * 0.5D;
        double viewY = (sy + ey) * 0.5D;
        double viewZ = (sz + ez) * 0.5D;
        double sideX = dirY * viewZ - dirZ * viewY;
        double sideY = dirZ * viewX - dirX * viewZ;
        double sideZ = dirX * viewY - dirY * viewX;
        double sideLenSqr = sideX * sideX + sideY * sideY + sideZ * sideZ;
        if (sideLenSqr < 1.0e-8D) {
            sideX = -dirZ;
            sideY = 0.0D;
            sideZ = dirX;
            sideLenSqr = sideX * sideX + sideZ * sideZ;
            if (sideLenSqr < 1.0e-8D) {
                sideX = 1.0D;
                sideY = 0.0D;
                sideZ = 0.0D;
                sideLenSqr = 1.0D;
            }
        }

        double midLength = (lengthStart + lengthEnd) * 0.5D;
        double midNorm = midLength / totalLength;
        double halfWidth = (highlightColor != NO_HIGHLIGHT
                ? highlightRibbonHalfWidth(activeColorTuning)
                : ribbonHalfWidth(activeColorTuning))
                * thicknessMultiplier(midNorm, pulsePositions, extractEnd);
        double scale = halfWidth / Math.sqrt(sideLenSqr);
        sideX *= scale;
        sideY *= scale;
        sideZ *= scale;

        quad(buffer, pose,
                sx + sideX, sy + sideY, sz + sideZ,
                ex + sideX, ey + sideY, ez + sideZ,
                ex - sideX, ey - sideY, ez - sideZ,
                sx - sideX, sy - sideY, sz - sideZ,
                stripe, light0, light1, 0, highlightColor, kind, powered, tier);
    }

    private static void renderBoxStrip(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            double sx,
            double sy,
            double sz,
            double ex,
            double ey,
            double ez,
            double startSideX,
            double startSideY,
            double startSideZ,
            double startUpX,
            double startUpY,
            double startUpZ,
            double endSideX,
            double endSideY,
            double endSideZ,
            double endUpX,
            double endUpY,
            double endUpZ,
            int stripe,
            int light0,
            int light1,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            boolean forceAllFaces) {
        double ax = sx + startSideX + startUpX, ay = sy + startSideY + startUpY, az = sz + startSideZ + startUpZ;
        double bx = sx + startSideX - startUpX, by = sy + startSideY - startUpY, bz = sz + startSideZ - startUpZ;
        double cx = sx - startSideX - startUpX, cy = sy - startSideY - startUpY, cz = sz - startSideZ - startUpZ;
        double dx = sx - startSideX + startUpX, dy = sy - startSideY + startUpY, dz = sz - startSideZ + startUpZ;
        double ex0 = ex + endSideX + endUpX, ey0 = ey + endSideY + endUpY, ez0 = ez + endSideZ + endUpZ;
        double fx = ex + endSideX - endUpX, fy = ey + endSideY - endUpY, fz = ez + endSideZ - endUpZ;
        double gx = ex - endSideX - endUpX, gy = ey - endSideY - endUpY, gz = ez - endSideZ - endUpZ;
        double hx = ex - endSideX + endUpX, hy = ey - endSideY + endUpY, hz = ez - endSideZ + endUpZ;

        if (!forceAllFaces && activeBakeSim == null && highlightColor == NO_HIGHLIGHT) {
            double mx = (sx + ex) * 0.5D;
            double my = (sy + ey) * 0.5D;
            double mz = (sz + ez) * 0.5D;
            double cSideX = (startSideX + endSideX) * 0.5D;
            double cSideY = (startSideY + endSideY) * 0.5D;
            double cSideZ = (startSideZ + endSideZ) * 0.5D;
            double cUpX = (startUpX + endUpX) * 0.5D;
            double cUpY = (startUpY + endUpY) * 0.5D;
            double cUpZ = (startUpZ + endUpZ) * 0.5D;
            double dotSide = mx * cSideX + my * cSideY + mz * cSideZ;
            double dotUp = mx * cUpX + my * cUpY + mz * cUpZ;
            if (dotSide < 0.0D) {
                quad(buffer, pose, ax, ay, az, ex0, ey0, ez0, fx, fy, fz, bx, by, bz,
                        stripe, light0, light1, 0, highlightColor, kind, powered, tier);
            } else {
                quad(buffer, pose, cx, cy, cz, gx, gy, gz, hx, hy, hz, dx, dy, dz,
                        stripe, light0, light1, 2, highlightColor, kind, powered, tier);
            }
            if (dotUp < 0.0D) {
                quad(buffer, pose, dx, dy, dz, hx, hy, hz, ex0, ey0, ez0, ax, ay, az,
                        stripe, light0, light1, 3, highlightColor, kind, powered, tier);
            } else {
                quad(buffer, pose, bx, by, bz, fx, fy, fz, gx, gy, gz, cx, cy, cz,
                        stripe, light0, light1, 1, highlightColor, kind, powered, tier);
            }
            return;
        }

        quad(buffer, pose, ax, ay, az, ex0, ey0, ez0, fx, fy, fz, bx, by, bz,
                stripe, light0, light1, 0, highlightColor, kind, powered, tier);
        quad(buffer, pose, bx, by, bz, fx, fy, fz, gx, gy, gz, cx, cy, cz,
                stripe, light0, light1, 1, highlightColor, kind, powered, tier);
        quad(buffer, pose, cx, cy, cz, gx, gy, gz, hx, hy, hz, dx, dy, dz,
                stripe, light0, light1, 2, highlightColor, kind, powered, tier);
        quad(buffer, pose, dx, dy, dz, hx, hy, hz, ex0, ey0, ez0, ax, ay, az,
                stripe, light0, light1, 3, highlightColor, kind, powered, tier);
    }

    /**
     * 4-block-cube quantization of camera position. Used as a ribbon-bake cache key
     * so the
     * cache survives small camera motion but invalidates when the player walks far
     * enough
     * for the camera-facing side vector to drift visibly.
     */
    private static int cameraBin(Vec3 cameraPos) {
        int bx = (int) Math.floor(cameraPos.x * 0.25D);
        int by = (int) Math.floor(cameraPos.y * 0.25D);
        int bz = (int) Math.floor(cameraPos.z * 0.25D);
        return (bx * 73856093) ^ (by * 19349663) ^ (bz * 83492791);
    }

    private static int pulsesHash(float[] pulsePositions, int extractEnd) {
        int h = extractEnd;
        if (pulsePositions != null) {
            for (float p : pulsePositions) {
                h = h * 31 + Float.floatToIntBits(p);
            }
        }
        return h;
    }

    private static double thicknessMultiplier(double normalizedPos, float[] pulsePositions, int extractEnd) {
        double bonus = 0.0D;
        if (pulsePositions != null) {
            for (float p : pulsePositions) {
                double d = (normalizedPos - p) / PULSE_SIGMA;
                bonus += PULSE_AMPLITUDE * Math.exp(-d * d);
            }
        }
        // extractEnd: 1 = `from` end (start of rope), 2 = `to` end (end of rope), 0 =
        // none.
        if (extractEnd == 1 && normalizedPos < FIRST_SEGMENT_LENGTH) {
            double f = 1.0D - (normalizedPos / FIRST_SEGMENT_LENGTH);
            bonus += FIRST_SEGMENT_AMPLITUDE * f * f;
        } else if (extractEnd == 2 && normalizedPos > 1.0D - FIRST_SEGMENT_LENGTH) {
            double f = (normalizedPos - (1.0D - FIRST_SEGMENT_LENGTH)) / FIRST_SEGMENT_LENGTH;
            bonus += FIRST_SEGMENT_AMPLITUDE * f * f;
        }
        return 1.0D + bonus;
    }

    private static void quad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            double p0x, double p0y, double p0z,
            double p1x, double p1y, double p1z,
            double p2x, double p2y, double p2z,
            double p3x, double p3y, double p3z,
            int stripe,
            int light0,
            int light1,
            int face,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier) {
        int color = highlightColor != NO_HIGHLIGHT ? highlightOverlayColor(face, highlightColor)
                : ropeColor(stripe, face, kind, powered, tier);
        vertex(buffer, pose, p3x, p3y, p3z, color, light0);
        vertex(buffer, pose, p2x, p2y, p2z, color, light1);
        vertex(buffer, pose, p1x, p1y, p1z, color, light1);
        vertex(buffer, pose, p0x, p0y, p0z, color, light0);
    }

    public static int ropeColor(int stripe, int face, LeadKind kind, boolean powered, int tier) {
        RopeTuning tuning = activeColorTuning != null ? activeColorTuning : RopeTuning.localDefaults();
        return ropeColor(stripe, face, kind, powered, tier, tuning);
    }

    public static int ropeColor(int stripe, int face, LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        LeadKind effectiveKind = kind == null ? LeadKind.NORMAL : kind;
        RopeTuning effectiveTuning = tuning == null ? RopeTuning.localDefaults() : tuning;
        boolean bright = (stripe & 1) == 0;
        double shade = switch (face) {
            case 0 -> 1.0D;
            case 1 -> 0.82D;
            case 2 -> 0.68D;
            default -> 0.9D;
        };
        int base = effectiveTuning.baseColor(effectiveKind);
        int accent = effectiveTuning.accentColor(effectiveKind);
        int rgb = bright ? accent : base;

        if (effectiveKind == LeadKind.REDSTONE && powered) {
            rgb = brighten(blend(rgb, accent, bright ? 0.35D : 0.20D), bright ? 1.45D : 1.30D);
            rgb = blend(rgb, 0xFF302A, bright ? 0.18D : 0.08D);
        } else if (effectiveKind == LeadKind.ENERGY) {
            boolean goldBand = tier > 0 && (stripe % Math.max(2, 4 - Math.min(3, tier))) == 0 && bright;
            if (powered) {
                rgb = brighten(blend(rgb, accent, 0.20D), 1.14D);
            }
            if (goldBand) {
                rgb = brighten(blend(rgb, accent, 0.78D), powered ? 1.18D : 1.06D);
            }
        } else if (effectiveKind == LeadKind.AE_NETWORK) {
            boolean channelBand = tier > 0 && (stripe % Math.max(2, 6 - Math.min(4, tier))) == 0 && bright;
            if (channelBand) {
                rgb = brighten(blend(rgb, accent, 0.72D), 1.12D);
            }
        }

        return 0xFF000000 | shade(rgb, shade);
    }

    private static int shade(int rgb, double shade) {
        int r = clampChannel((int) Math.round(((rgb >> 16) & 255) * shade));
        int g = clampChannel((int) Math.round(((rgb >> 8) & 255) * shade));
        int b = clampChannel((int) Math.round((rgb & 255) * shade));
        return (r << 16) | (g << 8) | b;
    }

    private static int brighten(int rgb, double factor) {
        int r = clampChannel((int) Math.round(((rgb >> 16) & 255) * factor));
        int g = clampChannel((int) Math.round(((rgb >> 8) & 255) * factor));
        int b = clampChannel((int) Math.round((rgb & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, double t) {
        double u = Math.max(0.0D, Math.min(1.0D, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = clampChannel((int) Math.round(ar + (br - ar) * u));
        int g = clampChannel((int) Math.round(ag + (bg - ag) * u));
        int bl = clampChannel((int) Math.round(ab + (bb - ab) * u));
        return (r << 16) | (g << 8) | bl;
    }

    private static int clampChannel(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }

    private static int highlightOverlayColor(int face, int baseColor) {
        double shade = switch (face) {
            case 0 -> 1.0D;
            case 1 -> 0.9D;
            case 2 -> 0.8D;
            default -> 0.95D;
        };
        int a = baseColor >>> 24;
        int r = (int) (((baseColor >> 16) & 255) * shade);
        int g = (int) (((baseColor >> 8) & 255) * shade);
        int b = (int) ((baseColor & 255) * shade);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double y, double z, int color,
            int light) {
        RopeSimulation bakeSim = activeBakeSim;
        if (bakeSim != null) {
            bakeSim.appendBakedVertex(x + bakeCamOffsetX, y + bakeCamOffsetY, z + bakeCamOffsetZ, color, light);
            return;
        }
        // allocates a Vector3f and runs a 4x4 matmul per vertex. We already feed
        // world-space
        // (cam-relative) coords, so the matrix is pure waste.
        buffer.addVertex((float) x, (float) y, (float) z)
                .setColor(color)
                .setLight(light);
        verticesEmitted++;
    }
}
