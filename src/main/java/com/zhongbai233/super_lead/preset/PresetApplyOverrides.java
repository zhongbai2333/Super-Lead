package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetApplyOverrides(String presetName, Map<String, String> overrides) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetApplyOverrides> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_apply_overrides"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetApplyOverrides> STREAM_CODEC = CustomPacketPayload
            .codec(PresetApplyOverrides::write, PresetApplyOverrides::read);

    public PresetApplyOverrides {
        overrides = PresetPayloadCodecs.immutableCopy(overrides);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(presetName);
        PresetPayloadCodecs.writeStringMap(buf, overrides);
    }

    private static PresetApplyOverrides read(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf();
        return new PresetApplyOverrides(name, PresetPayloadCodecs.readStringMap(buf));
    }
}
