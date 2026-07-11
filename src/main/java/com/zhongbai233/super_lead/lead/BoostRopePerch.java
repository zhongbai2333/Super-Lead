package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * C→S: scatter seeds on a client-picked rope to boost parrot perching
 * attractiveness. The server verifies the client's pick (same pattern as
 * {@link UseConnectionAction}) instead of doing its own ray-trace.
 */
public record BoostRopePerch(UUID connectionId, Vec3 hitPoint, double hitT)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BoostRopePerch> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "boost_rope_perch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoostRopePerch> STREAM_CODEC = CustomPacketPayload
            .codec((payload, buf) -> payload.write(buf), BoostRopePerch::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeDouble(hitPoint.x);
        buffer.writeDouble(hitPoint.y);
        buffer.writeDouble(hitPoint.z);
        buffer.writeDouble(hitT);
    }

    private static BoostRopePerch read(RegistryFriendlyByteBuf buffer) {
        return new BoostRopePerch(buffer.readUUID(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readDouble());
    }
}
