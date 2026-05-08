package com.zhongbai233.super_lead;

import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(Super_lead.MODID)
public class Super_lead {
    public static final String MODID = "super_lead";

    public Super_lead(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(SuperLeadPayloads::register);
    }
}
