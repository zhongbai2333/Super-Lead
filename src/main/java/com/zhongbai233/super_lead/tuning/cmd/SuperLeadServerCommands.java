package com.zhongbai233.super_lead.tuning.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.serverconfig.ServerConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
                                        .executes(SuperLeadServerCommands::reset))))
                .then(Commands.literal("audit")
                        .then(Commands.literal("ropes").executes(SuperLeadServerCommands::auditRopes)));
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
        if (!canMutate(ctx.getSource(), entry)) {
            ctx.getSource().sendFailure(Component.literal("Changing " + entry.id() + " requires OP level 4."));
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
        if (!canMutate(ctx.getSource(), entry)) {
            ctx.getSource().sendFailure(Component.literal("Resetting " + entry.id() + " requires OP level 4."));
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
        var line = Component.literal("  " + entry.id() + " = ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  default " + entry.def() + "  range " + entry.range())
                        .withStyle(ChatFormatting.DARK_GRAY));
        if (ServerConfigManager.isDangerousKey(entry.id())) {
            line.append(Component.literal("  OP4").withStyle(ChatFormatting.RED));
        }
        return line;
    }

    private static boolean canMutate(CommandSourceStack source, Entry entry) {
        return !ServerConfigManager.isDangerousKey(entry.id())
                || source.permissions().hasPermission(ServerConfigManager.dangerousPermission());
    }

    private static int auditRopes(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        RopeAudit audit = RopeAudit.scan(server);
        ctx.getSource().sendSuccess(() -> Component.literal("Super Lead rope audit")
                .withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  total=%d adventure=%d functional=%d crossChunk=%d attachments=%d",
                audit.total, audit.adventure, audit.functional, audit.crossChunk, audit.attachments))
                .withStyle(ChatFormatting.WHITE), false);

        if (!audit.byKind.isEmpty()) {
            StringBuilder kinds = new StringBuilder("  by kind:");
            for (Map.Entry<LeadKind, Integer> entry : audit.byKind.entrySet()) {
                kinds.append(' ').append(entry.getKey().name().toLowerCase(Locale.ROOT))
                        .append('=').append(entry.getValue());
            }
            ctx.getSource().sendSuccess(() -> Component.literal(kinds.toString())
                    .withStyle(ChatFormatting.GRAY), false);
        }

        if (audit.total == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("  no ropes found.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("  hottest 512x512 regions:")
                .withStyle(ChatFormatting.YELLOW), false);
        for (RegionBucket bucket : audit.topRegions(5)) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "    %s region[%d,%d]: ropes=%d adventure=%d functional=%d attachments=%d",
                    bucket.dimension, bucket.regionX, bucket.regionZ, bucket.count, bucket.adventure,
                    bucket.functional, bucket.attachments))
                    .withStyle(bucket.count >= 64 ? ChatFormatting.RED : ChatFormatting.AQUA), false);
        }

        if (!audit.byAdventureOwner.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  adventure owner counts:")
                    .withStyle(ChatFormatting.YELLOW), false);
            audit.byAdventureOwner.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(entry -> ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "    %s: %d", shortUuid(entry.getKey()), entry.getValue()))
                            .withStyle(entry.getValue() >= 32 ? ChatFormatting.RED : ChatFormatting.GRAY), false));
        }

        if (audit.maxRegionCount() >= 64 || audit.maxOwnerCount() >= 32) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  warning: dense rope activity found. Output is intentionally region-level, not per-rope coordinates.")
                    .withStyle(ChatFormatting.RED), false);
        }
        return Math.max(1, audit.total);
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static final class RopeAudit {
        private int total;
        private int adventure;
        private int functional;
        private int crossChunk;
        private int attachments;
        private final EnumMap<LeadKind, Integer> byKind = new EnumMap<>(LeadKind.class);
        private final Map<RegionKey, RegionBucket> byRegion = new LinkedHashMap<>();
        private final Map<UUID, Integer> byAdventureOwner = new LinkedHashMap<>();

        private static RopeAudit scan(MinecraftServer server) {
            RopeAudit audit = new RopeAudit();
            for (ServerLevel level : server.getAllLevels()) {
                SuperLeadSavedData data = SuperLeadSavedData.get(level);
                String dimension = level.dimension().toString();
                for (LeadConnection connection : data.connections()) {
                    audit.accept(data, dimension, connection);
                }
            }
            return audit;
        }

        private void accept(SuperLeadSavedData data, String dimension, LeadConnection connection) {
            total++;
            byKind.merge(connection.kind(), 1, (a, b) -> a + b);
            boolean adventurePlaced = connection.adventurePlaced();
            boolean functionalRope = connection.kind() != LeadKind.NORMAL;
            int attachmentCount = connection.attachments().size();

            if (adventurePlaced) {
                adventure++;
                byAdventureOwner.merge(connection.adventureOwner(), 1, (a, b) -> a + b);
            }
            if (functionalRope) {
                functional++;
            }
            if (data.chunksForConnection(connection.id()).size() > 1) {
                crossChunk++;
            }
            attachments += attachmentCount;

            int midX = Math.floorDiv(connection.from().pos().getX() + connection.to().pos().getX(), 2);
            int midZ = Math.floorDiv(connection.from().pos().getZ() + connection.to().pos().getZ(), 2);
            int regionX = Math.floorDiv(midX, 512);
            int regionZ = Math.floorDiv(midZ, 512);
            RegionKey key = new RegionKey(dimension, regionX, regionZ);
            RegionBucket bucket = byRegion.computeIfAbsent(key,
                    k -> new RegionBucket(k.dimension, k.regionX, k.regionZ));
            bucket.count++;
            if (adventurePlaced) {
                bucket.adventure++;
            }
            if (functionalRope) {
                bucket.functional++;
            }
            bucket.attachments += attachmentCount;
        }

        private ArrayList<RegionBucket> topRegions(int limit) {
            ArrayList<RegionBucket> out = new ArrayList<>(byRegion.values());
            out.sort(Comparator.comparingInt((RegionBucket bucket) -> bucket.count).reversed()
                    .thenComparing(bucket -> bucket.dimension)
                    .thenComparingInt(bucket -> bucket.regionX)
                    .thenComparingInt(bucket -> bucket.regionZ));
            if (out.size() > limit) {
                return new ArrayList<>(out.subList(0, limit));
            }
            return out;
        }

        private int maxRegionCount() {
            int max = 0;
            for (RegionBucket bucket : byRegion.values()) {
                max = Math.max(max, bucket.count);
            }
            return max;
        }

        private int maxOwnerCount() {
            int max = 0;
            for (int count : byAdventureOwner.values()) {
                max = Math.max(max, count);
            }
            return max;
        }
    }

    private record RegionKey(String dimension, int regionX, int regionZ) {
    }

    private static final class RegionBucket {
        private final String dimension;
        private final int regionX;
        private final int regionZ;
        private int count;
        private int adventure;
        private int functional;
        private int attachments;

        private RegionBucket(String dimension, int regionX, int regionZ) {
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }
    }
}
