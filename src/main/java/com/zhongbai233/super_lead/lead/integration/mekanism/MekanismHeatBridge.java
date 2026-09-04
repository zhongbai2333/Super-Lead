package com.zhongbai233.super_lead.lead.integration.mekanism;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Small facade over Mekanism's heat capability for thermal leads.
 */
public final class MekanismHeatBridge {
    private static final double HEAT_EPSILON = 1.0e-6D;

    private MekanismHeatBridge() {
    }

    public static IHeatHandler handler(ServerLevel level, LeadAnchor anchor) {
        if (level == null || anchor == null) {
            return null;
        }
        // Mekanism's null-side heat handler is deliberately read-only. Falling back
        // to it bypasses side configuration and makes handleHeat silently do nothing.
        return level.getCapability(Capabilities.HEAT, anchor.pos(), anchor.face());
    }

    public static boolean hasHandler(ServerLevel level, LeadAnchor anchor) {
        return usable(handler(level, anchor));
    }

    public static double balance(ServerLevel level, LeadAnchor first, LeadAnchor second, double maxHeat) {
        return balance(handler(level, first), handler(level, second), maxHeat);
    }

    public static double balance(IHeatHandler first, IHeatHandler second, double maxHeat) {
        if (!usable(first) || !usable(second) || !Double.isFinite(maxHeat) || maxHeat <= 0.0D) {
            return 0.0D;
        }

        double tempA = first.getTemperature();
        double tempB = second.getTemperature();
        double capA = first.getHeatCapacity();
        double capB = second.getHeatCapacity();
        double inverseA = first.getInverseConduction();
        double inverseB = second.getInverseConduction();
        if (!finiteAtLeastOne(capA) || !finiteAtLeastOne(capB)
                || !finiteAtLeastOne(inverseA) || !finiteAtLeastOne(inverseB)
                || !finiteNonNegative(tempA) || !finiteNonNegative(tempB)
                || Math.abs(tempA - tempB) <= HEAT_EPSILON) {
            return 0.0D;
        }

        boolean firstHotter = tempA > tempB;
        IHeatHandler hot = firstHotter ? first : second;
        IHeatHandler cold = firstHotter ? second : first;
        double hotTemp = firstHotter ? tempA : tempB;
        double coldTemp = firstHotter ? tempB : tempA;
        double hotCap = firstHotter ? capA : capB;
        double coldCap = firstHotter ? capB : capA;
        double inverseConduction = inverseA + inverseB;
        if (!Double.isFinite(inverseConduction)) {
            return 0.0D;
        }

        // This is the same calorimetry step used by Mekanism's
        // ITileHeatHandler#simulateAdjacent: approach equilibrium by the combined
        // inverse conduction instead of jumping directly to equilibrium.
        double hotShare;
        if (hotCap >= coldCap) {
            hotShare = 1.0D / (1.0D + coldCap / hotCap);
        } else {
            double ratio = hotCap / coldCap;
            hotShare = ratio / (1.0D + ratio);
        }
        double equilibrium = coldTemp + (hotTemp - coldTemp) * hotShare;
        double temperatureToTransfer = (hotTemp - equilibrium) / inverseConduction;
        double heatToEquilibrium = temperatureToTransfer * hotCap;
        double transfer = Math.min(maxHeat, heatToEquilibrium);
        if (!Double.isFinite(transfer) || transfer <= HEAT_EPSILON) {
            return 0.0D;
        }

        try (Transaction transaction = Transaction.openRoot()) {
            hot.handleHeat(-transfer, transaction);
            cold.handleHeat(transfer, transaction);
            double hotAfter = hot.getTemperature();
            double coldAfter = cold.getTemperature();
            if (!finiteNonNegative(hotAfter) || !finiteNonNegative(coldAfter)) {
                return 0.0D;
            }
            double removed = (hotTemp - hotAfter) * hotCap;
            double inserted = (coldAfter - coldTemp) * coldCap;
            if (!approximatelyEqual(transfer, removed) || !approximatelyEqual(transfer, inserted)) {
                // Sided Mekanism proxies may reject insertion or extraction without
                // returning an amount. Closing the uncommitted transaction rolls the
                // other endpoint back, preserving heat exactly.
                return 0.0D;
            }
            transaction.commit();
        }
        return transfer;
    }

    public static final class HandlerCache {
        private final Map<LeadAnchor, IHeatHandler> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        public boolean has(ServerLevel level, LeadAnchor anchor) {
            return usable(get(level, anchor));
        }

        public double balance(ServerLevel level, LeadAnchor first, LeadAnchor second, double maxHeat) {
            return MekanismHeatBridge.balance(get(level, first), get(level, second), maxHeat);
        }

        private IHeatHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            IHeatHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            IHeatHandler found = handler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }

    private static boolean usable(IHeatHandler handler) {
        return handler != null
                && finiteAtLeastOne(handler.getHeatCapacity())
                && finiteAtLeastOne(handler.getInverseConduction())
                && finiteNonNegative(handler.getTemperature());
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static boolean finiteAtLeastOne(double value) {
        return Double.isFinite(value) && value >= 1.0D;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }

    private static boolean approximatelyEqual(double expected, double actual) {
        double tolerance = Math.max(HEAT_EPSILON, Math.abs(expected) * 1.0e-9D);
        return Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance;
    }
}
