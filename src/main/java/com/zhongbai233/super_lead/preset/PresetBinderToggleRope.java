package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * C→S: toggle the held binder's preset on the rope the client picked.
 * The client sends the connection id and hit position it determined from the
 * rendered physics rope; the server verifies proximity instead of doing its
 * own (often mismatched) ray-pick.
 */
public record PresetBinderToggleRope(UUID connectionId, boolean useOffhand, Vec3 hitPoint, double hitT)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetBinderToggleRope> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_binder_toggle_rope"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetBinderToggleRope> STREAM_CODEC = CustomPacketPayload
            .codec((payload, buf) -> payload.write(buf), PresetBinderToggleRope::read);

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

    private static PresetBinderToggleRope read(RegistryFriendlyByteBuf buffer) {
        return new PresetBinderToggleRope(buffer.readUUID(), buffer.readBoolean(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readDouble());
    }
}