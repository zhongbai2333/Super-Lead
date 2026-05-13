package com.zhongbai233.super_lead.lead.integration.mekanism;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import mekanism.api.Action;
import mekanism.api.MekanismAPITags;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;

/**
 * Small, generic-free facade over Mekanism's chemical capability.
 * <p>
 * Super Lead's item/fluid transfer code is based on NeoForge's
 * {@code ResourceHandler<R extends Resource>} model. Mekanism 26.1 exposes
 * chemicals through its own {@link IChemicalHandler} instead, so trying to force
 * chemicals through the existing generic transfer path makes call sites hard to
 * read and fragile. Keep all Mekanism-specific API use here and let the rope
 * logic call simple ChemicalStack-based methods.
 */
public final class MekanismChemicalBridge {
    private MekanismChemicalBridge() {
    }

    public enum ChemicalFilter {
        ANY,
        GASEOUS,
        NON_GASEOUS;

        public boolean accepts(ChemicalStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            boolean gaseous = stack.typeHolder().is(MekanismAPITags.Chemicals.GASEOUS);
            return switch (this) {
                case ANY -> true;
                case GASEOUS -> gaseous;
                case NON_GASEOUS -> !gaseous;
            };
        }
    }

    public static IChemicalHandler handler(ServerLevel level, LeadAnchor anchor) {
        IChemicalHandler handler = level.getCapability(Capabilities.CHEMICAL.block(), anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(Capabilities.CHEMICAL.block(), anchor.pos(), null);
        }
        return handler;
    }

    public static boolean hasHandler(ServerLevel level, LeadAnchor anchor) {
        return handler(level, anchor) != null;
    }

    public static long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
            ChemicalFilter filter) {
        return transferOne(handler(level, source), handler(level, target), maxAmount, filter);
    }

    public static long transferOne(IChemicalHandler source, IChemicalHandler target, long maxAmount,
            ChemicalFilter filter) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        ChemicalFilter effectiveFilter = filter == null ? ChemicalFilter.ANY : filter;
        int tanks = source.getChemicalTanks();
        for (int tank = 0; tank < tanks; tank++) {
            ChemicalStack stored = source.getChemicalInTank(tank);
            if (!effectiveFilter.accepts(stored)) {
                continue;
            }

            long requested = Math.min(maxAmount, stored.amount());
            ChemicalStack simulatedExtract = source.extractChemical(tank, requested, Action.SIMULATE);
            if (!effectiveFilter.accepts(simulatedExtract)) {
                continue;
            }

            long accepted = acceptedAmount(target, simulatedExtract);
            if (accepted <= 0L) {
                continue;
            }

            ChemicalStack extracted = source.extractChemical(tank, Math.min(accepted, simulatedExtract.amount()),
                    Action.EXECUTE);
            if (!effectiveFilter.accepts(extracted)) {
                reinsert(source, extracted);
                continue;
            }

            ChemicalStack remainder = target.insertChemical(extracted, Action.EXECUTE);
            long inserted = Math.max(0L, extracted.amount() - remainder.amount());
            reinsert(source, remainder);
            if (inserted > 0L) {
                return inserted;
            }
        }
        return 0L;
    }

    private static long acceptedAmount(IChemicalHandler target, ChemicalStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        ChemicalStack remainder = target.insertChemical(stack, Action.SIMULATE);
        return Math.max(0L, stack.amount() - remainder.amount());
    }

    private static void reinsert(IChemicalHandler source, ChemicalStack stack) {
        if (source != null && stack != null && !stack.isEmpty()) {
            source.insertChemical(stack, Action.EXECUTE);
        }
    }
}