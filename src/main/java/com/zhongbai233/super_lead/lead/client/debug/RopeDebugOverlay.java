package com.zhongbai233.super_lead.lead.client.debug;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import org.jspecify.annotations.Nullable;

public final class RopeDebugOverlay implements DebugScreenEntry {

    private static final Identifier ENTRY_ID = Identifier.fromNamespaceAndPath(Super_lead.MODID, "stats");
    private static final Identifier GROUP = Identifier.fromNamespaceAndPath(Super_lead.MODID, "stats");

    private RopeDebugOverlay() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(RopeDebugOverlay::onRegister);
    }

    private static void onRegister(RegisterDebugEntriesEvent event) {
        event.register(ENTRY_ID, new RopeDebugOverlay());
        event.includeInProfile(ENTRY_ID, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.ALWAYS_ON);
    }

    @Override
    public void display(DebugScreenDisplayer displayer,
                        @Nullable Level serverOrClientLevel,
                        @Nullable LevelChunk clientChunk,
                        @Nullable LevelChunk serverChunk) {
        displayer.addToGroup(GROUP, List.of(
                "[SuperLead] conns=" + RopeDebugStats.totalConnections
                        + " sim=" + RopeDebugStats.simEntries
                        + " render=" + RopeDebugStats.renderEntries,
                "[SuperLead] dyn=" + RopeDebugStats.dynamicJobs
                    + " claimed=" + RopeDebugStats.chunkMeshClaimed
                    + " elig=" + RopeDebugStats.chunkMeshEligible
                    + " wait=" + RopeDebugStats.chunkMeshWaitingQuiet,
                "[SuperLead] mesh src sim=" + RopeDebugStats.chunkMeshClaimedFromSim
                    + " anchor=" + RopeDebugStats.chunkMeshClaimedAnchorBake
                    + " ready " + RopeDebugStats.chunkMeshReadyFromSim + "/"
                    + RopeDebugStats.chunkMeshReadyAnchorBake
                    + " miss=" + RopeDebugStats.chunkMeshMissingAnchors,
                "[SuperLead] bake " + RopeDebugStats.bakeCacheHits + "h/"
                    + RopeDebugStats.bakeCacheMisses + "m"
                    + " inelig=" + RopeDebugStats.chunkMeshIneligible
                    + " atts=" + RopeDebugStats.attachmentsTotal
                    + " sims=" + RopeDebugStats.simCount,
                "[SuperLead] nodes=" + RopeDebugStats.totalRenderNodes
                        + " (dyn=" + RopeDebugStats.dynamicNodesTotal
                        + " mesh=" + RopeDebugStats.chunkMeshNodesTotal + ")"
                        + " verts=" + RopeDebugStats.verticesEmitted,
                "[SuperLead] meshSec=" + RopeDebugStats.chunkMeshSections
                    + " snap=" + RopeDebugStats.chunkMeshSnapshots,
                "[SuperLead] zones=" + PhysicsZonesClient.zones().size()
                        + " overrides=" + zoneOverrideCount()
                        + " zoneEpoch=" + PhysicsZonesClient.epoch(),
                String.format(Locale.ROOT, "[SuperLead] push dV=%.3f dir=(%.2f, %.2f) contacts=%d",
                        RopeDebugStats.pushMagnitude,
                        pushDirX(),
                        pushDirZ(),
                        RopeDebugStats.pushContacts),
                "[SuperLead] mode phys=" + bool(ClientTuning.MODE_PHYSICS.get())
                        + " 3d=" + bool(ClientTuning.MODE_RENDER3D.get())
                        + " chunkMesh=" + bool(ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get())));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    private static String bool(Boolean b) {
        return b != null && b ? "on" : "off";
    }

    private static int zoneOverrideCount() {
        int count = 0;
        for (var zone : PhysicsZonesClient.zones()) {
            count += zone.overrides().size();
        }
        return count;
    }

    private static float pushDirX() {
        return RopeDebugStats.pushMagnitude <= 1.0e-5F ? 0.0F : RopeDebugStats.pushX / RopeDebugStats.pushMagnitude;
    }

    private static float pushDirZ() {
        return RopeDebugStats.pushMagnitude <= 1.0e-5F ? 0.0F : RopeDebugStats.pushZ / RopeDebugStats.pushMagnitude;
    }
}
