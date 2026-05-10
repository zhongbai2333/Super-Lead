package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;

public enum LeadKind {
    NORMAL("normal"),
    REDSTONE("redstone"),
    ENERGY("energy"),
    ITEM("item"),
    FLUID("fluid");

    public static final Codec<LeadKind> CODEC = Codec.STRING.xmap(LeadKind::byName, LeadKind::serializedName);

    private final String serializedName;

    LeadKind(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static LeadKind byName(String name) {
        return switch (name) {
            case "redstone" -> REDSTONE;
            case "energy" -> ENERGY;
            case "item" -> ITEM;
            case "fluid" -> FLUID;
            default -> NORMAL;
        };
    }
}
