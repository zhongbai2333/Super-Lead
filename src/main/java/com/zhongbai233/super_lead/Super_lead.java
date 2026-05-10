package com.zhongbai233.super_lead;

import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(Super_lead.MODID)
public class Super_lead {
    public static final String MODID = "super_lead";

    public Super_lead(IEventBus modEventBus, ModContainer modContainer) {
        SuperLeadItems.register(modEventBus);
        modEventBus.addListener(SuperLeadPayloads::register);
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(Config::onReload);
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
            com.zhongbai233.super_lead.lead.client.debug.RopeDebugOverlay.register(modEventBus);
        }
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
