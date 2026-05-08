package com.zhongbai233.super_lead;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue ENERGY_TIER_MAX_LEVEL;
    public static final ModConfigSpec.IntValue ENERGY_BASE_TRANSFER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("energy");
        ENERGY_TIER_MAX_LEVEL = builder
                .comment("Maximum upgrade tier for energy leads. tier 0 = 1×, each tier doubles speed.",
                        "Default 30 caps multiplier at Integer.MAX_VALUE (≈ no practical limit).")
                .defineInRange("tier_max_level", 30, 0, 30);
        ENERGY_BASE_TRANSFER = builder
                .comment("Base FE/tick transferred per energy lead at tier 0.")
                .defineInRange("base_transfer_per_tick", 256, 1, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private static volatile int cachedTierMax = 30;
    private static volatile int cachedBaseTransfer = 256;

    private Config() {}

    public static int energyTierMaxLevel() {
        return cachedTierMax;
    }

    public static int energyBaseTransfer() {
        return cachedBaseTransfer;
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    private static void refresh() {
        cachedTierMax = ENERGY_TIER_MAX_LEVEL.getAsInt();
        cachedBaseTransfer = ENERGY_BASE_TRANSFER.getAsInt();
    }
}
