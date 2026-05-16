package com.zhongbai233.super_lead.lead;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class LeadConnectionPayloadCodec {
    static final int MAX_CONNECTIONS_PER_PAYLOAD = 65_536;
    static final int MAX_ATTACHMENTS_PER_CONNECTION = 4_096;
    static final int MAX_CONTACTS_PER_PAYLOAD = 65_536;
    static final int MAX_ZIPLINES_PER_PAYLOAD = 65_536;

    private LeadConnectionPayloadCodec() {
    }

    static void writeConnection(RegistryFriendlyByteBuf buffer, LeadConnection connection) {
        buffer.writeUUID(connection.id());
        writeAnchor(buffer, connection.from());
        writeAnchor(buffer, connection.to());
        buffer.writeEnum(connection.kind());
        buffer.writeVarInt(connection.power());
        buffer.writeVarInt(connection.tier());
        buffer.writeVarInt(connection.extractAnchor());
        buffer.writeUtf(connection.physicsPreset(), 64);
        buffer.writeUtf(connection.manualPhysicsPreset(), 64);
        buffer.writeBoolean(connection.adventurePlaced());
        if (connection.adventurePlaced()) {
            buffer.writeUUID(connection.adventureOwner());
        }
        writeCount(buffer, connection.attachments().size(), MAX_ATTACHMENTS_PER_CONNECTION, "rope attachment");
        for (RopeAttachment attachment : connection.attachments()) {
            RopeAttachment.STREAM_CODEC.encode(buffer, attachment);
        }
    }

    static LeadConnection readConnection(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        LeadAnchor from = readAnchor(buffer);
        LeadAnchor to = readAnchor(buffer);
        LeadKind kind = buffer.readEnum(LeadKind.class);
        int power = buffer.readVarInt();
        int tier = buffer.readVarInt();
        int extract = buffer.readVarInt();
        String physicsPreset = buffer.readUtf(64);
        String manualPhysicsPreset = buffer.readUtf(64);
        UUID adventureOwner = buffer.readBoolean()
                ? buffer.readUUID()
                : LeadConnection.NO_ADVENTURE_OWNER;
        int attachCount = readCount(buffer, MAX_ATTACHMENTS_PER_CONNECTION, "rope attachment");
        ArrayList<RopeAttachment> attachments = new ArrayList<>(attachCount);
        for (int j = 0; j < attachCount; j++) {
            attachments.add(RopeAttachment.STREAM_CODEC.decode(buffer));
        }
        return new LeadConnection(id, from, to, kind, power, tier, extract, attachments, physicsPreset,
                manualPhysicsPreset, adventureOwner);
    }

    private static void writeAnchor(RegistryFriendlyByteBuf buffer, LeadAnchor anchor) {
        buffer.writeBlockPos(anchor.pos());
        buffer.writeEnum(anchor.face());
    }

    private static LeadAnchor readAnchor(RegistryFriendlyByteBuf buffer) {
        return new LeadAnchor(buffer.readBlockPos(), buffer.readEnum(Direction.class));
    }

    static void writeCount(RegistryFriendlyByteBuf buffer, int count, int max, String label) {
        validateCount(count, max, label);
        buffer.writeVarInt(count);
    }

    static int readCount(RegistryFriendlyByteBuf buffer, int max, String label) {
        int count = buffer.readVarInt();
        validateCount(count, max, label);
        return count;
    }

    private static void validateCount(int count, int max, String label) {
        if (count < 0 || count > max) {
            throw new DecoderException(label + " count " + count + " outside 0.." + max);
        }
    }
}