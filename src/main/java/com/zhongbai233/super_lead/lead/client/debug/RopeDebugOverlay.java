package com.zhongbai233.super_lead.lead.client.debug;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
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
                        + " mesh=" + RopeDebugStats.chunkMeshClaimed
                        + " bake " + RopeDebugStats.bakeCacheHits + "h/"
                        + RopeDebugStats.bakeCacheMisses + "m",
                "[SuperLead] nodes=" + RopeDebugStats.totalRenderNodes
                        + " (dyn=" + RopeDebugStats.dynamicNodesTotal
                        + " mesh=" + RopeDebugStats.chunkMeshNodesTotal + ")"
                        + " verts=" + RopeDebugStats.verticesEmitted,
                "[SuperLead] meshSec=" + RopeDebugStats.chunkMeshSections
                        + " snap=" + RopeDebugStats.chunkMeshSnapshots
                        + " atts=" + RopeDebugStats.attachmentsTotal
                        + " sims=" + RopeDebugStats.simCount,
                "[SuperLead] zones=" + PhysicsZonesClient.zones().size()
                        + " overrides=" + zoneOverrideCount()
                        + " zoneEpoch=" + PhysicsZonesClient.epoch(),
                "[SuperLead] mode phys=" + bool(ClientTuning.MODE_PHYSICS.get())
                        + " 3d=" + bool(ClientTuning.MODE_RENDER3D.get())
                        + " chunkMesh=" + bool(ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get())));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    private static String bool(Boolean b) { return b != null && b ? "on" : "off"; }

        private static int zoneOverrideCount() {
                int n = 0;
                for (var zone : PhysicsZonesClient.zones()) n += zone.overrides().size();
                return n;
        }
}
