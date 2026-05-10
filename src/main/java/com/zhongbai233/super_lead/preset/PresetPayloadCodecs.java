package com.zhongbai233.super_lead.preset;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class PresetPayloadCodecs {
    private PresetPayloadCodecs() {}

    public static void writeStringMap(RegistryFriendlyByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static Map<String, String> readStringMap(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, String> m = new LinkedHashMap<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf();
            String v = buf.readUtf();
            m.put(k, v);
        }
        return m;
    }

    public static Map<String, String> immutableCopy(Map<String, String> in) {
        return Map.copyOf(in == null ? new HashMap<>() : in);
    }
}
