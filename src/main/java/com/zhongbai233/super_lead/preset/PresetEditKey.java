package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C->S: OP edits one key inside a stored preset. value="" + clear=true means
 * remove that key.
 */
public record PresetEditKey(String presetName, String keyId, String value, boolean clear)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PresetEditKey> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "preset_edit_key"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetEditKey> STREAM_CODEC = CustomPacketPayload
            .codec(PresetEditKey::write, PresetEditKey::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(presetName);
        buf.writeUtf(keyId);
        buf.writeUtf(value);
        buf.writeBoolean(clear);
    }

    private static PresetEditKey read(RegistryFriendlyByteBuf buf) {
        return new PresetEditKey(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }
}
