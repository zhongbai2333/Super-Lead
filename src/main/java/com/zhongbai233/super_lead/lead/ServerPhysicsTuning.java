package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.preset.RopePreset;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

record ServerPhysicsTuning(
        boolean physicsEnabled,
        double gravity,
    double slack,
        double segmentLength,
        int segmentMax,
        double damping,
        int iterAir,
        double compliance,
        boolean pushbackEnabled,
        double contactRadius,
        double springK,
        double velocityDamping,
        double maxRecoilPerTick,
        double ziplineSpeedLimit,
        double ziplineRedstoneAccelerationMultiplier) {
    private static final double CONTACT_RADIUS_FALLBACK = ClientTuning.CONTACT_RADIUS.defaultValue;
    private static final double SPRING_K_FALLBACK = ClientTuning.CONTACT_SPRING.defaultValue;
    private static final double SPRING_K_MINIMUM = 0.30D;
    private static final double VELOCITY_DAMPING_FALLBACK = ClientTuning.CONTACT_VELOCITY_DAMPING.defaultValue;
    private static final double MAX_RECOIL_PER_TICK_FALLBACK = ClientTuning.CONTACT_MAX_RECOIL_PER_TICK.defaultValue;
    private static final double MAX_RECOIL_PER_TICK_MINIMUM = 0.20D;

    static ServerPhysicsTuning loadServerPhysicsTuning(ServerLevel level, String presetName) {
        Map<String, String> overrides = RopePresetLibrary.forServer(level.getServer())
                .load(presetName)
                .map(RopePreset::overrides)
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
        double compliance = parseDouble(overrides.get(ClientTuning.COMPLIANCE.id),
                ClientTuning.COMPLIANCE, ClientTuning.COMPLIANCE.defaultValue);
        boolean pushbackEnabled = parseBool(overrides.get(ClientTuning.CONTACT_PUSHBACK.id),
                ClientTuning.CONTACT_PUSHBACK.defaultValue);
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
        double ziplineSpeedLimit = parseDouble(overrides.get(ClientTuning.ZIPLINE_SPEED_LIMIT.id),
                ClientTuning.ZIPLINE_SPEED_LIMIT, Double.NaN);
        double ziplineRedstoneAccelerationMultiplier = parseDouble(
                overrides.get(ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER.id),
                ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER,
                ClientTuning.ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER.defaultValue);
        return new ServerPhysicsTuning(physicsEnabled, gravity, slack,
                segmentLength, segmentMax, damping, iterAir, compliance,
                pushbackEnabled, contactRadius, springK, velocityDamping, maxRecoilPerTick,
                ziplineSpeedLimit, ziplineRedstoneAccelerationMultiplier);
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
