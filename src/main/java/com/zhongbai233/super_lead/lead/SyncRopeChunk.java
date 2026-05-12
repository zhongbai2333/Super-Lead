package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

public record SyncRopeChunk(ChunkPos chunk, List<LeadConnection> connections) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncRopeChunk> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_rope_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRopeChunk> STREAM_CODEC =
            CustomPacketPayload.codec(SyncRopeChunk::write, SyncRopeChunk::read);

    public SyncRopeChunk {
        connections = List.copyOf(connections);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeChunkPos(chunk);
        buffer.writeVarInt(connections.size());
        for (LeadConnection connection : connections) {
            SyncConnections.writeConnection(buffer, connection);
        }
    }

    private static SyncRopeChunk read(RegistryFriendlyByteBuf buffer) {
        ChunkPos chunk = buffer.readChunkPos();
        int size = buffer.readVarInt();
        ArrayList<LeadConnection> connections = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            connections.add(SyncConnections.readConnection(buffer));
        }
        return new SyncRopeChunk(chunk, connections);
    }
}
