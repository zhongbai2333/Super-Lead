package com.zhongbai233.super_lead.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

/**
 * Per-dimension persistent list of {@link PhysicsZone}s defined by OPs.
 */
public final class PhysicsZoneSavedData extends SavedData {
    private static final Codec<PhysicsZone> ZONE_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("name").forGetter(PhysicsZone::name),
            Codec.STRING.fieldOf("preset").forGetter(PhysicsZone::presetName),
            Codec.DOUBLE.fieldOf("minX").forGetter(z -> z.area().minX),
            Codec.DOUBLE.fieldOf("minY").forGetter(z -> z.area().minY),
            Codec.DOUBLE.fieldOf("minZ").forGetter(z -> z.area().minZ),
            Codec.DOUBLE.fieldOf("maxX").forGetter(z -> z.area().maxX),
            Codec.DOUBLE.fieldOf("maxY").forGetter(z -> z.area().maxY),
            Codec.DOUBLE.fieldOf("maxZ").forGetter(z -> z.area().maxZ),
            Codec.BOOL.optionalFieldOf("adventure_place", Boolean.FALSE)
                    .forGetter(z -> Boolean.valueOf(z.adventurePlacement())),
            Codec.INT.optionalFieldOf("adventure_limit", 0)
                    .forGetter(z -> Integer.valueOf(z.adventureLimit())))
            .apply(inst,
                    (n, p, x0, y0, z0, x1, y1, z1, adventurePlace, adventureLimit) -> new PhysicsZone(n, p,
                            new AABB(x0, y0, z0, x1, y1, z1),
                            adventurePlace.booleanValue(), adventureLimit.intValue())));

    public static final Codec<PhysicsZoneSavedData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ZONE_CODEC.listOf().optionalFieldOf("zones", List.of())
                    .forGetter(d -> new ArrayList<>(d.zones.values())))
            .apply(inst, list -> {
                PhysicsZoneSavedData d = new PhysicsZoneSavedData();
                for (PhysicsZone z : list)
                    d.zones.put(z.name(), z);
                return d;
            }));

    public static final SavedDataType<PhysicsZoneSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "physics_zones"),
            PhysicsZoneSavedData::new,
            CODEC);

    // LinkedHashMap so iteration order is stable for overlap resolution.
    private final Map<String, PhysicsZone> zones = new LinkedHashMap<>();

    public PhysicsZoneSavedData() {
    }

    public static PhysicsZoneSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<PhysicsZone> zones() {
        return List.copyOf(zones.values());
    }

    public Optional<PhysicsZone> get(String name) {
        return Optional.ofNullable(zones.get(name));
    }

    /**
     * Returns true if a new zone was inserted (false if it replaced an existing one
     * of the same name).
     */
    public boolean put(PhysicsZone zone) {
        boolean fresh = !zones.containsKey(zone.name());
        zones.put(zone.name(), zone);
        setDirty();
        return fresh;
    }

    public boolean remove(String name) {
        if (zones.remove(name) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** First zone whose AABB contains the point, or empty. */
    public Optional<PhysicsZone> findContaining(double x, double y, double z) {
        for (PhysicsZone zone : zones.values()) {
            if (zone.contains(x, y, z))
                return Optional.of(zone);
        }
        return Optional.empty();
    }
}
