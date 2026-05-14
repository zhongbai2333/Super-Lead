package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdateCargoManifestTag(int containerId, boolean add, String tag) implements CustomPacketPayload {
    public static final Type<UpdateCargoManifestTag> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "update_cargo_manifest_tag"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCargoManifestTag> STREAM_CODEC = CustomPacketPayload
            .codec(UpdateCargoManifestTag::write, UpdateCargoManifestTag::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(add);
        buffer.writeUtf(tag, CargoManifestData.TAG_MAX_LENGTH);
    }

    private static UpdateCargoManifestTag read(RegistryFriendlyByteBuf buffer) {
        return new UpdateCargoManifestTag(buffer.readVarInt(), buffer.readBoolean(),
                buffer.readUtf(CargoManifestData.TAG_MAX_LENGTH));
    }
}