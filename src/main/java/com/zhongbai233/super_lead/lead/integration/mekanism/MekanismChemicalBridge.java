package com.zhongbai233.super_lead.lead.integration.mekanism;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import mekanism.api.Action;
import mekanism.api.MekanismAPITags;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;

/**
 * Small, generic-free facade over Mekanism's chemical capability.
 * <p>
 * Super Lead's item/fluid transfer code is based on NeoForge's
 * {@code ResourceHandler<R extends Resource>} model. Mekanism 26.1 exposes
 * chemicals through its own {@link IChemicalHandler} instead, so trying to
 * force
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

    public static long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
            ChemicalFilter filter, List<LeadConnection> path) {
        return transferOne(handler(level, source), handler(level, target), maxAmount, filter, path);
    }

    public static long transferOne(IChemicalHandler source, IChemicalHandler target, long maxAmount,
            ChemicalFilter filter) {
        ChemicalFilter effectiveFilter = filter == null ? ChemicalFilter.ANY : filter;
        return transferOne(source, target, maxAmount, effectiveFilter::accepts);
    }

    public static long transferOne(IChemicalHandler source, IChemicalHandler target, long maxAmount,
            ChemicalFilter filter, List<LeadConnection> path) {
        ChemicalFilter effectiveFilter = filter == null ? ChemicalFilter.ANY : filter;
        return transferOne(source, target, maxAmount,
                stack -> effectiveFilter.accepts(stack) && pathAllows(path, stack));
    }

    public static long transferOne(IChemicalHandler source, IChemicalHandler target, long maxAmount,
            Predicate<ChemicalStack> filter) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<ChemicalStack> effectiveFilter = filter == null ? ChemicalFilter.ANY::accepts : filter;
        int tanks = source.getChemicalTanks();
        for (int tank = 0; tank < tanks; tank++) {
            ChemicalStack stored = source.getChemicalInTank(tank);
            if (!effectiveFilter.test(stored)) {
                continue;
            }

            long requested = Math.min(maxAmount, stored.amount());
            ChemicalStack simulatedExtract = source.extractChemical(tank, requested, Action.SIMULATE);
            if (!effectiveFilter.test(simulatedExtract)) {
                continue;
            }

            long accepted = acceptedAmount(target, simulatedExtract);
            if (accepted <= 0L) {
                continue;
            }

            ChemicalStack extracted = source.extractChemical(tank, Math.min(accepted, simulatedExtract.amount()),
                    Action.EXECUTE);
            if (!effectiveFilter.test(extracted)) {
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

    public static final class HandlerCache {
        private final Map<LeadAnchor, IChemicalHandler> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        public boolean has(ServerLevel level, LeadAnchor anchor) {
            return get(level, anchor) != null;
        }

        public long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
                ChemicalFilter filter, List<LeadConnection> path) {
            return MekanismChemicalBridge.transferOne(get(level, source), get(level, target), maxAmount, filter, path);
        }

        private IChemicalHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            IChemicalHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            IChemicalHandler found = handler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }

    private static boolean pathAllows(List<LeadConnection> path, ChemicalStack stack) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        for (LeadConnection connection : path) {
            if (!connectionAllows(connection, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean connectionAllows(LeadConnection connection, ChemicalStack stack) {
        if (connection == null || stack == null || stack.isEmpty() || connection.attachments().isEmpty()) {
            return true;
        }
        boolean hasChemicalFilter = false;
        for (RopeAttachment attachment : connection.attachments()) {
            ChemicalStack sample = firstChemicalContained(attachment.stack());
            if (sample.isEmpty()) {
                continue;
            }
            hasChemicalFilter = true;
            if (ChemicalStack.isSameChemical(stack, sample)) {
                return true;
            }
        }
        return !hasChemicalFilter;
    }

    private static ChemicalStack firstChemicalContained(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ChemicalStack.EMPTY;
        }
        IChemicalHandler handler = Capabilities.CHEMICAL.getCapability(ItemAccess.forStack(stack));
        if (handler == null) {
            return ChemicalStack.EMPTY;
        }
        int tanks = handler.getChemicalTanks();
        for (int tank = 0; tank < tanks; tank++) {
            ChemicalStack stored = handler.getChemicalInTank(tank);
            if (stored != null && !stored.isEmpty()) {
                return stored.copy();
            }
        }
        return ChemicalStack.EMPTY;
    }

    private static long acceptedAmount(IChemicalHandler target, ChemicalStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        ChemicalStack remainder = target.insertChemical(stack, Action.SIMULATE);
        return Math.max(0L, stack.amount() - remainder.amount());
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static void reinsert(IChemicalHandler source, ChemicalStack stack) {
        if (source != null && stack != null && !stack.isEmpty()) {
            source.insertChemical(stack, Action.EXECUTE);
        }
    }
}