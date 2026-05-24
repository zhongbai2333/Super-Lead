package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.data.BlockPropertyRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Redstone and energy service for rope networks.
 *
 * <p>
 * This class owns the redstone recursion guard used by the signal mixin, the
 * per-tick redstone component solver, and energy transfer/power-stickiness
 * state.
 * Keeping these systems together is important because energy traversal reuses
 * the
 * same signal-bridge semantics as redstone ropes.
 */
final class LeadSignalService {
    /**
     * Sticky "recently active" deadline per ENERGY connection, keyed by game-time
     * tick at which the visual powered state expires.
     */
    private static final Map<UUID, Long> ENERGY_ACTIVE_UNTIL = new HashMap<>();
    private static final long ENERGY_STICKY_TICKS = 40L;
    private static final ThreadLocal<Boolean> SUPPRESS_LEAD_SIGNALS = ThreadLocal.withInitial(() -> false);
    /**
     * Per-dimension BlockPos -> redstone connection list index for O(1) lookup
     * in {@link #leadSignal}. Rebuilt lazily when the SavedData generation changes.
     */
    private static final Map<ServerLevel, Map<BlockPos, List<LeadConnection>>> REDSTONE_POS_INDEX = new HashMap<>();
    private static final Map<ServerLevel, Long> REDSTONE_POS_INDEX_GEN = new HashMap<>();

    private LeadSignalService() {
    }

    static void tickRedstone(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> redstoneConnections = data.connectionsOfKindFast(LeadKind.REDSTONE);
        if (redstoneConnections.isEmpty()) {
            invalidateRedstonePosIndex(level);
            return;
        }

        int size = redstoneConnections.size();
        boolean changed = false;
        boolean[] visited = new boolean[size];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(redstoneConnections, size);
        // Rebuild the BlockPos index for leadSignal while we already iterate
        rebuildRedstonePosIndex(level, redstoneConnections);
        RedstoneTraversalCache redstoneCache = new RedstoneTraversalCache(level);
        for (int i = 0; i < redstoneConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;
            int power = 0;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = redstoneConnections.get(component.get(cursor));
                power = Math.max(power, redstoneCache.externalSignalAt(current.from()));
                power = Math.max(power, redstoneCache.externalSignalAt(current.to()));

                addUnvisitedNeighborsBridge(redstoneCache, current.from(), connectionsByAnchor, visited, component);
                addUnvisitedNeighborsBridge(redstoneCache, current.to(), connectionsByAnchor, visited, component);
            }

            final int componentPower = power;

            // Collect connections whose power actually changed before updating,
            // so we can rebuild the REDSTONE_POS_INDEX with fresh data before
            // notifying neighbors. Otherwise getSignal() queries inside
            // updateNeighborsAt would read stale LeadConnection objects from
            // the index and return the old (non-zero) power, causing the
            // signal to "stick" until the next block update.
            List<LeadConnection> changedInComponent = new ArrayList<>();
            for (int index : component) {
                LeadConnection connection = redstoneConnections.get(index);
                if (connection.power() == componentPower) {
                    continue;
                }

                LeadConnection updated = connection.withPower(componentPower);
                changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(componentPower),
                        false);
                changedInComponent.add(updated);
            }

            if (!changedInComponent.isEmpty()) {
                // Rebuild the pos index so leadSignalServer returns the new
                // power when neighbor blocks re-evaluate during
                // notifyRedstoneChange below.
                rebuildRedstonePosIndex(level, data.connectionsOfKindFast(LeadKind.REDSTONE));

                for (LeadConnection updated : changedInComponent) {
                    notifyRedstoneChange(level, updated);
                }
            }
        }

        if (changed) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
    }

    static void tickEnergy(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> energyConnections = data.connectionsOfKindFast(LeadKind.ENERGY);
        if (energyConnections.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        int size = energyConnections.size();
        EnergyHandlerCache energyHandlers = new EnergyHandlerCache();
        Set<UUID> transferredIds = HashSet.newHashSet(size / 2);
        boolean[] visited = new boolean[size];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(energyConnections, size);
        RedstoneTraversalCache bridgeCache = new RedstoneTraversalCache(level);
        for (int i = 0; i < energyConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = energyConnections.get(component.get(cursor));
                addUnvisitedNeighborsBridge(bridgeCache, current.from(), connectionsByAnchor, visited, component);
                addUnvisitedNeighborsBridge(bridgeCache, current.to(), connectionsByAnchor, visited, component);
            }

            transferEnergyComponent(level, component, energyConnections, transferredIds, energyHandlers);
        }

        // Refresh sticky deadlines for connections that actually moved energy this
        // tick.
        for (UUID id : transferredIds) {
            ENERGY_ACTIVE_UNTIL.put(id, now + ENERGY_STICKY_TICKS);
        }

        boolean changed = false;
        Set<UUID> currentIds = new HashSet<>();
        for (LeadConnection connection : energyConnections) {
            currentIds.add(connection.id());
            Long until = ENERGY_ACTIVE_UNTIL.get(connection.id());
            int newPower = (until != null && until >= now) ? 1 : 0;
            if (connection.power() != newPower) {
                changed |= data.update(connection.id(), c -> c.withPower(newPower), false);
            }
        }
        // Drop deadlines for connections that no longer exist.
        ENERGY_ACTIVE_UNTIL.keySet().retainAll(currentIds);
        if (changed) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
    }

    static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        if (level instanceof ServerLevel serverLevel) {
            return leadSignalServer(serverLevel, pos);
        }
        // Client-side fallback: iterate all connections (client rope count is
        // typically low since it's chunk-scoped).
        int signal = 0;
        List<LeadConnection> redstoneConnections = SuperLeadNetwork.connections(level);
        for (LeadConnection connection : redstoneConnections) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            if (isRedstoneOutputPosition(connection.from(), pos) || isRedstoneOutputPosition(connection.to(), pos)) {
                signal = Math.max(signal, connection.power());
                if (signal >= 15) {
                    return 15;
                }
            }
        }
        return signal;
    }

    private static int leadSignalServer(ServerLevel level, BlockPos pos) {
        Map<BlockPos, List<LeadConnection>> index = REDSTONE_POS_INDEX.get(level);
        if (index == null) {
            return 0;
        }
        List<LeadConnection> candidates = index.get(pos.immutable());
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int signal = 0;
        for (LeadConnection connection : candidates) {
            if (connection.power() > signal) {
                signal = connection.power();
                if (signal >= 15) {
                    return 15;
                }
            }
        }
        return signal;
    }

    static void notifyRedstoneChange(ServerLevel level, LeadConnection connection) {
        if (connection.kind() != LeadKind.REDSTONE) {
            return;
        }
        notifyRedstoneAnchor(level, connection.from());
        notifyRedstoneAnchor(level, connection.to());
    }

    private static void transferEnergyComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> energyConnections, Set<UUID> transferredIds, EnergyHandlerCache energyHandlers) {
        // --- determine mode and build endpoint lists ---
        // Directional mode: at least one connection has extractAnchor != 0.
        // In that mode energy only flows from extractSource -> extractTarget.
        // Equalization mode (legacy): all endpoints participate, flow from
        // fuller to emptier.
        boolean directional = false;
        for (int index : component) {
            if (energyConnections.get(index).extractAnchor() != 0) {
                directional = true;
                break;
            }
        }

        List<EnergyEndpoint> sources = new ArrayList<>();
        List<EnergyEndpoint> targets = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();

        long componentRate = 0;
        long base = Config.energyBaseTransfer();
        for (int index : component) {
            LeadConnection connection = energyConnections.get(index);
            int tier = Math.min(30, connection.tier());
            long rate = base << tier;
            componentRate = Math.min(Integer.MAX_VALUE, componentRate + rate);

            if (directional) {
                LeadAnchor src = connection.extractSource();
                if (src != null && seenAnchors.add(src)) {
                    EnergyHandler handler = energyHandlers.get(level, src);
                    if (handler != null && handler.getCapacityAsLong() > 0L) {
                        sources.add(new EnergyEndpoint(src, handler));
                    }
                }
                LeadAnchor tgt = connection.extractTarget();
                if (tgt != null && seenAnchors.add(tgt)) {
                    EnergyHandler handler = energyHandlers.get(level, tgt);
                    if (handler != null && handler.getCapacityAsLong() > 0L) {
                        targets.add(new EnergyEndpoint(tgt, handler));
                    }
                }
            } else {
                addEnergyEndpoint(level, connection.from(), targets, seenAnchors, energyHandlers);
                addEnergyEndpoint(level, connection.to(), targets, seenAnchors, energyHandlers);
            }
        }

        long remaining = componentRate;
        if (remaining <= 0) {
            return;
        }

        boolean componentMoved;
        if (directional) {
            componentMoved = transferDirectional(sources, targets, remaining);
        } else {
            componentMoved = transferEqualizing(targets, remaining);
        }

        if (componentMoved) {
            for (int index : component) {
                transferredIds.add(energyConnections.get(index).id());
            }
        }
    }

    /**
     * Directional transfer: move energy from source endpoints to target endpoints.
     * Sources are drained completely (subject to the per-tick rate cap).
     * Targets only receive; they never send energy back.
     */
    private static boolean transferDirectional(List<EnergyEndpoint> sources, List<EnergyEndpoint> targets,
            long rateCap) {
        if (sources.isEmpty() || targets.isEmpty()) {
            return false;
        }

        // Sources sorted by fill ratio descending (fullest first).
        sources.sort((x, y) -> Double.compare(energyFillRatio(y.handler()), energyFillRatio(x.handler())));
        // Targets sorted by fill ratio ascending (emptiest first).
        targets.sort((x, y) -> Double.compare(energyFillRatio(x.handler()), energyFillRatio(y.handler())));

        long remaining = rateCap;
        boolean moved = false;
        int si = 0;
        int ti = 0;

        while (si < sources.size() && ti < targets.size() && remaining > 0) {
            EnergyEndpoint source = sources.get(si);
            double srcFill = energyFillRatio(source.handler());
            if (srcFill <= 0.0D) {
                si++;
                continue;
            }

            EnergyEndpoint target = targets.get(ti);
            double tgtFill = energyFillRatio(target.handler());
            if (tgtFill >= 1.0D) {
                ti++;
                continue;
            }

            int maxAmount = remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
            int transferred = transferEnergy(source.handler(), target.handler(), maxAmount);
            if (transferred > 0) {
                remaining -= transferred;
                moved = true;
            }

            // Re-evaluate fill after transfer
            if (energyFillRatio(source.handler()) <= 0.0D || transferred == 0) {
                si++;
            }
            if (energyFillRatio(target.handler()) >= 1.0D || transferred == 0) {
                ti++;
            }
        }
        return moved;
    }

    /**
     * Equalization mode (legacy): energy flows from fuller to emptier across all
     * endpoints, respecting the per-tick rate cap.
     */
    private static boolean transferEqualizing(List<EnergyEndpoint> endpoints, long rateCap) {
        int epCount = endpoints.size();
        if (epCount < 2) {
            return false;
        }

        endpoints.sort((x, y) -> Double.compare(energyFillRatio(y.handler()), energyFillRatio(x.handler())));

        long remaining = rateCap;
        boolean moved = false;
        int lo = 0;
        int hi = epCount - 1;

        while (lo < hi && remaining > 0) {
            EnergyEndpoint full = endpoints.get(lo);
            double fillFull = energyFillRatio(full.handler());
            if (fillFull <= 0.0D) {
                break;
            }
            EnergyEndpoint empty = endpoints.get(hi);
            double fillEmpty = energyFillRatio(empty.handler());
            if (fillEmpty >= 1.0D) {
                break;
            }
            if (fillFull <= fillEmpty + 1.0e-6D) {
                break;
            }
            int maxAmount = remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
            int transferred = transferEnergy(full.handler(), empty.handler(), maxAmount);
            if (transferred > 0) {
                remaining -= transferred;
                moved = true;
            }
            if (energyFillRatio(full.handler()) <= 0.0D || transferred == 0) {
                lo++;
            }
            if (energyFillRatio(empty.handler()) >= 1.0D || transferred == 0) {
                hi--;
            }
        }
        return moved;
    }

    private static void addEnergyEndpoint(ServerLevel level, LeadAnchor anchor, List<EnergyEndpoint> endpoints,
            Set<LeadAnchor> seenAnchors, EnergyHandlerCache energyHandlers) {
        if (!seenAnchors.add(anchor)) {
            return;
        }

        EnergyHandler handler = energyHandlers.get(level, anchor);
        if (handler != null && handler.getCapacityAsLong() > 0L) {
            endpoints.add(new EnergyEndpoint(anchor, handler));
        }
    }

    private static double energyFillRatio(EnergyHandler handler) {
        long capacity = handler.getCapacityAsLong();
        if (capacity <= 0L) {
            return 0.0D;
        }
        return handler.getAmountAsLong() / (double) capacity;
    }

    private static int transferEnergy(EnergyHandler source, EnergyHandler target, int maxAmount) {
        int transferable;
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = source.extract(maxAmount, transaction);
            if (extracted <= 0) {
                return 0;
            }
            transferable = target.insert(extracted, transaction);
        }

        if (transferable <= 0) {
            return 0;
        }

        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = source.extract(transferable, transaction);
            if (extracted <= 0) {
                return 0;
            }
            int inserted = target.insert(extracted, transaction);
            if (inserted != extracted) {
                return 0;
            }

            transaction.commit();
            return inserted;
        }
    }

    private static EnergyHandler energyHandler(ServerLevel level, LeadAnchor anchor) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), null);
        }
        return handler;
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static Map<LeadAnchor, List<Integer>> indexConnectionsByAnchor(List<LeadConnection> connections, int size) {
        Map<LeadAnchor, List<Integer>> byAnchor = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            LeadConnection connection = connections.get(i);
            addAnchorIndex(byAnchor, connection.from(), i);
            if (!connection.to().equals(connection.from())) {
                addAnchorIndex(byAnchor, connection.to(), i);
            }
        }
        return byAnchor;
    }

    private static void rebuildRedstonePosIndex(ServerLevel level, List<LeadConnection> connections) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        Map<BlockPos, List<LeadConnection>> index = REDSTONE_POS_INDEX.get(level);
        Long cachedGen = REDSTONE_POS_INDEX_GEN.get(level);
        long currentGen = data.generation();
        if (cachedGen != null && cachedGen.longValue() == currentGen && index != null) {
            return;
        }
        if (index == null) {
            index = new HashMap<>();
            REDSTONE_POS_INDEX.put(level, index);
        } else {
            index.clear();
        }
        for (LeadConnection c : connections) {
            index.computeIfAbsent(c.from().pos().immutable(), k -> new ArrayList<>(1)).add(c);
            BlockPos toPos = c.to().pos().immutable();
            if (!toPos.equals(c.from().pos())) {
                index.computeIfAbsent(toPos, k -> new ArrayList<>(1)).add(c);
            }
        }
        REDSTONE_POS_INDEX_GEN.put(level, currentGen);
    }

    private static void invalidateRedstonePosIndex(ServerLevel level) {
        REDSTONE_POS_INDEX.remove(level);
        REDSTONE_POS_INDEX_GEN.remove(level);
    }

    static void discardLevelState(ServerLevel level) {
        invalidateRedstonePosIndex(level);
    }

    private static void addAnchorIndex(Map<LeadAnchor, List<Integer>> byAnchor, LeadAnchor anchor, int index) {
        byAnchor.computeIfAbsent(anchor, key -> new ArrayList<>()).add(index);
    }

    private static void addUnvisitedNeighbors(LeadAnchor anchor, Map<LeadAnchor, List<Integer>> byAnchor,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byAnchor.get(anchor);
        if (neighbors != null) {
            for (int index : neighbors) {
                if (!visited[index]) {
                    visited[index] = true;
                    component.add(index);
                }
            }
        }
    }

    private static void addUnvisitedNeighborsBridge(RedstoneTraversalCache cache, LeadAnchor anchor,
            Map<LeadAnchor, List<Integer>> byAnchor, boolean[] visited, List<Integer> component) {
        addUnvisitedNeighbors(anchor, byAnchor, visited, component);
        if (!cache.signalBridgeEnabled(anchor.pos())) {
            return;
        }
        for (Direction face : Direction.values()) {
            if (face == anchor.face()) {
                continue;
            }
            LeadAnchor otherFace = new LeadAnchor(anchor.pos(), face);
            addUnvisitedNeighbors(otherFace, byAnchor, visited, component);
        }
    }

    private static int externalSignalAt(ServerLevel level, LeadAnchor anchor) {
        return withoutLeadSignals(() -> {
            int power = 0;
            power = Math.max(power, level.getSignal(anchor.pos(), anchor.face()));
            power = Math.max(power, level.getSignal(anchor.pos(), anchor.face().getOpposite()));
            power = Math.max(power, level.getBestNeighborSignal(anchor.pos()));
            power = Math.max(power, level.getBestNeighborSignal(anchor.pos().relative(anchor.face())));
            return Math.min(power, 15);
        });
    }

    private static int withoutLeadSignals(IntSupplier supplier) {
        boolean old = SUPPRESS_LEAD_SIGNALS.get();
        SUPPRESS_LEAD_SIGNALS.set(true);
        try {
            return supplier.getAsInt();
        } finally {
            SUPPRESS_LEAD_SIGNALS.set(old);
        }
    }

    private static boolean isRedstoneOutputPosition(LeadAnchor anchor, BlockPos pos) {
        return anchor.pos().equals(pos) || anchor.pos().relative(anchor.face()).equals(pos);
    }

    private static void notifyRedstoneAnchor(ServerLevel level, LeadAnchor anchor) {
        BlockPos pos = anchor.pos();
        level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
        level.updateNeighborsAt(pos.relative(anchor.face()), Blocks.AIR);
    }

    private static final class EnergyHandlerCache {
        private final Map<LeadAnchor, EnergyHandler> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        private EnergyHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            EnergyHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            EnergyHandler found = energyHandler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }

    private static final class RedstoneTraversalCache {
        private final ServerLevel level;
        private final Map<LeadAnchor, Integer> externalSignals = new HashMap<>();
        private final Map<BlockPos, Boolean> signalBridgeEnabled = new HashMap<>();

        private RedstoneTraversalCache(ServerLevel level) {
            this.level = level;
        }

        private int externalSignalAt(LeadAnchor anchor) {
            LeadAnchor key = cacheKey(anchor);
            Integer cached = externalSignals.get(key);
            if (cached != null) {
                return cached.intValue();
            }
            int signal = LeadSignalService.externalSignalAt(level, key);
            externalSignals.put(key, signal);
            return signal;
        }

        private boolean signalBridgeEnabled(BlockPos pos) {
            BlockPos key = pos.immutable();
            Boolean cached = signalBridgeEnabled.get(key);
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean enabled = BlockPropertyRegistry.signalBridgeEnabled(level.getBlockState(key).getBlock());
            signalBridgeEnabled.put(key, enabled);
            return enabled;
        }
    }

    private record EnergyEndpoint(LeadAnchor anchor, EnergyHandler handler) {
    }
}
