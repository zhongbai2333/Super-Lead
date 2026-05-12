package com.zhongbai233.super_lead.tuning;

import java.util.Locale;

public interface TuningType<T> {
    String format(T value);

    T parse(String value);

    boolean validate(T value);

    String describeRange();

    static TuningType<Double> doubleRange(double min, double max) {
        return new DoubleTuningType(min, max);
    }

    static TuningType<Integer> intRange(int min, int max) {
        return new IntTuningType(min, max);
    }

    static TuningType<Boolean> bool() {
        return BOOL;
    }

    TuningType<Boolean> BOOL = new TuningType<>() {
        @Override
        public String format(Boolean value) {
            return Boolean.toString(value);
        }

        @Override
        public Boolean parse(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("true") || normalized.equals("1")
                    || normalized.equals("on") || normalized.equals("yes")) {
                return Boolean.TRUE;
            }
            if (normalized.equals("false") || normalized.equals("0")
                    || normalized.equals("off") || normalized.equals("no")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("not a boolean: " + value);
        }

        @Override
        public boolean validate(Boolean value) {
            return value != null;
        }

        @Override
        public String describeRange() {
            return "{true,false}";
        }
    };
}
