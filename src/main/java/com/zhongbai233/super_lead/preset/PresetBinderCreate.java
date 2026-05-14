package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S: create a player-owned preset and bind it to the held binder item. */
public record PresetBinderCreate(boolean useOffhand, String displayName) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetBinderCreate> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_binder_create"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetBinderCreate> STREAM_CODEC = CustomPacketPayload
            .codec(PresetBinderCreate::write, PresetBinderCreate::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(useOffhand);
        buffer.writeUtf(displayName);
    }

    private static PresetBinderCreate read(RegistryFriendlyByteBuf buffer) {
        return new PresetBinderCreate(buffer.readBoolean(), buffer.readUtf());
    }
}