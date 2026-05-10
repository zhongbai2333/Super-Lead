package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C broadcast carrying the current shape of every active server-side {@link ServerRopeVerlet}.
 * Endpoints are NOT included (clients already know them from {@code LeadConnection}); only the
 * interior nodes are sent, in order, as float triples. This is the synchronisation channel that
 * lets every client render the same rope shape regardless of its own physics LOD state.
 *
 * <p>Wire layout:
 * <pre>
 *   VarInt segments               // server sim segment count (e.g. 8 → 7 interior nodes)
 *   VarInt count                  // number of ropes in this pulse
 *   for each rope:
 *     UUID ropeId
 *     (segments-1) * { float x, y, z }   // interior nodes, root→tail
 * </pre>
 * Per rope: 16 + (segments-1)*12 bytes (= 100 B at the default 8 segments).
 */
public record RopeNodesPulse(int segments, List<Entry> ropes) implements CustomPacketPayload {
    /** {@code interior} length is exactly {@code (segments-1) * 3}, root→tail interleaved xyz. */
    public record Entry(UUID ropeId, float[] interior) {}

    public static final CustomPacketPayload.Type<RopeNodesPulse> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "rope_nodes_pulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RopeNodesPulse> STREAM_CODEC =
            CustomPacketPayload.codec(RopeNodesPulse::write, RopeNodesPulse::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(segments);
        buffer.writeVarInt(ropes.size());
        int interiorCount = Math.max(0, segments - 1) * 3;
        for (Entry e : ropes) {
            buffer.writeUUID(e.ropeId);
            float[] arr = e.interior;
            // Defensive bound — corrupt entries would otherwise tear the stream for everything
            // that follows on the wire, including unrelated payloads.
            int len = Math.min(arr.length, interiorCount);
            for (int i = 0; i < len; i++) buffer.writeFloat(arr[i]);
            for (int i = len; i < interiorCount; i++) buffer.writeFloat(0.0F);
        }
    }

    private static RopeNodesPulse read(RegistryFriendlyByteBuf buffer) {
        int segments = buffer.readVarInt();
        int n = buffer.readVarInt();
        int interiorCount = Math.max(0, segments - 1) * 3;
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buffer.readUUID();
            float[] arr = new float[interiorCount];
            for (int k = 0; k < interiorCount; k++) arr[k] = buffer.readFloat();
            list.add(new Entry(id, arr));
        }
        return new RopeNodesPulse(segments, list);
    }
}
