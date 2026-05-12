package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdateRopeAttachmentSignText(UUID connectionId, UUID attachmentId,
        boolean frontText, String line0, String line1, String line2, String line3)
        implements CustomPacketPayload {
    private static final int MAX_LINE_LENGTH = 384;

    public static final CustomPacketPayload.Type<UpdateRopeAttachmentSignText> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "update_rope_attachment_sign_text"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateRopeAttachmentSignText> STREAM_CODEC = CustomPacketPayload
            .codec(UpdateRopeAttachmentSignText::write, UpdateRopeAttachmentSignText::read);

    public UpdateRopeAttachmentSignText {
        line0 = sanitize(line0);
        line1 = sanitize(line1);
        line2 = sanitize(line2);
        line3 = sanitize(line3);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public List<String> lines() {
        return List.of(line0, line1, line2, line3);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
        buffer.writeBoolean(frontText);
        buffer.writeUtf(line0, MAX_LINE_LENGTH);
        buffer.writeUtf(line1, MAX_LINE_LENGTH);
        buffer.writeUtf(line2, MAX_LINE_LENGTH);
        buffer.writeUtf(line3, MAX_LINE_LENGTH);
    }

    private static UpdateRopeAttachmentSignText read(RegistryFriendlyByteBuf buffer) {
        return new UpdateRopeAttachmentSignText(buffer.readUUID(), buffer.readUUID(), buffer.readBoolean(),
                buffer.readUtf(MAX_LINE_LENGTH), buffer.readUtf(MAX_LINE_LENGTH),
                buffer.readUtf(MAX_LINE_LENGTH), buffer.readUtf(MAX_LINE_LENGTH));
    }

    private static String sanitize(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH);
    }
}