package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S: create a physics zone from the shears selection GUI. */
public record ZoneCreateRequest(String name, String presetName, BlockPos from, BlockPos to)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneCreateRequest> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "zone_create_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneCreateRequest> STREAM_CODEC =
            CustomPacketPayload.codec(ZoneCreateRequest::write, ZoneCreateRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(name);
        buffer.writeUtf(presetName);
        buffer.writeBlockPos(from);
        buffer.writeBlockPos(to);
    }

    private static ZoneCreateRequest read(RegistryFriendlyByteBuf buffer) {
        return new ZoneCreateRequest(buffer.readUtf(64), buffer.readUtf(64),
                buffer.readBlockPos(), buffer.readBlockPos());
    }
}