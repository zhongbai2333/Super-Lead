package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.Super_lead;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class SuperLeadKeyMappingRegistration {
    private SuperLeadKeyMappingRegistration() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(SuperLeadKeybindings.OPEN_CONFIG);
    }
}
