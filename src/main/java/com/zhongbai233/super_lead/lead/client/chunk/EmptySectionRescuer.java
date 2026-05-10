package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.Super_lead;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class EmptySectionRescuer {

    private EmptySectionRescuer() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        StaticRopeChunkRegistry reg = StaticRopeChunkRegistry.get();
        if (!reg.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LevelRenderer levelRenderer = mc.levelRenderer;
        if (level == null || levelRenderer == null) return;

        LongOpenHashSet emptySet = level.getChunkSource().getLoadedEmptySections();
        if (emptySet.isEmpty()) return;

        for (long key : reg.publishedSectionKeys()) {
            if (emptySet.remove(key)) {
                levelRenderer.onSectionBecomingNonEmpty(key);
            }
        }
    }
}
