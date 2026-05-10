package com.zhongbai233.super_lead.tuning;

import java.util.Locale;

public record DoubleTuningType(double min, double max) implements TuningType<Double> {
    @Override
    public String format(Double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }

    @Override
    public Double parse(String value) {
        return Double.parseDouble(value.trim());
    }

    @Override
    public boolean validate(Double value) {
        return value != null && !value.isNaN() && value >= min && value <= max;
    }

    @Override
    public String describeRange() {
        return "[" + min + ", " + max + "]";
    }
}
