package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Clears all client-side rope connection caches before chunk-scoped sync
 * refills them.
 */
public record ClearRopeCache() implements CustomPacketPayload {
    public static final ClearRopeCache INSTANCE = new ClearRopeCache();
    public static final CustomPacketPayload.Type<ClearRopeCache> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "clear_rope_cache"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClearRopeCache> STREAM_CODEC = CustomPacketPayload
            .codec(ClearRopeCache::write, ClearRopeCache::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
    }

    private static ClearRopeCache read(RegistryFriendlyByteBuf buffer) {
        return INSTANCE;
    }
}