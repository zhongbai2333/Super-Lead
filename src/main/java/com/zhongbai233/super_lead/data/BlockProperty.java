package com.zhongbai233.super_lead.data;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-block properties for rope attachments and signal routing.
 * <p>
 * All fields are nullable — a null value means "use the built-in default".
 */
public record BlockProperty(
        Boolean attachPierced,
        Boolean attachMountAbove,
        Double attachHangOffset,
        Double attachMountOffset,
        Double attachHangerLength,
        Double attachHangerSpacing,
        Boolean attachHangerEnabled,
        Boolean signalBridgeEnabled,
        String attachModelMode,
        Map<String, String> attachModelState) {

    // ---- built-in defaults ----------
    public static final double DEF_HANG_OFFSET = 0.40D;
    public static final double DEF_MOUNT_OFFSET = 0.30D;
    public static final double DEF_HANGER_LENGTH = 0.20D;
    public static final double DEF_HANGER_SPACING = 0.18D;
    public static final String MODEL_MODE_AUTO = "auto";
    public static final String MODEL_MODE_BLOCK = "block";
    public static final String MODEL_MODE_ITEM = "item";

    public static final BlockProperty DEFAULTS = new BlockProperty(
            null, false, DEF_HANG_OFFSET, DEF_MOUNT_OFFSET,
            DEF_HANGER_LENGTH, DEF_HANGER_SPACING, true, false,
            MODEL_MODE_AUTO, Map.of());

    public static final BlockProperty EMPTY = new BlockProperty(
            null, null, null, null, null, null, null, null, null, null);

    public BlockProperty {
        attachModelMode = normalizeModelMode(attachModelMode);
        attachModelState = normalizeModelState(attachModelState);
    }

    public BlockProperty merge(BlockProperty fallback) {
        if (fallback == null || fallback == EMPTY)
            return this;
        if (this == EMPTY)
            return fallback;
        return new BlockProperty(
                attachPierced, // pierced stays null unless explicitly set — fallback is shape heuristic
                pick(attachMountAbove, fallback.attachMountAbove),
                pick(attachHangOffset, fallback.attachHangOffset),
                pick(attachMountOffset, fallback.attachMountOffset),
                pick(attachHangerLength, fallback.attachHangerLength),
                pick(attachHangerSpacing, fallback.attachHangerSpacing),
                pick(attachHangerEnabled, fallback.attachHangerEnabled),
                pick(signalBridgeEnabled, fallback.signalBridgeEnabled),
                pick(attachModelMode, fallback.attachModelMode),
                pick(attachModelState, fallback.attachModelState));
    }

    // convenience getters — nullable fields return null when not set
    public Boolean pierced() {
        return attachPierced;
    }

    public Boolean mountAbove() {
        return attachMountAbove;
    }

    public double hangOffset() {
        return attachHangOffset != null ? attachHangOffset : DEFAULTS.attachHangOffset;
    }

    public double mountOffset() {
        return attachMountOffset != null ? attachMountOffset : DEFAULTS.attachMountOffset;
    }

    public double hangerLen() {
        return attachHangerLength != null ? attachHangerLength : DEFAULTS.attachHangerLength;
    }

    public double hangerSpc() {
        return attachHangerSpacing != null ? attachHangerSpacing : DEFAULTS.attachHangerSpacing;
    }

    public boolean hangerOn() {
        return attachHangerEnabled != null ? attachHangerEnabled : DEFAULTS.attachHangerEnabled;
    }

    public boolean bridgeOn() {
        return signalBridgeEnabled != null ? signalBridgeEnabled : DEFAULTS.signalBridgeEnabled;
    }

    public String modelMode() {
        return attachModelMode != null ? attachModelMode : MODEL_MODE_AUTO;
    }

    public Map<String, String> modelState() {
        return attachModelState != null ? attachModelState : Map.of();
    }

    public static String normalizeModelMode(String raw) {
        if (raw == null)
            return null;
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || mode.equals("default"))
            return null;
        return switch (mode) {
            case MODEL_MODE_AUTO, MODEL_MODE_BLOCK, MODEL_MODE_ITEM -> mode;
            default -> MODEL_MODE_AUTO;
        };
    }

    private static Map<String, String> normalizeModelState(Map<String, String> raw) {
        if (raw == null)
            return null;
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue().trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static <T> T pick(T a, T b) {
        return a != null ? a : b;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Boolean attachPierced, attachMountAbove, attachHangerEnabled, signalBridgeEnabled;
        private Double attachHangOffset, attachMountOffset, attachHangerLength, attachHangerSpacing;
        private String attachModelMode;
        private Map<String, String> attachModelState;

        public Builder pierced(boolean v) {
            attachPierced = v;
            return this;
        }

        public Builder mountAbove(boolean v) {
            attachMountAbove = v;
            return this;
        }

        public Builder hangOffset(double v) {
            attachHangOffset = v;
            return this;
        }

        public Builder mountOffset(double v) {
            attachMountOffset = v;
            return this;
        }

        public Builder hangerLength(double v) {
            attachHangerLength = v;
            return this;
        }

        public Builder hangerSpacing(double v) {
            attachHangerSpacing = v;
            return this;
        }

        public Builder hangerEnabled(boolean v) {
            attachHangerEnabled = v;
            return this;
        }

        public Builder signalBridge(boolean v) {
            signalBridgeEnabled = v;
            return this;
        }

        public Builder modelMode(String v) {
            attachModelMode = v;
            return this;
        }

        public Builder modelState(Map<String, String> v) {
            attachModelState = v;
            return this;
        }

        public BlockProperty build() {
            return new BlockProperty(attachPierced, attachMountAbove, attachHangOffset, attachMountOffset,
                    attachHangerLength, attachHangerSpacing, attachHangerEnabled, signalBridgeEnabled,
                    attachModelMode, attachModelState);
        }
    }
}
