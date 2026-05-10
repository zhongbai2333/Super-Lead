package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RemoveRopeAttachment(UUID connectionId, UUID attachmentId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RemoveRopeAttachment> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "remove_rope_attachment"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveRopeAttachment> STREAM_CODEC =
            CustomPacketPayload.codec(RemoveRopeAttachment::write, RemoveRopeAttachment::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
    }

    private static RemoveRopeAttachment read(RegistryFriendlyByteBuf buffer) {
        return new RemoveRopeAttachment(buffer.readUUID(), buffer.readUUID());
    }
}
