package com.zhongbai233.super_lead.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Item-stack binding between a preset binder tool and one stored preset. */
public record PresetBinderData(String presetName, UUID owner) {
    public static final Codec<PresetBinderData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("presetName").forGetter(PresetBinderData::presetName),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(PresetBinderData::owner))
            .apply(instance, PresetBinderData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresetBinderData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PresetBinderData::presetName,
            UUIDUtil.STREAM_CODEC, PresetBinderData::owner,
            PresetBinderData::new);

    public PresetBinderData {
        presetName = presetName == null ? "" : presetName.trim();
        owner = owner == null ? new UUID(0L, 0L) : owner;
    }

    public boolean isBound() {
        return !presetName.isBlank();
    }
}