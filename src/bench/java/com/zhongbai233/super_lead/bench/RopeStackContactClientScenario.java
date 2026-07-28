package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * In-game regression bench for rope-vs-rope contact.
 *
 * <p>
 * Two equal spans cross at their bellies. A healthy contact solve keeps them
 * separated by at least the rope diameter and lets the pair come to rest; the
 * two historical failure modes are a stack that never stops jittering
 * (asymmetric/over-strong contact injecting energy) and ropes that sleep
 * inside each other (contact too weak to see — "collisions are gone").
 */
final class RopeStackContactClientScenario implements BenchClientScenario {

    private static final int SPAN = 6;
    private static final int MEASURE_TICKS = 500;
    private static final int TAIL_TICKS = 100;
    private static final int MAX_TICKS_TO_REST = 450;
    private static final double TAIL_AMPLITUDE_LIMIT = 0.01D;
    private static final double MIN_CROSSING_SEPARATION = 0.05D;
    // Belly-to-belly distance is only a proxy for the closest segment-pair distance;
    // the fixed seed settles at about 0.094 blocks while the actual segments remain
    // in contact, so keep a small proxy margin above that measured baseline.
    private static final double MAX_CONTACT_SEPARATION = 0.11D;
    private static final int MIN_TAIL_CONTACT_SAMPLES = 60;
    private static final double MAX_TAIL_SLIP_PEAK = 0.005D;
    private static final double MAX_TAIL_SLIP_RMS = 0.002D;
    private static final double MAX_TAIL_ACCUMULATED_SLIP = 0.08D;
    // The pre-fix waveform peaked at 0.008125645 blocks/tick before sleep hid it.
    private static final double MAX_ACTIVE_CONTACT_SLIP_PEAK = 0.0080D;
    private static final int MIN_ACTIVE_CONTACT_SAMPLES = 3;

    private static final BenchMetricDescriptor STACK_SEPARATION = new BenchMetricDescriptor(
            "super_lead.rope.stack_separation", "blocks", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor STACK_TAIL_AMPLITUDE = new BenchMetricDescriptor(
            "super_lead.rope.stack_tail_amplitude", "blocks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor STACK_TICKS_TO_REST = new BenchMetricDescriptor(
            "super_lead.rope.stack_ticks_to_rest", "ticks", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor STACK_TANGENTIAL_SLIP_RMS = new BenchMetricDescriptor(
            "super_lead.rope.stack_tangential_slip_rms", "blocks/tick", MetricDirection.LOWER_IS_BETTER);
        private static final BenchMetricDescriptor STACK_TANGENTIAL_SLIP_PEAK = new BenchMetricDescriptor(
            "super_lead.rope.stack_tangential_slip_peak", "blocks/tick", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor STACK_ACTIVE_CONTACT_SLIP_PEAK = new BenchMetricDescriptor(
            "super_lead.rope.stack_active_contact_slip_peak", "blocks/tick", MetricDirection.LOWER_IS_BETTER);

    private final CopyOnWriteArrayList<UUID> createdConnections = new CopyOnWriteArrayList<>();
    private final List<BlockPos> placedBlocks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> serverError = new AtomicReference<>();
    private final RopeMeshLifecycleTracker meshLifecycle = new RopeMeshLifecycleTracker();
    private volatile boolean rigReady;

        private final StringBuilder trace = new StringBuilder(
            "tick,bellyA,bellyB,motionA,motionB,restA,restB,separation,tangentialSlip,contactSample\n");
    private BlockPos rigBase;
    private BenchClientPose viewPose;
    private int measuredTicks;
    private long firstBothRestTick = -1;
    private double tailMinA = Double.POSITIVE_INFINITY, tailMaxA = Double.NEGATIVE_INFINITY;
    private double tailMinB = Double.POSITIVE_INFINITY, tailMaxB = Double.NEGATIVE_INFINITY;
    private double lastSeparation = Double.NaN;
    private double previousAX, previousAY, previousAZ;
    private double previousBX, previousBY, previousBZ;
    private boolean previousBelliesValid;
    private int tailContactSamples;
    private double tailSlipPeak;
    private double tailSlipSumSqr;
    private double tailAccumulatedSlip;
    private int activeContactSamples;
    private double activeContactSlipPeak;
    private int missingSamples;

    @Override
    public void setup(BenchClientContext context) {
        MinecraftServer server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("rope bench requires the integrated server");
        }
        BenchClientPose pose = context.automation().pose();
        // Offset from the air-rest rig so the two scenarios never share sims.
        rigBase = BlockPos.containing(pose.x(), pose.y(), pose.z()).above(24).offset(0, 0, 24);
        BlockPos base = rigBase;
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                // Rope A along +X, rope B along +Z, crossing above A's midpoint.
                BlockPos a0 = base;
                BlockPos a1 = base.offset(SPAN, 0, 0);
                BlockPos b0 = base.offset(SPAN / 2, 0, -SPAN / 2);
                BlockPos b1 = base.offset(SPAN / 2, 0, SPAN / 2);
                for (BlockPos pos : List.of(a0, a1, b0, b1)) {
                    level.setBlockAndUpdate(pos, Blocks.OAK_FENCE.defaultBlockState());
                    placedBlocks.add(pos);
                }
                for (BlockPos[] pair : new BlockPos[][] { { a0, a1 }, { b0, b1 } }) {
                    LeadConnection connection = SuperLeadNetwork.connect(level,
                            new LeadAnchor(pair[0], Direction.UP), new LeadAnchor(pair[1], Direction.UP),
                            LeadKind.NORMAL, null, LeadConnection.MIN_LENGTH_UNITS);
                    if (connection == null) {
                        throw new IllegalStateException("connect() refused a stack rope");
                    }
                    createdConnections.add(connection.id());
                }
                rigReady = true;
            } catch (Exception e) {
                serverError.set("stack rig setup failed: " + e);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        failOnServerError();
        if (!rigReady || !context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        for (UUID id : createdConnections) {
            if (SuperLeadClientEvents.probeSimForBench(id) == null) {
                return BenchClientStepResult.CONTINUE;
            }
        }
        double cx = rigBase.getX() + SPAN * 0.5D;
        double cy = rigBase.getY();
        double cz = rigBase.getZ();
        viewPose = lookPose(cx - 9.0D, cy + 2.0D, cz + 9.0D, cx, cy, cz);
        context.automation().stopMovement();
        context.automation().setPose(viewPose);
        context.automation().setHudHidden(true);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        failOnServerError();
        context.automation().stopMovement();
        if (viewPose != null) {
            context.automation().setPose(viewPose);
        }
        measuredTicks++;
        meshLifecycle.sample(createdConnections);
        SuperLeadClientEvents.RopeSimBenchProbe a =
                SuperLeadClientEvents.probeSimForBench(createdConnections.get(0));
        SuperLeadClientEvents.RopeSimBenchProbe b =
                SuperLeadClientEvents.probeSimForBench(createdConnections.get(1));
        if (a == null || b == null) {
            missingSamples++;
        } else {
            // Full per-tick waveform: oscillation frequency and A/B phase relation
            // identify the energy source (anti-phase = contact pump, in-phase =
            // shared driver, sawtooth = constraint fight) without reruns.
                double dx = a.bellyX() - b.bellyX();
                double dy = a.bellyY() - b.bellyY();
                double dz = a.bellyZ() - b.bellyZ();
                lastSeparation = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double tangentialSlip = Double.NaN;
                boolean contactSample = false;
                if (previousBelliesValid && lastSeparation > 1.0e-9D) {
                double relativeX = (a.bellyX() - previousAX) - (b.bellyX() - previousBX);
                double relativeY = (a.bellyY() - previousAY) - (b.bellyY() - previousBY);
                double relativeZ = (a.bellyZ() - previousAZ) - (b.bellyZ() - previousBZ);
                double invSeparation = 1.0D / lastSeparation;
                double normalSpeed = (relativeX * dx + relativeY * dy + relativeZ * dz) * invSeparation;
                double tangentX = relativeX - dx * invSeparation * normalSpeed;
                double tangentY = relativeY - dy * invSeparation * normalSpeed;
                double tangentZ = relativeZ - dz * invSeparation * normalSpeed;
                tangentialSlip = Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
                contactSample = measuredTicks > MEASURE_TICKS - TAIL_TICKS
                    && lastSeparation >= MIN_CROSSING_SEPARATION
                    && lastSeparation <= MAX_CONTACT_SEPARATION;
                boolean activeContactSample = firstBothRestTick < 0
                    && lastSeparation >= MIN_CROSSING_SEPARATION
                    && lastSeparation <= MAX_CONTACT_SEPARATION;
                if (activeContactSample) {
                    activeContactSamples++;
                    activeContactSlipPeak = Math.max(activeContactSlipPeak, tangentialSlip);
                }
                if (contactSample) {
                    tailContactSamples++;
                    tailSlipPeak = Math.max(tailSlipPeak, tangentialSlip);
                    tailSlipSumSqr += tangentialSlip * tangentialSlip;
                    tailAccumulatedSlip += tangentialSlip;
                }
                }
                trace.append(measuredTicks).append(',')
                    .append(String.format(java.util.Locale.ROOT,
                        "%.6f,%.6f,%.3e,%.3e,%d,%d,%.6f,%.6e,%d%n",
                        a.bellyY(), b.bellyY(), a.maxNodeMotionSqr(), b.maxNodeMotionSqr(),
                        a.visuallyAtRest() ? 1 : 0, b.visuallyAtRest() ? 1 : 0,
                        lastSeparation, tangentialSlip, contactSample ? 1 : 0));
                previousAX = a.bellyX();
                previousAY = a.bellyY();
                previousAZ = a.bellyZ();
                previousBX = b.bellyX();
                previousBY = b.bellyY();
                previousBZ = b.bellyZ();
                previousBelliesValid = true;
            if (a.visuallyAtRest() && b.visuallyAtRest() && firstBothRestTick < 0) {
                firstBothRestTick = measuredTicks;
            }
            if (measuredTicks > MEASURE_TICKS - TAIL_TICKS) {
                tailMinA = Math.min(tailMinA, a.bellyY());
                tailMaxA = Math.max(tailMaxA, a.bellyY());
                tailMinB = Math.min(tailMinB, b.bellyY());
                tailMaxB = Math.max(tailMaxB, b.bellyY());
            }
        }
        if (measuredTicks == MEASURE_TICKS - 1) {
            context.automation().captureScreenshot("rope-stack-contact");
        }
        return measuredTicks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) throws Exception {
        java.nio.file.Files.writeString(
                context.resultDirectory().resolve("rope-stack-trace.csv"), trace.toString());
        failOnServerError();
        if (missingSamples > 0) {
            throw new AssertionError("probe returned null on " + missingSamples + " ticks");
        }
        double amplitude = Math.max(tailMaxA - tailMinA, tailMaxB - tailMinB);
        double slipRms = tailContactSamples > 0
            ? Math.sqrt(tailSlipSumSqr / tailContactSamples) : Double.POSITIVE_INFINITY;
        String state = String.format(
            "firstBothRest=%d tailAmp=%.5f separation=%.5f contactSamples=%d slipPeak=%.6f slipRms=%.6f accumulatedSlip=%.6f activeSamples=%d activeSlipPeak=%.6f",
            firstBothRestTick, amplitude, lastSeparation, tailContactSamples,
            tailSlipPeak, slipRms, tailAccumulatedSlip, activeContactSamples, activeContactSlipPeak);
        if (firstBothRestTick < 0 || firstBothRestTick > MAX_TICKS_TO_REST) {
            throw new AssertionError("stack never came to rest (the jitter bug): " + state);
        }
        if (amplitude > TAIL_AMPLITUDE_LIMIT) {
            throw new AssertionError("stack keeps moving at rest (energy leak): " + state);
        }
        if (!(lastSeparation >= MIN_CROSSING_SEPARATION)) {
            throw new AssertionError(
                    "crossing ropes sleep inside each other (contact too weak): " + state);
        }
        if (tailContactSamples < MIN_TAIL_CONTACT_SAMPLES) {
            throw new AssertionError("stack did not retain enough tail contact samples: " + state);
        }
        if (tailSlipPeak > MAX_TAIL_SLIP_PEAK || slipRms > MAX_TAIL_SLIP_RMS
                || tailAccumulatedSlip > MAX_TAIL_ACCUMULATED_SLIP) {
            throw new AssertionError("contacting ropes keep sliding after settling: " + state);
        }
        if (activeContactSamples < MIN_ACTIVE_CONTACT_SAMPLES) {
            throw new AssertionError("stack did not expose enough loaded pre-rest contact samples: " + state);
        }
        if (activeContactSlipPeak > MAX_ACTIVE_CONTACT_SLIP_PEAK) {
            throw new AssertionError("contacting ropes rock excessively before sleep: " + state);
        }
        meshLifecycle.requireAllActiveAtLeastOnce(createdConnections, "stack-contact");
        context.metrics().record(STACK_TICKS_TO_REST, firstBothRestTick);
        context.metrics().record(STACK_TAIL_AMPLITUDE, amplitude);
        context.metrics().record(STACK_SEPARATION, lastSeparation);
        context.metrics().record(STACK_TANGENTIAL_SLIP_RMS, slipRms);
        context.metrics().record(STACK_TANGENTIAL_SLIP_PEAK, tailSlipPeak);
        context.metrics().record(STACK_ACTIVE_CONTACT_SLIP_PEAK, activeContactSlipPeak);
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
            SuperLeadSavedData.get(level).removeIf(connection -> ids.contains(connection.id()));
            for (BlockPos pos : blocks) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            SuperLeadPayloads.sendDirtyToDimension(level);
        });
    }

    private void failOnServerError() {
        String error = serverError.get();
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    private static BenchClientPose lookPose(double x, double y, double z,
            double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new BenchClientPose(x, y, z, yaw, pitch);
    }
}
