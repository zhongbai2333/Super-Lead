package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetDetailsResponse(String name, boolean exists, Map<String, String> overrides)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetDetailsResponse> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_details_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetDetailsResponse> STREAM_CODEC =
            CustomPacketPayload.codec(PresetDetailsResponse::write, PresetDetailsResponse::read);

    public PresetDetailsResponse { overrides = PresetPayloadCodecs.immutableCopy(overrides); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeBoolean(exists);
        PresetPayloadCodecs.writeStringMap(buf, overrides);
    }

    private static PresetDetailsResponse read(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf();
        boolean exists = buf.readBoolean();
        Map<String, String> overrides = PresetPayloadCodecs.readStringMap(buf);
        return new PresetDetailsResponse(name, exists, overrides);
    }
}
