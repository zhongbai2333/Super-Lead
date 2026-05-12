package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetListRequest() implements CustomPacketPayload {
    public static final PresetListRequest INSTANCE = new PresetListRequest();
    public static final CustomPacketPayload.Type<PresetListRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_list_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetListRequest> STREAM_CODEC = CustomPacketPayload
            .codec(PresetListRequest::write, PresetListRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
    }

    private static PresetListRequest read(RegistryFriendlyByteBuf buf) {
        return INSTANCE;
    }
}
