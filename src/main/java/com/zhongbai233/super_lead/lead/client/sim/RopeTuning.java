package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable snapshot of the tuning values a single rope should use.
 * <p>
 * Values are resolved from the synced physics zone containing the rope midpoint. Keys absent
 * from the zone preset fall back to the player's local/default tuning, deliberately ignoring
 * the player-position preset overlay so zone presets do not leak onto ropes outside the zone.
 */
public record RopeTuning(
        double slackLoose,
        double slackTight,
        double segmentLength,
        int segmentMax,
        double gravity,
        double damping,
        int iterAir,
        int iterContact,
        int iterRope,
        double compliance,
        boolean modePhysics) {

    public static RopeTuning forMidpoint(Vec3 a, Vec3 b) {
        ClientTuning.loadOnce();
        return fromOverrides(PhysicsZonesClient.overridesForRope(a, b));
    }

    public static RopeTuning at(double x, double y, double z) {
        ClientTuning.loadOnce();
        return fromOverrides(PhysicsZonesClient.overridesAt(x, y, z));
    }

    public static RopeTuning localDefaults() {
        ClientTuning.loadOnce();
        return fromOverrides(Map.of());
    }

    private static RopeTuning fromOverrides(Map<String, String> overrides) {
        return new RopeTuning(
                resolve(overrides, ClientTuning.SLACK_LOOSE),
                resolve(overrides, ClientTuning.SLACK_TIGHT),
                resolve(overrides, ClientTuning.SEGMENT_LENGTH),
                resolve(overrides, ClientTuning.SEGMENT_MAX),
                resolve(overrides, ClientTuning.GRAVITY),
                resolve(overrides, ClientTuning.DAMPING),
                resolve(overrides, ClientTuning.ITER_AIR),
                resolve(overrides, ClientTuning.ITER_CONTACT),
                resolve(overrides, ClientTuning.ITER_ROPE),
                resolve(overrides, ClientTuning.COMPLIANCE),
                resolve(overrides, ClientTuning.MODE_PHYSICS));
    }

    private static <T> T resolve(Map<String, String> overrides, TuningKey<T> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                T parsed = key.type.parse(raw);
                if (key.type.validate(parsed)) return parsed;
            } catch (Exception ignored) {
            }
        }
        return key.getLocalOrDefault();
    }
}