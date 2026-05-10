package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/**
 * S→C broadcast of the full set of physics zones in a dimension. Sent on player login and
 * whenever zones are added, removed, or their bound preset changes. Each zone carries the
 * stringified preset overrides so clients can resolve physics parameters by rope midpoint,
 * even when the observing player is outside that zone.
 */
public record SyncPhysicsZones(List<Entry> zones) implements CustomPacketPayload {
    public record Entry(String name, String presetName,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ,
                        Map<String, String> overrides) {
        public Entry { overrides = PresetPayloadCodecs.immutableCopy(overrides); }
        public AABB toAabb() { return new AABB(minX, minY, minZ, maxX, maxY, maxZ); }
        public static Entry of(PhysicsZone zone) {
            return of(zone, Map.of());
        }
        public static Entry of(PhysicsZone zone, Map<String, String> overrides) {
            AABB a = zone.area();
            return new Entry(zone.name(), zone.presetName(), a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ,
                    overrides);
        }
    }

    public static final CustomPacketPayload.Type<SyncPhysicsZones> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_physics_zones"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPhysicsZones> STREAM_CODEC =
            CustomPacketPayload.codec(SyncPhysicsZones::write, SyncPhysicsZones::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(zones.size());
        for (Entry e : zones) {
            buffer.writeUtf(e.name());
            buffer.writeUtf(e.presetName());
            buffer.writeDouble(e.minX()); buffer.writeDouble(e.minY()); buffer.writeDouble(e.minZ());
            buffer.writeDouble(e.maxX()); buffer.writeDouble(e.maxY()); buffer.writeDouble(e.maxZ());
            PresetPayloadCodecs.writeStringMap(buffer, e.overrides());
        }
    }

    private static SyncPhysicsZones read(RegistryFriendlyByteBuf buffer) {
        int n = buffer.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    PresetPayloadCodecs.readStringMap(buffer)));
        }
        return new SyncPhysicsZones(list);
    }
}
