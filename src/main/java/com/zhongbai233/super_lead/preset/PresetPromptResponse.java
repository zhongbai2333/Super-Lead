package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetPromptResponse(String presetName, boolean accepted) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetPromptResponse> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_prompt_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetPromptResponse> STREAM_CODEC = CustomPacketPayload
            .codec(PresetPromptResponse::write, PresetPromptResponse::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(presetName, PresetPayloadCodecs.NAME_MAX_LENGTH);
        buf.writeBoolean(accepted);
    }

    private static PresetPromptResponse read(RegistryFriendlyByteBuf buf) {
        return new PresetPromptResponse(buf.readUtf(PresetPayloadCodecs.NAME_MAX_LENGTH), buf.readBoolean());
    }
}
