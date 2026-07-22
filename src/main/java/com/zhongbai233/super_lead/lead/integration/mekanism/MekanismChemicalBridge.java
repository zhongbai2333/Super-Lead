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
import mekanism.api.MekanismAPITags;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.ChemicalStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Small, generic-free facade over Mekanism's chemical capability.
 * <p>
 * Super Lead's item/fluid transfer code is based on NeoForge's
 * {@code ResourceHandler<R extends Resource>} model. Mekanism 26.1 exposes
 * chemicals as {@link ChemicalResource} handlers, so keep the Mekanism-specific
 * resource-to-stack conversion here and let the rope logic call simple
 * ChemicalStack-based methods.
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

    public static ResourceHandler<ChemicalResource> handler(ServerLevel level, LeadAnchor anchor) {
        ResourceHandler<ChemicalResource> handler = level.getCapability(Capabilities.CHEMICAL.block(), anchor.pos(),
                anchor.face());
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

    public static long transferOne(ResourceHandler<ChemicalResource> source, ResourceHandler<ChemicalResource> target,
            long maxAmount,
            ChemicalFilter filter) {
        ChemicalFilter effectiveFilter = filter == null ? ChemicalFilter.ANY : filter;
        return transferOne(source, target, maxAmount, effectiveFilter::accepts);
    }

    public static long transferOne(ResourceHandler<ChemicalResource> source, ResourceHandler<ChemicalResource> target,
            long maxAmount,
            ChemicalFilter filter, List<LeadConnection> path) {
        ChemicalFilter effectiveFilter = filter == null ? ChemicalFilter.ANY : filter;
        return transferOne(source, target, maxAmount,
                stack -> effectiveFilter.accepts(stack) && pathAllows(path, stack));
    }

    public static long transferOne(ResourceHandler<ChemicalResource> source, ResourceHandler<ChemicalResource> target,
            long maxAmount,
            Predicate<ChemicalStack> filter) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<ChemicalStack> effectiveFilter = filter == null ? ChemicalFilter.ANY::accepts : filter;
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            ChemicalResource resource = source.getResource(slot);
            if (resource == null || resource.isEmpty()) {
                continue;
            }
            long available = source.getAmountAsLong(slot);
            if (available <= 0L) {
                continue;
            }

            int requested = (int) Math.min(Math.min(maxAmount, available), Integer.MAX_VALUE);
            ChemicalStack stored = resource.toStack(requested);
            if (!effectiveFilter.test(stored)) {
                continue;
            }

            int accepted = acceptedAmount(target, stored);
            if (accepted <= 0) {
                continue;
            }

            int transferred = ExactResourceTransfer.transfer(
                    source, target, slot, resource, Math.min(accepted, requested),
                    extracted -> effectiveFilter.test(resource.toStack(extracted)));
            if (transferred > 0) {
                return transferred;
            }
        }
        return 0L;
    }

    public static final class HandlerCache {
        private final Map<LeadAnchor, ResourceHandler<ChemicalResource>> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        public boolean has(ServerLevel level, LeadAnchor anchor) {
            return get(level, anchor) != null;
        }

        public long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
                ChemicalFilter filter, List<LeadConnection> path) {
            return MekanismChemicalBridge.transferOne(get(level, source), get(level, target), maxAmount, filter, path);
        }

        private ResourceHandler<ChemicalResource> get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            ResourceHandler<ChemicalResource> cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            ResourceHandler<ChemicalResource> found = handler(level, key);
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
        ResourceHandler<ChemicalResource> handler = Capabilities.CHEMICAL.getCapability(ItemAccess.forStack(stack));
        if (handler == null) {
            return ChemicalStack.EMPTY;
        }
        int slots = handler.size();
        for (int slot = 0; slot < slots; slot++) {
            ChemicalResource resource = handler.getResource(slot);
            long amount = handler.getAmountAsLong(slot);
            if (resource != null && !resource.isEmpty() && amount > 0L) {
                return resource.toStack((int) Math.min(amount, Integer.MAX_VALUE));
            }
        }
        return ChemicalStack.EMPTY;
    }

    private static int acceptedAmount(ResourceHandler<ChemicalResource> target, ChemicalStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            return target.insert(ChemicalResource.of(stack), stack.amount(), tx);
        }
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

}