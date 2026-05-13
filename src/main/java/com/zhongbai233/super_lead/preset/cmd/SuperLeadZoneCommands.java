package com.zhongbai233.super_lead.preset.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.ParrotRopePerchController;
import com.zhongbai233.super_lead.preset.PhysicsZone;
import com.zhongbai233.super_lead.preset.PhysicsZoneSavedData;
import com.zhongbai233.super_lead.preset.PhysicsZoneSelectionManager;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadZoneCommands {
    private SuperLeadZoneCommands() {
    }

    private static final Permission.HasCommandLevel OP = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PRESET = (ctx,
            builder) -> SharedSuggestionProvider.suggest(
                    RopePresetLibrary.forServer(ctx.getSource().getServer()).list(), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ZONE = (ctx, builder) -> {
        ServerLevel level = ctx.getSource().getLevel();
        return SharedSuggestionProvider.suggest(
                PhysicsZoneSavedData.get(level).zones().stream().map(PhysicsZone::name), builder);
    };

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .requires(src -> src.permissions().hasPermission(OP));

        LiteralArgumentBuilder<CommandSourceStack> zoneNode = Commands.literal("zone")
                .then(Commands.literal("list").executes(SuperLeadZoneCommands::list))
                .then(Commands.literal("select").executes(SuperLeadZoneCommands::select))
                .then(Commands.literal("cancel").executes(SuperLeadZoneCommands::cancelSelect))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(SUGGEST_PRESET)
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(SuperLeadZoneCommands::add))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_ZONE)
                                .executes(SuperLeadZoneCommands::remove)))
                .then(Commands.literal("adventure")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_ZONE)
                                .then(Commands.argument("allow", BoolArgumentType.bool())
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(0))
                                                .executes(SuperLeadZoneCommands::setAdventureRules)))))
                .then(Commands.literal("clearAdventure")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_ZONE)
                                .executes(SuperLeadZoneCommands::clearAdventureRopes)));

        root.then(zoneNode);
        root.then(Commands.literal("parrotperch").executes(SuperLeadZoneCommands::parrotPerch));
        dispatcher.register(root);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        List<PhysicsZone> zones = PresetServerManager.listZones(level);
        if (zones.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No physics zones in this dimension.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Physics zones (" + zones.size() + "):")
                .withStyle(ChatFormatting.GOLD), false);
        for (PhysicsZone zone : zones) {
            AABB area = zone.area();
            int adventureCount = PresetServerManager.adventureRopeCount(level, zone);
            String adventure = zone.adventurePlacement()
                    ? String.format(" adventure=%d/%s", adventureCount,
                            zone.adventureLimit() <= 0 ? "unlimited" : Integer.toString(zone.adventureLimit()))
                    : " adventure=off";
            String line = String.format("  %s -> %s  [%.0f,%.0f,%.0f .. %.0f,%.0f,%.0f]%s",
                    zone.name(), zone.presetName(), area.minX, area.minY, area.minZ,
                    area.maxX, area.maxY, area.maxZ, adventure);
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        }
        return zones.size();
    }

    private static int add(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String preset = StringArgumentType.getString(ctx, "preset");
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgument.getBlockPos(ctx, "to");
        AABB area = new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0,
                Math.max(a.getY(), b.getY()) + 1.0,
                Math.max(a.getZ(), b.getZ()) + 1.0);
        ServerLevel level = ctx.getSource().getLevel();
        boolean ok = PresetServerManager.addZone(level, name, preset, area);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal(
                    "Could not add zone (presets disabled, invalid name, or preset '" + preset + "' missing)."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Added zone '" + name + "' bound to preset '" + preset + "'.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int select(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PhysicsZoneSelectionManager.start(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int cancelSelect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PhysicsZoneSelectionManager.cancel(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        boolean ok = PresetServerManager.removeZone(level, name);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("No zone named '" + name + "' here."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed zone '" + name + "'.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setAdventureRules(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean allow = BoolArgumentType.getBool(ctx, "allow");
        int limit = IntegerArgumentType.getInteger(ctx, "limit");
        ServerLevel level = ctx.getSource().getLevel();
        boolean ok = PresetServerManager.setZoneAdventureRules(level, name, allow, limit);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("No zone named '" + name + "' here."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Zone '" + name + "' adventure placement: " + (allow ? "enabled" : "disabled")
                        + ", limit=" + (limit <= 0 ? "unlimited" : Integer.toString(limit)) + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int clearAdventureRopes(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        int removed = PresetServerManager.clearAdventureRopes(level, name);
        if (removed < 0) {
            ctx.getSource().sendFailure(Component.literal("No zone named '" + name + "' here."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Removed " + removed + " adventure rope(s) from zone '" + name + "'.")
                .withStyle(ChatFormatting.GREEN), true);
        return removed;
    }

    private static int parrotPerch(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        boolean ok = ParrotRopePerchController.forcePerchNearby(level,
                ctx.getSource().getPosition(), 10.0D);
        if (ok) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Forced a nearby parrot to perch on a sync-zone rope.")
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No suitable parrot + rope pair found nearby."));
        return 0;
    }
}
