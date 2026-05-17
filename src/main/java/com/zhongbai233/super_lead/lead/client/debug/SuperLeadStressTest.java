package com.zhongbai233.super_lead.lead.client.debug;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import com.zhongbai233.super_lead.lead.client.chunk.StressSource;
import com.zhongbai233.super_lead.lead.client.chunk.RopeSectionSnapshot;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeJob;
import com.zhongbai233.super_lead.lead.client.render.RopeVisibility;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
/**
 * Client/server debug utility for spawning deterministic rope stress-test
 * scenes.
 *
 * <p>
 * Use this only from development commands or debug UI paths. It intentionally
 * exercises dense rope networks and should stay isolated from normal gameplay
 * event flow.
 */
public final class SuperLeadStressTest {
    private static final int MAX_COUNT = 20_000;
    private static final double DEFAULT_LENGTH = 8.0D;
    private static final double DEFAULT_SPACING = 0.35D;
    private static final double DEFAULT_AMPLITUDE = 0.35D;
    private static final double DEFAULT_SPEED = 0.32D;
    private static final double SPAWN_DISTANCE = 12.0D;
    private static final double FRUSTUM_BOUNDS_MARGIN = 1.0D;
    private static final double NEIGHBOR_GRID_SIZE = 4.0D;
    private static final double NEIGHBOR_BOUNDS_MARGIN = 0.08D;
    private static final double NEIGHBOR_CONTACT_DISTANCE = 0.14D;
    private static final int GRID_KEY_BIAS = 1 << 20;
    private static final Logger LOG = LogUtils.getLogger();

    private static final List<StressRope> ROPES = new ArrayList<>();
    private static boolean movingMode;
    private static boolean collisionMode = true;
    private static boolean forceChunkMeshMode;
    private static boolean localSectionMode;
    private static long lastStepTick = Long.MIN_VALUE;
    private static long lastPhysicsNanos;
    private static int lastEstimatedVertices;
    private static int lastVisibleRopes;
    private static int lastCulledRopes;
    private static int lastNeighborCandidates;
    private static int lastNeighborLinks;
    private static int lastBakeHits;
    private static int lastBakeMisses;
    private static int lastVerticesEmitted;
    private static BenchRun benchRun;
    private static BenchPipeline benchPipeline;

    private SuperLeadStressTest() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead_stress")
                .then(modeCommand("static", false, false))
                .then(modeCommand("static_chunk", false, false, true))
                .then(modeCommand("static_chunk_local", false, false, true, true))
                .then(modeCommand("moving", true, true))
                .then(modeCommand("moving_nocollide", true, false))
                .then(benchCommand("static", false, false))
                .then(benchCommand("static_chunk", false, false, true))
                .then(benchCommand("static_chunk_local", false, false, true, true))
                .then(benchCommand("moving", true, true))
                .then(benchCommand("moving_nocollide", true, false))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("pipeline").executes(ctx -> startPipeline(ctx.getSource())));
        dispatcher.register(root);
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (ROPES.isEmpty())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clear();
            return;
        }

        long tick = level.getGameTime();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        Frustum frustum = camera.getCullFrustum();
        RopeVisibility.beginFrame(cameraPos);
        recordBenchFrameStart();

        if (tick != lastStepTick) {
            lastStepTick = tick;
            if (movingMode) {
                long start = System.nanoTime();
                Map<RopeSimulation, List<RopeSimulation>> neighbors;
                if (collisionMode) {
                    neighbors = buildNeighborMap(tick);
                    lastNeighborLinks = countNeighborLinks(neighbors);
                } else {
                    neighbors = Map.of();
                    lastNeighborLinks = 0;
                    lastNeighborCandidates = 0;
                }
                for (StressRope rope : ROPES) {
                    Vec3 a = rope.a(tick);
                    Vec3 b = rope.b(tick);
                    rope.sim().step(level, a, b, tick,
                            neighbors.getOrDefault(rope.sim(), List.of()),
                            List.of(),
                            List.of());
                }
                lastPhysicsNanos = System.nanoTime() - start;
            } else {
                // Static mode is intentionally render-only. This isolates the FPS/vertex cost
                // from
                // the XPBD solver so static and moving results are meaningful instead of
                // identical.
                lastPhysicsNanos = 0L;
                lastNeighborCandidates = 0;
                lastNeighborLinks = 0;
            }
        }

        int vertices = 0;
        int visible = 0;
        int culled = 0;
        StaticRopeChunkRegistry chunkReg = StaticRopeChunkRegistry.get();
        java.util.List<RopeJob> jobs = new java.util.ArrayList<>(ROPES.size());
        for (StressRope rope : ROPES) {
            // Static stress ropes often live in otherwise-empty air sections. Keep
            // the dynamic fallback until the section rebuild has actually accepted
            // our custom geometry, not merely until the registry has a snapshot.
            if (!movingMode && shouldSkipDynamicForStaticStress(chunkReg, rope.id(), tick))
                continue;
            Vec3 a = rope.a(tick);
            Vec3 b = rope.b(tick);
            AABB renderBounds = rope.sim().renderBounds(partialTick).inflate(FRUSTUM_BOUNDS_MARGIN);
            if (!RopeVisibility.shouldRender(level, minecraft.player, frustum, cameraPos,
                    renderBounds, rope.sim(), partialTick)) {
                culled++;
                continue;
            }
            visible++;
            BlockPos lightA = BlockPos.containing(a);
            BlockPos lightB = BlockPos.containing(b);
            int blockA = level.getBrightness(LightLayer.BLOCK, lightA);
            int blockB = level.getBrightness(LightLayer.BLOCK, lightB);
            int skyA = level.getBrightness(LightLayer.SKY, lightA);
            int skyB = level.getBrightness(LightLayer.SKY, lightB);
            jobs.add(LeashBuilder.collect(rope.sim(), blockA, blockB, skyA, skyB,
                    LeashBuilder.NO_HIGHLIGHT, LeadKind.NORMAL, false, 0, null, 0));
            vertices += estimateVertices(rope.sim(), cameraPos);
        }
        // Snapshot stats BEFORE reset+flush. The flush below only enqueues a deferred
        // lambda;
        // the actual renderJob calls (which increment cacheHits/Misses/verticesEmitted)
        // run
        // later in the render pipeline. Reading the counters right after flush() always
        // sees
        // the just-cleared values. Instead we report what the previous frame's lambda
        // left
        // behind, then reset for this frame.
        lastBakeHits = LeashBuilder.cacheHits;
        lastBakeMisses = LeashBuilder.cacheMisses;
        lastVerticesEmitted = LeashBuilder.verticesEmitted;
        LeashBuilder.resetStats();
        LeashBuilder.flush(event.getSubmitNodeCollector(), cameraPos, partialTick, jobs);
        lastVisibleRopes = visible;
        lastCulledRopes = culled;
        lastEstimatedVertices = vertices;
        finishBenchFrame();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeCommand(String name, boolean moving,
            boolean collision) {
        return modeCommand(name, moving, collision, false);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeCommand(String name, boolean moving,
            boolean collision, boolean forceChunkMesh) {
        return modeCommand(name, moving, collision, forceChunkMesh, false);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeCommand(String name, boolean moving,
            boolean collision, boolean forceChunkMesh, boolean localSection) {
        return Commands.literal(name)
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                        .executes(ctx -> start(ctx, moving, collision, forceChunkMesh, localSection,
                                IntegerArgumentType.getInteger(ctx, "count"),
                                DEFAULT_LENGTH, DEFAULT_SPACING, 0,
                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                        .then(Commands.argument("length", DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                .executes(ctx -> start(ctx, moving, collision, forceChunkMesh, localSection,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        DoubleArgumentType.getDouble(ctx, "length"),
                                        DEFAULT_SPACING, 0,
                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                .then(Commands.argument("spacing", DoubleArgumentType.doubleArg(0.05D, 16.0D))
                                        .executes(ctx -> start(ctx, moving, collision, forceChunkMesh, localSection,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                DoubleArgumentType.getDouble(ctx, "length"),
                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                0,
                                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                        .then(Commands.argument("columns", IntegerArgumentType.integer(1, MAX_COUNT))
                                                .executes(ctx -> start(ctx, moving, collision, forceChunkMesh,
                                                        localSection,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                        IntegerArgumentType.getInteger(ctx, "columns"),
                                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                                .then(Commands
                                                        .argument("amplitude", DoubleArgumentType.doubleArg(0.0D, 8.0D))
                                                        .executes(ctx -> start(ctx, moving, collision, forceChunkMesh,
                                                                localSection,
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                DoubleArgumentType.getDouble(ctx, "length"),
                                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                IntegerArgumentType.getInteger(ctx, "columns"),
                                                                DoubleArgumentType.getDouble(ctx, "amplitude"),
                                                                DEFAULT_SPEED))
                                                        .then(Commands
                                                                .argument("speed",
                                                                        DoubleArgumentType.doubleArg(0.0D, 4.0D))
                                                                .executes(ctx -> start(ctx, moving, collision,
                                                                        forceChunkMesh,
                                                                        localSection,
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                        IntegerArgumentType.getInteger(ctx, "columns"),
                                                                        DoubleArgumentType.getDouble(ctx, "amplitude"),
                                                                        DoubleArgumentType.getDouble(ctx,
                                                                                "speed")))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> benchCommand(String name, boolean moving,
            boolean collision) {
        return benchCommand(name, moving, collision, false);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> benchCommand(String name, boolean moving,
            boolean collision, boolean forceChunkMesh) {
        return benchCommand(name, moving, collision, forceChunkMesh, false);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> benchCommand(String name, boolean moving,
            boolean collision, boolean forceChunkMesh, boolean localSection) {
        return Commands.literal("bench_" + name)
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                        .executes(ctx -> bench(ctx, moving, collision, forceChunkMesh, localSection,
                                IntegerArgumentType.getInteger(ctx, "count"),
                                240, 40,
                                DEFAULT_LENGTH, DEFAULT_SPACING, 0,
                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                        .then(Commands.argument("frames", IntegerArgumentType.integer(30, 20_000))
                                .executes(ctx -> bench(ctx, moving, collision, forceChunkMesh, localSection,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        IntegerArgumentType.getInteger(ctx, "frames"), 40,
                                        DEFAULT_LENGTH, DEFAULT_SPACING, 0,
                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                .then(Commands.argument("warmup", IntegerArgumentType.integer(0, 5_000))
                                        .executes(ctx -> bench(ctx, moving, collision, forceChunkMesh, localSection,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                IntegerArgumentType.getInteger(ctx, "frames"),
                                                IntegerArgumentType.getInteger(ctx, "warmup"),
                                                DEFAULT_LENGTH, DEFAULT_SPACING, 0,
                                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                        .then(Commands.argument("length", DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                                .executes(ctx -> bench(ctx, moving, collision, forceChunkMesh,
                                                        localSection,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "frames"),
                                                        IntegerArgumentType.getInteger(ctx, "warmup"),
                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                        DEFAULT_SPACING, 0,
                                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                                .then(Commands.argument("spacing",
                                                        DoubleArgumentType.doubleArg(0.05D, 16.0D))
                                                        .executes(ctx -> bench(ctx, moving, collision, forceChunkMesh,
                                                                localSection,
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                IntegerArgumentType.getInteger(ctx, "frames"),
                                                                IntegerArgumentType.getInteger(ctx, "warmup"),
                                                                DoubleArgumentType.getDouble(ctx, "length"),
                                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                0,
                                                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                                        .then(Commands.argument("columns",
                                                                IntegerArgumentType.integer(1, MAX_COUNT))
                                                                .executes(ctx -> bench(ctx, moving, collision,
                                                                        forceChunkMesh,
                                                                        localSection,
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        IntegerArgumentType.getInteger(ctx, "frames"),
                                                                        IntegerArgumentType.getInteger(ctx, "warmup"),
                                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                        IntegerArgumentType.getInteger(ctx, "columns"),
                                                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))))))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean moving, boolean collision,
            boolean forceChunkMesh, boolean localSection, int count,
            double length, double spacing, int columns, double amplitude, double speed) {
        CommandSourceStack source = ctx.getSource();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            source.sendFailure(Component.literal("Super Lead stress: no client level loaded."));
            return 0;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        var left = camera.leftVector();
        var up = camera.upVector();
        double fx = forward.x(), fy = forward.y(), fz = forward.z();
        double rx = -left.x(), ry = -left.y(), rz = -left.z();
        double ux = up.x(), uy = up.y(), uz = up.z();
        int cols = columns > 0 ? columns : Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        int rows = (count + cols - 1) / cols;
        Vec3 origin;
        if (localSection) {
            SectionPos section = SectionPos.of(BlockPos.containing(camera.position()));
            double centerX = SectionPos.sectionToBlockCoord(section.x()) + 8.0D;
            double centerY = SectionPos.sectionToBlockCoord(section.y()) + 8.0D;
            double centerZ = SectionPos.sectionToBlockCoord(section.z()) + 8.0D;
            origin = new Vec3(centerX, centerY, centerZ);
        } else {
            // Ropes run along camera-right; the grid spreads along camera-forward and
            // camera-up so adjacent ropes are parallel lanes and the test measures normal
            // render/solver cost.
            origin = camera.position().add(
                    fx * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                    fy * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                    fz * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D));
        }
        List<StressRope> generated = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int row = i / cols;
            int col = i % cols;
            double gridX = (col - (cols - 1) * 0.5D) * spacing;
            double gridY = ((rows - 1) * 0.5D - row) * spacing;
            double mx = origin.x + fx * gridX + ux * gridY;
            double my = origin.y + fy * gridX + uy * gridY;
            double mz = origin.z + fz * gridX + uz * gridY;
            Vec3 a = new Vec3(mx - rx * length * 0.5D, my - ry * length * 0.5D, mz - rz * length * 0.5D);
            Vec3 b = new Vec3(mx + rx * length * 0.5D, my + ry * length * 0.5D, mz + rz * length * 0.5D);
            RopeSimulation sim = new RopeSimulation(a, b, 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L);
            sim.resetCatenary(a, b, 0.035D);
            double phase = i * 0.61803398875D;
            generated.add(new StressRope(UUID.randomUUID(), sim, a, b, rx, ry, rz, phase, amplitude, speed, moving));
        }

        ROPES.clear();
        ROPES.addAll(generated);
        movingMode = moving;
        collisionMode = collision;
        forceChunkMeshMode = !moving && forceChunkMesh;
        localSectionMode = localSection;
        if (!moving) {
            publishChunkMeshSources(level);
        }
        lastStepTick = Long.MIN_VALUE;
        lastPhysicsNanos = 0L;
        lastEstimatedVertices = 0;
        lastVisibleRopes = 0;
        lastCulledRopes = 0;
        lastNeighborCandidates = 0;
        lastNeighborLinks = 0;

        String modeLabel = modeLabel();
        source.sendSuccess(() -> Component.literal(String.format(
                "Super Lead stress: spawned %,d %s ropes, length=%.2f, spacing=%.2f, columns=%d. Use status or stop.",
                count, modeLabel, length, spacing, cols)), false);
        return count;
    }

    private static int bench(CommandContext<CommandSourceStack> ctx, boolean moving, boolean collision,
            boolean forceChunkMesh, boolean localSection, int count,
            int frames, int warmup, double length, double spacing, int columns, double amplitude, double speed) {
        int spawned = start(ctx, moving, collision, forceChunkMesh, localSection, count, length, spacing, columns,
                amplitude, speed);
        if (spawned <= 0) {
            return spawned;
        }
        benchRun = new BenchRun(modeLabel(),
                count, frames, warmup, renderModeLabel());
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Super Lead bench: mode=%s render=%s ropes=%,d warmup=%d frames=%d.",
                benchRun.modeLabel, benchRun.renderMode, count, warmup, frames)), false);
        return spawned;
    }

    private static int stop(CommandSourceStack source) {
        int count = ROPES.size();
        clear();
        source.sendSuccess(() -> Component.literal("Super Lead stress: cleared " + count + " client-only ropes."),
                false);
        return count;
    }

    private static int status(CommandSourceStack source) {
        double physicsMs = lastPhysicsNanos / 1_000_000.0D;
        String modeLabel = modeLabel();
        source.sendSuccess(() -> Component.literal(String.format(
                "Super Lead stress: %,d ropes, mode=%s, last physics=%.3f ms/tick, visible=%d, culled=%d, neighbor candidates=%,d, neighbor links=%,d, est verts=%,d, emitted=%,d, bake hits=%,d, misses=%,d.",
                ROPES.size(), modeLabel, physicsMs,
                lastVisibleRopes, lastCulledRopes,
                lastNeighborCandidates, lastNeighborLinks, lastEstimatedVertices,
                lastVerticesEmitted, lastBakeHits, lastBakeMisses)), false);
        return ROPES.size();
    }

    private static void recordBenchFrameStart() {
        BenchRun bench = benchRun;
        if (bench == null) {
            return;
        }
        long now = System.nanoTime();
        if (bench.lastFrameNanos != 0L) {
            long dt = now - bench.lastFrameNanos;
            if (bench.seenFrames >= bench.warmupFrames && bench.sampleCount < bench.frameSamples.length) {
                bench.frameSamples[bench.sampleCount++] = dt;
                bench.visibleSum += lastVisibleRopes;
                bench.emittedSum += lastVerticesEmitted;
                bench.physicsNanosSum += lastPhysicsNanos;
            }
        }
        bench.seenFrames++;
        bench.lastFrameNanos = now;
    }

    private static void finishBenchFrame() {
        BenchRun bench = benchRun;
        if (bench == null) {
            return;
        }
        if (bench.sampleCount < bench.frameSamples.length) {
            return;
        }
        BenchPipeline pipeline = benchPipeline;
        if (pipeline != null) {
            pipeline.recordStepResult(bench);
            benchRun = null;
            pipeline.advance();
            return;
        }
        benchRun = null;
        reportBench(bench);
    }

    private static void reportBench(BenchRun bench) {
        long[] samples = Arrays.copyOf(bench.frameSamples, bench.sampleCount);
        Arrays.sort(samples);
        double avgMs = averageMs(samples);
        double p50Ms = percentileMs(samples, 0.50D);
        double p95Ms = percentileMs(samples, 0.95D);
        double p99Ms = percentileMs(samples, 0.99D);
        double worstMs = samples.length == 0 ? 0.0D : samples[samples.length - 1] / 1_000_000.0D;
        double avgVisible = bench.sampleCount == 0 ? 0.0D : bench.visibleSum / (double) bench.sampleCount;
        double avgEmitted = bench.sampleCount == 0 ? 0.0D : bench.emittedSum / (double) bench.sampleCount;
        double avgPhysicsMs = bench.sampleCount == 0 ? 0.0D : bench.physicsNanosSum / 1_000_000.0D / bench.sampleCount;
        String msg = String.format(
                "Super Lead bench result: scene=%s render=%s ropes=%,d samples=%d avg=%.2f ms (%.1f fps), p50=%.2f, p95=%.2f, p99=%.2f, worst=%.2f, visible=%.1f, emitted=%.0f, physics=%.3f ms/tick.",
                bench.modeLabel, bench.renderMode, bench.ropeCount, bench.sampleCount,
                avgMs, fps(avgMs), p50Ms, p95Ms, p99Ms, worstMs,
                avgVisible, avgEmitted, avgPhysicsMs);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(msg));
        }
        LOG.info(msg);
    }

    private static double averageMs(long[] samples) {
        if (samples.length == 0) {
            return 0.0D;
        }
        long sum = 0L;
        for (long sample : samples) {
            sum += sample;
        }
        return sum / 1_000_000.0D / samples.length;
    }

    private static double percentileMs(long[] samples, double p) {
        if (samples.length == 0) {
            return 0.0D;
        }
        int idx = Math.max(0, Math.min(samples.length - 1, (int) Math.ceil(samples.length * p) - 1));
        return samples[idx] / 1_000_000.0D;
    }

    private static double fps(double frameMs) {
        return frameMs <= 1.0e-9D ? 0.0D : 1000.0D / frameMs;
    }

    private static String renderModeLabel() {
        if (forceChunkMeshMode) {
            return localSectionMode ? "force_chunk_mesh_local_section" : "force_chunk_mesh";
        }
        return "vertex_consumer";
    }

    private static String modeLabel() {
        if (movingMode) {
            return collisionMode ? "moving" : "moving_nocollide";
        }
        if (forceChunkMeshMode) {
            return localSectionMode ? "static_chunk_local" : "static_chunk";
        }
        return "static";
    }

    private static boolean shouldSkipDynamicForStaticStress(StaticRopeChunkRegistry chunkReg, UUID ropeId, long tick) {
        RopeSectionSnapshot snapshot = chunkReg.snapshotForRender(ropeId, tick);
        if (snapshot == null)
            return false;
        return forceChunkMeshMode || chunkReg.isMeshAccepted(ropeId);
    }

    private static void publishChunkMeshSources(ClientLevel level) {
        StaticRopeChunkRegistry reg = StaticRopeChunkRegistry.get();
        if (level == null || ROPES.isEmpty()) {
            reg.clearStressSources(level);
            return;
        }
        List<StressSource> sources = new ArrayList<>(ROPES.size());
        for (StressRope rope : ROPES) {
            sources.add(new StressSource(rope.id(), rope.baseA(), rope.baseB()));
        }
        reg.publishStressSources(level, sources);
    }

    private static void clear() {
        StaticRopeChunkRegistry.get().clearStressSources(Minecraft.getInstance().level);
        ROPES.clear();
        movingMode = false;
        forceChunkMeshMode = false;
        localSectionMode = false;
        lastStepTick = Long.MIN_VALUE;
        lastPhysicsNanos = 0L;
        lastEstimatedVertices = 0;
        lastVisibleRopes = 0;
        lastCulledRopes = 0;
        lastNeighborCandidates = 0;
        lastNeighborLinks = 0;
    }

    private static int countNeighborLinks(Map<RopeSimulation, List<RopeSimulation>> neighbors) {
        int links = 0;
        for (List<RopeSimulation> list : neighbors.values()) {
            links += list.size();
        }
        return links;
    }

    private static Map<RopeSimulation, List<RopeSimulation>> buildNeighborMap(long tick) {
        lastNeighborCandidates = 0;
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < ROPES.size(); i++) {
            addToNeighborBuckets(buckets, i, ROPES.get(i).bounds(tick).inflate(NEIGHBOR_BOUNDS_MARGIN));
        }

        Map<RopeSimulation, List<RopeSimulation>> out = new HashMap<>();
        for (int i = 0; i < ROPES.size(); i++) {
            StressRope rope = ROPES.get(i);
            AABB query = rope.bounds(tick).inflate(NEIGHBOR_BOUNDS_MARGIN);
            HashSet<RopeSimulation> set = new HashSet<>();
            HashSet<Integer> seen = new HashSet<>();
            int minX = cell(query.minX);
            int maxX = cell(query.maxX);
            int minY = cell(query.minY);
            int maxY = cell(query.maxY);
            int minZ = cell(query.minZ);
            int maxZ = cell(query.maxZ);
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cy = minY; cy <= maxY; cy++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        List<Integer> candidates = buckets.get(cellKey(cx, cy, cz));
                        if (candidates == null)
                            continue;
                        for (int idx : candidates) {
                            if (idx == i || !seen.add(idx))
                                continue;
                            StressRope other = ROPES.get(idx);
                            AABB otherBounds = other.bounds(tick);
                            if (query.intersects(otherBounds)) {
                                lastNeighborCandidates++;
                                if (rope.sim().mightContact(other.sim(), NEIGHBOR_CONTACT_DISTANCE)) {
                                    set.add(other.sim());
                                }
                            }
                        }
                    }
                }
            }
            if (!set.isEmpty()) {
                out.put(rope.sim(), new ArrayList<>(set));
            }
        }
        return out;
    }

    private static void addToNeighborBuckets(Map<Long, List<Integer>> buckets, int index, AABB bounds) {
        int minX = cell(bounds.minX);
        int maxX = cell(bounds.maxX);
        int minY = cell(bounds.minY);
        int maxY = cell(bounds.maxY);
        int minZ = cell(bounds.minZ);
        int maxZ = cell(bounds.maxZ);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    buckets.computeIfAbsent(cellKey(cx, cy, cz), key -> new ArrayList<>()).add(index);
                }
            }
        }
    }

    private static int cell(double value) {
        return (int) Math.floor(value / NEIGHBOR_GRID_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        long px = (x + GRID_KEY_BIAS) & 0x1FFFFFL;
        long py = (y + GRID_KEY_BIAS) & 0x1FFFFFL;
        long pz = (z + GRID_KEY_BIAS) & 0x1FFFFFL;
        return (px << 42) | (py << 21) | pz;
    }

    private static int estimateVertices(RopeSimulation sim, Vec3 cameraPos) {
        int segments = Math.max(0, sim.nodeCount() - 1);
        int mid = sim.nodeCount() / 2;
        double dx = sim.renderX(mid) - cameraPos.x;
        double dy = sim.renderY(mid) - cameraPos.y;
        double dz = sim.renderZ(mid) - cameraPos.z;
        return dx * dx + dy * dy + dz * dz > 48.0D * 48.0D
                ? segments * 4
                : segments * 16;
    }

    private static int startPipeline(CommandSourceStack source) {
        if (ROPES.isEmpty()) {
            // No ropes active — spawn a default scene for the pipeline
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                source.sendFailure(Component.literal("No level loaded."));
                return 0;
            }
            benchPipeline = BenchPipeline.createDefault(mc.level);
            benchPipeline.startNextStep();
            source.sendSuccess(() -> Component.literal(
                    "Super Lead pipeline: started " + benchPipeline.steps.size()
                            + " steps × " + benchPipeline.iterations + " iterations."),
                    false);
            return 1;
        }
        source.sendFailure(Component.literal("Stop current stress test first."));
        return 0;
    }

    // ── Bench pipeline ────────────────────────────────────────────

    /**
     * One step in a bench pipeline. Describes what to run and which
     * {@link ClientTuning} knobs to flip before running.
     */
    private record PipelineStep(String label, boolean moving, boolean collision, boolean forceChunkMesh,
            int count, int frames, int warmup, double length, double spacing,
            Runnable beforeStep, Runnable afterStep) {
    }

    private static final class BenchPipeline {
        final List<PipelineStep> steps;
        final int iterations;
        final List<BenchRun>[] stepResults;
        int currentStep;
        int currentIteration;

        @SuppressWarnings("unchecked")
        BenchPipeline(List<PipelineStep> steps, int iterations) {
            this.steps = steps;
            this.iterations = iterations;
            this.stepResults = new List[steps.size()];
            for (int i = 0; i < steps.size(); i++) {
                stepResults[i] = new ArrayList<>();
            }
        }

        void advance() {
            currentStep++;
            if (currentStep >= steps.size()) {
                currentStep = 0;
                currentIteration++;
                if (currentIteration >= iterations) {
                    report();
                    clear();
                    benchPipeline = null;
                    return;
                }
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("Super Lead pipeline: iteration " + (currentIteration + 1)
                                + "/" + iterations));
            }
            // Apply after-step hook from previous step before starting next
            PipelineStep prev = steps.get((currentStep - 1 + steps.size()) % steps.size());
            if (prev.afterStep() != null)
                prev.afterStep().run();
            startNextStep();
        }

        void startNextStep() {
            PipelineStep step = steps.get(currentStep);
            if (step.beforeStep() != null)
                step.beforeStep().run();
            clearRopesOnly();
            spawnRopesForStep(step);
            benchRun = new BenchRun(step.label(), step.count(), step.frames(), step.warmup(),
                    renderModeLabel());
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("  [" + (currentStep + 1) + "/" + steps.size() + "] " + step.label()));
        }

        void recordStepResult(BenchRun run) {
            stepResults[currentStep].add(run);
        }

        void report() {
            Minecraft mc = Minecraft.getInstance();
            // ── Header ──
            mc.player.sendSystemMessage(Component.literal(
                    "══════ Super Lead Pipeline Report (" + iterations + " iterations) ══════"));
            StringBuilder header = new StringBuilder(
                    String.format("%-28s %8s %8s %8s %8s %8s %8s", "Step", "Avg ms", "P50", "P95", "P99",
                            "FPS", "Emitted"));
            mc.player.sendSystemMessage(Component.literal(header.toString()));

            // ── Per-step rows ──
            double[][] stepAvgs = new double[steps.size()][7]; // avg, p50, p95, p99, fps, emitted, visible
            for (int s = 0; s < steps.size(); s++) {
                List<BenchRun> results = stepResults[s];
                if (results.isEmpty()) {
                    mc.player.sendSystemMessage(
                            Component.literal(String.format("%-28s (no data)", steps.get(s).label())));
                    continue;
                }
                // Aggregate across iterations
                double sumAvg = 0, sumP50 = 0, sumP95 = 0, sumP99 = 0, sumFps = 0, sumEmitted = 0,
                        sumVisible = 0;
                int n = results.size();
                for (BenchRun r : results) {
                    long[] samples = Arrays.copyOf(r.frameSamples, r.sampleCount);
                    Arrays.sort(samples);
                    double avg = averageMs(samples);
                    sumAvg += avg;
                    sumP50 += percentileMs(samples, 0.50);
                    sumP95 += percentileMs(samples, 0.95);
                    sumP99 += percentileMs(samples, 0.99);
                    sumFps += fps(avg);
                    sumEmitted += r.sampleCount == 0 ? 0 : r.emittedSum / (double) r.sampleCount;
                    sumVisible += r.sampleCount == 0 ? 0 : r.visibleSum / (double) r.sampleCount;
                }
                double aAvg = sumAvg / n, aP50 = sumP50 / n, aP95 = sumP95 / n, aP99 = sumP99 / n;
                double aFps = sumFps / n, aEmitted = sumEmitted / n, aVisible = sumVisible / n;
                stepAvgs[s] = new double[] { aAvg, aP50, aP95, aP99, aFps, aEmitted, aVisible };
                mc.player.sendSystemMessage(Component.literal(String.format(
                        "%-28s %8.2f %8.2f %8.2f %8.2f %7.1f %8.0f",
                        steps.get(s).label(), aAvg, aP50, aP95, aP99, aFps, aEmitted)));
            }

            // ── Comparison ──
            if (steps.size() >= 2 && stepAvgs[0][0] > 0 && stepAvgs[1][0] > 0) {
                double ratio = stepAvgs[1][0] / stepAvgs[0][0];
                double deltaMs = stepAvgs[1][0] - stepAvgs[0][0];
                String faster = deltaMs < 0 ? steps.get(1).label() : steps.get(0).label();
                String slower = deltaMs < 0 ? steps.get(0).label() : steps.get(1).label();
                mc.player.sendSystemMessage(Component.literal(String.format(
                        "── Comparison: %s is %.2f× %s (%+.2f ms) ──",
                        faster, deltaMs < 0 ? (1.0 / ratio) : ratio, slower, Math.abs(deltaMs))));
            }
            mc.player.sendSystemMessage(Component.literal("═══════════════════════════════════════════"));

            // Cleanup
            for (PipelineStep step : steps) {
                if (step.afterStep() != null)
                    step.afterStep().run();
            }
            benchPipeline = null;
        }

        static BenchPipeline createDefault(ClientLevel level) {
            int count = 500;
            int frames = 500;
            int warmup = 40;
            int iterations = 5;
            double length = 8.0;
            double spacing = 0.35;
            List<PipelineStep> steps = new ArrayList<>();
            steps.add(new PipelineStep("static (chunk mesh off)",
                    false, false, false, count, frames, warmup, length, spacing,
                    () -> ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.setLocalFromString("false"),
                    () -> ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.setLocalFromString("true")));
            steps.add(new PipelineStep("static (chunk mesh on)",
                    false, false, false, count, frames, warmup, length, spacing,
                    () -> ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.setLocalFromString("true"),
                    () -> ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.setLocalFromString("false")));
            return new BenchPipeline(steps, iterations);
        }
    }

    private static void clearRopesOnly() {
        StaticRopeChunkRegistry.get().clearStressSources(Minecraft.getInstance().level);
        ROPES.clear();
        movingMode = false;
        collisionMode = true;
        forceChunkMeshMode = false;
        localSectionMode = false;
    }

    private static void spawnRopesForStep(PipelineStep step) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null)
            return;
        Camera camera = mc.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        var left = camera.leftVector();
        var up = camera.upVector();
        double fx = forward.x(), fy = forward.y(), fz = forward.z();
        double rx = -left.x(), ry = -left.y(), rz = -left.z();
        double ux = up.x(), uy = up.y(), uz = up.z();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(step.count())));
        int rows = (step.count() + cols - 1) / cols;
        double length = step.length();
        double spacing = step.spacing();
        Vec3 origin = camera.position().add(
                fx * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                fy * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                fz * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D));
        for (int i = 0; i < step.count(); i++) {
            int row = i / cols;
            int col = i % cols;
            double gridX = (col - (cols - 1) * 0.5D) * spacing;
            double gridY = ((rows - 1) * 0.5D - row) * spacing;
            double mx = origin.x + fx * gridX + ux * gridY;
            double my = origin.y + fy * gridX + uy * gridY;
            double mz = origin.z + fz * gridX + uz * gridY;
            Vec3 a = new Vec3(mx - rx * length * 0.5D, my - ry * length * 0.5D, mz - rz * length * 0.5D);
            Vec3 b = new Vec3(mx + rx * length * 0.5D, my + ry * length * 0.5D, mz + rz * length * 0.5D);
            RopeSimulation sim = new RopeSimulation(a, b, 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L);
            sim.resetCatenary(a, b, 0.035D);
            ROPES.add(new StressRope(UUID.randomUUID(), sim, a, b, rx, ry, rz, i * 0.618, 0, 0, false));
        }
        movingMode = step.moving();
        collisionMode = step.collision();
        forceChunkMeshMode = step.forceChunkMesh();
        if (!step.moving()) {
            publishChunkMeshSources(level);
        }
        lastStepTick = Long.MIN_VALUE;
        lastPhysicsNanos = 0L;
    }

    private static final class BenchRun {
        final String modeLabel;
        final int ropeCount;
        final int warmupFrames;
        final long[] frameSamples;
        final String renderMode;
        int seenFrames;
        int sampleCount;
        long lastFrameNanos;
        long visibleSum;
        long emittedSum;
        long physicsNanosSum;

        BenchRun(String modeLabel, int ropeCount, int sampleFrames, int warmupFrames, String renderMode) {
            this.modeLabel = modeLabel;
            this.ropeCount = ropeCount;
            this.warmupFrames = warmupFrames;
            this.frameSamples = new long[sampleFrames];
            this.renderMode = renderMode;
        }
    }

}
