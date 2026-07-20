package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;

public final class RopeJob {
    public final RopeSimulation sim;
    public final int blockA;
    public final int blockB;
    public final int skyA;
    public final int skyB;
    public final int highlightColor;
    public final LeadKind kind;
    public final boolean powered;
    public final int tier;
    public final float[] pulsePositions;
    public final int extractEnd;
    /** True when the opaque base rope is already provided by the static chunk mesh. */
    public final boolean chunkMeshActive;
    /** Per-rope interpolation phase; NaN means use the frame-wide partial tick. */
    public final float renderPartialTick;

    public RopeJob(
            RopeSimulation sim,
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
        this(sim, blockA, blockB, skyA, skyB, highlightColor, kind, powered, tier,
            pulsePositions, extractEnd, false, Float.NaN);
    }

    public RopeJob(
            RopeSimulation sim,
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
            boolean chunkMeshActive) {
            this(sim, blockA, blockB, skyA, skyB, highlightColor, kind, powered, tier,
                pulsePositions, extractEnd, chunkMeshActive, Float.NaN);
            }

            public RopeJob(
                RopeSimulation sim,
                int blockA, int blockB,
                int skyA, int skyB,
                int highlightColor,
                LeadKind kind,
                boolean powered,
                int tier,
                float[] pulsePositions,
                int extractEnd,
                boolean chunkMeshActive,
                float renderPartialTick) {
        this.sim = sim;
        this.blockA = blockA;
        this.blockB = blockB;
        this.skyA = skyA;
        this.skyB = skyB;
        this.highlightColor = highlightColor;
        this.kind = kind;
        this.powered = powered;
        this.tier = tier;
        this.pulsePositions = pulsePositions;
        this.extractEnd = extractEnd;
        this.chunkMeshActive = chunkMeshActive;
        this.renderPartialTick = renderPartialTick;
    }
}
