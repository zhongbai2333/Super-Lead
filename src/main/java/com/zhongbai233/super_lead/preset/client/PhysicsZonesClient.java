package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.preset.SyncDimensionPresets;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Client cache for physics presets and OP-only zone previews.
 *
 * <p>
 * Normal rope simulation resolves tuning from the rope's stamped preset name
 * and the
 * {@link SyncDimensionPresets} cache. {@link SyncPhysicsZones} remains only for
 * OP preview UI.
 */
public final class PhysicsZonesClient {
    private static volatile List<SyncPhysicsZones.Entry> ZONES = List.of();
    private static volatile Map<String, Map<String, String>> PRESETS = Map.of();
    private static volatile long EPOCH;

    private PhysicsZonesClient() {
    }

    public static void apply(SyncPhysicsZones payload) {
        List<SyncPhysicsZones.Entry> next = List.copyOf(payload.zones());
        if (next.equals(ZONES))
            return;
        ZONES = next;
        EPOCH++;
    }

    public static void apply(SyncDimensionPresets payload) {
        Map<String, Map<String, String>> next = payload.presets();
        if (next.equals(PRESETS))
            return;
        PRESETS = next;
        EPOCH++;
    }

    public static List<SyncPhysicsZones.Entry> zones() {
        return ZONES;
    }

    public static long epoch() {
        return EPOCH;
    }

    public static Map<String, String> overridesForPreset(String presetName) {
        if (presetName == null || presetName.isBlank())
            return Map.of();
        return PRESETS.getOrDefault(presetName, Map.of());
    }

    public static boolean hasPreset(String presetName) {
        return presetName != null && !presetName.isBlank() && PRESETS.containsKey(presetName);
    }

    public static Map<String, String> overridesAt(Vec3 pos) {
        return overridesAt(pos.x, pos.y, pos.z);
    }

    public static Map<String, String> overridesForRope(Vec3 a, Vec3 b) {
        SyncPhysicsZones.Entry entry = findForRope(a, b);
        return entry == null ? Map.of() : entry.overrides();
    }

    public static Map<String, String> overridesAt(double x, double y, double z) {
        SyncPhysicsZones.Entry entry = findAt(x, y, z);
        return entry == null ? Map.of() : entry.overrides();
    }

    public static SyncPhysicsZones.Entry findAt(double x, double y, double z) {
        for (SyncPhysicsZones.Entry zone : ZONES) {
            if (contains(zone, x, y, z))
                return zone;
        }
        return null;
    }

    /**
     * Resolve a rope to the first zone that contains its midpoint, or otherwise
     * intersects
     * the endpoint segment. This keeps the fast, stable midpoint rule for overlaps
     * while still
     * matching ropes that are visibly inside a zone but whose midpoint lies on a
     * boundary.
     */
    public static SyncPhysicsZones.Entry findForRope(Vec3 a, Vec3 b) {
        double mx = (a.x + b.x) * 0.5D;
        double my = (a.y + b.y) * 0.5D;
        double mz = (a.z + b.z) * 0.5D;
        SyncPhysicsZones.Entry midpoint = findAt(mx, my, mz);
        if (midpoint != null)
            return midpoint;
        for (SyncPhysicsZones.Entry zone : ZONES) {
            if (segmentIntersects(zone, a, b))
                return zone;
        }
        return null;
    }

    public static void clear() {
        if (!ZONES.isEmpty() || !PRESETS.isEmpty()) {
            ZONES = List.of();
            PRESETS = Map.of();
            EPOCH++;
        }
    }

    private static boolean contains(SyncPhysicsZones.Entry zone, double x, double y, double z) {
        return x >= zone.minX() && x < zone.maxX()
                && y >= zone.minY() && y < zone.maxY()
                && z >= zone.minZ() && z < zone.maxZ();
    }

    private static boolean segmentIntersects(SyncPhysicsZones.Entry zone, Vec3 a, Vec3 b) {
        if (contains(zone, a.x, a.y, a.z) || contains(zone, b.x, b.y, b.z))
            return true;
        double t0 = 0.0D;
        double t1 = 1.0D;
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double[] lo = { zone.minX(), zone.minY(), zone.minZ() };
        double[] hi = { zone.maxX(), zone.maxY(), zone.maxZ() };
        double[] p = { a.x, a.y, a.z };
        double[] d = { dx, dy, dz };
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9D) {
                if (p[axis] < lo[axis] || p[axis] >= hi[axis])
                    return false;
                continue;
            }
            double inv = 1.0D / d[axis];
            double ta = (lo[axis] - p[axis]) * inv;
            double tb = (hi[axis] - p[axis]) * inv;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
            }
            if (ta > t0)
                t0 = ta;
            if (tb < t1)
                t1 = tb;
            if (t0 > t1)
                return false;
        }
        return t1 >= 0.0D && t0 <= 1.0D;
    }
}
