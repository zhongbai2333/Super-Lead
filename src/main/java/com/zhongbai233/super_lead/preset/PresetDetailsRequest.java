package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetDetailsRequest(String name) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetDetailsRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_details_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetDetailsRequest> STREAM_CODEC = CustomPacketPayload
            .codec(PresetDetailsRequest::write, PresetDetailsRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(name, PresetPayloadCodecs.NAME_MAX_LENGTH);
    }

    private static PresetDetailsRequest read(RegistryFriendlyByteBuf buf) {
        return new PresetDetailsRequest(buf.readUtf(PresetPayloadCodecs.NAME_MAX_LENGTH));
    }
}
