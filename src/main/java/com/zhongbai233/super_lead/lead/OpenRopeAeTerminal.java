package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OpenRopeAeTerminal(UUID connectionId, UUID attachmentId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenRopeAeTerminal> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "open_rope_ae_terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRopeAeTerminal> STREAM_CODEC = CustomPacketPayload
            .codec(OpenRopeAeTerminal::write, OpenRopeAeTerminal::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
    }

    private static OpenRopeAeTerminal read(RegistryFriendlyByteBuf buffer) {
        return new OpenRopeAeTerminal(buffer.readUUID(), buffer.readUUID());
    }
}