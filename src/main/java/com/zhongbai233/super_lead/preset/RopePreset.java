package com.zhongbai233.super_lead.preset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A named bundle of TuningKey id -> stringified value overrides. */
public final class RopePreset {
    private final String name;
    private final Map<String, String> overrides;
    private final UUID owner;

    public RopePreset(String name, Map<String, String> overrides) {
        this(name, overrides, null);
    }

    public RopePreset(String name, Map<String, String> overrides, UUID owner) {
        this.name = Objects.requireNonNull(name);
        this.overrides = new LinkedHashMap<>(overrides);
        this.owner = owner;
    }

    public String name() {
        return name;
    }

    public Map<String, String> overrides() {
        return overrides;
    }

    public UUID owner() {
        return owner;
    }

    public boolean ownedBy(UUID playerId) {
        return owner != null && owner.equals(playerId);
    }

    public RopePreset withOverride(String keyId, String value) {
        Map<String, String> m = new LinkedHashMap<>(this.overrides);
        m.put(keyId, value);
        return new RopePreset(name, m, owner);
    }

    public RopePreset withoutOverride(String keyId) {
        Map<String, String> m = new LinkedHashMap<>(this.overrides);
        m.remove(keyId);
        return new RopePreset(name, m, owner);
    }
}
