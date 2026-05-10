package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class SuperLeadConfigKeyHandler {
    private SuperLeadConfigKeyHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (SuperLeadKeybindings.OPEN_CONFIG.consumeClick()) {
            SuperLeadConfigScreen.open();
        }
    }
}
