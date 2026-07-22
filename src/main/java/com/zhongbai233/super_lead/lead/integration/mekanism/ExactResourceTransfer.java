package com.zhongbai233.super_lead.lead.integration.mekanism;

import java.util.function.IntPredicate;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Shared all-or-nothing transfer used by optional Mekanism resource bridges. */
final class ExactResourceTransfer {
    private ExactResourceTransfer() {
    }

    static <R extends Resource> int transfer(
            ResourceHandler<R> source, ResourceHandler<R> target, int sourceSlot, R resource, int amount) {
        return transfer(source, target, sourceSlot, resource, amount, ignored -> true);
    }

    static <R extends Resource> int transfer(
            ResourceHandler<R> source, ResourceHandler<R> target, int sourceSlot, R resource, int amount,
            IntPredicate extractedAmountFilter) {
        if (source == null || target == null || resource == null || resource.isEmpty() || amount <= 0) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = source.extract(sourceSlot, resource, amount, tx);
            if (extracted <= 0) {
                return 0;
            }
            if (extractedAmountFilter != null && !extractedAmountFilter.test(extracted)) {
                return 0;
            }
            int inserted = target.insert(resource, extracted, tx);
            if (inserted != extracted) {
                return 0;
            }
            tx.commit();
            return extracted;
        }
    }
}