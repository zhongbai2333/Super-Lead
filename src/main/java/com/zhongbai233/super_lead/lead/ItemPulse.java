package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ItemPulse(UUID connectionId, boolean reverse, long startTick, int durationTicks)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ItemPulse> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "item_pulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemPulse> STREAM_CODEC =
            CustomPacketPayload.codec(ItemPulse::write, ItemPulse::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeBoolean(reverse);
        buffer.writeLong(startTick);
        buffer.writeVarInt(durationTicks);
    }

    private static ItemPulse read(RegistryFriendlyByteBuf buffer) {
        return new ItemPulse(buffer.readUUID(), buffer.readBoolean(), buffer.readLong(), buffer.readVarInt());
    }
}
