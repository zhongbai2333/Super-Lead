package com.zhongbai233.super_lead.lead.client.cargo;

import com.zhongbai233.super_lead.lead.cargo.SuperLeadMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public final class SuperLeadClientMenus {
    private SuperLeadClientMenus() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(SuperLeadClientMenus::registerScreens);
    }

    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SuperLeadMenus.CARGO_MANIFEST.get(), CargoManifestScreen::new);
    }
}