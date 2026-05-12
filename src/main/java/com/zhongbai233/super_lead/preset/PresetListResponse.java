package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetListResponse(List<String> names) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetListResponse> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_list_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetListResponse> STREAM_CODEC = CustomPacketPayload
            .codec(PresetListResponse::write, PresetListResponse::read);

    public PresetListResponse {
        names = List.copyOf(names);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(names.size());
        for (String s : names)
            buf.writeUtf(s);
    }

    private static PresetListResponse read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            out.add(buf.readUtf());
        return new PresetListResponse(out);
    }
}
