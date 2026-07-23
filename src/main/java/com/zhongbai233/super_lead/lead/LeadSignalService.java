package com.zhongbai233.super_lead.lead;

import com.mojang.logging.LogUtils;
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
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * Redstone and energy service for rope networks.
 */
final class LeadSignalService {
    private static final Logger LOG = LogUtils.getLogger();
    /**
     * Sticky "recently active" deadline per ENERGY connection, keyed by game-time
     * tick at which the visual powered state expires.
     */
    private static final Map<ServerLevel, Map<UUID, Long>> ENERGY_ACTIVE_UNTIL = new HashMap<>();
    private static final long ENERGY_STICKY_TICKS = 40L;
    private static final long ENERGY_BREAKER_LOG_INTERVAL_TICKS = 1200L;
    private static final Map<ServerLevel, Map<LeadAnchor, Long>> ENERGY_BREAKER_UNTIL = new HashMap<>();
    private static final Map<ServerLevel, Map<LeadAnchor, Long>> ENERGY_BREAKER_LAST_LOG = new HashMap<>();
    private static final ThreadLocal<Boolean> ENERGY_TICK_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> SUPPRESS_LEAD_SIGNALS = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> REDSTONE_UPDATE_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final Set<ServerLevel> REDSTONE_DIRTY_LEVELS = new HashSet<>();
    private static final Set<ServerLevel> REDSTONE_INITIALIZED_LEVELS = new HashSet<>();

    private LeadSignalService() {
    }

    static void tickRedstone(ServerLevel level) {
        boolean firstUpdate = REDSTONE_INITIALIZED_LEVELS.add(level);
        boolean dirty = REDSTONE_DIRTY_LEVELS.remove(level);
        if (!shouldProcessRedstoneUpdate(firstUpdate, dirty)) {
            return;
        }
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> redstoneConnections = data.connectionsOfKindFast(LeadKind.REDSTONE);
        if (redstoneConnections.isEmpty()) {
            return;
        }

        REDSTONE_UPDATE_ACTIVE.set(true);
        try {
            updateRedstoneNetworks(level, data, redstoneConnections);
        } finally {
            REDSTONE_UPDATE_ACTIVE.set(false);
        }
    }

    private static void updateRedstoneNetworks(ServerLevel level, SuperLeadSavedData data,
            List<LeadConnection> redstoneConnections) {
        int size = redstoneConnections.size();
        boolean changed = false;
        boolean[] visited = new boolean[size];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(redstoneConnections, size);
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
            for (int index : component) {
                LeadConnection connection = redstoneConnections.get(index);
                if (connection.power() == componentPower) {
                    continue;
                }

                LeadConnection updated = connection.withPower(componentPower);
                changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(componentPower),
                        false);
                notifyRedstoneChange(level, updated);
            }
        }

        if (changed) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
    }

    static void markRedstoneDirty(ServerLevel level) {
        if (level != null && !REDSTONE_UPDATE_ACTIVE.get()) {
            REDSTONE_DIRTY_LEVELS.add(level);
        }
    }

    static boolean shouldProcessRedstoneUpdate(boolean firstUpdate, boolean dirty) {
        return firstUpdate || dirty;
    }

    static void tickEnergy(ServerLevel level) {
        if (ENERGY_TICK_ACTIVE.get()) {
            return;
        }
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> energyConnections = data.connectionsOfKindFast(LeadKind.ENERGY);
        if (energyConnections.isEmpty()) {
            return;
        }

        ENERGY_TICK_ACTIVE.set(true);
        try {
            tickEnergyGuarded(level, data, energyConnections);
        } finally {
            ENERGY_TICK_ACTIVE.set(false);
        }
    }

    private static void tickEnergyGuarded(ServerLevel level, SuperLeadSavedData data,
            List<LeadConnection> energyConnections) {
        long now = level.getGameTime();
        int size = energyConnections.size();
        EnergyTickBudget budget = new EnergyTickBudget(Config.energyMaxHandlerCallsPerLevelTick(),
                Config.energyTickBudgetMicros());
        EnergyHandlerCache energyHandlers = new EnergyHandlerCache(budget);
        Set<UUID> transferredIds = HashSet.newHashSet(size / 2);
        boolean[] visited = new boolean[size];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(energyConnections, size);
        RedstoneTraversalCache bridgeCache = new RedstoneTraversalCache(level);
        for (int i = 0; i < energyConnections.size() && budget.canStartCall(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size() && budget.canStartCall(); cursor++) {
                LeadConnection current = energyConnections.get(component.get(cursor));
                addUnvisitedNeighborsBridge(bridgeCache, current.from(), connectionsByAnchor, visited, component);
                addUnvisitedNeighborsBridge(bridgeCache, current.to(), connectionsByAnchor, visited, component);
            }

            transferEnergyComponent(level, component, energyConnections, transferredIds, energyHandlers, budget);
        }

        // Refresh sticky deadlines for connections that actually moved energy this
        // tick.
        Map<UUID, Long> activeUntil = ENERGY_ACTIVE_UNTIL.computeIfAbsent(level, ignored -> new HashMap<>());
        for (UUID id : transferredIds) {
            activeUntil.put(id, now + ENERGY_STICKY_TICKS);
        }

        boolean changed = false;
        Set<UUID> currentIds = new HashSet<>();
        for (LeadConnection connection : energyConnections) {
            currentIds.add(connection.id());
            Long until = activeUntil.get(connection.id());
            int newPower = (until != null && until >= now) ? 1 : 0;
            if (connection.power() != newPower) {
                changed |= data.update(connection.id(), c -> c.withPower(newPower), false);
            }
        }
        // Drop deadlines for connections that no longer exist.
        activeUntil.keySet().retainAll(currentIds);
        if (changed) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
    }

    static void clearEnergySafetyState(ServerLevel level) {
        ENERGY_BREAKER_UNTIL.remove(level);
        ENERGY_BREAKER_LAST_LOG.remove(level);
        ENERGY_ACTIVE_UNTIL.remove(level);
        REDSTONE_DIRTY_LEVELS.remove(level);
        REDSTONE_INITIALIZED_LEVELS.remove(level);
    }

    static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        int signal = 0;
        List<LeadConnection> redstoneConnections = redstoneConnections(level);
        for (LeadConnection connection : redstoneConnections) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            if (isRedstoneOutputPosition(connection, pos)) {
                signal = Math.max(signal, connection.power());
                if (signal >= 15) {
                    return 15;
                }
            }
        }
        return signal;
    }

    static int leadDirectSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        int signal = 0;
        List<LeadConnection> redstoneConnections = redstoneConnections(level);
        for (LeadConnection connection : redstoneConnections) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            if (isRedstoneDirectOutput(connection, pos, direction)) {
                signal = Math.max(signal, connection.power());
                if (signal >= 15) {
                    return 15;
                }
            }
        }
        return signal;
    }

    static boolean hasLeadNeighborSignal(SignalGetter getter, BlockPos pos) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return false;
        }
        List<LeadConnection> redstoneConnections = redstoneConnections(level);
        for (LeadConnection connection : redstoneConnections) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            if (isRedstoneOutputPosition(connection, pos)) {
                return true;
            }
        }
        return false;
    }

    private static List<LeadConnection> redstoneConnections(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).connectionsOfKindFast(LeadKind.REDSTONE);
        }
        return SuperLeadNetwork.connections(level);
    }

    static void notifyRedstoneChange(ServerLevel level, LeadConnection connection) {
        if (connection.kind() != LeadKind.REDSTONE) {
            return;
        }
        markRedstoneDirty(level);
        notifyRedstoneAnchor(level, connection.from());
        notifyRedstoneAnchor(level, connection.to());
    }

    private static void transferEnergyComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> energyConnections, Set<UUID> transferredIds, EnergyHandlerCache energyHandlers,
            EnergyTickBudget budget) {
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
            int tier = Math.min(Config.energyTierMaxLevel(), Math.min(30, connection.tier()));
            long rate = base << tier;
            componentRate = Math.min(Integer.MAX_VALUE, componentRate + rate);

            if (directional) {
                LeadAnchor src = connection.extractSource();
                if (src != null && seenAnchors.add(src)) {
                    EnergyHandler handler = energyHandlers.get(level, src);
                    EnergyEndpoint endpoint = snapshotEnergyEndpoint(level, src, handler, budget);
                    if (endpoint != null) {
                        sources.add(endpoint);
                    }
                }
                LeadAnchor tgt = connection.extractTarget();
                if (tgt != null && seenAnchors.add(tgt)) {
                    EnergyHandler handler = energyHandlers.get(level, tgt);
                    EnergyEndpoint endpoint = snapshotEnergyEndpoint(level, tgt, handler, budget);
                    if (endpoint != null) {
                        targets.add(endpoint);
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
            componentMoved = transferDirectional(level, sources, targets, remaining, budget);
        } else {
            componentMoved = transferEqualizing(level, targets, remaining, budget);
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
    private static boolean transferDirectional(ServerLevel level, List<EnergyEndpoint> sources,
            List<EnergyEndpoint> targets, long rateCap, EnergyTickBudget budget) {
        if (sources.isEmpty() || targets.isEmpty()) {
            return false;
        }

        // Sources sorted by fill ratio descending (fullest first).
        sources.sort((x, y) -> Double.compare(y.fillRatio(), x.fillRatio()));
        // Targets sorted by fill ratio ascending (emptiest first).
        targets.sort((x, y) -> Double.compare(x.fillRatio(), y.fillRatio()));

        long remaining = rateCap;
        boolean moved = false;
        int si = 0;
        int ti = 0;
        int attempts = 0;

        while (si < sources.size() && ti < targets.size() && remaining > 0
                && attempts < Config.energyMaxAttemptsPerComponentTick() && budget.canStartTransfer()) {
            EnergyEndpoint source = sources.get(si);
            double srcFill = source.fillRatio();
            if (srcFill <= 0.0D) {
                si++;
                continue;
            }

            EnergyEndpoint target = targets.get(ti);
            double tgtFill = target.fillRatio();
            if (tgtFill >= 1.0D) {
                ti++;
                continue;
            }

            if (source.handler() == target.handler()) {
                si++;
                ti++;
                continue;
            }
            int maxAmount = boundedEnergyRequest(remaining);
            int transferred = transferEnergy(level, source, target, maxAmount, budget);
            attempts++;
            if (transferred > 0) {
                remaining -= transferred;
                source.remove(transferred);
                target.add(transferred);
                moved = true;
            }

            if (source.fillRatio() <= 0.0D || transferred == 0 || transferred < maxAmount
                    || isEnergyCircuitOpen(level, source.anchor())) {
                si++;
            }
            if (target.fillRatio() >= 1.0D || transferred == 0 || transferred < maxAmount
                    || isEnergyCircuitOpen(level, target.anchor())) {
                ti++;
            }
        }
        return moved;
    }

    /**
     * Equalization mode (legacy): energy flows from fuller to emptier across all
     * endpoints, respecting the per-tick rate cap.
     */
    private static boolean transferEqualizing(ServerLevel level, List<EnergyEndpoint> endpoints, long rateCap,
            EnergyTickBudget budget) {
        int epCount = endpoints.size();
        if (epCount < 2) {
            return false;
        }

        endpoints.sort((x, y) -> Double.compare(y.fillRatio(), x.fillRatio()));

        long remaining = rateCap;
        boolean moved = false;
        int lo = 0;
        int hi = epCount - 1;
        int attempts = 0;

        while (lo < hi && remaining > 0 && attempts < Config.energyMaxAttemptsPerComponentTick()
                && budget.canStartTransfer()) {
            EnergyEndpoint full = endpoints.get(lo);
            double fillFull = full.fillRatio();
            if (fillFull <= 0.0D) {
                break;
            }
            EnergyEndpoint empty = endpoints.get(hi);
            double fillEmpty = empty.fillRatio();
            if (fillEmpty >= 1.0D) {
                break;
            }
            if (fillFull <= fillEmpty + 1.0e-6D) {
                break;
            }
            if (full.handler() == empty.handler()) {
                lo++;
                hi--;
                continue;
            }
            int maxAmount = boundedEnergyRequest(remaining);
            int transferred = transferEnergy(level, full, empty, maxAmount, budget);
            attempts++;
            if (transferred > 0) {
                remaining -= transferred;
                full.remove(transferred);
                empty.add(transferred);
                moved = true;
            }
            if (full.fillRatio() <= 0.0D || transferred == 0 || transferred < maxAmount
                    || isEnergyCircuitOpen(level, full.anchor())) {
                lo++;
            }
            if (empty.fillRatio() >= 1.0D || transferred == 0 || transferred < maxAmount
                    || isEnergyCircuitOpen(level, empty.anchor())) {
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
        EnergyEndpoint endpoint = snapshotEnergyEndpoint(level, anchor, handler, energyHandlers.budget());
        if (endpoint != null) {
            endpoints.add(endpoint);
        }
    }

    static int boundedEnergyRequest(long remaining) {
        if (remaining <= 0L) {
            return 0;
        }
        return (int) Math.min(remaining, Config.energyMaxRequestPerCall());
    }

    private static int transferEnergy(ServerLevel level, EnergyEndpoint source, EnergyEndpoint target, int maxAmount,
            EnergyTickBudget budget) {
        if (maxAmount <= 0 || !budget.canStartCall()) {
            return 0;
        }
        return transferEnergyGuarded(level, source, target, maxAmount, budget);
    }

    private static int transferEnergyGuarded(ServerLevel level, EnergyEndpoint source, EnergyEndpoint target,
            int maxAmount, EnergyTickBudget budget) {
        int transferable;
        try (Transaction transaction = Transaction.openRoot()) {
            Integer extractedValue = callEnergyInt(level, source.anchor(), budget, "simulated extraction",
                    () -> source.handler().extract(maxAmount, transaction));
            if (extractedValue == null) {
                return 0;
            }
            int extracted = extractedValue.intValue();
            if (!validEnergyResponse(extracted, maxAmount)) {
                openEnergyCircuit(level, source.anchor(), "invalid simulated extraction", null);
                return 0;
            }
            if (extracted <= 0) {
                return 0;
            }
            Integer insertedValue = callEnergyInt(level, target.anchor(), budget, "simulated insertion",
                    () -> target.handler().insert(extracted, transaction));
            if (insertedValue == null) {
                return 0;
            }
            transferable = insertedValue.intValue();
            if (!validEnergyResponse(transferable, extracted)) {
                openEnergyCircuit(level, target.anchor(), "invalid simulated insertion", null);
                return 0;
            }
        } catch (RuntimeException | LinkageError error) {
            openEnergyCircuit(level, source.anchor(), "energy simulation failed", error);
            openEnergyCircuit(level, target.anchor(), "energy simulation failed", error);
            return 0;
        }

        if (transferable <= 0) {
            return 0;
        }

        try (Transaction transaction = Transaction.openRoot()) {
            Integer extractedValue = callEnergyInt(level, source.anchor(), budget, "committed extraction",
                    () -> source.handler().extract(transferable, transaction));
            if (extractedValue == null) {
                return 0;
            }
            int extracted = extractedValue.intValue();
            if (!validEnergyResponse(extracted, transferable)) {
                openEnergyCircuit(level, source.anchor(), "invalid committed extraction", null);
                return 0;
            }
            if (extracted <= 0) {
                return 0;
            }
            Integer insertedValue = callEnergyInt(level, target.anchor(), budget, "committed insertion",
                    () -> target.handler().insert(extracted, transaction));
            if (insertedValue == null) {
                return 0;
            }
            int inserted = insertedValue.intValue();
            if (!validEnergyResponse(inserted, extracted)) {
                openEnergyCircuit(level, target.anchor(), "invalid committed insertion", null);
                return 0;
            }
            if (inserted != extracted) {
                return 0;
            }

            transaction.commit();
            return inserted;
        } catch (RuntimeException | LinkageError error) {
            openEnergyCircuit(level, source.anchor(), "energy commit failed", error);
            openEnergyCircuit(level, target.anchor(), "energy commit failed", error);
            return 0;
        }
    }

    private static boolean validEnergyResponse(int value, int requested) {
        return value >= 0 && value <= requested;
    }

    private static Integer callEnergyInt(ServerLevel level, LeadAnchor anchor, EnergyTickBudget budget,
            String operation, IntSupplier call) {
        if (isEnergyCircuitOpen(level, anchor) || !budget.reserveCalls(1)) {
            return null;
        }
        long started = System.nanoTime();
        try {
            int value = call.getAsInt();
            checkSlowEnergyCall(level, anchor, anchor, started, operation);
            return isEnergyCircuitOpen(level, anchor) ? null : Integer.valueOf(value);
        } catch (RuntimeException | LinkageError error) {
            openEnergyCircuit(level, anchor, operation + " failed", error);
            return null;
        }
    }

    private static Long callEnergyLong(ServerLevel level, LeadAnchor anchor, EnergyTickBudget budget,
            String operation, LongSupplier call) {
        if (isEnergyCircuitOpen(level, anchor) || !budget.reserveCalls(1)) {
            return null;
        }
        long started = System.nanoTime();
        try {
            long value = call.getAsLong();
            checkSlowEnergyCall(level, anchor, anchor, started, operation);
            return isEnergyCircuitOpen(level, anchor) ? null : Long.valueOf(value);
        } catch (RuntimeException | LinkageError error) {
            openEnergyCircuit(level, anchor, operation + " failed", error);
            return null;
        }
    }

    private static EnergyEndpoint snapshotEnergyEndpoint(ServerLevel level, LeadAnchor anchor, EnergyHandler handler,
            EnergyTickBudget budget) {
        if (handler == null || isEnergyCircuitOpen(level, anchor)) {
            return null;
        }
        Long capacityValue = callEnergyLong(level, anchor, budget, "energy capacity snapshot",
                handler::getCapacityAsLong);
        if (capacityValue == null) {
            return null;
        }
        Long amountValue = callEnergyLong(level, anchor, budget, "energy amount snapshot", handler::getAmountAsLong);
        if (amountValue == null) {
            return null;
        }
        long capacity = capacityValue.longValue();
        long amount = amountValue.longValue();
        try {
            if (capacity <= 0L || amount < 0L || amount > capacity) {
                openEnergyCircuit(level, anchor, "invalid energy amount/capacity", null);
                return null;
            }
            return new EnergyEndpoint(anchor, handler, amount, capacity);
        } catch (RuntimeException | LinkageError error) {
            openEnergyCircuit(level, anchor, "energy snapshot failed", error);
            return null;
        }
    }

    private static void checkSlowEnergyCall(ServerLevel level, LeadAnchor first, LeadAnchor second, long started,
            String operation) {
        long elapsed = System.nanoTime() - started;
        if (elapsed > (long) Config.energySlowCallThresholdMicros() * 1000L) {
            openEnergyCircuit(level, first, operation + " exceeded slow-call threshold", null);
            if (!second.equals(first)) {
                openEnergyCircuit(level, second, operation + " exceeded slow-call threshold", null);
            }
        }
    }

    private static boolean isEnergyCircuitOpen(ServerLevel level, LeadAnchor anchor) {
        Map<LeadAnchor, Long> perLevel = ENERGY_BREAKER_UNTIL.get(level);
        if (perLevel == null) {
            return false;
        }
        LeadAnchor key = cacheKey(anchor);
        Long until = perLevel.get(key);
        if (until == null) {
            return false;
        }
        if (level.getGameTime() >= until) {
            perLevel.remove(key);
            return false;
        }
        return true;
    }

    private static void openEnergyCircuit(ServerLevel level, LeadAnchor anchor, String reason, Throwable error) {
        LeadAnchor key = cacheKey(anchor);
        long now = level.getGameTime();
        ENERGY_BREAKER_UNTIL.computeIfAbsent(level, ignored -> new HashMap<>())
                .put(key, now + Config.energyBreakerCooldownTicks());
        Map<LeadAnchor, Long> logs = ENERGY_BREAKER_LAST_LOG.computeIfAbsent(level, ignored -> new HashMap<>());
        long lastLog = logs.getOrDefault(key, Long.MIN_VALUE / 2L);
        if (now - lastLog >= ENERGY_BREAKER_LOG_INTERVAL_TICKS) {
            logs.put(key, now);
            if (error == null) {
                LOG.warn("[super_lead] FE endpoint circuit opened at {} {}: {}", key.pos(), key.face(), reason);
            } else {
                LOG.warn("[super_lead] FE endpoint circuit opened at {} {}: {} ({})", key.pos(), key.face(), reason,
                        error.toString());
            }
        }
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static Map<LeadAnchor, List<Integer>> indexConnectionsByAnchor(List<LeadConnection> connections, int size) {
        Map<LeadAnchor, List<Integer>> byAnchor = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            LeadConnection connection = connections.get(i);
            addAnchorIndex(byAnchor, connection.from(), i);
            if (!connection.to().samePort(connection.from())) {
                addAnchorIndex(byAnchor, connection.to(), i);
            }
        }
        return byAnchor;
    }

    private static void addAnchorIndex(Map<LeadAnchor, List<Integer>> byAnchor, LeadAnchor anchor, int index) {
        byAnchor.computeIfAbsent(anchor.logicalPort(), key -> new ArrayList<>()).add(index);
    }

    private static void addUnvisitedNeighbors(LeadAnchor anchor, Map<LeadAnchor, List<Integer>> byAnchor,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byAnchor.get(anchor.logicalPort());
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

    private static boolean isRedstoneOutputPosition(LeadConnection connection, BlockPos pos) {
        return isRedstoneOutputPosition(connection.from(), pos)
                || isRedstoneOutputPosition(connection.to(), pos);
    }

    private static boolean isRedstoneOutputPosition(LeadAnchor anchor, BlockPos pos) {
        return anchor.pos().equals(pos) || anchor.pos().relative(anchor.face()).equals(pos);
    }

    private static boolean isRedstoneDirectOutput(LeadConnection connection, BlockPos pos, Direction direction) {
        return isRedstoneDirectOutput(connection.from(), pos, direction)
                || isRedstoneDirectOutput(connection.to(), pos, direction);
    }

    private static boolean isRedstoneDirectOutput(LeadAnchor anchor, BlockPos pos, Direction direction) {
        return direction == anchor.face() && anchor.pos().relative(anchor.face()).equals(pos);
    }

    private static void notifyRedstoneAnchor(ServerLevel level, LeadAnchor anchor) {
        BlockPos attachedPos = anchor.pos();
        BlockPos outputPos = attachedPos.relative(anchor.face());

        level.neighborChanged(attachedPos, level.getBlockState(outputPos).getBlock(), null);

        level.updateNeighborsAt(outputPos, level.getBlockState(outputPos).getBlock());
        level.updateNeighborsAt(attachedPos, level.getBlockState(attachedPos).getBlock());
    }

    private static final class EnergyHandlerCache {
        private final Map<LeadAnchor, EnergyHandler> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();
        private final EnergyTickBudget budget;

        private EnergyHandlerCache(EnergyTickBudget budget) {
            this.budget = budget;
        }

        private EnergyTickBudget budget() {
            return budget;
        }

        private EnergyHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            EnergyHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            if (!level.isLoaded(key.pos()) || isEnergyCircuitOpen(level, key)) {
                misses.add(key);
                return null;
            }
            EnergyHandler found = callEnergyCapability(level, key, budget, key.face());
            if (found == null && !isEnergyCircuitOpen(level, key)) {
                found = callEnergyCapability(level, key, budget, null);
            }
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }

        private static EnergyHandler callEnergyCapability(ServerLevel level, LeadAnchor anchor,
                EnergyTickBudget budget, Direction face) {
            if (!budget.reserveCalls(1)) {
                return null;
            }
            long started = System.nanoTime();
            try {
                EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), face);
                checkSlowEnergyCall(level, anchor, anchor, started, "energy capability lookup");
                return isEnergyCircuitOpen(level, anchor) ? null : handler;
            } catch (RuntimeException | LinkageError error) {
                openEnergyCircuit(level, anchor, "energy capability lookup failed", error);
                return null;
            }
        }
    }

    static final class EnergyTickBudget {
        private final int maxCalls;
        private final long deadlineNanos;
        private int calls;

        EnergyTickBudget(int maxCalls, int budgetMicros) {
            this.maxCalls = Math.max(1, maxCalls);
            this.deadlineNanos = System.nanoTime() + Math.max(1L, budgetMicros) * 1000L;
        }

        boolean canStartCall() {
            return calls < maxCalls && System.nanoTime() < deadlineNanos;
        }

        boolean canStartTransfer() {
            return canStartCall();
        }

        boolean reserveTransferCalls() {
            return reserveCalls(1);
        }

        boolean reserveCalls(int count) {
            if (count <= 0 || calls > maxCalls - count || System.nanoTime() >= deadlineNanos) {
                return false;
            }
            calls += count;
            return true;
        }

        int calls() {
            return calls;
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
                boolean enabled = level.isLoaded(key)
                    && BlockPropertyRegistry.signalBridgeEnabled(level.getBlockState(key).getBlock());
            signalBridgeEnabled.put(key, enabled);
            return enabled;
        }
    }

    private static final class EnergyEndpoint {
        private final LeadAnchor anchor;
        private final EnergyHandler handler;
        private final long capacity;
        private long amount;

        private EnergyEndpoint(LeadAnchor anchor, EnergyHandler handler, long amount, long capacity) {
            this.anchor = anchor;
            this.handler = handler;
            this.amount = amount;
            this.capacity = capacity;
        }

        private LeadAnchor anchor() {
            return anchor;
        }

        private EnergyHandler handler() {
            return handler;
        }

        private double fillRatio() {
            return capacity <= 0L ? 0.0D : amount / (double) capacity;
        }

        private void add(int value) {
            long added = Math.max(0, value);
            amount = added >= capacity - amount ? capacity : amount + added;
        }

        private void remove(int value) {
            amount = Math.max(0L, amount - Math.max(0, value));
        }
    }
}
