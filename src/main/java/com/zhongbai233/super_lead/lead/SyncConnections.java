package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncConnections(List<LeadConnection> connections) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncConnections> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_connections"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncConnections> STREAM_CODEC =
            CustomPacketPayload.codec(SyncConnections::write, SyncConnections::read);

    public SyncConnections {
        connections = List.copyOf(connections);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(connections.size());
        for (LeadConnection connection : connections) {
            writeConnection(buffer, connection);
        }
    }

    private static SyncConnections read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        ArrayList<LeadConnection> connections = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            connections.add(readConnection(buffer));
        }
        return new SyncConnections(connections);
    }

    static void writeConnection(RegistryFriendlyByteBuf buffer, LeadConnection connection) {
        buffer.writeUUID(connection.id());
        writeAnchor(buffer, connection.from());
        writeAnchor(buffer, connection.to());
        buffer.writeEnum(connection.kind());
        buffer.writeVarInt(connection.power());
        buffer.writeVarInt(connection.tier());
        buffer.writeVarInt(connection.extractAnchor());
        buffer.writeVarInt(connection.attachments().size());
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
        int attachCount = buffer.readVarInt();
        ArrayList<RopeAttachment> attachments = new ArrayList<>(attachCount);
        for (int j = 0; j < attachCount; j++) {
            attachments.add(RopeAttachment.STREAM_CODEC.decode(buffer));
        }
        return new LeadConnection(id, from, to, kind, power, tier, extract, attachments);
    }

    private static void writeAnchor(RegistryFriendlyByteBuf buffer, LeadAnchor anchor) {
        buffer.writeBlockPos(anchor.pos());
        buffer.writeEnum(anchor.face());
    }

    private static LeadAnchor readAnchor(RegistryFriendlyByteBuf buffer) {
        return new LeadAnchor(buffer.readBlockPos(), buffer.readEnum(Direction.class));
    }
}
