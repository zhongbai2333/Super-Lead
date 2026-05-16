package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S->C cache of preset packages used by ropes in the current dimension.
 *
 * <p>
 * This deliberately carries preset names and override values only. Zone
 * positions stay
 * server-private unless an OP explicitly asks for the zone preview list.
 */
public record SyncDimensionPresets(Map<String, Map<String, String>> presets) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncDimensionPresets> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_dimension_presets"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDimensionPresets> STREAM_CODEC = CustomPacketPayload
            .codec(SyncDimensionPresets::write, SyncDimensionPresets::read);

    public SyncDimensionPresets {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        if (presets != null) {
            for (Map.Entry<String, Map<String, String>> entry : presets.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isBlank())
                    continue;
                copy.put(name, PresetPayloadCodecs.immutableCopy(entry.getValue()));
            }
        }
        presets = Map.copyOf(copy);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        PresetPayloadCodecs.writeCount(buffer, presets.size(), PresetPayloadCodecs.LIST_MAX_ENTRIES,
                "dimension preset list");
        for (Map.Entry<String, Map<String, String>> entry : presets.entrySet()) {
            buffer.writeUtf(entry.getKey(), 64);
            PresetPayloadCodecs.writeStringMap(buffer, entry.getValue());
        }
    }

    private static SyncDimensionPresets read(RegistryFriendlyByteBuf buffer) {
        int size = PresetPayloadCodecs.readCount(buffer, PresetPayloadCodecs.LIST_MAX_ENTRIES,
                "dimension preset list");
        Map<String, Map<String, String>> presets = new LinkedHashMap<>(Math.max(8, size));
        for (int i = 0; i < size; i++) {
            presets.put(buffer.readUtf(64), PresetPayloadCodecs.readStringMap(buffer));
        }
        return new SyncDimensionPresets(presets);
    }
}
