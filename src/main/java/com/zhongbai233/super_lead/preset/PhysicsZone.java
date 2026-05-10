package com.zhongbai233.super_lead.preset;

import net.minecraft.world.phys.AABB;

/**
 * A named axis-aligned area that binds a {@link RopePreset} to space. Any player whose
 * position falls inside the area receives the preset's tuning overrides; on leaving they
 * fall back to defaults (or to the preset of the next zone they enter).
 *
 * <p>Zones are scoped to a single dimension via {@link PhysicsZoneSavedData}. They are not
 * exclusive — overlapping zones resolve to the first one encountered in iteration order.
 */
public record PhysicsZone(String name, String presetName, AABB area) {
    public PhysicsZone {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("zone name");
        if (presetName == null || presetName.isEmpty()) throw new IllegalArgumentException("presetName");
        if (area == null) throw new IllegalArgumentException("area");
    }

    public boolean contains(double x, double y, double z) {
        return area.contains(x, y, z);
    }
}
