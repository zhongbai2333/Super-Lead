package com.zhongbai233.super_lead.tuning.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import com.zhongbai233.super_lead.tuning.gui.SuperLeadConfigScreen;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class SuperLeadCommands {
    private SuperLeadCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        ClientTuning.loadOnce();
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .then(Commands.literal("config")
                        .then(Commands.literal("list")
                                .executes(SuperLeadCommands::list)
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .suggests(SUGGEST_GROUP)
                                        .executes(SuperLeadCommands::listGroup)))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .suggests(SUGGEST_KEY)
                                        .executes(SuperLeadCommands::get)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(SUGGEST_KEY)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(SuperLeadCommands::set))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .suggests(SUGGEST_KEY_OR_ALL)
                                        .executes(SuperLeadCommands::reset))))
                .then(Commands.literal("status").executes(SuperLeadCommands::status))
                .then(Commands.literal("gui").executes(SuperLeadCommands::openGui));
        dispatcher.register(root);
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_KEY =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    ClientTuning.allKeys().stream().map(key -> key.id), builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_KEY_OR_ALL =
            (ctx, builder) -> {
                var stream = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("all"),
                        ClientTuning.allKeys().stream().map(key -> key.id));
                return SharedSuggestionProvider.suggest(stream, builder);
            };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_GROUP =
            (ctx, builder) -> SharedSuggestionProvider.suggest(ClientTuning.groups(), builder);

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Super Lead config keys by group:")
                        .withStyle(ChatFormatting.GOLD),
                false);
        String currentGroup = "";
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            if (!key.group.equals(currentGroup)) {
                currentGroup = key.group;
                String group = currentGroup;
                ctx.getSource().sendSuccess(
                        () -> Component.literal("[" + group + "]").withStyle(ChatFormatting.AQUA),
                        false);
            }
            ctx.getSource().sendSuccess(() -> formatKeyLine(key), false);
        }
        return 1;
    }

    private static int listGroup(CommandContext<CommandSourceStack> ctx) {
        String group = StringArgumentType.getString(ctx, "group");
        boolean any = false;
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            if (!key.group.equals(group)) {
                continue;
            }
            any = true;
            ctx.getSource().sendSuccess(() -> formatKeyLine(key), false);
        }
        if (!any) {
            ctx.getSource().sendFailure(Component.literal("Unknown group: " + group));
            return 0;
        }
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key").trim();
        TuningKey<?> key = ClientTuning.byId(id);
        if (key == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> formatKeyLine(key), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key").trim();
        String value = StringArgumentType.getString(ctx, "value").trim();
        TuningKey<?> key = ClientTuning.byId(id);
        if (key == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        if (!key.setLocalFromString(value)) {
            ctx.getSource().sendFailure(Component.literal(String.format(Locale.ROOT,
                    "Cannot set %s = %s. Expected %s", id, value, key.type.describeRange())));
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Set ").withStyle(ChatFormatting.GREEN)
                        .append(formatKeyLine(key)),
                false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "key").trim();
        if (id.equalsIgnoreCase("all")) {
            int resetCount = 0;
            for (TuningKey<?> key : ClientTuning.allKeys()) {
                if (key.isLocalOverridden()) {
                    key.clearLocal();
                    resetCount++;
                }
            }
            int count = resetCount;
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Reset " + count + " keys.")
                            .withStyle(ChatFormatting.GREEN),
                    false);
            return 1;
        }

        TuningKey<?> key = ClientTuning.byId(id);
        if (key == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + id));
            return 0;
        }
        key.clearLocal();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Reset ").withStyle(ChatFormatting.GREEN)
                        .append(formatKeyLine(key)),
                false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        int local = 0;
        int preset = 0;
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            if (key.isLocalOverridden()) {
                local++;
            }
            if (key.isPresetActive()) {
                preset++;
            }
        }
        int localCount = local;
        int presetCount = preset;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Super Lead: %d local overrides, %d preset overrides, renderEpoch=%d, physicsEpoch=%d",
                localCount, presetCount, ClientTuning.renderEpoch(), ClientTuning.physicsEpoch())), false);
        return 1;
    }

    private static int openGui(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.client.Minecraft.getInstance().execute(SuperLeadConfigScreen::open);
        return 1;
    }

    private static Component formatKeyLine(TuningKey<?> key) {
        ChatFormatting valueColor = key.isPresetActive() ? ChatFormatting.LIGHT_PURPLE
                : key.isLocalOverridden() ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
        Component value = Component.literal(key.formatEffective()).withStyle(valueColor);
        return Component.literal("  " + key.id + " = ").withStyle(ChatFormatting.WHITE)
                .append(value)
                .append(Component.literal("  default " + key.formatDefault()
                        + "  range " + key.type.describeRange())
                        .withStyle(ChatFormatting.DARK_GRAY));
    }
}
