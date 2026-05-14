package com.zhongbai233.super_lead.lead;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zhongbai233.super_lead.Super_lead;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Server-side rope-curve particle visualizer for debugging zipline physics. */
@EventBusSubscriber(modid = Super_lead.MODID)
public final class ServerRopeDebugCommand {
    private static final Permission.HasCommandLevel OP = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final double DEFAULT_RADIUS = 24.0D;
    private static final int DEFAULT_SECONDS = 10;
    private static final int MAX_SECONDS = 120;
    private static final double LINE_STEP = 0.28D;
    private static final DustParticleOptions LINE_DUST = new DustParticleOptions(0x22CCFF, 1.0F);
    private static final DustParticleOptions NODE_DUST = new DustParticleOptions(0xFFE066, 1.1F);
    private static final DustParticleOptions START_DUST = new DustParticleOptions(0x33FF66, 1.25F);
    private static final DustParticleOptions END_DUST = new DustParticleOptions(0xFF5555, 1.25F);

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ServerRopeDebugCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .requires(src -> src.permissions().hasPermission(OP))
                .then(Commands.literal("serverrope")
                        .then(Commands.literal("show")
                                .executes(ctx -> show(ctx, DEFAULT_RADIUS, DEFAULT_SECONDS))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 128.0D))
                                        .executes(ctx -> show(ctx,
                                                DoubleArgumentType.getDouble(ctx, "radius"), DEFAULT_SECONDS))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                                                .executes(ctx -> show(ctx,
                                                        DoubleArgumentType.getDouble(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))
                        .then(Commands.literal("once")
                                .executes(ctx -> show(ctx, DEFAULT_RADIUS, 1))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 128.0D))
                                        .executes(ctx -> show(ctx,
                                                DoubleArgumentType.getDouble(ctx, "radius"), 1))))
                        .then(Commands.literal("clear").executes(ServerRopeDebugCommand::clear)));
        dispatcher.register(root);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || SESSIONS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            if (!session.dimension().equals(level.dimension())) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive() || player.level() != level) {
                it.remove();
                continue;
            }
            Optional<LeadConnection> connection = SuperLeadNetwork.findConnectionById(level, session.connectionId());
            if (connection.isEmpty()) {
                it.remove();
                continue;
            }
            if ((level.getGameTime() & 1L) == 0L) {
                draw(level, connection.get());
            }
            session.remainingTicks--;
            if (session.remainingTicks <= 0) {
                it.remove();
            }
        }
    }

    private static int show(CommandContext<CommandSourceStack> ctx, double radius, int seconds)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        Vec3 origin = player.getEyePosition();
        Optional<LeadConnection> nearest = nearestConnection(level, origin, radius);
        if (nearest.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No Super Lead rope within " + format(radius) + " blocks."));
            return 0;
        }

        LeadConnection connection = nearest.get();
        int ticks = Math.max(1, Math.min(MAX_SECONDS, seconds)) * 20;
        SESSIONS.put(player.getUUID(), new Session(level.dimension(), connection.id(), ticks));
        draw(level, connection);

        ServerRopeCurve.Shape shape = shape(level, connection);
        ctx.getSource().sendSuccess(() -> Component.literal("Showing server rope ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(shortId(connection.id())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" preset=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(connection.physicsPreset().isBlank() ? "<none>" : connection.physicsPreset())
                        .withStyle(connection.physicsPreset().isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN))
                .append(Component.literal(" nodes=" + shape.x().length
                        + " length=" + format(shape.length())
                        + " target=" + format(shape.targetLength())
                        + " for " + seconds + "s").withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean removed = SESSIONS.remove(player.getUUID()) != null;
        ctx.getSource().sendSuccess(() -> Component.literal(
                removed ? "Cleared server rope display." : "No active server rope display."), false);
        return removed ? 1 : 0;
    }

    private static Optional<LeadConnection> nearestConnection(ServerLevel level, Vec3 origin, double radius) {
        double maxSqr = radius * radius;
        LeadConnection best = null;
        double bestSqr = maxSqr;
        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        for (LeadConnection connection : connections) {
            ServerRopeCurve.Shape shape = shape(level, connection, connections);
            double[] out = new double[4];
            double d = ServerRopeCurve.distancePointToCurveSqr(shape, origin, out);
            if (d < bestSqr) {
                bestSqr = d;
                best = connection;
            }
        }
        return Optional.ofNullable(best);
    }

    private static ServerRopeCurve.Shape shape(ServerLevel level, LeadConnection connection) {
        return shape(level, connection, SuperLeadNetwork.connections(level));
    }

    private static ServerRopeCurve.Shape shape(ServerLevel level, LeadConnection connection,
            List<LeadConnection> connections) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        return ServerRopeCurve.from(level, connection, endpoints.from(), endpoints.to());
    }

    private static void draw(ServerLevel level, LeadConnection connection) {
        ServerRopeCurve.Shape shape = shape(level, connection);
        double[] x = shape.x();
        double[] y = shape.y();
        double[] z = shape.z();
        for (int i = 0; i < x.length; i++) {
            DustParticleOptions dust = i == 0 ? START_DUST : (i == x.length - 1 ? END_DUST : NODE_DUST);
            level.sendParticles(dust, x[i], y[i], z[i], 1, 0.025D, 0.025D, 0.025D, 0.0D);
        }
        for (int i = 0; i < x.length - 1; i++) {
            Vec3 a = new Vec3(x[i], y[i], z[i]);
            Vec3 b = new Vec3(x[i + 1], y[i + 1], z[i + 1]);
            double len = a.distanceTo(b);
            int samples = Math.max(1, (int) Math.ceil(len / LINE_STEP));
            for (int s = 1; s < samples; s++) {
                double t = s / (double) samples;
                Vec3 p = a.lerp(b, t);
                level.sendParticles(LINE_DUST, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(0, 8);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static final class Session {
        private final ResourceKey<Level> dimension;
        private final UUID connectionId;
        private int remainingTicks;

        private Session(ResourceKey<Level> dimension, UUID connectionId, int remainingTicks) {
            this.dimension = dimension;
            this.connectionId = connectionId;
            this.remainingTicks = remainingTicks;
        }

        private ResourceKey<Level> dimension() {
            return dimension;
        }

        private UUID connectionId() {
            return connectionId;
        }
    }
}