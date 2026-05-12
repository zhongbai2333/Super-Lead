package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AddRopeAttachment(UUID connectionId, double t, boolean useOffhand, int frontSide)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AddRopeAttachment> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "add_rope_attachment"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AddRopeAttachment> STREAM_CODEC = CustomPacketPayload
            .codec(AddRopeAttachment::write, AddRopeAttachment::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeDouble(t);
        buffer.writeBoolean(useOffhand);
        buffer.writeVarInt(RopeAttachment.normalizeFrontSide(frontSide));
    }

    private static AddRopeAttachment read(RegistryFriendlyByteBuf buffer) {
        return new AddRopeAttachment(buffer.readUUID(), buffer.readDouble(), buffer.readBoolean(),
                buffer.readVarInt());
    }
}
