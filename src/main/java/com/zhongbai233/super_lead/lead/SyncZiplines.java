package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S→C snapshot of players currently riding Super Lead ziplines in this dimension. */
public record SyncZiplines(List<Entry> entries) implements CustomPacketPayload {
    public record Entry(int entityId, UUID connectionId, float t) {
    }

    public static final CustomPacketPayload.Type<SyncZiplines> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_ziplines"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncZiplines> STREAM_CODEC = CustomPacketPayload
            .codec(SyncZiplines::write, SyncZiplines::read);

    public SyncZiplines {
        entries = List.copyOf(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeVarInt(entry.entityId());
            buffer.writeUUID(entry.connectionId());
            buffer.writeFloat(entry.t());
        }
    }

    private static SyncZiplines read(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        ArrayList<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buffer.readVarInt(), buffer.readUUID(), buffer.readFloat()));
        }
        return new SyncZiplines(entries);
    }
}