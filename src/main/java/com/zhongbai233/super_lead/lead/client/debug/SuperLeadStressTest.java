package com.zhongbai233.super_lead.lead.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import com.zhongbai233.super_lead.lead.client.chunk.StressSource;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeJob;
import com.zhongbai233.super_lead.lead.client.render.RopeVisibility;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.ArrayList;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
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

    private static final List<StressRope> ROPES = new ArrayList<>();
    private static boolean movingMode;
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

    private SuperLeadStressTest() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("superlead_stress")
                .then(modeCommand("static", false))
                .then(modeCommand("moving", true))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
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

        if (tick != lastStepTick) {
            lastStepTick = tick;
            if (movingMode) {
                long start = System.nanoTime();
                Map<RopeSimulation, List<RopeSimulation>> neighbors = buildNeighborMap(tick);
                lastNeighborLinks = countNeighborLinks(neighbors);
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
            // Pipeline parity: when chunk-mesh has claimed this rope, the section mesh
            if (chunkReg.isClaimed(rope.id()))
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
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeCommand(String name, boolean moving) {
        return Commands.literal(name)
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                        .executes(ctx -> start(ctx, moving,
                                IntegerArgumentType.getInteger(ctx, "count"),
                                DEFAULT_LENGTH, DEFAULT_SPACING, 0,
                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                        .then(Commands.argument("length", DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                .executes(ctx -> start(ctx, moving,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        DoubleArgumentType.getDouble(ctx, "length"),
                                        DEFAULT_SPACING, 0,
                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                .then(Commands.argument("spacing", DoubleArgumentType.doubleArg(0.05D, 16.0D))
                                        .executes(ctx -> start(ctx, moving,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                DoubleArgumentType.getDouble(ctx, "length"),
                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                0,
                                                DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                        .then(Commands.argument("columns", IntegerArgumentType.integer(1, MAX_COUNT))
                                                .executes(ctx -> start(ctx, moving,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                        IntegerArgumentType.getInteger(ctx, "columns"),
                                                        DEFAULT_AMPLITUDE, DEFAULT_SPEED))
                                                .then(Commands
                                                        .argument("amplitude", DoubleArgumentType.doubleArg(0.0D, 8.0D))
                                                        .executes(ctx -> start(ctx, moving,
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                DoubleArgumentType.getDouble(ctx, "length"),
                                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                IntegerArgumentType.getInteger(ctx, "columns"),
                                                                DoubleArgumentType.getDouble(ctx, "amplitude"),
                                                                DEFAULT_SPEED))
                                                        .then(Commands
                                                                .argument("speed",
                                                                        DoubleArgumentType.doubleArg(0.0D, 4.0D))
                                                                .executes(ctx -> start(ctx, moving,
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        DoubleArgumentType.getDouble(ctx, "length"),
                                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                        IntegerArgumentType.getInteger(ctx, "columns"),
                                                                        DoubleArgumentType.getDouble(ctx, "amplitude"),
                                                                        DoubleArgumentType.getDouble(ctx,
                                                                                "speed")))))))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean moving, int count,
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
        // Ropes run along camera-right; the grid spreads along camera-forward and
        // camera-up so
        // adjacent ropes are parallel lanes and the test measures normal render/solver
        // cost.
        Vec3 origin = camera.position().add(
                fx * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                fy * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D),
                fz * (SPAWN_DISTANCE + (cols - 1) * spacing * 0.5D));
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
        publishChunkMeshSources(level);
        lastStepTick = Long.MIN_VALUE;
        lastPhysicsNanos = 0L;
        lastEstimatedVertices = 0;
        lastVisibleRopes = 0;
        lastCulledRopes = 0;
        lastNeighborCandidates = 0;
        lastNeighborLinks = 0;

        source.sendSuccess(() -> Component.literal(String.format(
                "Super Lead stress: spawned %,d %s ropes, length=%.2f, spacing=%.2f, columns=%d. Static is render-only; moving measures solver cost. Use status or stop.",
                count, moving ? "moving" : "static", length, spacing, cols)), false);
        return count;
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
        source.sendSuccess(() -> Component.literal(String.format(
                "Super Lead stress: %,d ropes, mode=%s, last physics=%.3f ms/tick, visible=%d, culled=%d, neighbor candidates=%,d, neighbor links=%,d, est verts=%,d, emitted=%,d, bake hits=%,d, misses=%,d.",
                ROPES.size(), movingMode ? "moving" : "static", physicsMs,
                lastVisibleRopes, lastCulledRopes,
                lastNeighborCandidates, lastNeighborLinks, lastEstimatedVertices,
                lastVerticesEmitted, lastBakeHits, lastBakeMisses)), false);
        return ROPES.size();
    }

    private static void publishChunkMeshSources(ClientLevel level) {
        StaticRopeChunkRegistry reg = StaticRopeChunkRegistry.get();
        if (level == null || movingMode || ROPES.isEmpty()) {
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

}
