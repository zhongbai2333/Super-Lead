package com.zhongbai233.super_lead.tuning;

public record IntTuningType(int min, int max) implements TuningType<Integer> {
    @Override
    public String format(Integer value) {
        return Integer.toString(value);
    }

    @Override
    public Integer parse(String value) {
        return Integer.parseInt(value.trim());
    }

    @Override
    public boolean validate(Integer value) {
        return value != null && value >= min && value <= max;
    }

    @Override
    public String describeRange() {
        return "[" + min + ", " + max + "]";
    }
}
