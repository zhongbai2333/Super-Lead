package com.zhongbai233.super_lead.preset;

import io.netty.handler.codec.DecoderException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Shared wire-format helpers for preset and server-configuration payloads. */
public final class PresetPayloadCodecs {
    public static final int NAME_MAX_LENGTH = 64;
    public static final int KEY_MAX_LENGTH = 128;
    public static final int VALUE_MAX_LENGTH = 512;
    public static final int MAP_MAX_ENTRIES = 512;
    public static final int LIST_MAX_ENTRIES = 4096;

    private PresetPayloadCodecs() {
    }

    public static void writeStringMap(RegistryFriendlyByteBuf buf, Map<String, String> map) {
        writeCount(buf, map.size(), MAP_MAX_ENTRIES, "string map");
        for (Map.Entry<String, String> e : map.entrySet()) {
            buf.writeUtf(e.getKey(), KEY_MAX_LENGTH);
            buf.writeUtf(e.getValue(), VALUE_MAX_LENGTH);
        }
    }

    public static Map<String, String> readStringMap(RegistryFriendlyByteBuf buf) {
        int n = readCount(buf, MAP_MAX_ENTRIES, "string map");
        Map<String, String> m = new LinkedHashMap<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf(KEY_MAX_LENGTH);
            String v = buf.readUtf(VALUE_MAX_LENGTH);
            m.put(k, v);
        }
        return m;
    }

    public static void writeCount(RegistryFriendlyByteBuf buf, int count, int max, String label) {
        validateCount(count, max, label);
        buf.writeVarInt(count);
    }

    public static int readCount(RegistryFriendlyByteBuf buf, int max, String label) {
        int count = buf.readVarInt();
        validateCount(count, max, label);
        return count;
    }

    private static void validateCount(int count, int max, String label) {
        if (count < 0 || count > max) {
            throw new DecoderException(label + " count " + count + " outside 0.." + max);
        }
    }

    public static Map<String, String> immutableCopy(Map<String, String> in) {
        return Map.copyOf(in == null ? new HashMap<>() : in);
    }
}
