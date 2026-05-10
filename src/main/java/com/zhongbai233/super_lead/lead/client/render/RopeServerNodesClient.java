package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.RopeNodesPulse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Client cache of the latest server Verlet snapshot per rope (interior nodes only;
 * endpoints come from the synced {@code LeadConnection}). Driver code reads this each
 * tick and pushes the snapshot into the corresponding {@code RopeSimulation} as a
 * soft target so all clients converge on the same rope shape.
 */
public final class RopeServerNodesClient {
    /** Snapshots older than this (in ticks) are treated as stale and ignored. Server broadcasts
     *  every 2 ticks, so 10 ticks = up to 5 missed pulses before we stop pulling toward it. */
    public static final long STALE_AFTER_TICKS = 10L;

    public record Snapshot(int segments, float[] interior, long receiveTick) {}

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private RopeServerNodesClient() {}

    public static void apply(RopeNodesPulse pulse) {
        long now = currentClientTick();
        int segments = pulse.segments();
        Set<UUID> live = new HashSet<>(pulse.ropes().size() * 2);
        for (RopeNodesPulse.Entry entry : pulse.ropes()) {
            live.add(entry.ropeId());
            CACHE.put(entry.ropeId(), new Snapshot(segments, entry.interior(), now));
        }
        CACHE.keySet().retainAll(live);
    }

    public static Snapshot get(UUID ropeId) {
        Snapshot snap = CACHE.get(ropeId);
        if (snap == null) return null;
        if (currentClientTick() - snap.receiveTick > STALE_AFTER_TICKS) return null;
        return snap;
    }

    public static void clear() { CACHE.clear(); }

    private static long currentClientTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }
}
