package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UseConnectionAction(UUID connectionId, int actionOrdinal, boolean useOffhand)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UseConnectionAction> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "use_connection_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UseConnectionAction> STREAM_CODEC =
            CustomPacketPayload.codec(UseConnectionAction::write, UseConnectionAction::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(connectionId);
        buffer.writeVarInt(actionOrdinal);
        buffer.writeBoolean(useOffhand);
    }

    private static UseConnectionAction read(RegistryFriendlyByteBuf buffer) {
        return new UseConnectionAction(buffer.readUUID(), buffer.readVarInt(), buffer.readBoolean());
    }
}
