package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdateCargoManifestOptions(int containerId, boolean whitelist, boolean matchNbt)
        implements CustomPacketPayload {
    public static final Type<UpdateCargoManifestOptions> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "update_cargo_manifest_options"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCargoManifestOptions> STREAM_CODEC = CustomPacketPayload
            .codec(UpdateCargoManifestOptions::write, UpdateCargoManifestOptions::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(whitelist);
        buffer.writeBoolean(matchNbt);
    }

    private static UpdateCargoManifestOptions read(RegistryFriendlyByteBuf buffer) {
        return new UpdateCargoManifestOptions(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }
}