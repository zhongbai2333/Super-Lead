package com.zhongbai233.super_lead.preset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A named bundle of TuningKey id -> stringified value overrides. */
public final class RopePreset {
    private final String name;
    private final Map<String, String> overrides;

    public RopePreset(String name, Map<String, String> overrides) {
        this.name = Objects.requireNonNull(name);
        this.overrides = new LinkedHashMap<>(overrides);
    }

    public String name() { return name; }
    public Map<String, String> overrides() { return overrides; }

    public RopePreset withOverride(String keyId, String value) {
        Map<String, String> m = new LinkedHashMap<>(this.overrides);
        m.put(keyId, value);
        return new RopePreset(name, m);
    }

    public RopePreset withoutOverride(String keyId) {
        Map<String, String> m = new LinkedHashMap<>(this.overrides);
        m.remove(keyId);
        return new RopePreset(name, m);
    }
}
