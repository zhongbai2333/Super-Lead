package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.ItemPulse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ItemFlowAnimator {
    private static final Map<UUID, Deque<ItemPulse>> PULSES = new HashMap<>();
    private static final Map<UUID, ItemPulse> LAST_RECEIVED = new HashMap<>();
    private static final int MAX_PER_CONNECTION = 32;

    private ItemFlowAnimator() {
    }

    public static void queue(ItemPulse pulse) {
        Deque<ItemPulse> q = PULSES.computeIfAbsent(pulse.connectionId(), k -> new ArrayDeque<>());
        q.addLast(pulse);
        LAST_RECEIVED.put(pulse.connectionId(), pulse);
        while (q.size() > MAX_PER_CONNECTION)
            q.pollFirst();
    }

    /** Returns active pulses for the given connection, pruning expired. */
    public static Iterable<ItemPulse> activePulses(UUID id, long currentTick, float partialTick) {
        Deque<ItemPulse> q = PULSES.get(id);
        if (q == null)
            return java.util.Collections.emptyList();
        while (!q.isEmpty()) {
            ItemPulse head = q.peekFirst();
            if (currentTick - head.startTick() > head.durationTicks() + 2) {
                q.pollFirst();
            } else {
                break;
            }
        }
        if (q.isEmpty()) {
            PULSES.remove(id);
            return java.util.Collections.emptyList();
        }
        return q;
    }

    public static void clear(UUID id) {
        PULSES.remove(id);
        LAST_RECEIVED.remove(id);
    }

    public static void retainAll(Set<UUID> activeIds) {
        PULSES.keySet().retainAll(activeIds);
        LAST_RECEIVED.keySet().retainAll(activeIds);
    }

    public static void clearAll() {
        PULSES.clear();
        LAST_RECEIVED.clear();
    }

    /** Read-only persistent receipt used by BenchMod to verify client work feedback. */
    public static ItemPulseBenchProbe probeForBench(UUID connectionId) {
        ItemPulse pulse = LAST_RECEIVED.get(connectionId);
        return pulse == null ? null : new ItemPulseBenchProbe(
                pulse.startTick(), pulse.durationTicks(), pulse.reverse());
    }

    public record ItemPulseBenchProbe(long startTick, int durationTicks, boolean reverse) {
    }
}
