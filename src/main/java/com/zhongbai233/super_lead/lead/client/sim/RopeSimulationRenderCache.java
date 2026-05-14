package com.zhongbai233.super_lead.lead.client.sim;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationRenderCache extends RopeSimulationCore {
    protected RopeSimulationRenderCache(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    /**
     * Prepare and cache interpolated render nodes. Returns cumulative rope length.
     */
    public double prepareRender(float partialTick) {
        if (renderCacheValid
                && (isSettled() || Float.floatToIntBits(renderCachePartialTick) == Float.floatToIntBits(partialTick))) {
            return renderTotalLength;
        }
        if (useCollisionProxy) {
            renderTotalLength = prepareRenderProxy(partialTick);
        } else {
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
        }
        renderCachePartialTick = partialTick;
        renderCacheValid = true;
        // Render positions just changed; basis-vector scratch and occlusion cache are
        // stale.
        frameScratchValid = false;
        visOcclusionFrame = Long.MIN_VALUE;
        return renderTotalLength;
    }

    public double renderX(int i) {
        return renderX[i];
    }

    public double renderY(int i) {
        return renderY[i];
    }

    public double renderZ(int i) {
        return renderZ[i];
    }

    public double renderLength(int i) {
        return renderLengths[i];
    }

    public AABB renderBounds(float partialTick) {
        prepareRender(partialTick);
        double nx0 = renderX[0], nx1 = renderX[0];
        double ny0 = renderY[0], ny1 = renderY[0];
        double nz0 = renderZ[0], nz1 = renderZ[0];
        for (int i = 1; i < nodes; i++) {
            double vx = renderX[i];
            if (vx < nx0)
                nx0 = vx;
            else if (vx > nx1)
                nx1 = vx;
            double vy = renderY[i];
            if (vy < ny0)
                ny0 = vy;
            else if (vy > ny1)
                ny1 = vy;
            double vz = renderZ[i];
            if (vz < nz0)
                nz0 = vz;
            else if (vz > nz1)
                nz1 = vz;
        }
        return new AABB(nx0, ny0, nz0, nx1, ny1, nz1);
    }

    // Render-side scratch accessors
    // --------------------------------------------------------------
    public double[] frameSideX() {
        return frameSideX;
    }

    public double[] frameSideY() {
        return frameSideY;
    }

    public double[] frameSideZ() {
        return frameSideZ;
    }

    public double[] frameUpX() {
        return frameUpX;
    }

    public double[] frameUpY() {
        return frameUpY;
    }

    public double[] frameUpZ() {
        return frameUpZ;
    }

    /**
     * Lazily allocate / validate per-frame basis-vector scratch arrays. Returns
     * {@code true} if
     * the caller must (re)build them, {@code false} if the previously-built scratch
     * is still
     * valid for these parameters and can be reused.
     */
    public boolean acquireFrameScratch(double baseThickness, int pulsesHash) {
        if (frameSideX == null || frameSideX.length < nodes) {
            frameSideX = new double[nodes];
            frameSideY = new double[nodes];
            frameSideZ = new double[nodes];
            frameUpX = new double[nodes];
            frameUpY = new double[nodes];
            frameUpZ = new double[nodes];
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

    // Render-side occlusion cache accessors
    // ------------------------------------------------------
    public long visOcclusionFrame() {
        return visOcclusionFrame;
    }

    public boolean visOcclusionResult() {
        return visOcclusionResult;
    }

    public void setVisOcclusionCache(long frame, boolean result) {
        this.visOcclusionFrame = frame;
        this.visOcclusionResult = result;
    }

    /**
     * Reset the per-segment visibility mask and ensure the backing array can hold
     * {@code segmentCount} bits. A non-zero segment count activates mask filtering;
     * callers then
     * mark visible segments explicitly with {@link #setSegmentVisible}.
     */
    public void beginSegmentVisibility(int segmentCount) {
        segVisBitCount = segmentCount;
        segVisAllVisible = segmentCount <= 0;
        if (segmentCount <= 0) {
            return;
        }
        int words = (segmentCount + 63) >>> 6;
        if (segVisMask == null || segVisMask.length < words) {
            segVisMask = new long[words];
        } else {
            for (int i = 0; i < words; i++)
                segVisMask[i] = 0L;
        }
    }

    /** Mark segment {@code s} (0-based) as visible/hidden. */
    public void setSegmentVisible(int s, boolean visible) {
        if (s < 0 || s >= segVisBitCount)
            return;
        if (visible) {
            segVisMask[s >>> 6] |= 1L << (s & 63);
        } else {
            segVisAllVisible = false;
        }
    }

    /**
     * True if every segment in the current mask is visible (or no mask was
     * computed).
     */
    public boolean segVisAllVisible() {
        return segVisAllVisible;
    }

    /**
     * True if segment {@code s} is visible. Returns true when no mask is active.
     */
    public boolean isSegmentVisible(int s) {
        if (segVisAllVisible)
            return true;
        if (s < 0 || s >= segVisBitCount)
            return true;
        return (segVisMask[s >>> 6] & (1L << (s & 63))) != 0L;
    }

    // Static baked vertex cache (Pillar A)
    // ------------------------------------------------------
    /**
     * Returns true if the cache is populated and matches the supplied key. The
     * cache is
     * auto-invalidated by {@link #markBoundsDirty()} (called at the end of any
     * non-skipped
     * {@code step()}), so cache-hit always means "positions, light and material
     * identical
     * to the last bake".
     */
    public boolean tryUseBake(int nodeCountKey, boolean ribbonLodKey,
            int blockA, int blockB, int skyA, int skyB,
            int kindOrdinal, boolean powered, int tier, int pulsesHash, int colorHash, int cameraBin,
            double halfThickness) {
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
                && bakedColorHash == colorHash
                && bakedCameraBin == cameraBin
                && Double.doubleToLongBits(bakedHalfThickness) == Double.doubleToLongBits(halfThickness);
    }

    /**
     * Begin a fresh bake. Re-allocates only when the current arrays are too small.
     */
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
            int kindOrdinal, boolean powered, int tier, int pulsesHash, int colorHash, int cameraBin,
            double halfThickness) {
        bakedNodeCount = nodeCountKey;
        bakedRibbonLod = ribbonLodKey;
        bakedBlockA = blockA;
        bakedBlockB = blockB;
        bakedSkyA = skyA;
        bakedSkyB = skyB;
        bakedKindOrdinal = kindOrdinal;
        bakedPowered = powered;
        bakedTier = tier;
        bakedPulsesHash = pulsesHash;
        bakedColorHash = colorHash;
        bakedCameraBin = cameraBin;
        bakedHalfThickness = halfThickness;
        bakedValid = true;
    }

    public int bakedCount() {
        return bakedCount;
    }

    public float[] bakedX() {
        return bakedX;
    }

    public float[] bakedY() {
        return bakedY;
    }

    public float[] bakedZ() {
        return bakedZ;
    }

    public int[] bakedColor() {
        return bakedColor;
    }

    public int[] bakedLight() {
        return bakedLight;
    }

    public int bakedSegmentCount() {
        return bakedSegmentCount;
    }

    public float[] bakedSegMidX() {
        return bakedSegMidX;
    }

    public float[] bakedSegMidY() {
        return bakedSegMidY;
    }

    public float[] bakedSegMidZ() {
        return bakedSegMidZ;
    }

    public float[] bakedSegSideX() {
        return bakedSegSideX;
    }

    public float[] bakedSegSideY() {
        return bakedSegSideY;
    }

    public float[] bakedSegSideZ() {
        return bakedSegSideZ;
    }

    public float[] bakedSegUpX() {
        return bakedSegUpX;
    }

    public float[] bakedSegUpY() {
        return bakedSegUpY;
    }

    public float[] bakedSegUpZ() {
        return bakedSegUpZ;
    }
}
