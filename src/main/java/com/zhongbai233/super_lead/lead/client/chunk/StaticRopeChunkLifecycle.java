package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class StaticRopeChunkLifecycle {

    private static boolean tuningHooked = false;

    private StaticRopeChunkLifecycle() {}

    public static void ensureTuningHook() {
        if (tuningHooked) return;
        tuningHooked = true;
        ClientTuning.addListener((key, value) -> {
            if (key == ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES
                    || key == ClientTuning.MODE_RENDER3D) {
                Minecraft mc = Minecraft.getInstance();
                ClientLevel level = mc.level;
                if (level == null) {
                    StaticRopeChunkRegistry.get().clear();
                    return;
                }
                List<LeadConnection> conns = SuperLeadNetwork.connections(level);
                StaticRopeChunkRegistry.get().invalidateAll(level, conns);
            }
        });
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ensureTuningHook();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        StaticRopeChunkRegistry.get().clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            StaticRopeChunkRegistry.get().clear();
        }
    }
}
