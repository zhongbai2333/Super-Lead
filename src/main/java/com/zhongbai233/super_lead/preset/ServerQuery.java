package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Lightweight client-to-server query packet for GUI refresh actions. */
public record ServerQuery(Kind kind) implements CustomPacketPayload {
    public enum Kind {
        PRESET_LIST,
        ZONE_LIST,
        SERVER_CONFIG
    }

    public static final CustomPacketPayload.Type<ServerQuery> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "server_query"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerQuery> STREAM_CODEC = CustomPacketPayload
            .codec(ServerQuery::write, ServerQuery::read);

    public ServerQuery {
        kind = kind == null ? Kind.PRESET_LIST : kind;
    }

    public static ServerQuery presetList() {
        return new ServerQuery(Kind.PRESET_LIST);
    }

    public static ServerQuery zoneList() {
        return new ServerQuery(Kind.ZONE_LIST);
    }

    public static ServerQuery serverConfig() {
        return new ServerQuery(Kind.SERVER_CONFIG);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(kind);
    }

    private static ServerQuery read(RegistryFriendlyByteBuf buffer) {
        return new ServerQuery(buffer.readEnum(Kind.class));
    }
}