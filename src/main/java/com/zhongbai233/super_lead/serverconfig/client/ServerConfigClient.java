package com.zhongbai233.super_lead.serverconfig.client;

import com.zhongbai233.super_lead.serverconfig.ServerConfigSnapshot;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

public final class ServerConfigClient {
    private static volatile Map<String, String> last = Map.of();
    private static volatile Consumer<Map<String, String>> listener;

    private ServerConfigClient() {}

    public static Map<String, String> last() { return last; }

    public static void setListener(Consumer<Map<String, String>> l) { listener = l; }

    public static void onSnapshot(ServerConfigSnapshot payload) {
        last = Map.copyOf(payload.values());
        Consumer<Map<String, String>> l = listener;
        if (l != null) Minecraft.getInstance().execute(() -> l.accept(last));
    }
}
