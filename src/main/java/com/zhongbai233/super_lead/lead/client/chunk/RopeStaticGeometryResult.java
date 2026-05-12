package com.zhongbai233.super_lead.lead.client.chunk;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RopeStaticGeometryResult {
    public static final RopeStaticGeometryResult EMPTY = new RopeStaticGeometryResult((RopeSectionSnapshot) null,
            Set.of());

    public final RopeSectionSnapshot snapshot;
    public final Set<Long> sectionKeys;
    public final Map<Long, List<RopeSectionSnapshot>> snapshotsBySection;

    public RopeStaticGeometryResult(RopeSectionSnapshot snapshot, Set<Long> sectionKeys) {
        this.snapshot = snapshot;
        this.sectionKeys = sectionKeys;
        if (snapshot == null || sectionKeys.isEmpty()) {
            this.snapshotsBySection = Map.of();
        } else {
            java.util.HashMap<Long, List<RopeSectionSnapshot>> map = new java.util.HashMap<>();
            for (long key : sectionKeys) {
                map.put(key, List.of(snapshot));
            }
            this.snapshotsBySection = Map.copyOf(map);
        }
    }

    public RopeStaticGeometryResult(Map<Long, List<RopeSectionSnapshot>> snapshotsBySection, Set<Long> sectionKeys) {
        this.sectionKeys = Set.copyOf(sectionKeys);
        this.snapshotsBySection = Map.copyOf(snapshotsBySection);
        RopeSectionSnapshot first = null;
        for (List<RopeSectionSnapshot> list : snapshotsBySection.values()) {
            if (!list.isEmpty()) {
                first = list.get(0);
                break;
            }
        }
        this.snapshot = first;
    }
}
