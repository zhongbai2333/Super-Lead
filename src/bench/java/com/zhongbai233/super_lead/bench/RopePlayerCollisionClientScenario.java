package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Walks the local player straight through a chest-height rope, twice, and
 * checks the whole interaction contract: the rope must visibly yield while the
 * body passes (feedback), must not fly off or deform without bound
 * (stability), and must return to its natural hang and fall back asleep after
 * the player leaves (recovery). Samples every tick, including the walk.
 */
final class RopePlayerCollisionClientScenario implements BenchClientScenario {

    private static final int SPAN = 6;
    private static final int PILLAR_HEIGHT = 2;
    private static final double WALK_SPEED = 0.15D;
    private static final int SETTLE_TICKS = 200;
    private static final int RECOVER_TICKS = 400;
    private static final double MIN_PEAK_DEFLECTION = 0.15D;
    /**
     * Downward belly travel while a body passes = the rope slackening. The
     * chain-length net bounds it near the inextensibility lens; the whole-rope
     * slack-out bug measured 1.10 down here, and the fixed solver measures 0.06.
     * An earlier |delta| assertion at 3.0 waved the bug through — which is how a
     * regression the naked eye caught survived a green bench run.
     */
    private static final double MAX_SLACK_DOWN = 0.30D;
    /**
     * Upward travel is the slip-over-the-body response (the rope deliberately
     * rides over entities instead of snagging); bounded only against explosions.
     */
    private static final double MAX_RIDE_UP = 2.2D;
    private static final double RECOVERY_SAG_BAND = 0.35D;

    private static final BenchMetricDescriptor PEAK_DEFLECTION = new BenchMetricDescriptor(
            "super_lead.rope.push_peak_deflection", "blocks", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RECOVER_TICKS_METRIC = new BenchMetricDescriptor(
            "super_lead.rope.push_recover_ticks", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor RESIDUAL_SAG_ERROR = new BenchMetricDescriptor(
            "super_lead.rope.push_residual_sag_error", "blocks", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean rigReady;

    private final StringBuilder trace = new StringBuilder("tick,phase,playerZ,bellyY,motion,rest\n");
    private BlockPos rigBase;
    private double restingBellyY = Double.NaN;
    private double restingSag = Double.NaN;
    private double anchorY = Double.NaN;
    private int tick;
    private int walkPass;
    private double walkZ;
    private double peakDeflection;
    private double walkMinDelta;
    private double walkMaxDelta;
    private long recoveredAtTick = -1;
    private long walkEndedTick = -1;
    private String phase = "settle";

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        BenchClientPose pose = context.automation().pose();
        rigBase = BlockPos.containing(pose.x() + 6.0D, pose.y(), pose.z() + 6.0D);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                BlockPos a = RopeBenchSupport.fencePillar(level, base, PILLAR_HEIGHT, placedBlocks);
                BlockPos b = RopeBenchSupport.fencePillar(level, base.offset(SPAN, 0, 0), PILLAR_HEIGHT, placedBlocks);
                LeadConnection connection = RopeBenchSupport.connectTops(level, a, b);
                createdConnections.add(connection.id());
                rigReady = true;
            } catch (Exception e) {
                serverError.set("collision rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        if (!rigReady || !context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (probe() == null) {
            return BenchClientStepResult.CONTINUE;
        }
        parkObserver(context);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        tick++;
        meshLifecycle.sample(createdConnections);
        SuperLeadClientEvents.RopeSimBenchProbe probe = probe();
        if (probe == null) {
            throw new IllegalStateException("rope sim disappeared mid-measurement at tick " + tick);
        }
        if (!Double.isFinite(probe.bellyY())) {
            throw new AssertionError("belly became non-finite at tick " + tick + "\n" + trace);
        }
        trace.append(tick).append(',').append(phase).append(',')
                .append(String.format(java.util.Locale.ROOT, "%.3f,%.5f,%.3e,%d%n",
                        walkZ, probe.bellyY(), probe.maxNodeMotionSqr(), probe.visuallyAtRest() ? 1 : 0));

        switch (phase) {
            case "settle" -> {
                parkObserver(context);
                if (probe.visuallyAtRest() && meshLifecycle.isActive(createdConnections.get(0))) {
                    restingBellyY = probe.bellyY();
                    anchorY = Math.min(probe.anchorAY(), probe.anchorBY());
                    restingSag = anchorY - restingBellyY;
                    beginWalk(context);
                } else if (tick > SETTLE_TICKS) {
                    throw new AssertionError("rope never settled before the walk\n" + trace);
                }
            }
            case "walk" -> {
                double delta = probe.bellyY() - restingBellyY;
                walkMinDelta = Math.min(walkMinDelta, delta);
                walkMaxDelta = Math.max(walkMaxDelta, delta);
                peakDeflection = Math.max(peakDeflection, Math.abs(delta));
                if (delta < -MAX_SLACK_DOWN || delta > MAX_RIDE_UP) {
                    writeTrace(context);
                    throw new AssertionError(String.format(java.util.Locale.ROOT,
                            "rope left the allowed deformation band under a body push: "
                                    + "delta=%.4f (walk range %.4f..%.4f; negative = slackening "
                                    + "down, positive = riding up over the body)%n%s",
                            delta, walkMinDelta, walkMaxDelta, tail()));
                }
                walkZ += WALK_SPEED;
                double cx = rigBase.getX() + SPAN * 0.5D;
                context.automation().movePose(new BenchClientPose(
                        cx, rigBase.getY(), rigBase.getZ() + walkZ, 0.0F, 10.0F));
                if (walkZ >= 4.0D) {
                    walkPass++;
                    if (walkPass >= 2) {
                        walkEndedTick = tick;
                        phase = "recover";
                        parkObserver(context);
                    } else {
                        walkZ = -4.0D;
                        context.automation().setPose(new BenchClientPose(
                                cx, rigBase.getY(), rigBase.getZ() - 4.0D, 0.0F, 10.0F));
                    }
                }
            }
            case "recover" -> {
                parkObserver(context);
                if (probe.visuallyAtRest() && recoveredAtTick < 0) {
                    recoveredAtTick = tick;
                }
                if (tick - walkEndedTick > RECOVER_TICKS) {
                    context.automation().captureScreenshot("rope-player-collision");
                    return BenchClientStepResult.COMPLETE;
                }
            }
            default -> throw new IllegalStateException(phase);
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) throws Exception {
        writeTrace(context);
        failOnServerError();
        SuperLeadClientEvents.RopeSimBenchProbe probe = probe();
        if (probe == null) {
            throw new AssertionError("rope sim missing at verify");
        }
        if (peakDeflection < MIN_PEAK_DEFLECTION) {
            throw new AssertionError("walking through the rope produced no visible feedback: peak="
                    + peakDeflection + "\n" + tail());
        }
        if (recoveredAtTick < 0) {
            throw new AssertionError("rope never fell back asleep after the pushes\n" + tail());
        }
        double residualSagError = Math.abs((anchorY - probe.bellyY()) - restingSag);
        if (residualSagError > RECOVERY_SAG_BAND) {
            throw new AssertionError("rope did not return to its natural hang: residual error="
                    + residualSagError + " (restingSag=" + restingSag + ")\n" + tail());
        }
        meshLifecycle.requireExitAndReentry(createdConnections.get(0), "player-collision");
        context.metrics().record(PEAK_DEFLECTION, peakDeflection);
        context.metrics().record(RECOVER_TICKS_METRIC, recoveredAtTick - walkEndedTick);
        context.metrics().record(RESIDUAL_SAG_ERROR, residualSagError);
    }

    @Override
    public void teardown(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<UUID> ids = Set.copyOf(createdConnections);
        List<BlockPos> blocks = List.copyOf(placedBlocks);
        server.execute(() -> RopeBenchSupport.teardown(server.overworld(), ids, blocks));
    }

    private void beginWalk(BenchClientContext context) {
        phase = "walk";
        walkZ = -4.0D;
        double cx = rigBase.getX() + SPAN * 0.5D;
        context.automation().setPose(new BenchClientPose(
                cx, rigBase.getY(), rigBase.getZ() - 4.0D, 0.0F, 10.0F));
    }

    private void parkObserver(BenchClientContext context) {
        context.automation().stopMovement();
        if (!"walk".equals(phase)) {
            double cx = rigBase.getX() + SPAN * 0.5D;
            context.automation().setPose(RopeBenchSupport.lookPose(
                    cx, rigBase.getY() + 1.0D, rigBase.getZ() - 6.0D,
                    cx, rigBase.getY() + PILLAR_HEIGHT, rigBase.getZ()));
        }
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

    private void writeTrace(BenchClientContext context) {
        try {
            java.nio.file.Files.writeString(
                    context.resultDirectory().resolve("rope-player-collision-trace.csv"), trace.toString());
        } catch (Exception ignored) {
            // Trace is diagnostics; never mask the real failure with an IO error.
        }
    }

    private String tail() {
        String all = trace.toString();
        int from = Math.max(0, all.length() - 2000);
        return all.substring(from);
    }
}
