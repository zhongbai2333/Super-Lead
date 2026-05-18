package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C->S request to stand up from the rope trip hold after local player input.
 */
public record ClientRopeTripWakeRequest() implements CustomPacketPayload {
    public static final ClientRopeTripWakeRequest INSTANCE = new ClientRopeTripWakeRequest();
    public static final CustomPacketPayload.Type<ClientRopeTripWakeRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "client_rope_trip_wake_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientRopeTripWakeRequest> STREAM_CODEC = CustomPacketPayload
            .codec(ClientRopeTripWakeRequest::write, ClientRopeTripWakeRequest::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
    }

    private static ClientRopeTripWakeRequest read(RegistryFriendlyByteBuf buffer) {
        return INSTANCE;
    }
}
