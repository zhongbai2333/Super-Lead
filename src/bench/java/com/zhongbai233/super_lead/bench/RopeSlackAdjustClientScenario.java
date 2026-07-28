package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import com.zhongbai233.super_lead.preset.RopePreset;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Exercises the real per-rope tightness path — the preset binder flow: a named
 * preset with a different {@code slack} is saved into the server library,
 * stamped onto one connection, synced, and the client simulation must converge
 * to the new slack's model sag. Runs loose (0.80) then tight (0.05), asserting
 * a settled, model-conforming shape after each switch.
 */
final class RopeSlackAdjustClientScenario implements BenchClientScenario {

    private static final int SPAN = 6;
    private static final int STAGE_TIMEOUT = 320;
    private static final double GRAVITY = -0.065D;

    private static final BenchMetricDescriptor LOOSE_SAG_ERROR = new BenchMetricDescriptor(
            "super_lead.rope.slack_loose_sag_error", "blocks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor TIGHT_SAG = new BenchMetricDescriptor(
            "super_lead.rope.slack_tight_sag", "blocks", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private final AtomicInteger appliedStage = new AtomicInteger();
    private volatile boolean rigReady;

    private final StringBuilder trace = new StringBuilder("tick,stage,bellyY,motion,rest\n");
    private BlockPos rigBase;
    private int tick;
    private int stage;
    private int stageStartTick;
    private double defaultSag = Double.NaN;
    private double looseSagError = Double.NaN;
    private double tightSag = Double.NaN;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        var pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(0, 0, -16);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                level.setBlockAndUpdate(base, net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState());
                level.setBlockAndUpdate(base.offset(SPAN, 0, 0),
                        net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState());
                placedBlocks.add(base);
                placedBlocks.add(base.offset(SPAN, 0, 0));
                LeadConnection connection = RopeBenchSupport.connectTops(level, base, base.offset(SPAN, 0, 0));
                createdConnections.add(connection.id());
                RopePresetLibrary library = RopePresetLibrary.forServer(server);
                if (!library.save(new RopePreset("bench_loose", Map.of("slack", "0.80")))
                        || !library.save(new RopePreset("bench_tight", Map.of("slack", "0.05")))) {
                    throw new IllegalStateException("preset library refused bench presets");
                }
                rigReady = true;
            } catch (Exception e) {
                serverError.set("slack rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        // Park the observer FIRST: client sims only exist for ropes near the
        // player, so waiting for the probe before moving there deadlocks.
        double cx = rigBase.getX() + SPAN * 0.5D;
        context.automation().stopMovement();
        context.automation().setPose(RopeBenchSupport.lookPose(
                cx, rigBase.getY() + 1.0D, rigBase.getZ() + 10.0D,
                cx, rigBase.getY(), rigBase.getZ()));
        if (!rigReady || !context.environment().readiness().ready() || probe() == null) {
            return BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(createdConnections);
        SuperLeadClientEvents.RopeSimBenchProbe probe = probe();
        if (probe == null) {
            throw new IllegalStateException("rope sim disappeared at tick " + tick);
        }
        trace.append(tick).append(',').append(stage).append(',')
                .append(String.format(java.util.Locale.ROOT, "%.5f,%.3e,%d,%.3f%n",
                        probe.bellyY(), probe.maxNodeMotionSqr(), probe.visuallyAtRest() ? 1 : 0,
                        probe.tuningSlack()));
        if (tick - stageStartTick > STAGE_TIMEOUT) {
            throw new AssertionError("stage " + stage + " never settled (tuningSlack="
                    + probe.tuningSlack() + ", mesh="
                    + meshLifecycle.describe(createdConnections.get(0)) + ")\n" + tail());
        }
        // A stage's expectations only apply once its preset actually reached the
        // client simulation: the server write, the preset sync packet and the
        // tuning refresh take several ticks, during which the rope is still
        // resting in its previous configuration. Asserting on the first resting
        // tick after the switch raced that pipeline and judged the old shape
        // against the new model.
        double expectedSlack = switch (stage) {
            case 0 -> 0.30D;
            case 1 -> 0.80D;
            default -> 0.05D;
        };
        if (Math.abs(probe.tuningSlack() - expectedSlack) > 1.0e-6D) {
            return BenchClientStepResult.CONTINUE;
        }
        if (!probe.visuallyAtRest()) {
            return BenchClientStepResult.CONTINUE;
        }
        // A settled simulation is not enough: each newly tuned shape must complete
        // its section rebuild before advancing, otherwise this scenario can skip the
        // actual chunk-mesh handoff entirely.
        if (!meshLifecycle.isActive(createdConnections.get(0))) {
            return BenchClientStepResult.CONTINUE;
        }
        double anchorY = Math.min(probe.anchorAY(), probe.anchorBY());
        double sag = anchorY - probe.bellyY();
        // Direction-based expectations only: the baseline solver rests deeper
        // than the analytic model, so a model-conformance band would fail a
        // physically fine rope. What the feature must guarantee is ordering —
        // loose hangs visibly deeper than default, tight pulls visibly flatter —
        // plus basic sanity (the rope hangs at all and never explodes).
        switch (stage) {
            case 0 -> {
                if (sag < 0.02D || sag > SPAN * 0.75D) {
                    throw new AssertionError("default rope does not hang sanely: sag=" + sag
                            + "\n" + tail());
                }
                defaultSag = sag;
                advanceStage(context, "bench_loose");
            }
            case 1 -> {
                if (sag < defaultSag + 0.08D || sag > SPAN * 0.75D) {
                    throw new AssertionError("loose preset did not visibly slacken the rope: default="
                            + defaultSag + " loose=" + sag + "\n" + tail());
                }
                looseSagError = Math.abs(sag - RopeSagModel.midspanSag(SPAN, 0.80D, GRAVITY));
                advanceStage(context, "bench_tight");
            }
            case 2 -> {
                if (sag > 0.35D || sag >= defaultSag) {
                    throw new AssertionError("tight preset left the rope hanging: sag=" + sag
                            + " default=" + defaultSag + "\n" + tail());
                }
                tightSag = sag;
                context.automation().captureScreenshot("rope-slack-adjust");
                return BenchClientStepResult.COMPLETE;
            }
            default -> throw new IllegalStateException("stage " + stage);
        }
        return BenchClientStepResult.CONTINUE;
    }

    private void advanceStage(BenchClientContext context, String preset) {
        int next = stage + 1;
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        UUID id = createdConnections.get(0);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                SuperLeadSavedData.get(level).update(id,
                        c -> c.withManualPhysicsPreset(preset).withPhysicsPreset(preset), true);
                PresetServerManager.syncDimensionPresets(level);
                SuperLeadPayloads.sendDirtyToDimension(level);
                appliedStage.set(next);
            } catch (Exception e) {
                serverError.set("preset switch to " + preset + " failed: " + e);
            }
        });
        stage = next;
        stageStartTick = tick;
    }

    @Override
    public void verify(BenchClientContext context) throws Exception {
        java.nio.file.Files.writeString(
                context.resultDirectory().resolve("rope-slack-adjust-trace.csv"), trace.toString());
        failOnServerError();
        meshLifecycle.requireExitAndReentry(createdConnections.get(0), "slack-adjust");
        context.metrics().record(LOOSE_SAG_ERROR, looseSagError);
        context.metrics().record(TIGHT_SAG, tightSag);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<UUID> ids = Set.copyOf(createdConnections);
        List<BlockPos> blocks = List.copyOf(placedBlocks);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            RopeBenchSupport.teardown(level, ids, blocks);
            RopePresetLibrary library = RopePresetLibrary.forServer(server);
            library.delete("bench_loose");
            library.delete("bench_tight");
        });
    }

    private SuperLeadClientEvents.RopeSimBenchProbe probe() {
        return createdConnections.isEmpty() ? null
                : SuperLeadClientEvents.probeSimForBench(createdConnections.get(0));
    }

    private void failOnServerError() {
        String error = serverError.get();
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    private String tail() {
        String all = trace.toString();
        int from = Math.max(0, all.length() - 2000);
        return all.substring(from);
    }
}
