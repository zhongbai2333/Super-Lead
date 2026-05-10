package com.zhongbai233.super_lead.lead.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.RopeContactTracker;
import com.zhongbai233.super_lead.lead.ServerRopeVerlet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-side performance harness for {@link ServerRopeVerlet}. Adds:
 * <ul>
 *   <li>{@code /superlead bench verlet start <count> [length]} — spawns {@code count} synthetic
 *       Verlet sims and steps them every tick on the server thread.</li>
 *   <li>{@code /superlead bench verlet stop} — clears the harness.</li>
 *   <li>{@code /superlead bench verlet status} — reports last-tick wall-clock and a 200-tick
 *       moving average so the operator can see whether TPS is at risk.</li>
 *   <li>{@code /superlead bench tracker status} — reports the live tracker's per-tick cost.</li>
 * </ul>
 *
 * <p>The harness deliberately bypasses {@link RopeContactTracker} so the measurement covers
 * pure solver cost (Verlet integrate + 6 distance-iteration passes); the live tracker also
 * does player AABB tests, packet broadcast, etc., so its number is necessarily higher.
 */
@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadBenchCommand {
    private static final int    MAX_COUNT      = 50_000;
    private static final double DEFAULT_LENGTH = 8.0D;
    private static final double SPACING        = 1.5D;
    private static final int    SAMPLE_WINDOW  = 200;

    private static final List<HarnessRope> ROPES = new ArrayList<>();
    private static final long[] SAMPLES = new long[SAMPLE_WINDOW];
    private static int sampleIdx;
    private static int sampleCount;
    private static long peakNanos;

    private SuperLeadBenchCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        Permission.HasCommandLevel opLevel = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead")
                .requires(src -> src.permissions().hasPermission(opLevel))
                .then(Commands.literal("bench")
                        .then(Commands.literal("verlet")
                                .then(Commands.literal("start")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                                                .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "count"), DEFAULT_LENGTH))
                                                .then(Commands.argument("length", DoubleArgumentType.doubleArg(1.0D, 32.0D))
                                                        .executes(ctx -> start(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                DoubleArgumentType.getDouble(ctx, "length"))))))
                                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource()))))
                        .then(Commands.literal("tracker")
                                .then(Commands.literal("status").executes(ctx -> trackerStatus(ctx.getSource())))));
        dispatcher.register(root);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (ROPES.isEmpty()) return;
        // Run only on one dimension to keep results reproducible — the harness is bound to
        // whichever level was active when start was called, identified by reference equality
        // of its tick. Cheap proxy: just step on every server tick; cost is per rope, not per
        // dimension.
        long t0 = System.nanoTime();
        for (HarnessRope r : ROPES) {
            r.sim.step(r.a, r.b);
        }
        long elapsed = System.nanoTime() - t0;
        SAMPLES[sampleIdx] = elapsed;
        sampleIdx = (sampleIdx + 1) % SAMPLE_WINDOW;
        if (sampleCount < SAMPLE_WINDOW) sampleCount++;
        if (elapsed > peakNanos) peakNanos = elapsed;
    }

    private static int start(CommandContext<CommandSourceStack> ctx, int count, double length) {
        CommandSourceStack source = ctx.getSource();
        Vec3 origin = source.getPosition();
        ROPES.clear();
        sampleIdx = 0;
        sampleCount = 0;
        peakNanos = 0L;
        // Lay ropes out on a horizontal grid so they don't overlap (purely for clarity if you
        // ever want to render them; the solver itself doesn't care about position).
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        for (int i = 0; i < count; i++) {
            int row = i / cols;
            int col = i % cols;
            double cx = origin.x + col * SPACING;
            double cz = origin.z + row * SPACING;
            Vec3 a = new Vec3(cx,                origin.y, cz);
            Vec3 b = new Vec3(cx + length,       origin.y, cz);
            ServerRopeVerlet sim = new ServerRopeVerlet();
            sim.reset(a, b);
            ROPES.add(new HarnessRope(sim, a, b));
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[bench] spawned %,d Verlet ropes (length=%.1f). Run 'superlead bench verlet status' to see ms/tick.",
                count, length)).withStyle(ChatFormatting.GREEN), false);
        return count;
    }

    private static int stop(CommandSourceStack source) {
        int n = ROPES.size();
        ROPES.clear();
        source.sendSuccess(() -> Component.literal("[bench] cleared " + n + " harness ropes.")
                .withStyle(ChatFormatting.GRAY), false);
        return n;
    }

    private static int status(CommandSourceStack source) {
        if (ROPES.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[bench] no harness running."), false);
            return 0;
        }
        long sum = 0L;
        for (int i = 0; i < sampleCount; i++) sum += SAMPLES[i];
        double avgMs  = sampleCount == 0 ? 0.0D : (sum / (double) sampleCount) / 1_000_000.0D;
        double peakMs = peakNanos / 1_000_000.0D;
        double last   = sampleCount == 0 ? 0.0D : SAMPLES[(sampleIdx + SAMPLE_WINDOW - 1) % SAMPLE_WINDOW] / 1_000_000.0D;
        double tpsBudget = 50.0D;
        double pctOfTick = avgMs / tpsBudget * 100.0D;
        source.sendSuccess(() -> Component.literal(String.format(
                "[bench] ropes=%,d  last=%.3f ms  avg(%d)=%.3f ms  peak=%.3f ms  (avg = %.2f%% of one 50ms tick)",
                ROPES.size(), last, sampleCount, avgMs, peakMs, pctOfTick))
                .withStyle(pctOfTick > 80.0D ? ChatFormatting.RED
                          : pctOfTick > 40.0D ? ChatFormatting.YELLOW
                                              : ChatFormatting.GREEN), false);
        return ROPES.size();
    }

    private static int trackerStatus(CommandSourceStack source) {
        double ms = RopeContactTracker.lastTickNanos() / 1_000_000.0D;
        int active = RopeContactTracker.lastActiveSims();
        int stepped = RopeContactTracker.lastSteppedSims();
        int contacts = RopeContactTracker.lastContacts();
        source.sendSuccess(() -> Component.literal(String.format(
                "[tracker] live last-tick=%.3f ms  active sims=%d  stepped=%d  contacts=%d",
                ms, active, stepped, contacts)).withStyle(ChatFormatting.AQUA), false);
        return active;
    }

    private record HarnessRope(ServerRopeVerlet sim, Vec3 a, Vec3 b) {}
}
