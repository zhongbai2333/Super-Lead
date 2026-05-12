package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

public record UpdateSignAttachmentAppearance(UUID connectionId, UUID attachmentId,
        int operation, int dyeColor, boolean frontText) implements CustomPacketPayload {
    public static final int OP_DYE = 0;
    public static final int OP_GLOW = 1;

    public static final CustomPacketPayload.Type<UpdateSignAttachmentAppearance> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "update_sign_attachment_appearance"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateSignAttachmentAppearance> STREAM_CODEC = CustomPacketPayload
            .codec(UpdateSignAttachmentAppearance::write, UpdateSignAttachmentAppearance::read);

    public static UpdateSignAttachmentAppearance dye(UUID connectionId, UUID attachmentId,
            DyeColor color, boolean frontText) {
        return new UpdateSignAttachmentAppearance(connectionId, attachmentId, OP_DYE, color.getId(), frontText);
    }

    public static UpdateSignAttachmentAppearance glow(UUID connectionId, UUID attachmentId,
            boolean frontText) {
        return new UpdateSignAttachmentAppearance(connectionId, attachmentId, OP_GLOW, -1, frontText);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeUUID(attachmentId);
        buffer.writeVarInt(operation);
        buffer.writeVarInt(dyeColor);
        buffer.writeBoolean(frontText);
    }

    private static UpdateSignAttachmentAppearance read(RegistryFriendlyByteBuf buffer) {
        return new UpdateSignAttachmentAppearance(buffer.readUUID(), buffer.readUUID(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }
}
