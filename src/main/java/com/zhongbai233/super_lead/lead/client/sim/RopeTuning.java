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
        double slack,
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
        int aeNetworkBaseColor,
        int aeNetworkAccentColor,
        boolean modePhysics,
        double ropeRadius,
        double terrainRadius,
        double ropeRepelDistance,
        double collisionEps,
        double terrainProximityMargin,
        double segmentCornerPushFactor,
        double segmentTopSupportFactor,
        int minSegments,
        int maxSubsteps,
        double substepSpeedTier1,
        double substepSpeedTier2,
        double substepSpeedTier3,
        double supportDownInvMass,
        double contactPushGain,
        double entityPushGain,
        double ropeRopeParallelRelax,
        double contactNodeDamping,
        double initialVelocityKick,
        int settleThresholdTicks,
        double settleMotionSqr,
        double endpointWakeDistanceSqr,
        double sagArcApproxFactor,
        double fullSlackHorizontalRatio,
        double steepAngleDeg,
        int maxTickDelta,
        double tunnelThresholdSqr,
        boolean windEnabled,
        double windPhysicsDistance,
        double windStrength,
        double windStrengthJitter,
        double windDirectionDeg,
        double windDirectionJitterDeg,
        double windWaveLength,
        double windSpeed,
        double windDuty,
        double windDurationJitter,
        double windPauseJitter,
        double windRampBias,
        double windVerticalLift) {

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
                resolve(overrides, ClientTuning.SLACK),
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
                resolve(overrides, ClientTuning.COLOR_AE_NETWORK_BASE),
                resolve(overrides, ClientTuning.COLOR_AE_NETWORK_ACCENT),
                resolve(overrides, ClientTuning.MODE_PHYSICS),
                resolve(overrides, ClientTuning.ROPE_RADIUS_K),
                resolve(overrides, ClientTuning.TERRAIN_RADIUS_K),
                resolve(overrides, ClientTuning.ROPE_REPEL_DISTANCE),
                resolve(overrides, ClientTuning.COLLISION_EPS),
                resolve(overrides, ClientTuning.TERRAIN_PROXIMITY_MARGIN),
                resolve(overrides, ClientTuning.SEGMENT_CORNER_PUSH_FACTOR),
                resolve(overrides, ClientTuning.SEGMENT_TOP_SUPPORT_FACTOR),
                resolve(overrides, ClientTuning.MIN_SEGMENTS),
                resolve(overrides, ClientTuning.MAX_SUBSTEPS),
                resolve(overrides, ClientTuning.SUBSTEP_SPEED_TIER1),
                resolve(overrides, ClientTuning.SUBSTEP_SPEED_TIER2),
                resolve(overrides, ClientTuning.SUBSTEP_SPEED_TIER3),
                resolve(overrides, ClientTuning.SUPPORT_DOWN_INV_MASS),
                resolve(overrides, ClientTuning.CONTACT_PUSH_GAIN),
                resolve(overrides, ClientTuning.ENTITY_PUSH_GAIN),
                resolve(overrides, ClientTuning.ROPE_ROPE_PARALLEL_RELAX),
                resolve(overrides, ClientTuning.CONTACT_NODE_DAMPING),
                resolve(overrides, ClientTuning.INITIAL_VELOCITY_KICK),
                resolve(overrides, ClientTuning.SETTLE_THRESHOLD_TICKS),
                resolve(overrides, ClientTuning.SETTLE_MOTION_SQR),
                resolve(overrides, ClientTuning.ENDPOINT_WAKE_DISTANCE_SQR),
                resolve(overrides, ClientTuning.SAG_ARC_APPROX_FACTOR),
                resolve(overrides, ClientTuning.FULL_SLACK_HORIZONTAL_RATIO),
                resolve(overrides, ClientTuning.STEEP_ANGLE_DEG),
                resolve(overrides, ClientTuning.MAX_TICK_DELTA),
                resolve(overrides, ClientTuning.TUNNEL_THRESHOLD_SQR),
                resolve(overrides, ClientTuning.WIND_ENABLED),
                resolve(overrides, ClientTuning.WIND_PHYSICS_DISTANCE),
                resolve(overrides, ClientTuning.WIND_STRENGTH),
                resolve(overrides, ClientTuning.WIND_STRENGTH_JITTER),
                resolve(overrides, ClientTuning.WIND_DIRECTION_DEG),
                resolve(overrides, ClientTuning.WIND_DIRECTION_JITTER_DEG),
                resolve(overrides, ClientTuning.WIND_WAVELENGTH),
                resolve(overrides, ClientTuning.WIND_SPEED),
                resolve(overrides, ClientTuning.WIND_DUTY),
                resolve(overrides, ClientTuning.WIND_DURATION_JITTER),
                resolve(overrides, ClientTuning.WIND_PAUSE_JITTER),
                resolve(overrides, ClientTuning.WIND_RAMP_BIAS),
                resolve(overrides, ClientTuning.WIND_VERTICAL_LIFT));
    }

    public int baseColor(LeadKind kind) {
        return switch (kind) {
            case REDSTONE -> redstoneBaseColor;
            case ENERGY -> energyBaseColor;
            case ITEM -> itemBaseColor;
            case FLUID -> fluidBaseColor;
            case PRESSURIZED -> pressurizedBaseColor;
            case THERMAL -> thermalBaseColor;
            case AE_NETWORK -> aeNetworkBaseColor;
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
            case AE_NETWORK -> aeNetworkAccentColor;
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
        String raw = ClientTuning.overrideValue(overrides, key);
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
        if (!ClientTuning.isUncheckedFiniteDoubleKey(key)) {
            return false;
        }
        return parsed instanceof Double d && Double.isFinite(d);
    }
}
