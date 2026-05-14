package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S: toggle the held binder's preset on the rope currently in the player's view. */
public record PresetBinderToggleRope(boolean useOffhand) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetBinderToggleRope> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_binder_toggle_rope"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetBinderToggleRope> STREAM_CODEC = CustomPacketPayload
            .codec(PresetBinderToggleRope::write, PresetBinderToggleRope::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(useOffhand);
    }

    private static PresetBinderToggleRope read(RegistryFriendlyByteBuf buffer) {
        return new PresetBinderToggleRope(buffer.readBoolean());
    }
}