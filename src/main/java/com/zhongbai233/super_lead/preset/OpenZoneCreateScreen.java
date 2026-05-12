package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S→C: open the zone creation GUI for a selected block-box. */
public record OpenZoneCreateScreen(BlockPos from, BlockPos to) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenZoneCreateScreen> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "open_zone_create_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenZoneCreateScreen> STREAM_CODEC = CustomPacketPayload
            .codec(OpenZoneCreateScreen::write, OpenZoneCreateScreen::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(from);
        buffer.writeBlockPos(to);
    }

    private static OpenZoneCreateScreen read(RegistryFriendlyByteBuf buffer) {
        return new OpenZoneCreateScreen(buffer.readBlockPos(), buffer.readBlockPos());
    }
}