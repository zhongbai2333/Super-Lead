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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Facade over Mekanism's fluid capability for Super Lead fluid ropes.
 * <p>
 * Mekanism 1.21 registers its fluid handlers under
 * {@code Capabilities.FLUID.block()} (Mekanism's own capability key wrapping
 * NeoForge's {@code IFluidHandler}) rather than NeoForge's
 * {@code Capabilities.Fluid.BLOCK}. The generic rope transfer engine uses the
 * NeoForge resource-handler model and silently skips Mekanism tanks. This
 * bridge
 * provides the same transfer semantics using Mekanism's native API.
 */
@SuppressWarnings("removal")
public final class MekanismFluidBridge {

    private MekanismFluidBridge() {
    }

    /** Any non-empty fluid passes. */
    public static final Predicate<FluidStack> ANY = stack -> stack != null && !stack.isEmpty();

    public static IFluidHandler handler(ServerLevel level, LeadAnchor anchor) {
        IFluidHandler h = level.getCapability(
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

    public static long transferOne(IFluidHandler source, IFluidHandler target, long maxAmount,
            Predicate<FluidStack> filter) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<FluidStack> effectiveFilter = filter == null ? ANY : filter;
        int tanks = source.getTanks();
        for (int tank = 0; tank < tanks; tank++) {
            FluidStack stored = source.getFluidInTank(tank);
            if (stored.isEmpty() || !effectiveFilter.test(stored)) {
                continue;
            }

            int requested = (int) Math.min(maxAmount, stored.getAmount());
            FluidStack drainStack = new FluidStack(stored.getFluid(), requested, stored.getComponentsPatch());
            FluidStack simulatedExtract = source.drain(drainStack, IFluidHandler.FluidAction.SIMULATE);
            if (simulatedExtract.isEmpty() || !effectiveFilter.test(simulatedExtract)) {
                continue;
            }

            long accepted = acceptedAmount(target, simulatedExtract);
            if (accepted <= 0L) {
                continue;
            }

            int execAmount = (int) Math.min(accepted, simulatedExtract.getAmount());
            FluidStack execDrain = new FluidStack(simulatedExtract.getFluid(), execAmount,
                    simulatedExtract.getComponentsPatch());
            FluidStack extracted = source.drain(execDrain, IFluidHandler.FluidAction.EXECUTE);
            if (extracted.isEmpty() || !effectiveFilter.test(extracted)) {
                // Put back what we took
                if (!extracted.isEmpty()) {
                    source.fill(extracted, IFluidHandler.FluidAction.EXECUTE);
                }
                continue;
            }

            int inserted = target.fill(extracted, IFluidHandler.FluidAction.EXECUTE);
            int remainder = Math.max(0, extracted.getAmount() - inserted);
            if (remainder > 0) {
                source.fill(new FluidStack(extracted.getFluid(), remainder,
                        extracted.getComponentsPatch()),
                        IFluidHandler.FluidAction.EXECUTE);
            }
            if (inserted > 0L) {
                return inserted;
            }
        }
        return 0L;
    }

    public static long transferOne(IFluidHandler source, IFluidHandler target, long maxAmount,
            Predicate<FluidStack> filter, List<LeadConnection> path) {
        Predicate<FluidStack> effectiveFilter = filter == null ? ANY : filter;
        return transferOne(source, target, maxAmount,
                stack -> effectiveFilter.test(stack) && pathAllows(path, stack));
    }

    public static long transferOne(ResourceHandler<FluidResource> source, IFluidHandler target, int maxAmount,
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
            int accepted = target.fill(resource.toStack(requested), IFluidHandler.FluidAction.SIMULATE);
            if (accepted <= 0) {
                continue;
            }

            try (Transaction tx = Transaction.openRoot()) {
                int extracted = source.extract(slot, resource, accepted, tx);
                if (extracted <= 0) {
                    continue;
                }
                int inserted = target.fill(resource.toStack(extracted), IFluidHandler.FluidAction.EXECUTE);
                if (inserted <= 0) {
                    continue;
                }
                tx.commit();
                return inserted;
            }
        }
        return 0L;
    }

    public static long transferOne(IFluidHandler source, ResourceHandler<FluidResource> target, long maxAmount,
            Predicate<FluidStack> filter, List<LeadConnection> path) {
        if (source == null || target == null || maxAmount <= 0L) {
            return 0L;
        }
        Predicate<FluidStack> effectiveFilter = filter == null ? ANY : filter;
        int tanks = source.getTanks();
        for (int tank = 0; tank < tanks; tank++) {
            FluidStack stored = source.getFluidInTank(tank);
            if (stored.isEmpty() || !effectiveFilter.test(stored) || !pathAllows(path, stored)) {
                continue;
            }

            int requested = (int) Math.min(maxAmount, stored.getAmount());
            FluidStack drainStack = new FluidStack(stored.getFluid(), requested, stored.getComponentsPatch());
            FluidStack simulatedExtract = source.drain(drainStack, IFluidHandler.FluidAction.SIMULATE);
            if (simulatedExtract.isEmpty() || !effectiveFilter.test(simulatedExtract)) {
                continue;
            }

            FluidResource resource = FluidResource.of(simulatedExtract);
            try (Transaction tx = Transaction.openRoot()) {
                int inserted = target.insert(resource, simulatedExtract.getAmount(), tx);
                if (inserted <= 0) {
                    continue;
                }
                FluidStack extracted = source.drain(resource.toStack(inserted), IFluidHandler.FluidAction.EXECUTE);
                if (extracted.getAmount() != inserted) {
                    if (!extracted.isEmpty()) {
                        source.fill(extracted, IFluidHandler.FluidAction.EXECUTE);
                    }
                    continue;
                }
                tx.commit();
                return inserted;
            }
        }
        return 0L;
    }

    private static long acceptedAmount(IFluidHandler target, FluidStack stack) {
        return target.fill(stack, IFluidHandler.FluidAction.SIMULATE);
    }

    private static boolean pathAllows(List<LeadConnection> path, FluidStack stack) {
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
        private final Map<LeadAnchor, IFluidHandler> hits = new HashMap<>();
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

        private IFluidHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = new LeadAnchor(anchor.pos().immutable(), anchor.face());
            IFluidHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            IFluidHandler found = handler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }
}
