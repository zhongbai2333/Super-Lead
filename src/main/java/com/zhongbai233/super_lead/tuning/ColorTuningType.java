package com.zhongbai233.super_lead.tuning;

import java.util.Locale;

/** ARGB color tuning value stored as 0xAARRGGBB; legacy RGB is opaque. */
public record ColorTuningType() implements TuningType<Integer> {
    @Override
    public String format(Integer value) {
        int argb = value == null ? 0xFF000000 : value;
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    @Override
    public Integer parse(String value) {
        String raw = value.trim();
        if (raw.startsWith("#")) {
            return parseHex(raw.substring(1));
        }
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            return parseHex(raw.substring(2));
        }
        long decimal = Long.parseLong(raw);
        if (decimal >= 0L && decimal <= 0xFFFFFFL) {
            return 0xFF000000 | (int) decimal;
        }
        if (decimal >= Integer.MIN_VALUE && decimal <= 0xFFFFFFFFL) {
            return (int) decimal;
        }
        throw new NumberFormatException("ARGB value outside 32-bit range: " + raw);
    }

    @Override
    public boolean validate(Integer value) {
        return value != null;
    }

    @Override
    public String describeRange() {
        return "#00000000..#FFFFFFFF (#RRGGBB is opaque)";
    }

    private static int parseHex(String hex) {
        if (hex.length() == 6) {
            return 0xFF000000 | Integer.parseInt(hex, 16);
        }
        if (hex.length() == 8) {
            return Integer.parseUnsignedInt(hex, 16);
        }
        throw new NumberFormatException("Expected 6 or 8 hexadecimal digits");
    }
}