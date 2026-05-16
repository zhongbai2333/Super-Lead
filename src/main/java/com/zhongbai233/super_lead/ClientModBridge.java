package com.zhongbai233.super_lead;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Keeps the common mod constructor free of direct client-only class references.
 */
public final class ClientModBridge {
    private static final String ROPE_DEBUG_OVERLAY =
            "com.zhongbai233.super_lead.lead.client.debug.RopeDebugOverlay";
    private static final String CLIENT_MENUS =
            "com.zhongbai233.super_lead.lead.client.cargo.SuperLeadClientMenus";
    private static final String CLIENT_PAYLOADS =
            "com.zhongbai233.super_lead.lead.client.SuperLeadClientPayloads";

    private ClientModBridge() {
    }

    public static void register(IEventBus modEventBus) {
        invokeRegister(ROPE_DEBUG_OVERLAY, modEventBus);
        invokeRegister(CLIENT_MENUS, modEventBus);
        modEventBus.addListener(ClientModBridge::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        try {
            Class.forName(CLIENT_PAYLOADS)
                    .getMethod("register", RegisterPayloadHandlersEvent.class)
                    .invoke(null, event);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to register Super Lead client payload handlers", e);
        }
    }

    private static void invokeRegister(String className, IEventBus modEventBus) {
        try {
            Class.forName(className)
                    .getMethod("register", IEventBus.class)
                    .invoke(null, modEventBus);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to register Super Lead client init: " + className, e);
        }
    }
}
