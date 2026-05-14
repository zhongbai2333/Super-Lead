package com.zhongbai233.super_lead;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final int AE_CHANNEL_BASE = 8;
    /**
     * AE2's public networking API exposes ordinary cable capacity (8 channels)
     * and dense cable capacity (32 channels) via {@code GridFlags.DENSE_CAPACITY}.
     * It does not expose an arbitrary per-node channel limit such as 256.
     */
    public static final int AE_CHANNEL_MAX = 32;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue ENERGY_TIER_MAX_LEVEL;
    public static final ModConfigSpec.IntValue ENERGY_BASE_TRANSFER;

    public static final ModConfigSpec.DoubleValue NETWORK_MAX_LEASH_DISTANCE;
    public static final ModConfigSpec.IntValue NETWORK_ITEM_TIER_MAX;
    public static final ModConfigSpec.IntValue NETWORK_FLUID_TIER_MAX;
    public static final ModConfigSpec.IntValue NETWORK_PRESSURIZED_TIER_MAX;
    public static final ModConfigSpec.IntValue NETWORK_PRESSURIZED_BATCH_AMOUNT;
    public static final ModConfigSpec.IntValue NETWORK_THERMAL_TIER_MAX;
    public static final ModConfigSpec.DoubleValue NETWORK_THERMAL_TRANSFER;
    public static final ModConfigSpec.IntValue NETWORK_ITEM_TRANSFER_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue NETWORK_FLUID_BUCKET_AMOUNT;
    public static final ModConfigSpec.IntValue NETWORK_STUCK_BREAK_TICKS;
    public static final ModConfigSpec.IntValue NETWORK_MAX_ROPES_PER_BLOCK_FACE;

    public static final ModConfigSpec.BooleanValue PRESETS_ALLOW_OP_VISUAL_PRESETS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("energy");
        ENERGY_TIER_MAX_LEVEL = builder
                .comment("Maximum upgrade tier for energy leads. Tier 0 is 1x; each tier doubles speed.",
                        "Default 30 caps the multiplier at Integer.MAX_VALUE.")
                .defineInRange("tier_max_level", 30, 0, 30);
        ENERGY_BASE_TRANSFER = builder
                .comment("Base FE/tick transferred per energy lead at tier 0.")
                .defineInRange("base_transfer_per_tick", 256, 1, Integer.MAX_VALUE);
        builder.pop();

        builder.push("network");
        NETWORK_MAX_LEASH_DISTANCE = builder
                .comment("Maximum length (in blocks) of a single lead connection.")
                .defineInRange("max_leash_distance", 12.0D, 4.0D, 32.0D);
        NETWORK_ITEM_TIER_MAX = builder
                .comment("Maximum upgrade tier for item leads.")
                .defineInRange("item_tier_max", 6, 1, 12);
        NETWORK_FLUID_TIER_MAX = builder
                .comment("Maximum upgrade tier for fluid leads.")
                .defineInRange("fluid_tier_max", 4, 1, 12);
        NETWORK_PRESSURIZED_TIER_MAX = builder
            .comment("Maximum upgrade tier for Mekanism pressurized (chemical/gas) leads.")
            .defineInRange("pressurized_tier_max", 4, 1, 12);
        NETWORK_PRESSURIZED_BATCH_AMOUNT = builder
            .comment("Per-rope batch amount for Mekanism chemical/gas transfers.")
            .defineInRange("pressurized_batch_amount", 1000, 1, Integer.MAX_VALUE);
        NETWORK_THERMAL_TIER_MAX = builder
            .comment("Maximum upgrade tier for Mekanism thermal leads.")
            .defineInRange("thermal_tier_max", 4, 1, 12);
        NETWORK_THERMAL_TRANSFER = builder
            .comment("Base heat units transferred per thermal lead per tick at tier 0.")
            .defineInRange("thermal_transfer_per_tick", 1000.0D, 1.0D, 1.0e12D);
        NETWORK_ITEM_TRANSFER_INTERVAL_TICKS = builder
                .comment("Tick interval between item-lead transfer attempts. Lower = faster.")
                .defineInRange("item_transfer_interval_ticks", 4, 1, 40);
        NETWORK_FLUID_BUCKET_AMOUNT = builder
                .comment("Per-rope batch unit (millibuckets) for fluid transfers.")
                .defineInRange("fluid_bucket_amount", 1000, 100, 10000);
        NETWORK_STUCK_BREAK_TICKS = builder
                .comment("Ticks a rope must remain stuck inside collision before it auto-breaks.")
                .defineInRange("stuck_break_ticks", 100, 20, 1200);
        NETWORK_MAX_ROPES_PER_BLOCK_FACE = builder
                .comment("Maximum number of ropes that can anchor to a single block face.")
                .defineInRange("max_ropes_per_block_face", 8, 1, 64);
        builder.pop();

        builder.push("presets");
        PRESETS_ALLOW_OP_VISUAL_PRESETS = builder
                .comment("Whether OPs may push visual preset packages to selected players.")
                .define("allow_op_visual_presets", true);
        builder.pop();

        SPEC = builder.build();
    }

    private static volatile int cachedTierMax = 30;
    private static volatile int cachedBaseTransfer = 256;
    private static volatile double cachedMaxLeashDistance = 12.0D;
    private static volatile int cachedItemTierMax = 6;
    private static volatile int cachedFluidTierMax = 4;
    private static volatile int cachedPressurizedTierMax = 4;
    private static volatile int cachedPressurizedBatchAmount = 1000;
    private static volatile int cachedThermalTierMax = 4;
    private static volatile double cachedThermalTransfer = 1000.0D;
    private static volatile int cachedItemTransferIntervalTicks = 4;
    private static volatile int cachedFluidBucketAmount = 1000;
    private static volatile int cachedStuckBreakTicks = 100;
    private static volatile int cachedMaxRopesPerBlockFace = 8;
    private static volatile boolean cachedAllowOpVisualPresets = true;

    private Config() {
    }

    public static int energyTierMaxLevel() {
        return cachedTierMax;
    }

    public static int energyBaseTransfer() {
        return cachedBaseTransfer;
    }

    public static double maxLeashDistance() {
        return cachedMaxLeashDistance;
    }

    public static int itemTierMax() {
        return cachedItemTierMax;
    }

    public static int fluidTierMax() {
        return cachedFluidTierMax;
    }

    public static int pressurizedTierMax() {
        return cachedPressurizedTierMax;
    }

    public static int pressurizedBatchAmount() {
        return cachedPressurizedBatchAmount;
    }

    public static int thermalTierMax() {
        return cachedThermalTierMax;
    }

    public static double thermalBaseTransfer() {
        return cachedThermalTransfer;
    }

    public static int aeChannelBase() {
        return AE_CHANNEL_BASE;
    }

    public static int aeChannelMax() {
        return AE_CHANNEL_MAX;
    }

    public static int aeChannelTierMax() {
        return 1;
    }

    public static int aeChannelCapacity(int tier) {
        return tier <= 0 ? AE_CHANNEL_BASE : AE_CHANNEL_MAX;
    }

    public static int itemTransferIntervalTicks() {
        return cachedItemTransferIntervalTicks;
    }

    public static int fluidBucketAmount() {
        return cachedFluidBucketAmount;
    }

    public static int stuckBreakTicks() {
        return cachedStuckBreakTicks;
    }

    public static int maxRopesPerBlockFace() {
        return cachedMaxRopesPerBlockFace;
    }

    public static boolean allowOpVisualPresets() {
        return cachedAllowOpVisualPresets;
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
        cachedMaxLeashDistance = NETWORK_MAX_LEASH_DISTANCE.get();
        cachedItemTierMax = NETWORK_ITEM_TIER_MAX.getAsInt();
        cachedFluidTierMax = NETWORK_FLUID_TIER_MAX.getAsInt();
        cachedPressurizedTierMax = NETWORK_PRESSURIZED_TIER_MAX.getAsInt();
        cachedPressurizedBatchAmount = NETWORK_PRESSURIZED_BATCH_AMOUNT.getAsInt();
        cachedThermalTierMax = NETWORK_THERMAL_TIER_MAX.getAsInt();
        cachedThermalTransfer = NETWORK_THERMAL_TRANSFER.get();
        cachedItemTransferIntervalTicks = NETWORK_ITEM_TRANSFER_INTERVAL_TICKS.getAsInt();
        cachedFluidBucketAmount = NETWORK_FLUID_BUCKET_AMOUNT.getAsInt();
        cachedStuckBreakTicks = NETWORK_STUCK_BREAK_TICKS.getAsInt();
        cachedMaxRopesPerBlockFace = NETWORK_MAX_ROPES_PER_BLOCK_FACE.getAsInt();
        cachedAllowOpVisualPresets = PRESETS_ALLOW_OP_VISUAL_PRESETS.get();
    }

    /**
     * Pull cached values from the underlying spec after a runtime mutation via
     * {@link ModConfigSpec.ConfigValue#set}. The spec persists to disk on world
     * unload.
     */
    public static void refreshAfterRuntimeSet() {
        refresh();
    }

    public static java.util.Map<String, String> snapshot() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        m.put("energy.tier_max_level", Integer.toString(ENERGY_TIER_MAX_LEVEL.getAsInt()));
        m.put("energy.base_transfer_per_tick", Integer.toString(ENERGY_BASE_TRANSFER.getAsInt()));
        m.put("network.max_leash_distance", Double.toString(NETWORK_MAX_LEASH_DISTANCE.get()));
        m.put("network.item_tier_max", Integer.toString(NETWORK_ITEM_TIER_MAX.getAsInt()));
        m.put("network.fluid_tier_max", Integer.toString(NETWORK_FLUID_TIER_MAX.getAsInt()));
        m.put("network.pressurized_tier_max", Integer.toString(NETWORK_PRESSURIZED_TIER_MAX.getAsInt()));
        m.put("network.pressurized_batch_amount", Integer.toString(NETWORK_PRESSURIZED_BATCH_AMOUNT.getAsInt()));
        m.put("network.thermal_tier_max", Integer.toString(NETWORK_THERMAL_TIER_MAX.getAsInt()));
        m.put("network.thermal_transfer_per_tick", Double.toString(NETWORK_THERMAL_TRANSFER.get()));
        m.put("network.item_transfer_interval_ticks",
                Integer.toString(NETWORK_ITEM_TRANSFER_INTERVAL_TICKS.getAsInt()));
        m.put("network.fluid_bucket_amount", Integer.toString(NETWORK_FLUID_BUCKET_AMOUNT.getAsInt()));
        m.put("network.stuck_break_ticks", Integer.toString(NETWORK_STUCK_BREAK_TICKS.getAsInt()));
        m.put("network.max_ropes_per_block_face", Integer.toString(NETWORK_MAX_ROPES_PER_BLOCK_FACE.getAsInt()));
        m.put("presets.allow_op_visual_presets", Boolean.toString(PRESETS_ALLOW_OP_VISUAL_PRESETS.get()));
        return m;
    }

    public static boolean applyRuntime(String key, String value) {
        try {
            switch (key) {
                case "energy.tier_max_level" -> ENERGY_TIER_MAX_LEVEL.set(parseIntClamped(value, 0, 30));
                case "energy.base_transfer_per_tick" ->
                    ENERGY_BASE_TRANSFER.set(parseIntClamped(value, 1, Integer.MAX_VALUE));
                case "network.max_leash_distance" ->
                    NETWORK_MAX_LEASH_DISTANCE.set(parseDoubleClamped(value, 4.0D, 32.0D));
                case "network.item_tier_max" -> NETWORK_ITEM_TIER_MAX.set(parseIntClamped(value, 1, 12));
                case "network.fluid_tier_max" -> NETWORK_FLUID_TIER_MAX.set(parseIntClamped(value, 1, 12));
                case "network.pressurized_tier_max" ->
                    NETWORK_PRESSURIZED_TIER_MAX.set(parseIntClamped(value, 1, 12));
                case "network.pressurized_batch_amount" ->
                    NETWORK_PRESSURIZED_BATCH_AMOUNT.set(parseIntClamped(value, 1, Integer.MAX_VALUE));
                case "network.thermal_tier_max" -> NETWORK_THERMAL_TIER_MAX.set(parseIntClamped(value, 1, 12));
                case "network.thermal_transfer_per_tick" ->
                    NETWORK_THERMAL_TRANSFER.set(parseDoubleClamped(value, 1.0D, 1.0e12D));
                case "network.item_transfer_interval_ticks" ->
                    NETWORK_ITEM_TRANSFER_INTERVAL_TICKS.set(parseIntClamped(value, 1, 40));
                case "network.fluid_bucket_amount" ->
                    NETWORK_FLUID_BUCKET_AMOUNT.set(parseIntClamped(value, 100, 10000));
                case "network.stuck_break_ticks" -> NETWORK_STUCK_BREAK_TICKS.set(parseIntClamped(value, 20, 1200));
                case "network.max_ropes_per_block_face" ->
                    NETWORK_MAX_ROPES_PER_BLOCK_FACE.set(parseIntClamped(value, 1, 64));
                case "presets.allow_op_visual_presets" ->
                    PRESETS_ALLOW_OP_VISUAL_PRESETS.set(Boolean.parseBoolean(value.trim()));
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        refresh();
        return true;
    }

    private static int parseIntClamped(String s, int lo, int hi) {
        int v = Integer.parseInt(s.trim());
        if (v < lo)
            v = lo;
        if (v > hi)
            v = hi;
        return v;
    }

    private static double parseDoubleClamped(String s, double lo, double hi) {
        double v = Double.parseDouble(s.trim());
        if (v < lo)
            v = lo;
        if (v > hi)
            v = hi;
        return v;
    }
}
