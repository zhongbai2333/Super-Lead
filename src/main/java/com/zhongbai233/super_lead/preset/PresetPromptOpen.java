package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetPromptOpen(String presetName, Map<String, String> overrides) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetPromptOpen> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_prompt_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetPromptOpen> STREAM_CODEC = CustomPacketPayload
            .codec(PresetPromptOpen::write, PresetPromptOpen::read);

    public PresetPromptOpen {
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

    private static PresetPromptOpen read(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf();
        return new PresetPromptOpen(name, PresetPayloadCodecs.readStringMap(buf));
    }
}
