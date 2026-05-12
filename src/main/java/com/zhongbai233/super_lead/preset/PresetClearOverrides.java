package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresetClearOverrides() implements CustomPacketPayload {
    public static final PresetClearOverrides INSTANCE = new PresetClearOverrides();
    public static final CustomPacketPayload.Type<PresetClearOverrides> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_clear_overrides"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetClearOverrides> STREAM_CODEC = CustomPacketPayload
            .codec(PresetClearOverrides::write, PresetClearOverrides::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
    }

    private static PresetClearOverrides read(RegistryFriendlyByteBuf buf) {
        return INSTANCE;
    }
}
