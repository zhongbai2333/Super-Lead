package com.zhongbai233.super_lead.tuning;

import java.util.Objects;

public final class TuningKey<T> {
    public final String id;
    public final String group;
    public final TuningType<T> type;
    public final T defaultValue;
    public final String description;

    private volatile T localValue;
    private volatile T presetValue; // null means no active preset override.
    private volatile T effective;

    TuningKey(String id, String group, TuningType<T> type, T defaultValue, String description) {
        this.id = id;
        this.group = group;
        this.type = type;
        this.defaultValue = defaultValue;
        this.description = description;
        this.effective = defaultValue;
    }

    public T get() {
        return effective;
    }

    public T getDefault() {
        return defaultValue;
    }

    public T getLocalOrDefault() {
        return localValue != null ? localValue : defaultValue;
    }

    public T getPresetOrNull() {
        return presetValue;
    }

    public boolean isLocalOverridden() {
        return localValue != null;
    }

    public boolean isPresetActive() {
        return presetValue != null;
    }

    public boolean setLocalFromString(String raw) {
        T v = parseOrNull(raw);
        if (v == null)
            return false;
        if (!type.validate(v))
            return false;
        T old = effective;
        this.localValue = v;
        recomputeAndFire(old);
        return true;
    }

    public boolean setLocalUncheckedFromString(String raw) {
        T v = parseOrNull(raw);
        if (v == null)
            return false;
        if (v instanceof Double d && (!Double.isFinite(d)))
            return false;
        if (v instanceof Float f && (!Float.isFinite(f)))
            return false;
        T old = effective;
        this.localValue = v;
        recomputeAndFire(old);
        return true;
    }

    public void clearLocal() {
        if (localValue == null)
            return;
        T old = effective;
        this.localValue = null;
        recomputeAndFire(old);
    }

    public boolean setPresetFromString(String raw) {
        T v = parseOrNull(raw);
        if (v == null)
            return false;
        if (!type.validate(v))
            return false;
        T old = effective;
        this.presetValue = v;
        recomputeAndFire(old);
        return true;
    }

    public boolean setPresetUncheckedFromString(String raw) {
        T v = parseOrNull(raw);
        if (v == null)
            return false;
        if (v instanceof Double d && (!Double.isFinite(d)))
            return false;
        if (v instanceof Float f && (!Float.isFinite(f)))
            return false;
        T old = effective;
        this.presetValue = v;
        recomputeAndFire(old);
        return true;
    }

    public void clearPreset() {
        if (presetValue == null)
            return;
        T old = effective;
        this.presetValue = null;
        recomputeAndFire(old);
    }

    private void recomputeAndFire(T old) {
        T newEff = presetValue != null ? presetValue : (localValue != null ? localValue : defaultValue);
        this.effective = newEff;
        if (!Objects.equals(old, newEff)) {
            ClientTuning.fire(this, old, newEff);
        }
    }

    private T parseOrNull(String raw) {
        try {
            return type.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String formatEffective() {
        return type.format(effective);
    }

    public String formatDefault() {
        return type.format(defaultValue);
    }
}
