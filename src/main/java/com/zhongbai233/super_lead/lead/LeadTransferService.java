package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.lead.cargo.CargoManifestData;
import com.zhongbai233.super_lead.lead.integration.ae2.AE2NetworkBridge;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismChemicalBridge;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismHeatBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Server-side transfer engine for non-redstone rope networks.
 *
 * <p>
 * {@link SuperLeadNetwork} keeps the public gameplay facade; this helper owns
 * path search, round-robin cursors and resource filter checks for ITEM, FLUID,
 * PRESSURIZED, THERMAL and AE_NETWORK connections. Keeping this state here
 * makes
 * the path-transfer rules easier to audit without mixing them into placement,
 * cutting and rendering-sync code.
 */
final class LeadTransferService {
    private static final Map<BlockPos, Integer> ITEM_RR_CURSOR = new HashMap<>();
    private static final Map<BlockPos, Integer> FLUID_RR_CURSOR = new HashMap<>();
    private static final Map<BlockPos, Integer> PRESSURIZED_RR_CURSOR = new HashMap<>();
    private static final int MAX_TRANSFER_SEARCH_DEPTH = 64;
    private static final int ITEM_PULSE_DURATION_TICKS = 10;

    /**
     * Per-dimension cached generation and pre-built indexes for each transfer kind.
     * When the SavedData generation hasn't changed the index is reused,
     * avoiding a full O(N) rebuild every transfer tick.
     */
    private static final Map<ServerLevel, Map<LeadKind, Long>> CACHED_GENERATION = new HashMap<>();
    private static final Map<ServerLevel, Map<LeadKind, Map<BlockPos, List<LeadConnection>>>> CACHED_ROPES_AT = new HashMap<>();
    private static final Map<ServerLevel, Map<LeadKind, Map<BlockPos, List<LeadConnection>>>> CACHED_STARTS_BY_SOURCE = new HashMap<>();

    private LeadTransferService() {
    }

    private static Long cachedGeneration(ServerLevel level, LeadKind kind) {
        Map<LeadKind, Long> perKind = CACHED_GENERATION.get(level);
        return perKind == null ? null : perKind.get(kind);
    }

    private static Map<BlockPos, List<LeadConnection>> cachedRopesAt(ServerLevel level, LeadKind kind) {
        Map<LeadKind, Map<BlockPos, List<LeadConnection>>> perKind = CACHED_ROPES_AT.get(level);
        return perKind == null ? null : perKind.get(kind);
    }

    private static Map<BlockPos, List<LeadConnection>> cachedStartsBySource(ServerLevel level, LeadKind kind) {
        Map<LeadKind, Map<BlockPos, List<LeadConnection>>> perKind = CACHED_STARTS_BY_SOURCE.get(level);
        return perKind == null ? null : perKind.get(kind);
    }

    private static void cacheGenerationAndIndexes(ServerLevel level, LeadKind kind, long gen,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, List<LeadConnection>> startsBySource) {
        CACHED_GENERATION.computeIfAbsent(level, k -> new java.util.EnumMap<>(LeadKind.class)).put(kind, gen);
        CACHED_ROPES_AT.computeIfAbsent(level, k -> new java.util.EnumMap<>(LeadKind.class)).put(kind, ropesAt);
        CACHED_STARTS_BY_SOURCE.computeIfAbsent(level, k -> new java.util.EnumMap<>(LeadKind.class)).put(kind,
                startsBySource);
    }

    static void tickItem(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L) {
            return;
        }
        tickTransfer(level, LeadKind.ITEM, Capabilities.Item.BLOCK, ITEM_RR_CURSOR,
                rope -> Math.min(64, 1 << Math.min(Config.itemTierMax(), rope.tier())));
    }

    static void tickFluid(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L) {
            return;
        }
        tickTransfer(level, LeadKind.FLUID, Capabilities.Fluid.BLOCK, FLUID_RR_CURSOR,
                rope -> Config.fluidBucketAmount() * (1 << Math.min(Config.fluidTierMax(), rope.tier())));
    }

    static void tickPressurized(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L || !isMekanismLoaded()) {
            return;
        }
        tickPressurizedTransfer(level);
    }

    /**
     * Invalidate all cached indexes. Called when ropes are added/removed
     * from outside the tick path (e.g. by player interaction).
     */
    static void invalidateCaches() {
        CACHED_GENERATION.clear();
        CACHED_ROPES_AT.clear();
        CACHED_STARTS_BY_SOURCE.clear();
        ITEM_RR_CURSOR.clear();
        FLUID_RR_CURSOR.clear();
        PRESSURIZED_RR_CURSOR.clear();
    }

    /**
     * Invalidate cached indexes for a specific dimension.
     */
    static void invalidateCachesFor(ServerLevel level) {
        CACHED_GENERATION.remove(level);
        CACHED_ROPES_AT.remove(level);
        CACHED_STARTS_BY_SOURCE.remove(level);
    }

    static void discardLevelState(ServerLevel level) {
        invalidateCachesFor(level);
        if (isAe2Loaded()) {
            AE2NetworkBridge.clearDimension(level.dimension());
        }
    }

    static void tickThermal(ServerLevel level) {
        if (!isMekanismLoaded()) {
            return;
        }

        List<LeadConnection> thermalConnections = SuperLeadSavedData.get(level).connectionsOfKindFast(LeadKind.THERMAL);
        if (thermalConnections.isEmpty()) {
            return;
        }

        MekanismHeatBridge.HandlerCache heatHandlers = new MekanismHeatBridge.HandlerCache();
        int size = thermalConnections.size();
        boolean[] visited = new boolean[size];
        Map<BlockPos, List<Integer>> connectionsByPos = indexConnectionsByBlockPos(thermalConnections, size);
        for (int i = 0; i < thermalConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = thermalConnections.get(component.get(cursor));
                addUnvisitedNeighborsByPos(current.from().pos(), connectionsByPos, visited, component);
                addUnvisitedNeighborsByPos(current.to().pos(), connectionsByPos, visited, component);
            }

            balanceThermalComponent(level, component, thermalConnections, heatHandlers);
        }
    }

    static void tickAeNetwork(ServerLevel level) {
        if (!isAe2Loaded()) {
            return;
        }
        List<LeadConnection> aeConnections = SuperLeadSavedData.get(level).connectionsOfKindFast(LeadKind.AE_NETWORK);
        AE2NetworkBridge.reconcile(level, aeConnections);
    }

    private static void tickPressurizedTransfer(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);

        List<LeadConnection> pressurizedConnections = data.connectionsOfKindFast(LeadKind.PRESSURIZED);
        if (pressurizedConnections.isEmpty()) {
            return;
        }

        MekanismChemicalBridge.HandlerCache chemicalHandlers = new MekanismChemicalBridge.HandlerCache();
        Map<BlockPos, List<LeadConnection>> ropesAt;
        Map<BlockPos, List<LeadConnection>> startsBySource;
        long currentGen = data.generation();
        LeadKind kind = LeadKind.PRESSURIZED;
        Long cachedGen = cachedGeneration(level, kind);
        if (cachedGen != null && cachedGen.longValue() == currentGen) {
            ropesAt = cachedRopesAt(level, kind);
            startsBySource = cachedStartsBySource(level, kind);
        } else {
            int size = pressurizedConnections.size();
            ropesAt = new HashMap<>(size * 2);
            startsBySource = new HashMap<>(Math.max(16, size / 4));
            for (LeadConnection c : pressurizedConnections) {
                BlockPos a = c.from().pos().immutable();
                BlockPos b = c.to().pos().immutable();
                ropesAt.computeIfAbsent(a, k -> new ArrayList<>(2)).add(c);
                if (!a.equals(b)) {
                    ropesAt.computeIfAbsent(b, k -> new ArrayList<>(2)).add(c);
                }

                LeadAnchor src = c.extractSource();
                if (src != null) {
                    startsBySource.computeIfAbsent(src.pos().immutable(), k -> new ArrayList<>(2)).add(c);
                }
            }
            cacheGenerationAndIndexes(level, kind, currentGen, ropesAt, startsBySource);
        }

        for (Map.Entry<BlockPos, List<LeadConnection>> entry : startsBySource.entrySet()) {
            BlockPos sourcePos = entry.getKey();
            List<LeadConnection> ropes = entry.getValue();
            if (ropes.isEmpty()) {
                continue;
            }

            int n = ropes.size();
            int start = PRESSURIZED_RR_CURSOR.getOrDefault(sourcePos, 0) % n;

            for (int step = 0; step < n; step++) {
                int idx = (start + step) % n;
                LeadConnection rope = ropes.get(idx);
                LeadAnchor sourceAnchor = rope.extractSource();
                LeadAnchor firstFar = rope.extractTarget();
                if (sourceAnchor == null || firstFar == null
                        || !chemicalHandlers.has(level, sourceAnchor)) {
                    continue;
                }

                long batch = pressurizedBatch(rope);
                List<PathStep> path = new ArrayList<>();
                List<RrChoice> rrChoices = new ArrayList<>();
                Set<UUID> visited = new HashSet<>();
                visited.add(rope.id());
                path.add(new PathStep(rope, rope.extractAnchor() == 2));

                if (walkAndTransferPressurized(level, sourceAnchor, batch, firstFar, ropesAt,
                        PRESSURIZED_RR_CURSOR, chemicalHandlers, visited, path, rrChoices, 1)) {
                    long now = level.getGameTime();
                    for (int i = 0; i < path.size(); i++) {
                        PathStep s = path.get(i);
                        long startTick = now + (long) i * ITEM_PULSE_DURATION_TICKS;
                        SuperLeadPayloads.sendItemPulse(level,
                                new ItemPulse(s.rope.id(), s.reverse, startTick, ITEM_PULSE_DURATION_TICKS));
                    }
                    PRESSURIZED_RR_CURSOR.put(sourcePos, (idx + 1) % n);
                    for (RrChoice rc : rrChoices) {
                        PRESSURIZED_RR_CURSOR.put(rc.knot, (rc.idx + 1) % rc.n);
                    }
                    break;
                }
            }
        }
    }

    private static long pressurizedBatch(LeadConnection rope) {
        int tier = Math.min(Config.pressurizedTierMax(), rope.tier());
        long multiplier = 1L << Math.min(30, tier);
        return Math.max(1L, Math.min(Integer.MAX_VALUE, (long) Config.pressurizedBatchAmount() * multiplier));
    }

    private static void balanceThermalComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> thermalConnections, MekanismHeatBridge.HandlerCache heatHandlers) {
        List<LeadAnchor> endpoints = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();
        double componentRate = 0.0D;
        for (int index : component) {
            LeadConnection connection = thermalConnections.get(index);
            componentRate = Math.min(1.0e12D,
                    componentRate + Config.thermalBaseTransfer() * connection.speedMultiplier());
            addThermalEndpoint(level, connection.from(), endpoints, seenAnchors, heatHandlers);
            addThermalEndpoint(level, connection.to(), endpoints, seenAnchors, heatHandlers);
        }
        if (endpoints.size() < 2 || componentRate <= 0.0D) {
            return;
        }

        // Apply componentRate as a per-tick total cap, not per-pair.
        // Without this N endpoints would allow N*(N-1)/2 × componentRate
        // heat transfer per tick instead of the intended componentRate total.
        double remaining = componentRate;
        for (int i = 0; i < endpoints.size() && remaining > 0.0D; i++) {
            for (int j = i + 1; j < endpoints.size() && remaining > 0.0D; j++) {
                double moved = heatHandlers.balance(level, endpoints.get(i), endpoints.get(j), remaining);
                if (moved > 0.0D) {
                    remaining -= moved;
                }
            }
        }
    }

    private static void addThermalEndpoint(ServerLevel level, LeadAnchor anchor, List<LeadAnchor> endpoints,
            Set<LeadAnchor> seenAnchors, MekanismHeatBridge.HandlerCache heatHandlers) {
        if (seenAnchors.add(anchor) && heatHandlers.has(level, anchor)) {
            endpoints.add(anchor);
        }
    }

    private static boolean isMekanismLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded("mekanism");
    }

    private static boolean isAe2Loaded() {
        return net.neoforged.fml.ModList.get().isLoaded("ae2");
    }

    private static <R extends Resource> void tickTransfer(
            ServerLevel level,
            LeadKind kind,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            Map<BlockPos, Integer> rrCursor,
            java.util.function.ToIntFunction<LeadConnection> batchOf) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> transferConnections = data.connectionsOfKindFast(kind);
        if (transferConnections.isEmpty()) {
            return;
        }

        // Index every rope of this kind by both endpoint positions so we can walk
        // through fence-knot junctions where multiple ropes share a BlockPos.
        // Rebuild only when the SavedData generation has changed.
        ResourceHandlerCache<R> handlers = new ResourceHandlerCache<>();
        Map<BlockPos, List<LeadConnection>> ropesAt;
        Map<BlockPos, List<LeadConnection>> startsBySource;
        long currentGen = data.generation();
        Long cachedGen = cachedGeneration(level, kind);
        if (cachedGen != null && cachedGen.longValue() == currentGen) {
            ropesAt = cachedRopesAt(level, kind);
            startsBySource = cachedStartsBySource(level, kind);
        } else {
            int size = transferConnections.size();
            ropesAt = new HashMap<>(size * 2);
            startsBySource = new HashMap<>(Math.max(16, size / 4));
            for (LeadConnection c : transferConnections) {
                BlockPos a = c.from().pos().immutable();
                BlockPos b = c.to().pos().immutable();
                ropesAt.computeIfAbsent(a, k -> new ArrayList<>(2)).add(c);
                if (!a.equals(b)) {
                    ropesAt.computeIfAbsent(b, k -> new ArrayList<>(2)).add(c);
                }

                if (c.extractAnchor() == 0) {
                    continue;
                }
                LeadAnchor src = c.extractSource();
                if (src == null) {
                    continue;
                }
                startsBySource.computeIfAbsent(src.pos().immutable(), k -> new ArrayList<>(2)).add(c);
            }
            cacheGenerationAndIndexes(level, kind, currentGen, ropesAt, startsBySource);
        }

        for (Map.Entry<BlockPos, List<LeadConnection>> entry : startsBySource.entrySet()) {
            BlockPos sourcePos = entry.getKey();
            List<LeadConnection> ropes = entry.getValue();
            if (ropes.isEmpty()) {
                continue;
            }

            int n = ropes.size();
            int start = rrCursor.getOrDefault(sourcePos, 0) % n;

            for (int step = 0; step < n; step++) {
                int idx = (start + step) % n;
                LeadConnection rope = ropes.get(idx);
                LeadAnchor sourceAnchor = rope.extractSource();
                LeadAnchor firstFar = rope.extractTarget();
                if (sourceAnchor == null || firstFar == null) {
                    continue;
                }

                ResourceHandler<R> sourceHandler = handlers.get(level, sourceAnchor, cap);
                if (sourceHandler == null) {
                    continue;
                }

                int batch = Math.max(1, batchOf.applyAsInt(rope));

                List<PathStep> path = new ArrayList<>();
                List<RrChoice> rrChoices = new ArrayList<>();
                Set<UUID> visited = new HashSet<>();
                visited.add(rope.id());
                path.add(new PathStep(rope, rope.extractAnchor() == 2));

                if (walkAndTransfer(level, cap, handlers, sourceHandler, batch, firstFar, ropesAt, rrCursor, visited,
                        path, rrChoices, 1)) {
                    long now = level.getGameTime();
                    for (int i = 0; i < path.size(); i++) {
                        PathStep s = path.get(i);
                        long startTick = now + (long) i * ITEM_PULSE_DURATION_TICKS;
                        SuperLeadPayloads.sendItemPulse(level,
                                new ItemPulse(s.rope.id(), s.reverse, startTick, ITEM_PULSE_DURATION_TICKS));
                    }
                    rrCursor.put(sourcePos, (idx + 1) % n);
                    for (RrChoice rc : rrChoices) {
                        rrCursor.put(rc.knot, (rc.idx + 1) % rc.n);
                    }
                    break;
                }
            }
        }
    }

    private record PathStep(LeadConnection rope, boolean reverse) {
    }

    private record RrChoice(BlockPos knot, int idx, int n) {
    }

    /**
     * DFS through the rope graph starting at {@code current}. If {@code current}
     * hosts a handler, attempt a single transfer from {@code sourceHandler}.
     * Otherwise treat {@code current.pos()} as a knot and round-robin through its
     * unvisited ropes. Returns true only when a transfer was actually committed.
     */
    private static <R extends Resource> boolean walkAndTransfer(
            ServerLevel level,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            ResourceHandlerCache<R> handlers,
            ResourceHandler<R> sourceHandler,
            int batch,
            LeadAnchor current,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, Integer> rrCursor,
            Set<UUID> visited,
            List<PathStep> path,
            List<RrChoice> rrChoices,
            int depth) {
        if (depth > MAX_TRANSFER_SEARCH_DEPTH) {
            return false;
        }
        ResourceHandler<R> h = handlers.get(level, current, cap);
        if (h != null) {
            return transferOne(sourceHandler, h, batch, resource -> pathAllowsResource(path, resource));
        }

        BlockPos knot = current.pos().immutable();
        List<LeadConnection> all = ropesAt.getOrDefault(knot, List.of());
        List<LeadConnection> branches = new ArrayList<>();
        for (LeadConnection b : all) {
            if (!visited.contains(b.id())) {
                branches.add(b);
            }
        }
        if (branches.isEmpty()) {
            return false;
        }

        int n = branches.size();
        int rrStart = rrCursor.getOrDefault(knot, 0) % n;
        for (int step = 0; step < n; step++) {
            int idx = (rrStart + step) % n;
            LeadConnection branch = branches.get(idx);
            boolean enteredFromSide = branch.from().pos().equals(knot);
            LeadAnchor far = enteredFromSide ? branch.to() : branch.from();
            boolean reverse = !enteredFromSide;

            visited.add(branch.id());
            path.add(new PathStep(branch, reverse));
            rrChoices.add(new RrChoice(knot, idx, n));

            if (walkAndTransfer(level, cap, handlers, sourceHandler, batch, far, ropesAt, rrCursor, visited, path,
                    rrChoices, depth + 1)) {
                return true;
            }

            path.remove(path.size() - 1);
            rrChoices.remove(rrChoices.size() - 1);
            visited.remove(branch.id());
        }
        return false;
    }

    private static boolean walkAndTransferPressurized(
            ServerLevel level,
            LeadAnchor sourceAnchor,
            long batch,
            LeadAnchor current,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, Integer> rrCursor,
            MekanismChemicalBridge.HandlerCache chemicalHandlers,
            Set<UUID> visited,
            List<PathStep> path,
            List<RrChoice> rrChoices,
            int depth) {
        if (depth > MAX_TRANSFER_SEARCH_DEPTH) {
            return false;
        }
        if (chemicalHandlers.has(level, current)) {
            return chemicalHandlers.transferOne(level, sourceAnchor, current, batch,
                    MekanismChemicalBridge.ChemicalFilter.ANY, pathConnections(path)) > 0L;
        }

        BlockPos knot = current.pos().immutable();
        List<LeadConnection> all = ropesAt.getOrDefault(knot, List.of());
        List<LeadConnection> branches = new ArrayList<>();
        for (LeadConnection b : all) {
            if (!visited.contains(b.id())) {
                branches.add(b);
            }
        }
        if (branches.isEmpty()) {
            return false;
        }

        int n = branches.size();
        int rrStart = rrCursor.getOrDefault(knot, 0) % n;
        for (int step = 0; step < n; step++) {
            int idx = (rrStart + step) % n;
            LeadConnection branch = branches.get(idx);
            boolean enteredFromSide = branch.from().pos().equals(knot);
            LeadAnchor far = enteredFromSide ? branch.to() : branch.from();
            boolean reverse = !enteredFromSide;

            visited.add(branch.id());
            path.add(new PathStep(branch, reverse));
            rrChoices.add(new RrChoice(knot, idx, n));

            if (walkAndTransferPressurized(level, sourceAnchor, batch, far, ropesAt, rrCursor,
                    chemicalHandlers, visited, path, rrChoices, depth + 1)) {
                return true;
            }

            path.remove(path.size() - 1);
            rrChoices.remove(rrChoices.size() - 1);
            visited.remove(branch.id());
        }
        return false;
    }

    private static <R extends Resource> ResourceHandler<R> handler(
            ServerLevel level, LeadAnchor anchor, BlockCapability<ResourceHandler<R>, Direction> cap) {
        ResourceHandler<R> h = level.getCapability(cap, anchor.pos(), anchor.face());
        if (h == null) {
            h = level.getCapability(cap, anchor.pos(), null);
        }
        return h;
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static List<LeadConnection> pathConnections(List<PathStep> path) {
        List<LeadConnection> connections = new ArrayList<>(path.size());
        for (PathStep step : path) {
            connections.add(step.rope());
        }
        return connections;
    }

    private static <R extends Resource> boolean pathAllowsResource(List<PathStep> path, R resource) {
        for (PathStep step : path) {
            if (!connectionAllowsResource(step.rope(), resource)) {
                return false;
            }
        }
        return true;
    }

    private static <R extends Resource> boolean connectionAllowsResource(LeadConnection connection, R resource) {
        if (connection == null || resource == null || resource.isEmpty() || connection.attachments().isEmpty()) {
            return true;
        }
        if (resource instanceof ItemResource itemResource) {
            return connectionAllowsItemResource(connection, itemResource);
        }
        if (resource instanceof FluidResource fluidResource) {
            return connectionAllowsFluidResource(connection, fluidResource);
        }
        return true;
    }

    private static boolean connectionAllowsItemResource(LeadConnection connection, ItemResource resource) {
        boolean hasItemFilter = false;
        ItemStack candidate = null;
        for (RopeAttachment attachment : connection.attachments()) {
            ItemStack sample = attachment.stack();
            if (sample.isEmpty()) {
                continue;
            }
            if (CargoManifestData.isManifestStack(sample)) {
                hasItemFilter = true;
                if (candidate == null) {
                    candidate = resource.toStack(1);
                }
                if (CargoManifestData.matches(sample, candidate)) {
                    return true;
                }
                continue;
            }
            hasItemFilter = true;
            if (resource.matches(sample)) {
                return true;
            }
        }
        return !hasItemFilter;
    }

    private static boolean connectionAllowsFluidResource(LeadConnection connection, FluidResource resource) {
        boolean hasFluidFilter = false;
        for (RopeAttachment attachment : connection.attachments()) {
            var sample = FluidUtil.getFirstStackContained(attachment.stack());
            if (sample.isEmpty()) {
                continue;
            }
            hasFluidFilter = true;
            if (resource.matches(sample)) {
                return true;
            }
        }
        return !hasFluidFilter;
    }

    /**
     * Try to extract up to {@code batch} units from any source slot whose contents
     * the target accepts entirely. Returns true if anything was moved.
     */
    private static <R extends Resource> boolean transferOne(ResourceHandler<R> source, ResourceHandler<R> target,
            int batch, Predicate<R> filter) {
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            R res = source.getResource(slot);
            if (res == null || res.isEmpty()) {
                continue;
            }
            if (filter != null && !filter.test(res)) {
                continue;
            }
            long avail = source.getAmountAsLong(slot);
            if (avail <= 0L) {
                continue;
            }

            int requested = (int) Math.min(batch, avail);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = source.extract(slot, res, requested, tx);
                if (extracted <= 0) {
                    continue;
                }
                int inserted = target.insert(res, extracted, tx);
                if (inserted != extracted) {
                    continue;
                }
                tx.commit();
                return true;
            }
        }
        return false;
    }

    private static Map<BlockPos, List<Integer>> indexConnectionsByBlockPos(List<LeadConnection> connections, int size) {
        Map<BlockPos, List<Integer>> byPos = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            LeadConnection connection = connections.get(i);
            addBlockPosIndex(byPos, connection.from().pos(), i);
            if (!connection.to().pos().equals(connection.from().pos())) {
                addBlockPosIndex(byPos, connection.to().pos(), i);
            }
        }
        return byPos;
    }

    private static void addBlockPosIndex(Map<BlockPos, List<Integer>> byPos, BlockPos pos, int index) {
        byPos.computeIfAbsent(pos.immutable(), key -> new ArrayList<>()).add(index);
    }

    private static void addUnvisitedNeighborsByPos(BlockPos pos, Map<BlockPos, List<Integer>> byPos,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byPos.get(pos);
        if (neighbors != null) {
            for (int index : neighbors) {
                if (!visited[index]) {
                    visited[index] = true;
                    component.add(index);
                }
            }
        }
    }

    private static final class ResourceHandlerCache<R extends Resource> {
        private final Map<LeadAnchor, ResourceHandler<R>> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        private ResourceHandler<R> get(ServerLevel level, LeadAnchor anchor,
                BlockCapability<ResourceHandler<R>, Direction> cap) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            ResourceHandler<R> cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            ResourceHandler<R> found = handler(level, key, cap);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }
}
