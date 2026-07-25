package com.zhongbai233.super_lead.lead;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Canonical client rope changes produced by applying one chunk snapshot. */
public record ConnectionDelta(Set<UUID> addedIds, Set<UUID> updatedIds, Set<UUID> removedIds) {
    public static final ConnectionDelta EMPTY = new ConnectionDelta(Set.of(), Set.of(), Set.of());

    public ConnectionDelta {
        addedIds = Set.copyOf(addedIds);
        updatedIds = Set.copyOf(updatedIds);
        removedIds = Set.copyOf(removedIds);
    }

    public boolean isEmpty() {
        return addedIds.isEmpty() && updatedIds.isEmpty() && removedIds.isEmpty();
    }

    public Set<UUID> changedIds() {
        if (isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UUID> changed = new LinkedHashSet<>(addedIds);
        changed.addAll(updatedIds);
        changed.addAll(removedIds);
        return Set.copyOf(changed);
    }
}