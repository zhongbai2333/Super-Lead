package com.zhongbai233.super_lead.tuning.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class SuperLeadKeybindings {
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.super_lead.open_config",
            InputConstants.UNKNOWN.getValue(),
            KeyMapping.Category.MISC);

    private SuperLeadKeybindings() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (OPEN_CONFIG.consumeClick()) {
            SuperLeadConfigScreen.open();
        }
    }
}
