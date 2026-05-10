package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S: client confirmed a shears sneak-right-click while zone selection mode is active. */
public record ZoneSelectionClick(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneSelectionClick> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "zone_selection_click"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneSelectionClick> STREAM_CODEC =
            CustomPacketPayload.codec(ZoneSelectionClick::write, ZoneSelectionClick::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }

    private static ZoneSelectionClick read(RegistryFriendlyByteBuf buffer) {
        return new ZoneSelectionClick(buffer.readBlockPos());
    }
}