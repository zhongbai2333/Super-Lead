package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S: request the current dimension's zone list. */
public record ZoneListRequest() implements CustomPacketPayload {
    public static final ZoneListRequest INSTANCE = new ZoneListRequest();
    public static final CustomPacketPayload.Type<ZoneListRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "zone_list_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneListRequest> STREAM_CODEC = CustomPacketPayload
            .codec(ZoneListRequest::write, ZoneListRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
    }

    private static ZoneListRequest read(RegistryFriendlyByteBuf buffer) {
        return INSTANCE;
    }
}