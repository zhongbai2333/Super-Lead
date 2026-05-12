package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C->S report for a locally detected player-vs-rope contact.
 * The client owns the high precision rope shape; the server treats this as a claim and
 * accepts it only if the contact is plausible for the authoritative endpoints and player box.
 */
public record ClientRopeContactReport(
        UUID ropeId,
        float t,
        float pointX,
        float pointY,
        float pointZ,
        float normalX,
        float normalZ,
        float inputX,
        float inputZ,
        float depth) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientRopeContactReport> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "client_rope_contact_report"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientRopeContactReport> STREAM_CODEC =
            CustomPacketPayload.codec(ClientRopeContactReport::write, ClientRopeContactReport::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(ropeId);
        buffer.writeFloat(t);
        buffer.writeFloat(pointX);
        buffer.writeFloat(pointY);
        buffer.writeFloat(pointZ);
        buffer.writeFloat(normalX);
        buffer.writeFloat(normalZ);
        buffer.writeFloat(inputX);
        buffer.writeFloat(inputZ);
        buffer.writeFloat(depth);
    }

    private static ClientRopeContactReport read(RegistryFriendlyByteBuf buffer) {
        return new ClientRopeContactReport(
                buffer.readUUID(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat());
    }
}
