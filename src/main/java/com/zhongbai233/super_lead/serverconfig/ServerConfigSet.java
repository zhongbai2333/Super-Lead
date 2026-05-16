package com.zhongbai233.super_lead.serverconfig;

import com.zhongbai233.super_lead.preset.PresetPayloadCodecs;
import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S request to update one whitelisted runtime server config key. */
public record ServerConfigSet(String key, String value) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerConfigSet> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "server_config_set"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSet> STREAM_CODEC = CustomPacketPayload
            .codec(ServerConfigSet::write, ServerConfigSet::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(key, PresetPayloadCodecs.KEY_MAX_LENGTH);
        buf.writeUtf(value, PresetPayloadCodecs.VALUE_MAX_LENGTH);
    }

    private static ServerConfigSet read(RegistryFriendlyByteBuf buf) {
        return new ServerConfigSet(buf.readUtf(PresetPayloadCodecs.KEY_MAX_LENGTH),
                buf.readUtf(PresetPayloadCodecs.VALUE_MAX_LENGTH));
    }
}
