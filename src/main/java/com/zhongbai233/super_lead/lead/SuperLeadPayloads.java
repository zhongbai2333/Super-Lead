package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class SuperLeadPayloads {
    private SuperLeadPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SyncConnections.TYPE, SyncConnections.STREAM_CODEC, SuperLeadPayloads::handleSyncConnections);
    }

    public static void sendToPlayer(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            PacketDistributor.sendToPlayer(player, new SyncConnections(SuperLeadSavedData.get(level).connections()));
        }
    }

    public static void sendToDimension(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, new SyncConnections(SuperLeadSavedData.get(level).connections()));
    }

    private static void handleSyncConnections(SyncConnections payload, IPayloadContext context) {
        SuperLeadNetwork.replaceConnections(context.player().level(), payload.connections());
    }

    public record SyncConnections(List<LeadConnection> connections) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncConnections> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_connections"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncConnections> STREAM_CODEC = CustomPacketPayload.codec(
                SyncConnections::write,
                SyncConnections::read);

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
                buffer.writeUUID(connection.id());
                writeAnchor(buffer, connection.from());
                writeAnchor(buffer, connection.to());
                buffer.writeEnum(connection.kind());
                buffer.writeVarInt(connection.power());
            }
        }

        private static SyncConnections read(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            java.util.ArrayList<LeadConnection> connections = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                connections.add(new LeadConnection(
                        buffer.readUUID(),
                        readAnchor(buffer),
                        readAnchor(buffer),
                        buffer.readEnum(LeadKind.class),
                        buffer.readVarInt()));
            }
            return new SyncConnections(connections);
        }

        private static void writeAnchor(RegistryFriendlyByteBuf buffer, LeadAnchor anchor) {
            buffer.writeBlockPos(anchor.pos());
            buffer.writeEnum(anchor.face());
        }

        private static LeadAnchor readAnchor(RegistryFriendlyByteBuf buffer) {
            return new LeadAnchor(buffer.readBlockPos(), buffer.readEnum(Direction.class));
        }
    }
}
