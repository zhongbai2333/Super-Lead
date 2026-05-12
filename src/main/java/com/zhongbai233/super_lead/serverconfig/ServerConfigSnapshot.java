package com.zhongbai233.super_lead.serverconfig;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.preset.PresetPayloadCodecs;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ServerConfigSnapshot(Map<String, String> values) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerConfigSnapshot> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "server_config_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSnapshot> STREAM_CODEC = CustomPacketPayload
            .codec(ServerConfigSnapshot::write, ServerConfigSnapshot::read);

    public ServerConfigSnapshot {
        values = PresetPayloadCodecs.immutableCopy(values);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        PresetPayloadCodecs.writeStringMap(buf, values);
    }

    private static ServerConfigSnapshot read(RegistryFriendlyByteBuf buf) {
        return new ServerConfigSnapshot(PresetPayloadCodecs.readStringMap(buf));
    }
}
