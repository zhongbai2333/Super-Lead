package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C broadcast describing the current set of rope-vs-player contacts in a dimension.
 * The rope is server-authoritative for gameplay (player push-back) and the snapshot
 * tells every observer where to bend each rope visually so all clients stay in sync.
 */
public record RopeContactPulse(List<Entry> contacts) implements CustomPacketPayload {
    public record Entry(UUID ropeId, float t, float dx, float dy, float dz) {}

    public static final CustomPacketPayload.Type<RopeContactPulse> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "rope_contact_pulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RopeContactPulse> STREAM_CODEC =
            CustomPacketPayload.codec(RopeContactPulse::write, RopeContactPulse::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(contacts.size());
        for (Entry e : contacts) {
            buffer.writeUUID(e.ropeId);
            buffer.writeFloat(e.t);
            buffer.writeFloat(e.dx);
            buffer.writeFloat(e.dy);
            buffer.writeFloat(e.dz);
        }
    }

    private static RopeContactPulse read(RegistryFriendlyByteBuf buffer) {
        int n = buffer.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(
                    buffer.readUUID(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat()));
        }
        return new RopeContactPulse(list);
    }
}
