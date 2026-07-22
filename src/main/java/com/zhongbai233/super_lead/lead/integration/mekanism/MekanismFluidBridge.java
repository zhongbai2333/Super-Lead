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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Facade over Mekanism's fluid capability for Super Lead fluid ropes.
 * <p>
 * Mekanism 1.21 registers its fluid handlers under
 * {@code Capabilities.FLUID.block()} rather than NeoForge's
 * {@code Capabilities.Fluid.BLOCK}. This bridge provides the same transfer
 * semantics using Mekanism's capability key while still using NeoForge's
 * {@code ResourceHandler<FluidResource>} model.
 */
public final class MekanismFluidBridge {

    private MekanismFluidBridge() {
    }

    /** Any non-empty fluid passes. */
    public static final Predicate<FluidStack> ANY = stack -> stack != null && !stack.isEmpty();

    public static ResourceHandler<FluidResource> handler(ServerLevel level, LeadAnchor anchor) {
        ResourceHandler<FluidResource> h = level.getCapability(
                mekanism.common.capabilities.Capabilities.FLUID.block(),
                anchor.pos(), anchor.face());
        if (h == null) {
            h = level.getCapability(
                    mekanism.common.capabilities.Capabilities.FLUID.block(),
                    anchor.pos(), null);
        }
        return h;
    }

    public static boolean hasHandler(ServerLevel level, LeadAnchor anchor) {
        return handler(level, anchor) != null;
    }

    public static long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
            Predicate<FluidStack> filter) {
        return transferOne(handler(level, source), handler(level, target), maxAmount, filter);
    }

    public static long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
            Predicate<FluidStack> filter, List<LeadConnection> path) {
        return transferOne(handler(level, source), handler(level, target), maxAmount, filter, path);
    }

    public static long transferOne(ResourceHandler<FluidResource> source, ResourceHandler<FluidResource> target,
            long maxAmount,
            Predicate<FluidStack> filter) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<FluidStack> effectiveFilter = filter == null ? ANY : filter;
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            FluidResource resource = source.getResource(slot);
            if (resource == null || resource.isEmpty()) {
                continue;
            }
            long available = source.getAmountAsLong(slot);
            if (available <= 0L) {
                continue;
            }
            int requested = (int) Math.min(Math.min(maxAmount, available), Integer.MAX_VALUE);
            FluidStack stored = resource.toStack(requested);
            if (stored.isEmpty() || !effectiveFilter.test(stored)) {
                continue;
            }

            int accepted = acceptedAmount(target, stored);
            if (accepted <= 0) {
                continue;
            }

            int transferred = ExactResourceTransfer.transfer(
                    source, target, slot, resource, Math.min(accepted, requested), extracted -> {
                        FluidStack extractedStack = resource.toStack(extracted);
                        return !extractedStack.isEmpty() && effectiveFilter.test(extractedStack);
                    });
            if (transferred > 0) {
                return transferred;
            }
        }
        return 0L;
    }

    public static long transferResourceOne(ResourceHandler<FluidResource> source, ResourceHandler<FluidResource> target,
            int maxAmount,
            Predicate<FluidResource> filter, List<LeadConnection> path) {
        if (source == null || target == null || maxAmount <= 0) {
            return 0L;
        }
        Predicate<FluidResource> effectiveFilter = filter == null ? resource -> true : filter;
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            FluidResource resource = source.getResource(slot);
            if (resource == null || resource.isEmpty() || !effectiveFilter.test(resource)) {
                continue;
            }
            FluidStack sample = resource.toStack(1);
            if (!pathAllows(path, sample)) {
                continue;
            }
            long avail = source.getAmountAsLong(slot);
            if (avail <= 0L) {
                continue;
            }

            int requested = (int) Math.min(maxAmount, avail);
            int accepted = acceptedAmount(target, resource.toStack(requested));
            if (accepted <= 0) {
                continue;
            }

            int transferred = ExactResourceTransfer.transfer(source, target, slot, resource, accepted);
            if (transferred > 0) {
                return transferred;
            }
        }
        return 0L;
    }

    public static long transferOne(ResourceHandler<FluidResource> source, ResourceHandler<FluidResource> target,
            long maxAmount,
            Predicate<FluidStack> filter, List<LeadConnection> path) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<FluidStack> effectiveFilter = filter == null ? ANY : filter;
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            FluidResource resource = source.getResource(slot);
            if (resource == null || resource.isEmpty()) {
                continue;
            }
            long available = source.getAmountAsLong(slot);
            if (available <= 0L) {
                continue;
            }
            int requested = (int) Math.min(Math.min(maxAmount, available), Integer.MAX_VALUE);
            FluidStack stored = resource.toStack(requested);
            if (stored.isEmpty() || !effectiveFilter.test(stored) || !pathAllows(path, stored)) {
                continue;
            }

            try (Transaction tx = Transaction.openRoot()) {
                int inserted = target.insert(resource, requested, tx);
                if (inserted <= 0) {
                    continue;
                }
                int extracted = source.extract(slot, resource, inserted, tx);
                if (extracted != inserted) {
                    continue;
                }
                tx.commit();
                return inserted;
            }
        }
        return 0L;
    }

    private static int acceptedAmount(ResourceHandler<FluidResource> target, FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            return target.insert(FluidResource.of(stack), stack.getAmount(), tx);
        }
    }

    private static boolean pathAllows(List<LeadConnection> path, FluidStack stack) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        for (LeadConnection connection : path) {
            if (!connectionAllowsFluid(connection, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean connectionAllowsFluid(LeadConnection connection, FluidStack stack) {
        boolean hasFilter = false;
        for (RopeAttachment attachment : connection.attachments()) {
            ItemStack sample = attachment.stack();
            if (sample.isEmpty()) {
                continue;
            }
            var contained = net.neoforged.neoforge.transfer.fluid.FluidUtil.getFirstStackContained(sample);
            if (contained.isEmpty()) {
                continue;
            }
            hasFilter = true;
            if (contained.is(stack.getFluid())) {
                return true;
            }
        }
        return !hasFilter;
    }

    public static final class HandlerCache {
        private final Map<LeadAnchor, ResourceHandler<FluidResource>> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        public boolean has(ServerLevel level, LeadAnchor anchor) {
            return get(level, anchor) != null;
        }

        public long transferOne(ServerLevel level, LeadAnchor source, LeadAnchor target, long maxAmount,
                Predicate<FluidStack> filter, List<LeadConnection> path) {
            return MekanismFluidBridge.transferOne(
                    get(level, source), get(level, target), maxAmount, filter, path);
        }

        public long transferOne(ServerLevel level, LeadAnchor source, ResourceHandler<FluidResource> target,
                long maxAmount, Predicate<FluidStack> filter, List<LeadConnection> path) {
            return MekanismFluidBridge.transferOne(get(level, source), target, maxAmount, filter, path);
        }

        private ResourceHandler<FluidResource> get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = new LeadAnchor(anchor.pos().immutable(), anchor.face());
            ResourceHandler<FluidResource> cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            ResourceHandler<FluidResource> found = handler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }
}
