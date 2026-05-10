package com.zhongbai233.super_lead.lead.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.RopeContactTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** OP-only debugging commands for visualising server-authoritative rope state. */
@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadDebugCommand {
    private static final double DEFAULT_RANGE = 48.0D;
    private static final int DEFAULT_INTERVAL = 2;

    private SuperLeadDebugCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        Permission.HasCommandLevel opLevel = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .requires(src -> src.permissions().hasPermission(opLevel))
                .then(Commands.literal("debug")
                        .then(Commands.literal("server_rope")
                                .then(Commands.literal("on")
                                        .executes(ctx -> enable(ctx, DEFAULT_RANGE, DEFAULT_INTERVAL))
                                        .then(Commands.argument("range", DoubleArgumentType.doubleArg(2.0D, 256.0D))
                                                .executes(ctx -> enable(ctx,
                                                        DoubleArgumentType.getDouble(ctx, "range"),
                                                        DEFAULT_INTERVAL))
                                                .then(Commands.argument("intervalTicks", IntegerArgumentType.integer(1, 40))
                                                        .executes(ctx -> enable(ctx,
                                                                DoubleArgumentType.getDouble(ctx, "range"),
                                                                IntegerArgumentType.getInteger(ctx, "intervalTicks"))))))
                                .then(Commands.literal("off").executes(SuperLeadDebugCommand::disable))
                                .then(Commands.literal("status").executes(SuperLeadDebugCommand::status))));
        dispatcher.register(root);
    }

    private static int enable(CommandContext<CommandSourceStack> ctx, double range, int interval)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RopeContactTracker.enableServerRopeDebug(player, range, interval);
        RopeContactTracker.DebugInfo info = RopeContactTracker.serverRopeDebugInfo(player);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                        "[superlead debug] server_rope particles ON: range=%.1f, interval=%d tick(s), active server ropes=%d. "
                                + "Green/blue=endpoints, yellow=nodes, magenta=server rope curve.",
                        info.range(), info.intervalTicks(), info.activeServerRopes()))
                .withStyle(ChatFormatting.GREEN), false);
        if (info.activeServerRopes() == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[superlead debug] No active server ropes yet. Stand near a synced physics-zone rope; only RopeContactTracker's active server sims are drawn.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return info.activeServerRopes();
    }

    private static int disable(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean removed = RopeContactTracker.disableServerRopeDebug(player);
        ctx.getSource().sendSuccess(() -> Component.literal(removed
                        ? "[superlead debug] server_rope particles OFF."
                        : "[superlead debug] server_rope particles were not enabled.")
                .withStyle(removed ? ChatFormatting.GRAY : ChatFormatting.YELLOW), false);
        return removed ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RopeContactTracker.DebugInfo info = RopeContactTracker.serverRopeDebugInfo(player);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                        "[superlead debug] server_rope=%s  range=%.1f  interval=%d  active server ropes=%d",
                        info.enabled() ? "ON" : "OFF",
                        info.range(), info.intervalTicks(), info.activeServerRopes()))
                .withStyle(info.enabled() ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        return info.activeServerRopes();
    }
}