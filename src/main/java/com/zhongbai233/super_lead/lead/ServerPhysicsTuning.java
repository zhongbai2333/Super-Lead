package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

public record ServerPhysicsTuning(
        boolean physicsEnabled,
        double gravity,
        double slack,
        double segmentLength,
        int segmentMax,
        double damping,
        int iterAir,
        int iterContact,
        double compliance,
        double ropeRadius,
        double terrainRadius,
        double collisionEps,
        double entityPushGain,
        boolean pushbackEnabled,
        boolean parrotPathfindingEnabled,
        boolean playerZiplineEnabled,
        double contactRadius,
        double springK,
        double velocityDamping,
        double maxRecoilPerTick,
        double contactTopNormalThreshold,
        double contactSideAbsorb,
        double contactSideIntentRelease,
        double contactSideDeadbandRatio,
        double pushbackEnableDepth,
        boolean tripEnabled,
        double tripChance,
        int tripCooldownTicks,
        double ziplineSpeedLimit,
        double ziplineRedstoneAccelerationMultiplier,
        double maxSolvedSag,
        double sagArcApproxFactor,
        double fullSlackHorizontalRatio,
        boolean windEnabled,
        double windStrength,
        double windStrengthJitter,
        double windDirectionDeg,
        double windDirectionJitterDeg,
        double windCellDirectionSpreadDeg,
        double windWaveLength,
        double windSpeed,
        double windDuty,
        double windDurationJitter,
        double windPauseJitter,
        double windRampBias,
        double windVerticalLift) {
    private static final double CONTACT_RADIUS_FALLBACK = ClientTuning.CONTACT_RADIUS.defaultValue;
    private static final double SPRING_K_FALLBACK = ClientTuning.CONTACT_SPRING.defaultValue;
    private static final double SPRING_K_MINIMUM = 0.30D;
    private static final double VELOCITY_DAMPING_FALLBACK = ClientTuning.CONTACT_VELOCITY_DAMPING.defaultValue;
    private static final double MAX_RECOIL_PER_TICK_FALLBACK = ClientTuning.CONTACT_MAX_RECOIL_PER_TICK.defaultValue;
    private static final double TOP_NORMAL_THRESHOLD_FALLBACK = ClientTuning.CONTACT_TOP_NORMAL_THRESHOLD.defaultValue;
    private static final double SIDE_ABSORB_FALLBACK = ClientTuning.CONTACT_SIDE_ABSORB.defaultValue;
    private static final double SIDE_INTENT_RELEASE_FALLBACK = ClientTuning.CONTACT_SIDE_INTENT_RELEASE.defaultValue;
    private static final double SIDE_DEADBAND_RATIO_FALLBACK = ClientTuning.CONTACT_SIDE_DEADBAND_RATIO.defaultValue;
    private static final double PUSHBACK_ENABLE_DEPTH_FALLBACK = ClientTuning.CONTACT_PUSHBACK_ENABLE_DEPTH.defaultValue;
    private static final double MAX_RECOIL_PER_TICK_MINIMUM = 0.20D;
    private static final double MAX_SOLVED_SAG_FALLBACK = 64.0D;
    private static final double SAG_ARC_APPROX_FACTOR_FALLBACK = 0.375D;
    private static final double FULL_SLACK_HORIZONTAL_RATIO_FALLBACK = 0.45D;
        private static final Map<CacheKey, ServerPhysicsTuning> CACHE = new ConcurrentHashMap<>();

    static ServerPhysicsTuning loadServerPhysicsTuning(ServerLevel level, String presetName) {
                CacheKey key = CacheKey.of(level, presetName);
                return CACHE.computeIfAbsent(key, ignored -> loadUncached(level, presetName));
        }

        public static void clearCache() {
                CACHE.clear();
        }

        private static ServerPhysicsTuning loadUncached(ServerLevel level, String presetName) {
        Map<String, String> overrides = RopePresetLibrary.forServer(level.getServer())
                .load(presetName)
                .map(preset -> preset.overrides())
                .orElse(Map.of());
        boolean physicsEnabled = parseBool(overrides.get(ClientTuning.MODE_PHYSICS.id),
                ClientTuning.MODE_PHYSICS.defaultValue);
        double gravity = parseServerGravity(overrides.get(ClientTuning.GRAVITY.id));
        double slack = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.SLACK),
                ClientTuning.SLACK, ClientTuning.SLACK.defaultValue, true);
        double segmentLength = parseDouble(overrides.get(ClientTuning.SEGMENT_LENGTH.id),
                ClientTuning.SEGMENT_LENGTH, ClientTuning.SEGMENT_LENGTH.defaultValue);
        int segmentMax = parseInt(overrides.get(ClientTuning.SEGMENT_MAX.id),
                ClientTuning.SEGMENT_MAX, ClientTuning.SEGMENT_MAX.defaultValue);
        double damping = parseDouble(overrides.get(ClientTuning.DAMPING.id),
                ClientTuning.DAMPING, ClientTuning.DAMPING.defaultValue);
        int iterAir = parseInt(overrides.get(ClientTuning.ITER_AIR.id),
                ClientTuning.ITER_AIR, ClientTuning.ITER_AIR.defaultValue);
        int iterContact = parseInt(overrides.get(ClientTuning.ITER_CONTACT.id),
                ClientTuning.ITER_CONTACT, ClientTuning.ITER_CONTACT.defaultValue);
        double compliance = parseDouble(overrides.get(ClientTuning.COMPLIANCE.id),
                ClientTuning.COMPLIANCE, ClientTuning.COMPLIANCE.defaultValue);
        double ropeRadius = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.ROPE_RADIUS_K),
                ClientTuning.ROPE_RADIUS_K, ClientTuning.ROPE_RADIUS_K.defaultValue);
        double terrainRadius = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.TERRAIN_RADIUS_K),
                ClientTuning.TERRAIN_RADIUS_K, ClientTuning.TERRAIN_RADIUS_K.defaultValue);
        double collisionEps = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.COLLISION_EPS),
                ClientTuning.COLLISION_EPS, ClientTuning.COLLISION_EPS.defaultValue);
        double entityPushGain = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.ENTITY_PUSH_GAIN),
                ClientTuning.ENTITY_PUSH_GAIN, ClientTuning.ENTITY_PUSH_GAIN.defaultValue);
        boolean pushbackEnabled = parseBool(overrides.get(ClientTuning.CONTACT_PUSHBACK.id),
                ClientTuning.CONTACT_PUSHBACK.defaultValue);
        boolean parrotPathfindingEnabled = parseBool(overrides.get(ClientTuning.CONTACT_PARROT_PATHFINDING.id),
                ClientTuning.CONTACT_PARROT_PATHFINDING.defaultValue);
        boolean playerZiplineEnabled = parseBool(overrides.get(ClientTuning.CONTACT_PLAYER_ZIPLINE.id),
                ClientTuning.CONTACT_PLAYER_ZIPLINE.defaultValue);
        double contactRadius = parseDouble(overrides.get(ClientTuning.CONTACT_RADIUS.id),
                ClientTuning.CONTACT_RADIUS, CONTACT_RADIUS_FALLBACK);
        double springK = Math.max(SPRING_K_MINIMUM,
                parseDouble(overrides.get(ClientTuning.CONTACT_SPRING.id),
                        ClientTuning.CONTACT_SPRING, SPRING_K_FALLBACK));
        double velocityDamping = parseDouble(overrides.get(ClientTuning.CONTACT_VELOCITY_DAMPING.id),
                ClientTuning.CONTACT_VELOCITY_DAMPING, VELOCITY_DAMPING_FALLBACK);
        double maxRecoilPerTick = Math.max(MAX_RECOIL_PER_TICK_MINIMUM,
                parseDouble(overrides.get(ClientTuning.CONTACT_MAX_RECOIL_PER_TICK.id),
                        ClientTuning.CONTACT_MAX_RECOIL_PER_TICK, MAX_RECOIL_PER_TICK_FALLBACK));
        double contactTopNormalThreshold = parseDouble(
                overrides.get(ClientTuning.CONTACT_TOP_NORMAL_THRESHOLD.id),
                ClientTuning.CONTACT_TOP_NORMAL_THRESHOLD, TOP_NORMAL_THRESHOLD_FALLBACK);
        double contactSideAbsorb = parseDouble(overrides.get(ClientTuning.CONTACT_SIDE_ABSORB.id),
                ClientTuning.CONTACT_SIDE_ABSORB, SIDE_ABSORB_FALLBACK);
        double contactSideIntentRelease = parseDouble(
                overrides.get(ClientTuning.CONTACT_SIDE_INTENT_RELEASE.id),
                ClientTuning.CONTACT_SIDE_INTENT_RELEASE, SIDE_INTENT_RELEASE_FALLBACK);
        double contactSideDeadbandRatio = parseDouble(
                overrides.get(ClientTuning.CONTACT_SIDE_DEADBAND_RATIO.id),
                ClientTuning.CONTACT_SIDE_DEADBAND_RATIO, SIDE_DEADBAND_RATIO_FALLBACK);
        double pushbackEnableDepth = parseDouble(
                overrides.get(ClientTuning.CONTACT_PUSHBACK_ENABLE_DEPTH.id),
                ClientTuning.CONTACT_PUSHBACK_ENABLE_DEPTH, PUSHBACK_ENABLE_DEPTH_FALLBACK);
        boolean tripEnabled = parseBool(overrides.get(ClientTuning.CONTACT_TRIP_ENABLED.id),
                ClientTuning.CONTACT_TRIP_ENABLED.defaultValue);
        double tripChance = parseDouble(overrides.get(ClientTuning.CONTACT_TRIP_CHANCE.id),
                ClientTuning.CONTACT_TRIP_CHANCE, ClientTuning.CONTACT_TRIP_CHANCE.defaultValue);
        int tripCooldownTicks = parseInt(overrides.get(ClientTuning.CONTACT_TRIP_COOLDOWN_TICKS.id),
                ClientTuning.CONTACT_TRIP_COOLDOWN_TICKS, ClientTuning.CONTACT_TRIP_COOLDOWN_TICKS.defaultValue);
        double ziplineSpeedLimit = parseDouble(overrides.get(ClientTuning.ZIPLINE_SPEED_LIMIT.id),
                ClientTuning.ZIPLINE_SPEED_LIMIT, Double.NaN);
        double ziplineRedstoneAccelerationMultiplier = parseDouble(
                overrides.get(ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER.id),
                ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER,
                ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER.defaultValue);
        boolean windEnabled = parseBool(ClientTuning.overrideValue(overrides, ClientTuning.WIND_ENABLED),
                ClientTuning.WIND_ENABLED.defaultValue);
        double windStrength = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_STRENGTH),
                ClientTuning.WIND_STRENGTH, ClientTuning.WIND_STRENGTH.defaultValue);
        double windStrengthJitter = parseDouble(
                ClientTuning.overrideValue(overrides, ClientTuning.WIND_STRENGTH_JITTER),
                ClientTuning.WIND_STRENGTH_JITTER, ClientTuning.WIND_STRENGTH_JITTER.defaultValue);
        double windDirectionDeg = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_DIRECTION_DEG),
                ClientTuning.WIND_DIRECTION_DEG, ClientTuning.WIND_DIRECTION_DEG.defaultValue);
        double windDirectionJitterDeg = parseDouble(
                ClientTuning.overrideValue(overrides, ClientTuning.WIND_DIRECTION_JITTER_DEG),
                ClientTuning.WIND_DIRECTION_JITTER_DEG, ClientTuning.WIND_DIRECTION_JITTER_DEG.defaultValue);
        double windCellDirectionSpreadDeg = parseDouble(
                ClientTuning.overrideValue(overrides, ClientTuning.WIND_CELL_DIRECTION_SPREAD_DEG),
                ClientTuning.WIND_CELL_DIRECTION_SPREAD_DEG,
                ClientTuning.WIND_CELL_DIRECTION_SPREAD_DEG.defaultValue);
        double windWaveLength = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_WAVELENGTH),
                ClientTuning.WIND_WAVELENGTH, ClientTuning.WIND_WAVELENGTH.defaultValue);
        double windSpeed = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_SPEED),
                ClientTuning.WIND_SPEED, ClientTuning.WIND_SPEED.defaultValue);
        double windDuty = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_DUTY),
                ClientTuning.WIND_DUTY, ClientTuning.WIND_DUTY.defaultValue);
        double windDurationJitter = parseDouble(
                ClientTuning.overrideValue(overrides, ClientTuning.WIND_DURATION_JITTER),
                ClientTuning.WIND_DURATION_JITTER, ClientTuning.WIND_DURATION_JITTER.defaultValue);
        double windPauseJitter = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_PAUSE_JITTER),
                ClientTuning.WIND_PAUSE_JITTER, ClientTuning.WIND_PAUSE_JITTER.defaultValue);
        double windRampBias = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_RAMP_BIAS),
                ClientTuning.WIND_RAMP_BIAS, ClientTuning.WIND_RAMP_BIAS.defaultValue);
        double windVerticalLift = parseDouble(ClientTuning.overrideValue(overrides, ClientTuning.WIND_VERTICAL_LIFT),
                ClientTuning.WIND_VERTICAL_LIFT, ClientTuning.WIND_VERTICAL_LIFT.defaultValue);
        return new ServerPhysicsTuning(physicsEnabled, gravity, slack,
                segmentLength, segmentMax, damping, iterAir, iterContact, compliance,
                ropeRadius, terrainRadius, collisionEps, entityPushGain,
                pushbackEnabled, parrotPathfindingEnabled, playerZiplineEnabled,
                contactRadius, springK, velocityDamping, maxRecoilPerTick,
                contactTopNormalThreshold, contactSideAbsorb, contactSideIntentRelease, contactSideDeadbandRatio,
                pushbackEnableDepth,
                tripEnabled, tripChance, tripCooldownTicks,
                ziplineSpeedLimit, ziplineRedstoneAccelerationMultiplier,
                MAX_SOLVED_SAG_FALLBACK, SAG_ARC_APPROX_FACTOR_FALLBACK, FULL_SLACK_HORIZONTAL_RATIO_FALLBACK,
                windEnabled, windStrength, windStrengthJitter, windDirectionDeg, windDirectionJitterDeg,
                windCellDirectionSpreadDeg,
                windWaveLength, windSpeed, windDuty, windDurationJitter, windPauseJitter, windRampBias,
                windVerticalLift);
    }

        private record CacheKey(String worldPresetDirectory, String presetName) {
                private static CacheKey of(ServerLevel level, String presetName) {
                        String dir = RopePresetLibrary.worldDirectory(level.getServer()).toAbsolutePath().normalize().toString();
                        return new CacheKey(dir, presetName == null ? "" : presetName);
        }
        }

    private static double parseDouble(String raw, TuningKey<Double> key, double fallback) {
        return parseDouble(raw, key, fallback, false);
    }

    private static double parseDouble(String raw, TuningKey<Double> key, double fallback,
            boolean allowUncheckedFinite) {
        if (raw == null)
            return fallback;
        try {
            double value = key.type.parse(raw);
            return key.type.validate(value)
                    || (allowUncheckedFinite && Double.isFinite(value)) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double parseServerGravity(String raw) {
        if (raw == null)
            return ClientTuning.GRAVITY.defaultValue;
        try {
            double clientGravity = ClientTuning.GRAVITY.type.parse(raw);
            return Double.isFinite(clientGravity) ? clientGravity : ClientTuning.GRAVITY.defaultValue;
        } catch (RuntimeException ignored) {
            return ClientTuning.GRAVITY.defaultValue;
        }
    }

    private static int parseInt(String raw, TuningKey<Integer> key, int fallback) {
        if (raw == null)
            return fallback;
        try {
            int value = key.type.parse(raw);
            return key.type.validate(value) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null)
            return fallback;
        try {
            return ClientTuning.MODE_PHYSICS.type.parse(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
