package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientPose;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/**
 * Measures localized mesh enter/exit churn with a large resident registry.
 *
 * <p>Each run fixes one urgent-section budget. Run the registered 2/4/8/12
 * variants separately and compare the built-in MEASURE client.frame.interval
 * distribution together with the registry mutation and drain metrics below.
 */
public final class MeshSectionBudgetClientScenario implements BenchClientScenario {
    private static final int GRID_X = 6;
    private static final int GRID_Z = 4;
    private static final int SECTION_COUNT = GRID_X * GRID_Z;
    private static final int ROPES_PER_SECTION = 64;
    private static final int TOTAL_ROPES = SECTION_COUNT * ROPES_PER_SECTION;
    private static final int CHURN_SECTIONS = 12;
    private static final int STABLE_TICKS = 6;
    private static final int WARMUP_CYCLES = 1;
    private static final int MEASURE_CYCLES = 6;
    private static final int MAX_TRANSITION_TICKS = 240;

    private static final BenchMetricDescriptor SECTION_BUDGET = new BenchMetricDescriptor(
            "super_lead.mesh_churn.section_budget", "sections_per_tick", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RESIDENT_ROPES = new BenchMetricDescriptor(
            "super_lead.mesh_churn.resident_ropes", "ropes", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor CHURNED_SECTIONS = new BenchMetricDescriptor(
            "super_lead.mesh_churn.sections", "sections", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor REGISTRY_MUTATION = new BenchMetricDescriptor(
            "super_lead.mesh_churn.registry_mutation", "ns", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor DRAIN_TICKS = new BenchMetricDescriptor(
            "super_lead.mesh_churn.drain_ticks", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor ACCEPT_TICKS = new BenchMetricDescriptor(
            "super_lead.mesh_churn.accept_ticks", "ticks", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PEAK_DIRTY_QUEUE = new BenchMetricDescriptor(
            "super_lead.mesh_churn.peak_dirty_queue", "sections", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PEAK_DIRTY_FLUSH = new BenchMetricDescriptor(
            "super_lead.mesh_churn.peak_dirty_flush", "sections", MetricDirection.LOWER_IS_BETTER);

    private final int sectionBudget;
    private final List<StressSource> allSources = new ArrayList<>(TOTAL_ROPES);
    private final List<StressSource> reducedSources = new ArrayList<>(TOTAL_ROPES - CHURN_SECTIONS);
    private final Set<UUID> churnIds = new HashSet<>(CHURN_SECTIONS);
    private Stage stage = Stage.READY_TO_REMOVE;
    private BenchClientPose cameraPose;
    private int stableTicks;
    private int transitionTicks;
    private int queueDrainedAt = -1;
    private int meshAcceptedAt = -1;
    private int warmupCycles;
    private int measureCycles;
    private int peakDirtyQueue;
    private int peakDirtyFlush;
    private boolean measuring;

    public MeshSectionBudgetClientScenario(int sectionBudget) {
        if (sectionBudget < 1) {
            throw new IllegalArgumentException("sectionBudget must be positive");
        }
        this.sectionBudget = sectionBudget;
    }

    @Override
    public void setup(BenchClientContext context) {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        registry.clearStressSources(context.level());
        registry.configureSectionBudgetsForBench(sectionBudget, Math.min(2, sectionBudget));
        buildFixture();
        applyPose(context);
        registry.publishStressSources(context.level(), allSources);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        applyPose(context);
        if (!context.environment().readiness().ready()) {
            return BenchClientStepResult.CONTINUE;
        }
        if (!allSourcesAccepted()) {
            return BenchClientStepResult.CONTINUE;
        }
        return ++stableTicks >= STABLE_TICKS
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        measuring = false;
        applyPose(context);
        if (advanceCycle(context)) {
            warmupCycles++;
            return warmupCycles >= WARMUP_CYCLES
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        if (!measuring) {
            peakDirtyQueue = 0;
            peakDirtyFlush = 0;
        }
        measuring = true;
        applyPose(context);
        if (advanceCycle(context)) {
            measureCycles++;
            return measureCycles >= MEASURE_CYCLES
                    ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    private boolean advanceCycle(BenchClientContext context) {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        peakDirtyQueue = Math.max(peakDirtyQueue, registry.dirtyQueueCount());
        peakDirtyFlush = Math.max(peakDirtyFlush, registry.dirtyFlushedLastTick());
        transitionTicks++;
        if (transitionTicks > MAX_TRANSITION_TICKS) {
            throw new AssertionError("mesh churn transition timed out: budget=" + sectionBudget
                    + " stage=" + stage + " dirty=" + registry.dirtyQueueCount()
                    + " sections=" + registry.acceptedSectionCount() + "/" + registry.sectionCount());
        }

        return switch (stage) {
            case READY_TO_REMOVE -> {
                publishAndMeasure(context, reducedSources);
                beginWait(Stage.WAITING_FOR_REMOVAL);
                yield false;
            }
            case WAITING_FOR_REMOVAL -> {
                observeQueueDrain(registry);
                boolean ready = registry.dirtyQueueCount() == 0
                        && registry.acceptedSectionCount() == registry.sectionCount();
                if (ready) {
                    observeMeshAcceptance();
                    if (++stableTicks >= STABLE_TICKS) {
                        recordTransitionTicks(context);
                        stage = Stage.READY_TO_ADD;
                        stableTicks = 0;
                    }
                } else {
                    stableTicks = 0;
                }
                yield false;
            }
            case READY_TO_ADD -> {
                publishAndMeasure(context, allSources);
                beginWait(Stage.WAITING_FOR_ADDITION);
                yield false;
            }
            case WAITING_FOR_ADDITION -> {
                observeQueueDrain(registry);
                boolean ready = registry.dirtyQueueCount() == 0 && churnSourcesAccepted();
                if (ready) {
                    observeMeshAcceptance();
                    if (++stableTicks >= STABLE_TICKS) {
                        recordTransitionTicks(context);
                        stage = Stage.READY_TO_REMOVE;
                        stableTicks = 0;
                        transitionTicks = 0;
                        yield true;
                    }
                } else {
                    stableTicks = 0;
                }
                yield false;
            }
        };
    }

    private void publishAndMeasure(BenchClientContext context, List<StressSource> sources) {
        long started = System.nanoTime();
        StaticRopeChunkRegistry.get().publishStressSources(context.level(), sources);
        long elapsed = System.nanoTime() - started;
        if (measuring) {
            context.metrics().record(REGISTRY_MUTATION, elapsed);
        }
    }

    private void beginWait(Stage waitingStage) {
        stage = waitingStage;
        stableTicks = 0;
        transitionTicks = 0;
        queueDrainedAt = -1;
        meshAcceptedAt = -1;
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        peakDirtyQueue = Math.max(peakDirtyQueue, registry.dirtyQueueCount());
        peakDirtyFlush = Math.max(peakDirtyFlush, registry.dirtyFlushedLastTick());
    }

    private void observeQueueDrain(StaticRopeChunkRegistry registry) {
        if (queueDrainedAt < 0 && registry.dirtyQueueCount() == 0) {
            queueDrainedAt = transitionTicks;
        }
    }

    private void observeMeshAcceptance() {
        if (meshAcceptedAt < 0) {
            meshAcceptedAt = transitionTicks;
        }
    }

    private void recordTransitionTicks(BenchClientContext context) {
        if (measuring) {
            context.metrics().record(DRAIN_TICKS, queueDrainedAt);
            context.metrics().record(ACCEPT_TICKS, meshAcceptedAt);
        }
    }

    @Override
    public void verify(BenchClientContext context) {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        if (measureCycles != MEASURE_CYCLES || !allSourcesAccepted()) {
            throw new AssertionError("mesh churn run incomplete: budget=" + sectionBudget
                    + " cycles=" + measureCycles + "/" + MEASURE_CYCLES
                    + " dirty=" + registry.dirtyQueueCount()
                    + " claimed=" + registry.claimedCount()
                    + " sections=" + registry.acceptedSectionCount() + "/" + registry.sectionCount());
        }
        context.metrics().record(SECTION_BUDGET, sectionBudget);
        context.metrics().record(RESIDENT_ROPES, TOTAL_ROPES);
        context.metrics().record(CHURNED_SECTIONS, CHURN_SECTIONS);
        context.metrics().record(PEAK_DIRTY_QUEUE, peakDirtyQueue);
        context.metrics().record(PEAK_DIRTY_FLUSH, peakDirtyFlush);
    }

    @Override
    public void teardown(BenchClientContext context) {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        registry.clearStressSources(context.level());
        registry.configureSectionBudgetsForBench(Integer.MAX_VALUE, Integer.MAX_VALUE);
        registry.flushPendingDirtySections();
        registry.resetSectionBudgetsForBench();
    }

    private void buildFixture() {
        // Fixed coordinates keep repeated runs comparable. Deriving the fixture
        // from the saved player pose made every run climb by another 32 blocks.
        int baseChunkX = -GRID_X / 2;
        int baseChunkZ = -GRID_Z / 2;
        int sectionY = SectionPos.blockToSectionCoord(64);
        int sectionOriginY = SectionPos.sectionToBlockCoord(sectionY);

        for (int sectionIndex = 0; sectionIndex < SECTION_COUNT; sectionIndex++) {
            int chunkX = baseChunkX + sectionIndex % GRID_X;
            int chunkZ = baseChunkZ + sectionIndex / GRID_X;
            int originX = SectionPos.sectionToBlockCoord(chunkX);
            int originZ = SectionPos.sectionToBlockCoord(chunkZ);
            for (int ropeIndex = 0; ropeIndex < ROPES_PER_SECTION; ropeIndex++) {
                int localY = 4 + (ropeIndex / 16) * 3;
                double localZ = 0.75D + (ropeIndex % 16) * 0.9D;
                UUID id = UUID.nameUUIDFromBytes(("super-lead-mesh-churn-"
                        + sectionBudget + "-" + sectionIndex + "-" + ropeIndex)
                        .getBytes(StandardCharsets.UTF_8));
                StressSource source = new StressSource(
                        id,
                        new Vec3(originX + 2.0D, sectionOriginY + localY, originZ + localZ),
                        new Vec3(originX + 12.0D, sectionOriginY + localY, originZ + localZ));
                allSources.add(source);
                if (ropeIndex == ROPES_PER_SECTION - 1 && sectionIndex < CHURN_SECTIONS) {
                    churnIds.add(id);
                } else {
                    reducedSources.add(source);
                }
            }
        }

        double centerX = SectionPos.sectionToBlockCoord(baseChunkX) + GRID_X * 8.0D;
        double centerZ = SectionPos.sectionToBlockCoord(baseChunkZ) + GRID_Z * 8.0D;
        cameraPose = new BenchClientPose(
                centerX,
                sectionOriginY + 24.0D,
                centerZ + GRID_Z * 10.0D,
                180.0F,
                24.0F);
    }

    private boolean allSourcesAccepted() {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        for (StressSource source : allSources) {
            if (!registry.isMeshAccepted(source.id())) {
                return false;
            }
        }
        return true;
    }

    private boolean churnSourcesAccepted() {
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        for (UUID id : churnIds) {
            if (!registry.isMeshAccepted(id)) {
                return false;
            }
        }
        return true;
    }

    private void applyPose(BenchClientContext context) {
        context.automation().stopMovement();
        context.automation().setHudHidden(true);
        context.automation().setPose(cameraPose);
    }

    private enum Stage {
        READY_TO_REMOVE,
        WAITING_FOR_REMOVAL,
        READY_TO_ADD,
        WAITING_FOR_ADDITION
    }
}
