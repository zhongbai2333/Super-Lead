package com.zhongbai233.super_lead.tuning.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.Super_lead;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side OP commands for runtime mutation of {@link Config} values.
 * Mutations apply in memory immediately and are persisted by NeoForge on world
 * unload;
 * for guaranteed persistence between sessions edit the TOML directly.
 */
@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadServerCommands {
    private SuperLeadServerCommands() {
    }

    private record Entry(String id, ModConfigSpec.ConfigValue<?> value, String range, String def) {
    }

    private static final Map<String, Entry> ENTRIES = buildEntries();

    private static Map<String, Entry> buildEntries() {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("energy.tier_max_level",
                new Entry("energy.tier_max_level", Config.ENERGY_TIER_MAX_LEVEL, "[0..30]", "30"));
        m.put("energy.base_transfer_per_tick",
                new Entry("energy.base_transfer_per_tick", Config.ENERGY_BASE_TRANSFER, "[1..2147483647]", "256"));
        m.put("network.max_leash_distance",
                new Entry("network.max_leash_distance", Config.NETWORK_MAX_LEASH_DISTANCE, "[4.0..32.0]", "12.0"));
        m.put("network.item_tier_max",
                new Entry("network.item_tier_max", Config.NETWORK_ITEM_TIER_MAX, "[1..12]", "6"));
        m.put("network.fluid_tier_max",
                new Entry("network.fluid_tier_max", Config.NETWORK_FLUID_TIER_MAX, "[1..12]", "4"));
        m.put("network.pressurized_tier_max",
            new Entry("network.pressurized_tier_max", Config.NETWORK_PRESSURIZED_TIER_MAX, "[1..12]", "4"));
        m.put("network.pressurized_batch_amount",
            new Entry("network.pressurized_batch_amount", Config.NETWORK_PRESSURIZED_BATCH_AMOUNT,
                "[1..2147483647]", "1000"));
        m.put("network.thermal_tier_max",
            new Entry("network.thermal_tier_max", Config.NETWORK_THERMAL_TIER_MAX, "[1..12]", "4"));
        m.put("network.thermal_transfer_per_tick",
            new Entry("network.thermal_transfer_per_tick", Config.NETWORK_THERMAL_TRANSFER,
                "[1.0..1.0e12]", "1000.0"));
        m.put("network.item_transfer_interval_ticks",
                new Entry("network.item_transfer_interval_ticks", Config.NETWORK_ITEM_TRANSFER_INTERVAL_TICKS,
                        "[1..40]", "4"));
        m.put("network.fluid_bucket_amount",
                new Entry("network.fluid_bucket_amount", Config.NETWORK_FLUID_BUCKET_AMOUNT, "[100..10000]", "1000"));
        m.put("network.stuck_break_ticks",
                new Entry("network.stuck_break_ticks", Config.NETWORK_STUCK_BREAK_TICKS, "[20..1200]", "100"));
        m.put("network.max_ropes_per_block_face",
                new Entry("network.max_ropes_per_block_face", Config.NETWORK_MAX_ROPES_PER_BLOCK_FACE, "[1..64]", "8"));
        m.put("presets.allow_op_visual_presets",
                new Entry("presets.allow_op_visual_presets", Config.PRESETS_ALLOW_OP_VISUAL_PRESETS, "[true|false]",
                        "true"));
        return m;
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        Permission.HasCommandLevel opLevel = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .requires(src -> src.permissions().hasPermission(opLevel))
                .then(Commands.literal("serverconfig")
                        .then(Commands.literal("list").executes(SuperLeadServerCommands::list))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(SUGGEST_KEY)
                                        .executes(SuperLeadServerCommands::get)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(SUGGEST_KEY)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(SuperLeadServerCommands::set))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(SUGGEST_KEY)
                                        .executes(SuperLeadServerCommands::reset))));
        dispatcher.register(root);
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_KEY = (ctx, builder) -> SharedSuggestionProvider
            .suggest(ENTRIES.keySet(), builder);

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Super Lead server config:").withStyle(ChatFormatting.GOLD), false);
        for (Entry entry : ENTRIES.values()) {
            ctx.getSource().sendSuccess(() -> formatLine(entry), false);
        }
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key");
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> formatLine(entry), false);
        return 1;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int set(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key");
        String raw = StringArgumentType.getString(ctx, "value").trim();
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        Object parsed;
        try {
            parsed = parseFor(entry.value(), raw);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Cannot parse '" + raw + "' as " + describeType(entry.value()) + ": " + e.getMessage()));
            return 0;
        }
        ModConfigSpec.ConfigValue cv = (ModConfigSpec.ConfigValue) entry.value();
        try {
            cv.set(parsed);
        } catch (RuntimeException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Value out of range " + entry.range() + ": " + raw));
            return 0;
        }
        Config.refreshAfterRuntimeSet();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Set ").withStyle(ChatFormatting.GREEN).append(formatLine(entry)),
                true);
        return 1;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int reset(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key");
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        Object def = entry.value().getDefault();
        ModConfigSpec.ConfigValue cv = (ModConfigSpec.ConfigValue) entry.value();
        cv.set(def);
        Config.refreshAfterRuntimeSet();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Reset ").withStyle(ChatFormatting.GREEN).append(formatLine(entry)),
                true);
        return 1;
    }

    private static Object parseFor(ModConfigSpec.ConfigValue<?> cv, String raw) {
        if (cv instanceof ModConfigSpec.IntValue) {
            return Integer.parseInt(raw);
        }
        if (cv instanceof ModConfigSpec.LongValue) {
            return Long.parseLong(raw);
        }
        if (cv instanceof ModConfigSpec.DoubleValue) {
            return Double.parseDouble(raw);
        }
        if (cv instanceof ModConfigSpec.BooleanValue) {
            String n = raw.toLowerCase(Locale.ROOT);
            if (n.equals("true") || n.equals("1") || n.equals("on") || n.equals("yes"))
                return Boolean.TRUE;
            if (n.equals("false") || n.equals("0") || n.equals("off") || n.equals("no"))
                return Boolean.FALSE;
            throw new IllegalArgumentException("expected boolean");
        }
        return raw;
    }

    private static String describeType(ModConfigSpec.ConfigValue<?> cv) {
        if (cv instanceof ModConfigSpec.IntValue)
            return "integer";
        if (cv instanceof ModConfigSpec.LongValue)
            return "long";
        if (cv instanceof ModConfigSpec.DoubleValue)
            return "double";
        if (cv instanceof ModConfigSpec.BooleanValue)
            return "boolean";
        return "string";
    }

    private static Component formatLine(Entry entry) {
        Object current = entry.value().get();
        return Component.literal("  " + entry.id() + " = ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  default " + entry.def() + "  range " + entry.range())
                        .withStyle(ChatFormatting.DARK_GRAY));
    }
}
