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

    private LeadSignalService() {
    }

    static void tickRedstone(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> redstoneConnections = data.connectionsOfKind(LeadKind.REDSTONE);
        if (redstoneConnections.isEmpty()) {
            return;
        }

        boolean changed = false;
        boolean[] visited = new boolean[redstoneConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(redstoneConnections);
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

                changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(componentPower),
                        false);
                notifyRedstoneChange(level, connection.withPower(componentPower));
            }
        }

        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    static void tickEnergy(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> energyConnections = data.connectionsOfKind(LeadKind.ENERGY);
        if (energyConnections.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        EnergyHandlerCache energyHandlers = new EnergyHandlerCache();
        Set<UUID> transferredIds = new HashSet<>();
        boolean[] visited = new boolean[energyConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(energyConnections);
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
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        int signal = 0;
        List<LeadConnection> redstoneConnections = level instanceof ServerLevel serverLevel
                ? SuperLeadSavedData.get(serverLevel).connectionsOfKind(LeadKind.REDSTONE)
                : SuperLeadNetwork.connections(level);
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

    static void notifyRedstoneChange(ServerLevel level, LeadConnection connection) {
        if (connection.kind() != LeadKind.REDSTONE) {
            return;
        }
        notifyRedstoneAnchor(level, connection.from());
        notifyRedstoneAnchor(level, connection.to());
    }

    private static void transferEnergyComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> energyConnections, Set<UUID> transferredIds, EnergyHandlerCache energyHandlers) {
        List<EnergyEndpoint> endpoints = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();
        int componentRate = 0;
        long base = Config.energyBaseTransfer();
        for (int index : component) {
            LeadConnection connection = energyConnections.get(index);
            int tier = Math.min(30, connection.tier());
            long rate = base << tier;
            componentRate = (int) Math.min(Integer.MAX_VALUE, (long) componentRate + rate);
            addEnergyEndpoint(level, connection.from(), endpoints, seenAnchors, energyHandlers);
            addEnergyEndpoint(level, connection.to(), endpoints, seenAnchors, energyHandlers);
        }

        boolean componentMoved = false;
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                EnergyEndpoint a = endpoints.get(i);
                EnergyEndpoint b = endpoints.get(j);
                double fillA = energyFillRatio(a.handler());
                double fillB = energyFillRatio(b.handler());
                int moved;
                if (fillA > fillB + 1.0e-6D) {
                    moved = transferEnergy(a.handler(), b.handler(), componentRate);
                } else if (fillB > fillA + 1.0e-6D) {
                    moved = transferEnergy(b.handler(), a.handler(), componentRate);
                } else {
                    moved = 0;
                }
                if (moved > 0) {
                    componentMoved = true;
                }
            }
        }
        if (componentMoved) {
            for (int index : component) {
                transferredIds.add(energyConnections.get(index).id());
            }
        }
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

    private static Map<LeadAnchor, List<Integer>> indexConnectionsByAnchor(List<LeadConnection> connections) {
        Map<LeadAnchor, List<Integer>> byAnchor = new HashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            LeadConnection connection = connections.get(i);
            addAnchorIndex(byAnchor, connection.from(), i);
            if (!connection.to().equals(connection.from())) {
                addAnchorIndex(byAnchor, connection.to(), i);
            }
        }
        return byAnchor;
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
