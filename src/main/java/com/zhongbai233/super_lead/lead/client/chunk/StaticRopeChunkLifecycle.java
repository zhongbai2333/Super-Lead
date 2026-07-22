package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class StaticRopeChunkLifecycle {

    private static final double BLOCK_LIGHT_UPDATE_RADIUS = 15.0D;
    private static boolean tuningHooked = false;

    private StaticRopeChunkLifecycle() {
    }

    public static void ensureTuningHook() {
        if (tuningHooked)
            return;
        tuningHooked = true;
        ClientTuning.addListener((key, value) -> {
            if (key == ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES
                    || key == ClientTuning.MODE_RENDER3D
                    || key.group.startsWith("physics")
                    || key.group.equals("render.geom")
                    || key.group.equals("render.color")) {
                Minecraft mc = Minecraft.getInstance();
                ClientLevel level = mc.level;
                if (level == null) {
                    StaticRopeChunkRegistry.get().clear();
                    return;
                }
                List<LeadConnection> conns = SuperLeadNetwork.connections(level);
                SuperLeadClientEvents.disturbConnections(
                        level,
                    conns.stream().map(connection -> connection.id()).toList(),
                        level.getGameTime() + 8L);
            }
        });
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ensureTuningHook();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LeadEndpointLayout.clearClientCache();
        StaticRopeChunkRegistry.get().clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            LeadEndpointLayout.clearClientCache();
            StaticRopeChunkRegistry.get().clear();
        }
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        invalidateNearChangedBlock(event.getPos());
    }

    @SubscribeEvent
    public static void onNeighborNotified(BlockEvent.NeighborNotifyEvent event) {
        invalidateNearChangedBlock(event.getPos());
    }

    private static void invalidateNearChangedBlock(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            onClientBlockChanged(level, pos);
        }
    }

    /** Called after a block state has actually changed in the client world. */
    public static void onClientBlockChanged(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null)
            return;
        LeadEndpointLayout.invalidateClientNear(level, pos);
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        // Nearby geometry must return to dynamic simulation. Explicitly wake the
        // matching sims as well: the mesh hold is shorter than the settled block-hash
        // polling interval, so relying on polling could re-bake the stale shape.
        var affected = registry.invalidateNearBlock(level, pos);
        SuperLeadClientEvents.wakeForTerrainChange(affected);
        // Light propagation has a wider influence than physical collision changes.
        registry.requestLightRebuildNear(level, List.of(pos), BLOCK_LIGHT_UPDATE_RADIUS);
    }
}
