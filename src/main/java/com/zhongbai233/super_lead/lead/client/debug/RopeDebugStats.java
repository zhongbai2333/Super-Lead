package com.zhongbai233.super_lead.lead.client.debug;

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
    public static volatile int chunkMeshEligible;
    public static volatile int chunkMeshIneligible;
    public static volatile int chunkMeshWaitingQuiet;
    public static volatile int chunkMeshReadyFromSim;
    public static volatile int chunkMeshReadyAnchorBake;
    public static volatile int chunkMeshClaimedFromSim;
    public static volatile int chunkMeshClaimedAnchorBake;
    public static volatile int chunkMeshMissingAnchors;

    public static volatile int bakeCacheHits;
    public static volatile int bakeCacheMisses;
    public static volatile int verticesEmitted;
    public static volatile int pushContacts;
    public static volatile float pushX;
    public static volatile float pushZ;
    public static volatile float pushMagnitude;

    private RopeDebugStats() {}

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
        chunkMeshEligible = 0;
        chunkMeshIneligible = 0;
        chunkMeshWaitingQuiet = 0;
        chunkMeshReadyFromSim = 0;
        chunkMeshReadyAnchorBake = 0;
        chunkMeshClaimedFromSim = 0;
        chunkMeshClaimedAnchorBake = 0;
        chunkMeshMissingAnchors = 0;
        bakeCacheHits = 0;
        bakeCacheMisses = 0;
        verticesEmitted = 0;
        pushContacts = 0;
        pushX = 0.0F;
        pushZ = 0.0F;
        pushMagnitude = 0.0F;
    }
}
