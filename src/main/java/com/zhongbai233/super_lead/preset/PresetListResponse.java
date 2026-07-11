package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetListResponse(List<Entry> entries) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetListResponse> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_list_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetListResponse> STREAM_CODEC = CustomPacketPayload
            .codec((payload, buf) -> payload.write(buf), PresetListResponse::read);

    public PresetListResponse {
        entries = List.copyOf(entries);
    }

    public PresetListResponse(List<String> names, boolean legacyNames) {
        this(names.stream().map(name -> new Entry(name, "", false, false, false, false, 0)).toList());
    }

    public List<String> names() {
        return entries.stream().map(entry -> entry.name()).toList();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        PresetPayloadCodecs.writeCount(buf, entries.size(), PresetPayloadCodecs.LIST_MAX_ENTRIES, "preset list");
        for (Entry entry : entries) {
            entry.write(buf);
        }
    }

    private static PresetListResponse read(RegistryFriendlyByteBuf buf) {
        int n = PresetPayloadCodecs.readCount(buf, PresetPayloadCodecs.LIST_MAX_ENTRIES, "preset list");
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(Entry.read(buf));
        }
        return new PresetListResponse(out);
    }

    public record Entry(String name, String owner, boolean ownedByCurrentPlayer, boolean global,
            boolean canEdit, boolean canDelete, int zoneUseCount) {
        public Entry {
            name = name == null ? "" : name;
            owner = owner == null ? "" : owner;
        }

        public static Entry of(String name, UUID owner, boolean ownedByCurrentPlayer,
                boolean canEdit, boolean canDelete, int zoneUseCount) {
            return new Entry(name, owner == null ? "" : owner.toString(), ownedByCurrentPlayer,
                    owner == null, canEdit, canDelete, zoneUseCount);
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(name, PresetPayloadCodecs.NAME_MAX_LENGTH);
            buf.writeUtf(owner, PresetPayloadCodecs.VALUE_MAX_LENGTH);
            buf.writeBoolean(ownedByCurrentPlayer);
            buf.writeBoolean(global);
            buf.writeBoolean(canEdit);
            buf.writeBoolean(canDelete);
            buf.writeVarInt(zoneUseCount);
        }

        private static Entry read(RegistryFriendlyByteBuf buf) {
            return new Entry(
                    buf.readUtf(PresetPayloadCodecs.NAME_MAX_LENGTH),
                    buf.readUtf(PresetPayloadCodecs.VALUE_MAX_LENGTH),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    Math.max(0, buf.readVarInt()));
        }
    }
}
