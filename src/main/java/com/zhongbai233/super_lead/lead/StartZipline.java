package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** C→S request to mount a sync-zone rope as a zipline with a chain. */
public record StartZipline(UUID connectionId, boolean useOffhand, Vec3 hitPoint, double hitT)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StartZipline> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "start_zipline"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StartZipline> STREAM_CODEC = CustomPacketPayload
            .codec(StartZipline::write, StartZipline::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeBoolean(useOffhand);
        buffer.writeDouble(hitPoint.x);
        buffer.writeDouble(hitPoint.y);
        buffer.writeDouble(hitPoint.z);
        buffer.writeDouble(hitT);
    }

    private static StartZipline read(RegistryFriendlyByteBuf buffer) {
        return new StartZipline(
                buffer.readUUID(),
                buffer.readBoolean(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readDouble());
    }
}