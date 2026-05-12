package com.zhongbai233.super_lead.lead.client.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Per-step cache of world-space block collision boxes keyed by packed BlockPos.
 * Cleared each
 * outer step before the constraint loop so all inner iterations share box
 * lookups for free.
 */
final class BlockCollisionCache {
    private static final AABB[] EMPTY = new AABB[0];
    private final Long2ObjectOpenHashMap<AABB[]> map = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private boolean readOnly;

    void reset() {
        map.clear();
        readOnly = false;
    }

    void setReadOnly(boolean ro) {
        this.readOnly = ro;
    }

    /**
     * Pre-fill the cache for every BlockPos in the inclusive bbox by querying
     * {@code level}.
     * Must be called on the main thread; after this returns, worker threads can
     * safely call
     * {@link #aabbsAt} as long as their queries land inside the prefetched bbox.
     */
    void prefetch(Level level, int bx0, int by0, int bz0, int bx1, int by1, int bz1) {
        for (int by = by0; by <= by1; by++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int bx = bx0; bx <= bx1; bx++) {
                    aabbsAt(level, bx, by, bz);
                }
            }
        }
    }

    AABB[] aabbsAt(Level level, int bx, int by, int bz) {
        cursor.set(bx, by, bz);
        long key = cursor.asLong();
        AABB[] cached = map.get(key);
        if (cached != null)
            return cached;
        if (readOnly)
            return EMPTY;

        BlockState state = level.getBlockState(cursor);
        VoxelShape shape = state.getCollisionShape(level, cursor);
        if (shape.isEmpty()) {
            map.put(key, EMPTY);
            return EMPTY;
        }
        List<AABB> raw = shape.toAabbs();
        AABB[] boxes = new AABB[raw.size()];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = raw.get(i).move(bx, by, bz);
        }
        map.put(key, boxes);
        return boxes;
    }
}
