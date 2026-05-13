package com.zhongbai233.super_lead.tuning;

import java.util.Locale;

/** RGB color tuning value stored as 0xRRGGBB. */
public record ColorTuningType() implements TuningType<Integer> {
    public static final int MIN = 0x000000;
    public static final int MAX = 0xFFFFFF;

    @Override
    public String format(Integer value) {
        int rgb = value == null ? 0 : value & MAX;
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    @Override
    public Integer parse(String value) {
        String raw = value.trim();
        if (raw.startsWith("#")) {
            return Integer.parseInt(raw.substring(1), 16);
        }
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            return Integer.parseInt(raw.substring(2), 16);
        }
        return Integer.parseInt(raw);
    }

    @Override
    public boolean validate(Integer value) {
        return value != null && value >= MIN && value <= MAX;
    }

    @Override
    public String describeRange() {
        return "#000000..#FFFFFF";
    }
}