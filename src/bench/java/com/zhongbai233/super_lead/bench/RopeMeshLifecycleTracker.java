package com.zhongbai233.super_lead.bench;

import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared per-connection chunk-mesh lifecycle assertions for client scenarios. */
final class RopeMeshLifecycleTracker {
    private final Map<UUID, State> states = new HashMap<>();

    void sample(Iterable<UUID> ids) {
        for (UUID id : ids) {
            sample(id);
        }
    }

    void sample(UUID id) {
        if (id == null) {
            return;
        }
        SuperLeadClientEvents.RopeChunkMeshBenchProbe probe =
                SuperLeadClientEvents.probeChunkMeshForBench(id);
        if (probe == null) {
            return;
        }
        State state = states.computeIfAbsent(id, ignored -> new State());
        state.claimed |= probe.claimed();
        state.accepted |= probe.meshAccepted();
        if (probe.meshActive()) {
            state.activeTicks++;
            state.everActive = true;
        } else if (state.everActive) {
            state.inactiveAfterActive = true;
        }
        if (state.inactiveAfterActive && probe.meshActive()) {
            state.reentered = true;
        }
        if (probe.renderLod() >= 0 && probe.renderLod() < 4) {
            state.lodMask |= 1 << probe.renderLod();
        }
        state.sectionCount = probe.sectionCount();
        state.acceptedSections = probe.acceptedSections();
        state.awaitingSections = probe.awaitingSections();
        state.pendingDirtySections = probe.pendingDirtySections();
        state.firstMissingSection = probe.firstMissingSection();
        state.targetGeneration = probe.targetGeneration();
        state.compiledGeneration = probe.compiledGeneration();
        state.lastSubmitTick = probe.lastSubmitTick();
    }

    boolean isActive(UUID id) {
        SuperLeadClientEvents.RopeChunkMeshBenchProbe probe =
                SuperLeadClientEvents.probeChunkMeshForBench(id);
        return probe != null && probe.meshActive();
    }

    String describe(UUID id) {
        return String.valueOf(states.get(id));
    }

    void requireAllActiveAtLeastOnce(Iterable<UUID> ids, String scenario) {
        for (UUID id : ids) {
            State state = states.get(id);
            if (state == null || !state.claimed || !state.accepted || !state.everActive) {
                throw new AssertionError(scenario + " connection " + id
                        + " never completed chunk-mesh handoff: " + state);
            }
        }
    }

    void requireExitAndReentry(UUID id, String scenario) {
        State state = states.get(id);
        if (state == null || !state.everActive || !state.inactiveAfterActive || !state.reentered) {
            throw new AssertionError(scenario + " did not complete mesh->dynamic->mesh lifecycle: " + state);
        }
    }

    private static final class State {
        boolean claimed;
        boolean accepted;
        boolean everActive;
        boolean inactiveAfterActive;
        boolean reentered;
        int activeTicks;
        int lodMask;
        int sectionCount;
        int acceptedSections;
        int awaitingSections;
        int pendingDirtySections;
        long firstMissingSection = Long.MIN_VALUE;
        long targetGeneration = Long.MIN_VALUE;
        long compiledGeneration = Long.MIN_VALUE;
        long lastSubmitTick = Long.MIN_VALUE;

        @Override
        public String toString() {
            return "claimed=" + claimed + " accepted=" + accepted + " active=" + everActive
                    + " exited=" + inactiveAfterActive + " reentered=" + reentered
                    + " activeTicks=" + activeTicks + " lodMask=0x" + Integer.toHexString(lodMask)
                    + " sections=" + acceptedSections + "/" + sectionCount
                    + " awaiting=" + awaitingSections + " pendingDirty=" + pendingDirtySections
                    + " missingSection=" + firstMissingSection
                    + " generation=" + compiledGeneration + "/" + targetGeneration
                    + " lastSubmitTick=" + lastSubmitTick;
        }
    }
}