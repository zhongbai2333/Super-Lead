package com.zhongbai233.super_lead.lead.integration.mekanism;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;

/**
 * Small facade over Mekanism's heat capability for thermal leads.
 */
public final class MekanismHeatBridge {
    private MekanismHeatBridge() {
    }

    public static IHeatHandler handler(ServerLevel level, LeadAnchor anchor) {
        IHeatHandler handler = level.getCapability(Capabilities.HEAT, anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(Capabilities.HEAT, anchor.pos(), null);
        }
        return handler;
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

        double tempA = first.getTotalTemperature();
        double tempB = second.getTotalTemperature();
        double capA = first.getTotalHeatCapacity();
        double capB = second.getTotalHeatCapacity();
        if (!finitePositive(capA) || !finitePositive(capB)
                || !Double.isFinite(tempA) || !Double.isFinite(tempB)
                || Math.abs(tempA - tempB) <= 1.0e-6D) {
            return 0.0D;
        }

        boolean firstHotter = tempA > tempB;
        IHeatHandler hot = firstHotter ? first : second;
        IHeatHandler cold = firstHotter ? second : first;
        double hotTemp = firstHotter ? tempA : tempB;
        double coldTemp = firstHotter ? tempB : tempA;
        double hotCap = firstHotter ? capA : capB;
        double coldCap = firstHotter ? capB : capA;

        double equilibrium = ((hotTemp * hotCap) + (coldTemp * coldCap)) / (hotCap + coldCap);
        double heatToEquilibrium = Math.max(0.0D, (hotTemp - equilibrium) * hotCap);
        double transfer = Math.min(maxHeat, heatToEquilibrium);
        if (!Double.isFinite(transfer) || transfer <= 1.0e-6D) {
            return 0.0D;
        }

        hot.handleHeat(-transfer);
        cold.handleHeat(transfer);
        return transfer;
    }

    private static boolean usable(IHeatHandler handler) {
        return handler != null && handler.getHeatCapacitorCount() > 0;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0D;
    }
}