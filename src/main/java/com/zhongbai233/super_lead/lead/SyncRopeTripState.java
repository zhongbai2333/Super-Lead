package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S->C lock state for the rope trip/crawl preset effect. */
public record SyncRopeTripState(
        boolean active,
        int remainingTicks,
        double startX,
        double startZ,
        double lockX,
        double lockZ,
        int fallTicks)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncRopeTripState> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_rope_trip_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRopeTripState> STREAM_CODEC = CustomPacketPayload
            .codec(SyncRopeTripState::write, SyncRopeTripState::read);

    public static SyncRopeTripState active(int remainingTicks, double startX, double startZ, double lockX, double lockZ,
            int fallTicks) {
        return new SyncRopeTripState(true, Math.max(1, remainingTicks), startX, startZ, lockX, lockZ,
                Math.max(0, fallTicks));
    }

    public static SyncRopeTripState inactive() {
        return new SyncRopeTripState(false, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeVarInt(remainingTicks);
        buffer.writeDouble(startX);
        buffer.writeDouble(startZ);
        buffer.writeDouble(lockX);
        buffer.writeDouble(lockZ);
        buffer.writeVarInt(fallTicks);
    }

    private static SyncRopeTripState read(RegistryFriendlyByteBuf buffer) {
        return new SyncRopeTripState(
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readVarInt());
    }
}
