package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncConnectionChanges(List<UUID> removed, List<LeadConnection> upserts)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncConnectionChanges> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_connection_changes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncConnectionChanges> STREAM_CODEC =
            CustomPacketPayload.codec(SyncConnectionChanges::write, SyncConnectionChanges::read);

    public SyncConnectionChanges {
        removed = List.copyOf(removed);
        upserts = List.copyOf(upserts);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(removed.size());
        for (UUID id : removed) {
            buffer.writeUUID(id);
        }
        buffer.writeVarInt(upserts.size());
        for (LeadConnection connection : upserts) {
            SyncConnections.writeConnection(buffer, connection);
        }
    }

    private static SyncConnectionChanges read(RegistryFriendlyByteBuf buffer) {
        int removedSize = buffer.readVarInt();
        ArrayList<UUID> removed = new ArrayList<>(removedSize);
        for (int i = 0; i < removedSize; i++) {
            removed.add(buffer.readUUID());
        }
        int upsertSize = buffer.readVarInt();
        ArrayList<LeadConnection> upserts = new ArrayList<>(upsertSize);
        for (int i = 0; i < upsertSize; i++) {
            upserts.add(SyncConnections.readConnection(buffer));
        }
        return new SyncConnectionChanges(removed, upserts);
    }
}
