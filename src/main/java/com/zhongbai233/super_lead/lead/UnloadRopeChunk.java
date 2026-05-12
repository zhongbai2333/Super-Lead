package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

public record UnloadRopeChunk(ChunkPos chunk) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UnloadRopeChunk> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "unload_rope_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnloadRopeChunk> STREAM_CODEC = CustomPacketPayload
            .codec(UnloadRopeChunk::write, UnloadRopeChunk::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeChunkPos(chunk);
    }

    private static UnloadRopeChunk read(RegistryFriendlyByteBuf buffer) {
        return new UnloadRopeChunk(buffer.readChunkPos());
    }
}
