package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side queue of in-flight item pulses, indexed by connection id. */
public final class ItemFlowAnimator {
    private static final Map<UUID, Deque<SuperLeadPayloads.ItemPulse>> PULSES = new HashMap<>();
    private static final int MAX_PER_CONNECTION = 32;

    private ItemFlowAnimator() {}

    public static void queue(SuperLeadPayloads.ItemPulse pulse) {
        Deque<SuperLeadPayloads.ItemPulse> q = PULSES.computeIfAbsent(pulse.connectionId(), k -> new ArrayDeque<>());
        q.addLast(pulse);
        while (q.size() > MAX_PER_CONNECTION) q.pollFirst();
    }

    /** Returns active pulses for the given connection, pruning expired. */
    public static Iterable<SuperLeadPayloads.ItemPulse> activePulses(UUID id, long currentTick, float partialTick) {
        Deque<SuperLeadPayloads.ItemPulse> q = PULSES.get(id);
        if (q == null) return java.util.Collections.emptyList();
        // Drop expired (head-only optimisation; out-of-order arrivals are rare).
        while (!q.isEmpty()) {
            SuperLeadPayloads.ItemPulse head = q.peekFirst();
            if (currentTick - head.startTick() > head.durationTicks() + 2) {
                q.pollFirst();
            } else {
                break;
            }
        }
        return q;
    }

    public static void clear(UUID id) {
        PULSES.remove(id);
    }

    public static void clearAll() {
        PULSES.clear();
    }
}
