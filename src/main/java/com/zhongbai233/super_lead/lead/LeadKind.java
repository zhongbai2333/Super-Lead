package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;

public enum LeadKind {
    NORMAL("normal"),
    REDSTONE("redstone");

    public static final Codec<LeadKind> CODEC = Codec.STRING.xmap(LeadKind::byName, LeadKind::serializedName);

    private final String serializedName;

    LeadKind(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static LeadKind byName(String name) {
        for (LeadKind kind : values()) {
            if (kind.serializedName.equals(name)) {
                return kind;
            }
        }
        return NORMAL;
    }
}