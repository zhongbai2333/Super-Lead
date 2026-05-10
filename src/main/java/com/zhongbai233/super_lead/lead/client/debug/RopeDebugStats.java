package com.zhongbai233.super_lead.lead.client.debug;

import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;

public final class RopeDebugStats {

    public static volatile int totalConnections;
    public static volatile int simEntries;
    public static volatile int renderEntries;
    public static volatile int chunkMeshClaimed;
    public static volatile int dynamicJobs;
    public static volatile int simCount;
    public static volatile int dynamicNodesTotal;
    public static volatile int chunkMeshNodesTotal;
    public static volatile int totalRenderNodes;
    public static volatile int chunkMeshSections;
    public static volatile int chunkMeshSnapshots;
    public static volatile int attachmentsTotal;

    public static volatile int bakeCacheHits;
    public static volatile int bakeCacheMisses;
    public static volatile int verticesEmitted;

    private static int prevCacheHits;
    private static int prevCacheMisses;
    private static int prevVerticesEmitted;

    private RopeDebugStats() {}

    public static void captureLeashBuilderDeltas() {
        int hits = LeashBuilder.cacheHits;
        int misses = LeashBuilder.cacheMisses;
        int verts = LeashBuilder.verticesEmitted;
        bakeCacheHits = hits - prevCacheHits;
        bakeCacheMisses = misses - prevCacheMisses;
        verticesEmitted = verts - prevVerticesEmitted;
        prevCacheHits = hits;
        prevCacheMisses = misses;
        prevVerticesEmitted = verts;
    }

    public static void clear() {
        totalConnections = 0;
        simEntries = 0;
        renderEntries = 0;
        chunkMeshClaimed = 0;
        dynamicJobs = 0;
        simCount = 0;
        dynamicNodesTotal = 0;
        chunkMeshNodesTotal = 0;
        totalRenderNodes = 0;
        chunkMeshSections = 0;
        chunkMeshSnapshots = 0;
        attachmentsTotal = 0;
        bakeCacheHits = 0;
        bakeCacheMisses = 0;
        verticesEmitted = 0;
    }
}
