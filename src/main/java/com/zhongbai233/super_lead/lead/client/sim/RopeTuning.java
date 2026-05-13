package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable snapshot of the tuning values a single rope should use.
 * <p>
 * Values are resolved from the preset name stamped on each rope by the server.
 * Keys absent from
 * that preset fall back to the player's local/default tuning.
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
        double halfThickness,
        double ribbonWidthFactor,
        int normalBaseColor,
        int normalAccentColor,
        int redstoneBaseColor,
        int redstoneAccentColor,
        int energyBaseColor,
        int energyAccentColor,
        int itemBaseColor,
        int itemAccentColor,
        int fluidBaseColor,
        int fluidAccentColor,
        int pressurizedBaseColor,
        int pressurizedAccentColor,
        int thermalBaseColor,
        int thermalAccentColor,
        boolean modePhysics) {

    public static RopeTuning forMidpoint(Vec3 a, Vec3 b) {
        ClientTuning.loadOnce();
        return fromOverrides(PhysicsZonesClient.overridesForRope(a, b));
    }

    public static RopeTuning forConnection(LeadConnection connection) {
        ClientTuning.loadOnce();
        return fromOverrides(PhysicsZonesClient.overridesForPreset(connection.physicsPreset()));
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
                resolve(overrides, ClientTuning.THICKNESS_HALF),
                resolve(overrides, ClientTuning.RIBBON_WIDTH_FACTOR),
                resolve(overrides, ClientTuning.COLOR_NORMAL_BASE),
                resolve(overrides, ClientTuning.COLOR_NORMAL_ACCENT),
                resolve(overrides, ClientTuning.COLOR_REDSTONE_BASE),
                resolve(overrides, ClientTuning.COLOR_REDSTONE_ACCENT),
                resolve(overrides, ClientTuning.COLOR_ENERGY_BASE),
                resolve(overrides, ClientTuning.COLOR_ENERGY_ACCENT),
                resolve(overrides, ClientTuning.COLOR_ITEM_BASE),
                resolve(overrides, ClientTuning.COLOR_ITEM_ACCENT),
                resolve(overrides, ClientTuning.COLOR_FLUID_BASE),
                resolve(overrides, ClientTuning.COLOR_FLUID_ACCENT),
                resolve(overrides, ClientTuning.COLOR_PRESSURIZED_BASE),
                resolve(overrides, ClientTuning.COLOR_PRESSURIZED_ACCENT),
                resolve(overrides, ClientTuning.COLOR_THERMAL_BASE),
                resolve(overrides, ClientTuning.COLOR_THERMAL_ACCENT),
                resolve(overrides, ClientTuning.MODE_PHYSICS));
    }

    public int baseColor(LeadKind kind) {
        return switch (kind) {
            case REDSTONE -> redstoneBaseColor;
            case ENERGY -> energyBaseColor;
            case ITEM -> itemBaseColor;
            case FLUID -> fluidBaseColor;
            case PRESSURIZED -> pressurizedBaseColor;
            case THERMAL -> thermalBaseColor;
            default -> normalBaseColor;
        } & 0xFFFFFF;
    }

    public int accentColor(LeadKind kind) {
        return switch (kind) {
            case REDSTONE -> redstoneAccentColor;
            case ENERGY -> energyAccentColor;
            case ITEM -> itemAccentColor;
            case FLUID -> fluidAccentColor;
            case PRESSURIZED -> pressurizedAccentColor;
            case THERMAL -> thermalAccentColor;
            default -> normalAccentColor;
        } & 0xFFFFFF;
    }

    public int colorHashFor(LeadKind kind) {
        int h = 17;
        h = h * 31 + (kind == null ? 0 : kind.ordinal());
        h = h * 31 + baseColor(kind == null ? LeadKind.NORMAL : kind);
        h = h * 31 + accentColor(kind == null ? LeadKind.NORMAL : kind);
        return h;
    }

    private static <T> T resolve(Map<String, String> overrides, TuningKey<T> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                T parsed = key.type.parse(raw);
                if (key.type.validate(parsed) || acceptsUncheckedFiniteDouble(key, parsed))
                    return parsed;
            } catch (RuntimeException ignored) {
            }
        }
        return key.getLocalOrDefault();
    }

    private static <T> boolean acceptsUncheckedFiniteDouble(TuningKey<T> key, T parsed) {
        if (!key.id.equals(ClientTuning.SLACK_LOOSE.id)
                && !key.id.equals(ClientTuning.SLACK_TIGHT.id)) {
            return false;
        }
        return parsed instanceof Double d && Double.isFinite(d);
    }
}
