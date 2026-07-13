package com.zhongbai233.super_lead.lead.client.chunk;

import java.util.List;
import java.util.UUID;

public final class RopeSectionSnapshot {
    public final UUID connectionId;
    public final int nodeCount;
        /** World-space positions stay double until the chunk origin is subtracted. */
        public final double[] x;
        public final double[] y;
        public final double[] z;
    /** Unsmooth source polyline used only when rebuilding a finer physics topology. */
    public final double[] sourceX;
    public final double[] sourceY;
    public final double[] sourceZ;
    public final float[] sx;
    public final float[] sy;
    public final float[] sz;
    public final float[] ux;
    public final float[] uy;
    public final float[] uz;
    public final int[] nodeLight;
    public final int[] segmentColorARGB;
    public final float[] nodeThicknessScale;
    public final int extractEnd;
        public final List<RopeSectionLine> attachmentLines;
    public final int segmentStart;
    public final int segmentEndExclusive;

    public RopeSectionSnapshot(UUID connectionId,
            double[] x, double[] y, double[] z,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz,
            int[] nodeLight,
            int[] segmentColorARGB) {
        this(connectionId, x, y, z, sx, sy, sz, ux, uy, uz,
            nodeLight, segmentColorARGB, null, 0, List.of(), 0, Math.max(0, x.length - 1), x, y, z);
    }

    public RopeSectionSnapshot(UUID connectionId,
            double[] x, double[] y, double[] z,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz,
            int[] nodeLight,
            int[] segmentColorARGB,
            int segmentStart,
            int segmentEndExclusive) {
        this(connectionId, x, y, z, sx, sy, sz, ux, uy, uz,
            nodeLight, segmentColorARGB, null, 0, List.of(), segmentStart, segmentEndExclusive, x, y, z);
    }

    public RopeSectionSnapshot(UUID connectionId,
            double[] x, double[] y, double[] z,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz,
            int[] nodeLight,
            int[] segmentColorARGB,
            float[] nodeThicknessScale,
            int extractEnd,
            List<RopeSectionLine> attachmentLines,
            int segmentStart,
            int segmentEndExclusive) {
            this(connectionId, x, y, z, sx, sy, sz, ux, uy, uz, nodeLight, segmentColorARGB,
                nodeThicknessScale, extractEnd, attachmentLines, segmentStart, segmentEndExclusive, x, y, z);
            }

            public RopeSectionSnapshot(UUID connectionId,
                double[] x, double[] y, double[] z,
                float[] sx, float[] sy, float[] sz,
                float[] ux, float[] uy, float[] uz,
                int[] nodeLight,
                int[] segmentColorARGB,
                float[] nodeThicknessScale,
                int extractEnd,
                List<RopeSectionLine> attachmentLines,
                int segmentStart,
                int segmentEndExclusive,
                double[] sourceX, double[] sourceY, double[] sourceZ) {
        this.connectionId = connectionId;
        this.nodeCount = x.length;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceZ = sourceZ;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.nodeLight = nodeLight;
        this.segmentColorARGB = segmentColorARGB;
        this.nodeThicknessScale = nodeThicknessScale;
        this.extractEnd = Math.max(0, Math.min(2, extractEnd));
        this.attachmentLines = attachmentLines == null || attachmentLines.isEmpty()
                ? List.of()
                : List.copyOf(attachmentLines);
        this.segmentStart = Math.max(0, Math.min(segmentStart, Math.max(0, x.length - 1)));
        this.segmentEndExclusive = Math.max(this.segmentStart,
                Math.min(segmentEndExclusive, Math.max(0, x.length - 1)));
    }
}
