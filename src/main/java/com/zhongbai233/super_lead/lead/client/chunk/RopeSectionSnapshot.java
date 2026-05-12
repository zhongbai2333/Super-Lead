package com.zhongbai233.super_lead.lead.client.chunk;

import java.util.UUID;

public final class RopeSectionSnapshot {
    public final UUID connectionId;
    public final int nodeCount;
    public final float[] x;
    public final float[] y;
    public final float[] z;
    public final float[] sx;
    public final float[] sy;
    public final float[] sz;
    public final float[] ux;
    public final float[] uy;
    public final float[] uz;
    public final int[] nodeLight;
    public final int[] segmentColorARGB;
    public final int segmentStart;
    public final int segmentEndExclusive;

    public RopeSectionSnapshot(UUID connectionId,
            float[] x, float[] y, float[] z,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz,
            int[] nodeLight,
            int[] segmentColorARGB) {
        this(connectionId, x, y, z, sx, sy, sz, ux, uy, uz,
                nodeLight, segmentColorARGB, 0, Math.max(0, x.length - 1));
    }

    public RopeSectionSnapshot(UUID connectionId,
            float[] x, float[] y, float[] z,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz,
            int[] nodeLight,
            int[] segmentColorARGB,
            int segmentStart,
            int segmentEndExclusive) {
        this.connectionId = connectionId;
        this.nodeCount = x.length;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.nodeLight = nodeLight;
        this.segmentColorARGB = segmentColorARGB;
        this.segmentStart = Math.max(0, Math.min(segmentStart, Math.max(0, x.length - 1)));
        this.segmentEndExclusive = Math.max(this.segmentStart,
                Math.min(segmentEndExclusive, Math.max(0, x.length - 1)));
    }
}
