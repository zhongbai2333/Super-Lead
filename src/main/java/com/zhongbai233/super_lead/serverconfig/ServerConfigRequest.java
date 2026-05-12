package com.zhongbai233.super_lead.serverconfig;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ServerConfigRequest() implements CustomPacketPayload {
    public static final ServerConfigRequest INSTANCE = new ServerConfigRequest();
    public static final CustomPacketPayload.Type<ServerConfigRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "server_config_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigRequest> STREAM_CODEC = CustomPacketPayload
            .codec(ServerConfigRequest::write, ServerConfigRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
    }

    private static ServerConfigRequest read(RegistryFriendlyByteBuf buf) {
        return INSTANCE;
    }
}
