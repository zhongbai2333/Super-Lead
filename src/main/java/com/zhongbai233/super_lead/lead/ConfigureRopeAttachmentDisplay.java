package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-bound request to mutate a rope attachment's synchronized display settings. */
public record ConfigureRopeAttachmentDisplay(UUID connectionId, UUID attachmentId,
        int mountOverride, int displayModeOverride, int hangerOverride, int piercedOverride,
        double hangOffsetOverride, double mountOffsetOverride, double hangerLengthOverride,
        double hangerSpacingOverride, double scaleOverride, int frontSide, Map<String, String> modelStateOverride)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfigureRopeAttachmentDisplay> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "configure_rope_attachment_display"));

    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, ConfigureRopeAttachmentDisplay> STREAM_CODEC = CustomPacketPayload
            .codec((payload, buf) -> payload.write(buf), ConfigureRopeAttachmentDisplay::read);

    public ConfigureRopeAttachmentDisplay(UUID connectionId, UUID attachmentId) {
        this(connectionId, attachmentId, RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.DISPLAY_DEFAULT,
                RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.OVERRIDE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT, RopeAttachment.DOUBLE_DEFAULT,
                RopeAttachment.DOUBLE_DEFAULT, 1, Map.of());
    }

    public ConfigureRopeAttachmentDisplay {
        frontSide = RopeAttachment.normalizeFrontSide(frontSide);
        modelStateOverride = RopeAttachment.normalizeModelStateOverride(modelStateOverride);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
        buffer.writeVarInt(mountOverride);
        buffer.writeVarInt(displayModeOverride);
        buffer.writeVarInt(hangerOverride);
        buffer.writeVarInt(piercedOverride);
        buffer.writeDouble(hangOffsetOverride);
        buffer.writeDouble(mountOffsetOverride);
        buffer.writeDouble(hangerLengthOverride);
        buffer.writeDouble(hangerSpacingOverride);
        buffer.writeDouble(scaleOverride);
        buffer.writeVarInt(frontSide);
        RopeAttachment.writeModelStateOverride(buffer, modelStateOverride);
    }

    private static ConfigureRopeAttachmentDisplay read(RegistryFriendlyByteBuf buffer) {
        UUID connectionId = buffer.readUUID();
        UUID attachmentId = buffer.readUUID();
        int mountOverride = buffer.readVarInt();
        int displayModeOverride = buffer.readVarInt();
        int hangerOverride = buffer.readVarInt();
        int piercedOverride = buffer.readVarInt();
        double hangOffsetOverride = buffer.readDouble();
        double mountOffsetOverride = buffer.readDouble();
        double hangerLengthOverride = buffer.readDouble();
        double hangerSpacingOverride = buffer.readDouble();
        double scaleOverride = buffer.readDouble();
        int frontSide = buffer.readVarInt();
        Map<String, String> modelStateOverride = RopeAttachment.readModelStateOverride(buffer);
        return new ConfigureRopeAttachmentDisplay(connectionId, attachmentId, mountOverride, displayModeOverride,
                hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride, hangerLengthOverride,
                hangerSpacingOverride, scaleOverride, frontSide, modelStateOverride);
    }
}
