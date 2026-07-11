package com.zhongbai233.super_lead.tuning.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.SuperLeadItems;
import com.zhongbai233.super_lead.lead.cargo.SuperLeadDataComponents;
import com.zhongbai233.super_lead.permissions.SuperLeadPermissions;
import com.zhongbai233.super_lead.preset.PresetBinderData;
import com.zhongbai233.super_lead.preset.PresetEditKey;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import com.zhongbai233.super_lead.preset.RopePreset;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadPresetCommands {
    private SuperLeadPresetCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .then(Commands.literal("preset")
                        .requires(SuperLeadPresetCommands::canUsePresetRoot)
                        .then(Commands.literal("list").executes(SuperLeadPresetCommands::list))
                        .then(Commands.literal("export")
                                .requires(SuperLeadPermissions::sourceCanAdmin)
                                .executes(SuperLeadPresetCommands::exportAll))
                        .then(Commands.literal("import")
                                .requires(SuperLeadPermissions::sourceCanAdmin)
                                .executes(SuperLeadPresetCommands::importAll))
                        .then(Commands.literal("show")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SUGGEST_PRESET)
                                        .executes(SuperLeadPresetCommands::show)))
                        .then(Commands.literal("save")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.literal("from-keys")
                                                .then(Commands.argument("pairs", StringArgumentType.greedyString())
                                                        .executes(SuperLeadPresetCommands::saveFromKeys)))))
                        .then(Commands.literal("give-binder")
                                .requires(SuperLeadPermissions::sourceCanManage)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SUGGEST_PRESET)
                                        .executes(SuperLeadPresetCommands::giveBinder)
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(SuperLeadPresetCommands::giveBinderToTarget))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SUGGEST_PRESET)
                                        .executes(SuperLeadPresetCommands::delete)))
                        .then(Commands.literal("edit")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SUGGEST_PRESET)
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .suggests(SUGGEST_KEY)
                                                .then(Commands.literal("reset")
                                                        .executes(SuperLeadPresetCommands::editReset))
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(SuperLeadPresetCommands::editSet))))));
        dispatcher.register(root);
    }

    private static boolean canUsePresetRoot(CommandSourceStack source) {
        // Managers can list/create global presets. Regular players may still need
        // the root so owner-scoped show/edit/delete can report accurate per-preset
        // permission failures instead of hiding the whole command tree.
        return SuperLeadPermissions.sourceCanManage(source)
                || source.getEntity() instanceof ServerPlayer;
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PRESET = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        return SharedSuggestionProvider.suggest(RopePresetLibrary.forServer(server).list(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_KEY = (ctx, builder) -> SharedSuggestionProvider
            .suggest(ClientTuning.allKeys().stream().map(k -> k.id), builder);

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!SuperLeadPermissions.sourceCanManage(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("Missing Super Lead preset management permission."));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        List<String> names = RopePresetLibrary.forServer(server).list();
        if (names.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No presets stored.").withStyle(ChatFormatting.GRAY),
                    false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Stored presets (" + names.size() + "):")
                .withStyle(ChatFormatting.GOLD), false);
        for (String n : names) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + n).withStyle(ChatFormatting.AQUA), false);
        }
        return names.size();
    }

    private static int exportAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Path exchangeDir = RopePresetLibrary.exchangeDirectory(server);
        int count = PresetServerManager.exportPresets(server);
        if (count < 0) {
            ctx.getSource().sendFailure(Component.literal("Could not export presets to " + exchangeDir));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Exported " + count + " preset(s) to " + exchangeDir)
                .withStyle(ChatFormatting.GREEN), true);
        return Math.max(1, count);
    }

    private static int importAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Path exchangeDir = RopePresetLibrary.exchangeDirectory(server);
        int count = PresetServerManager.importPresets(server);
        if (count < 0) {
            ctx.getSource().sendFailure(Component.literal("Could not import presets from " + exchangeDir));
            return 0;
        }
        if (count == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("No preset JSON files found in " + exchangeDir)
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Imported " + count + " preset(s) from " + exchangeDir)
                .withStyle(ChatFormatting.GREEN), true);
        return count;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!canViewPreset(ctx.getSource(), name)) {
            ctx.getSource().sendFailure(Component.literal("Missing permission to view preset '" + name + "'."));
            return 0;
        }
        Optional<RopePreset> opt = RopePresetLibrary.forServer(ctx.getSource().getServer()).load(name);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No preset named '" + name + "'"));
            return 0;
        }
        RopePreset preset = opt.get();
        ctx.getSource()
                .sendSuccess(() -> Component.literal("Preset '" + name + "' (" + preset.overrides().size() + " keys):")
                        .withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<String, String> e : preset.overrides().entrySet()) {
            ctx.getSource()
                    .sendSuccess(() -> Component.literal("  " + e.getKey() + " = ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal(e.getValue()).withStyle(ChatFormatting.LIGHT_PURPLE)),
                            false);
        }
        return 1;
    }

    private static int saveFromKeys(CommandContext<CommandSourceStack> ctx) {
        if (!SuperLeadPermissions.sourceCanManage(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("Missing Super Lead preset management permission."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (!RopePresetLibrary.isValidName(name)) {
            ctx.getSource().sendFailure(Component.literal("Invalid name (use [A-Za-z0-9_-], up to 32 chars)"));
            return 0;
        }
        String pairs = StringArgumentType.getString(ctx, "pairs").trim();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String part : pairs.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
                continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                ctx.getSource().sendFailure(Component.literal("Bad pair (need key=value): " + trimmed));
                return 0;
            }
            String k = trimmed.substring(0, eq).trim();
            String v = trimmed.substring(eq + 1).trim();
            if (ClientTuning.byId(k) == null) {
                ctx.getSource().sendFailure(Component.literal("Unknown key: " + k));
                return 0;
            }
            overrides.put(k, v);
        }
        RopePreset preset = new RopePreset(name, overrides);
        if (!RopePresetLibrary.forServer(ctx.getSource().getServer()).save(preset)) {
            ctx.getSource().sendFailure(Component.literal("Could not save preset (see log)."));
            return 0;
        }
        PresetServerManager.refreshPresetUsage(ctx.getSource().getServer(), name);
        ctx.getSource()
                .sendSuccess(() -> Component.literal("Saved preset '" + name + "' with " + overrides.size() + " keys.")
                        .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int giveBinder(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return giveBinder(ctx, ctx.getSource().getPlayerOrException());
    }

    private static int giveBinderToTarget(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return giveBinder(ctx, EntityArgument.getPlayer(ctx, "target"));
    }

    private static int giveBinder(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        String name = StringArgumentType.getString(ctx, "name");
        Optional<RopePreset> preset = RopePresetLibrary.forServer(ctx.getSource().getServer()).load(name);
        if (preset.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No preset named '" + name + "'."));
            return 0;
        }

        ItemStack stack = new ItemStack(SuperLeadItems.PRESET_BINDER.asItem());
        stack.set(SuperLeadDataComponents.PRESET_BINDER.get(),
                new PresetBinderData(name, preset.get().owner()));
        boolean inserted = target.getInventory().add(stack);
        if (!inserted) {
            target.drop(stack, false);
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Gave preset binder for '" + name + "' to "
                + target.getName().getString() + (inserted ? "." : " (inventory full, dropped at player)."))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        PresetServerManager.PresetDeleteResult result = ctx.getSource().getEntity() instanceof ServerPlayer player
            ? PresetServerManager.deletePresetDetailed(ctx.getSource().getServer(), player, name)
            : SuperLeadPermissions.sourceCanAdmin(ctx.getSource())
                ? PresetServerManager.deletePresetDetailed(ctx.getSource().getServer(), name)
                : PresetServerManager.PresetDeleteResult.fail(
                        PresetServerManager.DeleteFailure.NO_PERMISSION, "");
        if (!result.deleted()) {
            ctx.getSource().sendFailure(Component.literal(result.message(name)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(result.message(name))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int editSet(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!canEditPreset(ctx.getSource(), name)) {
            ctx.getSource().sendFailure(Component.literal("Missing permission to edit preset '" + name + "'."));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value").trim();
        if (ClientTuning.byId(key) == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + key));
            return 0;
        }
        boolean ok = PresetServerManager.editKey(ctx.getSource().getServer(),
                new PresetEditKey(name, key, value, false));
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("Edit failed (presets disabled or invalid name)."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Set " + name + "." + key + " = " + value)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int editReset(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!canEditPreset(ctx.getSource(), name)) {
            ctx.getSource().sendFailure(Component.literal("Missing permission to edit preset '" + name + "'."));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key");
        boolean ok = PresetServerManager.editKey(ctx.getSource().getServer(),
                new PresetEditKey(name, key, "", true));
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("Edit failed."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + name + "." + key)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static boolean canViewPreset(CommandSourceStack source, String name) {
        if (SuperLeadPermissions.sourceCanManage(source)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer player
                && PresetServerManager.canEditPreset(player, name);
    }

    private static boolean canEditPreset(CommandSourceStack source, String name) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return PresetServerManager.canEditPreset(player, name);
        }
        return SuperLeadPermissions.sourceCanManage(source);
    }
}
