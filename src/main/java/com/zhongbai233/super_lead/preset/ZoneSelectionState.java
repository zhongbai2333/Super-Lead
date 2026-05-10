package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S→C: current per-player zone selection tool state. */
public record ZoneSelectionState(boolean active, boolean hasFirst, BlockPos first) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneSelectionState> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "zone_selection_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneSelectionState> STREAM_CODEC =
            CustomPacketPayload.codec(ZoneSelectionState::write, ZoneSelectionState::read);

    public ZoneSelectionState {
        if (!hasFirst) first = BlockPos.ZERO;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeBoolean(hasFirst);
        buffer.writeBlockPos(first);
    }

    private static ZoneSelectionState read(RegistryFriendlyByteBuf buffer) {
        return new ZoneSelectionState(buffer.readBoolean(), buffer.readBoolean(), buffer.readBlockPos());
    }
}