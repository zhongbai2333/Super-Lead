package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ToggleRopeAttachmentForm(UUID connectionId, UUID attachmentId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ToggleRopeAttachmentForm> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "toggle_rope_attachment_form"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleRopeAttachmentForm> STREAM_CODEC = CustomPacketPayload
            .codec(ToggleRopeAttachmentForm::write, ToggleRopeAttachmentForm::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
    }

    private static ToggleRopeAttachmentForm read(RegistryFriendlyByteBuf buffer) {
        return new ToggleRopeAttachmentForm(buffer.readUUID(), buffer.readUUID());
    }
}
