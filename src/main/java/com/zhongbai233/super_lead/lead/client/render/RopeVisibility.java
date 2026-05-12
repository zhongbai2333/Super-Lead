package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RopeVisibility {
    // Endpoint visibility samples used by the legacy whole-rope check. Kept for the
    // far LOD
    // bucket where per-segment cost is not worth paying.
    private static final int OCCLUSION_SAMPLES = 5;
    private static final double MIN_RAY_DISTANCE_SQR = 0.25D * 0.25D;
    // Distance buckets for occlusion-cache refresh interval. A rope inside the
    // inner ring is
    // re-tested every frame; outer rings reuse the cached visibility for several
    // frames so far
    // ropes don't pay the per-frame raycast cost. The cache is invalidated
    // automatically when the
    // rope's positions change (RopeSimulation.markBoundsDirty / prepareRender
    // rebuild).
    private static final double NEAR_DIST_SQR = 16.0D * 16.0D;
    private static final double MID_DIST_SQR = 32.0D * 32.0D;
    private static final double FAR_DIST_SQR = 64.0D * 64.0D;
    // Beyond this distance segment-level occlusion is too expensive (one ray per
    // segment) and
    // visually unnoticeable; fall back to the cheaper whole-rope sample-based
    // check.
    private static final double PER_SEGMENT_DIST_SQR = 48.0D * 48.0D;
    // Margin added to per-segment AABBs so thick ropes don't get culled when the
    // centerline
    // sits just outside the frustum.
    private static final double SEGMENT_FRUSTUM_MARGIN = 0.25D;
    private static final double VISIBILITY_RAY_TARGET_BIAS = 0.06D;
    private static final double CAMERA_SHIFT_INVALIDATE_SQR = 1.5D * 1.5D;

    private static long frameSeq;
    private static double lastFrameCamX, lastFrameCamY, lastFrameCamZ;

    private RopeVisibility() {
    }

    /**
     * Called once per submit-event before any shouldRender calls. Bumps the frame
     * counter and,
     * if the camera moved more than {@link #CAMERA_SHIFT_INVALIDATE_SQR}, advances
     * enough so that
     * every cached entry is treated as stale.
     */
    public static void beginFrame(Vec3 cameraPos) {
        double dx = cameraPos.x - lastFrameCamX;
        double dy = cameraPos.y - lastFrameCamY;
        double dz = cameraPos.z - lastFrameCamZ;
        if (dx * dx + dy * dy + dz * dz > CAMERA_SHIFT_INVALIDATE_SQR) {
            frameSeq += 16;
        } else {
            frameSeq++;
        }
        lastFrameCamX = cameraPos.x;
        lastFrameCamY = cameraPos.y;
        lastFrameCamZ = cameraPos.z;
    }

    public static boolean shouldRender(Level level, Entity sourceEntity, Frustum frustum,
            Vec3 cameraPos, AABB renderBounds, RopeSimulation sim, float partialTick) {
        if (frustum != null && !frustum.isVisible(renderBounds)) {
            return false;
        }
        // Distance-based refresh interval. Far/static ropes reuse cached occlusion
        // result.
        double cx = (renderBounds.minX + renderBounds.maxX) * 0.5D - cameraPos.x;
        double cy = (renderBounds.minY + renderBounds.maxY) * 0.5D - cameraPos.y;
        double cz = (renderBounds.minZ + renderBounds.maxZ) * 0.5D - cameraPos.z;
        double dSqr = cx * cx + cy * cy + cz * cz;
        int interval = dSqr < NEAR_DIST_SQR ? 1
                : dSqr < MID_DIST_SQR ? 2
                        : dSqr < FAR_DIST_SQR ? 4
                                : 8;
        long cached = sim.visOcclusionFrame();
        if (cached != Long.MIN_VALUE && (frameSeq - cached) < interval) {
            return sim.visOcclusionResult();
        }
        boolean visible;
        if (dSqr < PER_SEGMENT_DIST_SQR) {
            // Close-up: CPU line-of-sight culling is too coarse for ropes. A single block
            // edge
            // can cover the sampled centerline while half of the thick rendered segment is
            // still
            // visible. Let the render depth path handle block occlusion and only use the
            // frustum
            // here, preserving partial visibility instead of dropping whole segments.
            visible = computePerSegmentFrustumVisibility(frustum, sim, partialTick);
        } else {
            // Far ropes: cheap whole-rope test, mark mask as fully visible (or fully
            // hidden).
            sim.beginSegmentVisibility(0);
            visible = !isFullyOccluded(level, sourceEntity, cameraPos, renderBounds, sim, partialTick);
        }
        sim.setVisOcclusionCache(frameSeq, visible);
        return visible;
    }

    /**
     * Test each rope segment against the frustum, populating
     * {@link RopeSimulation}'s
     * segment-visibility mask. Returns {@code true} if any segment is visible.
     */
    private static boolean computePerSegmentFrustumVisibility(
            Frustum frustum, RopeSimulation sim, float partialTick) {
        sim.prepareRender(partialTick);
        int nodeCount = sim.nodeCount();
        int segCount = nodeCount - 1;
        sim.beginSegmentVisibility(segCount);
        if (segCount <= 0) {
            return false;
        }
        boolean anyVisible = false;
        for (int s = 0; s < segCount; s++) {
            double ax = sim.renderX(s);
            double ay = sim.renderY(s);
            double az = sim.renderZ(s);
            double bx = sim.renderX(s + 1);
            double by = sim.renderY(s + 1);
            double bz = sim.renderZ(s + 1);
            double minX = Math.min(ax, bx) - SEGMENT_FRUSTUM_MARGIN;
            double minY = Math.min(ay, by) - SEGMENT_FRUSTUM_MARGIN;
            double minZ = Math.min(az, bz) - SEGMENT_FRUSTUM_MARGIN;
            double maxX = Math.max(ax, bx) + SEGMENT_FRUSTUM_MARGIN;
            double maxY = Math.max(ay, by) + SEGMENT_FRUSTUM_MARGIN;
            double maxZ = Math.max(az, bz) + SEGMENT_FRUSTUM_MARGIN;
            if (frustum != null) {
                AABB segBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                if (!frustum.isVisible(segBox)) {
                    continue;
                }
            }
            sim.setSegmentVisible(s, true);
            anyVisible = true;
        }
        return anyVisible;
    }

    private static boolean isFullyOccluded(Level level, Entity sourceEntity,
            Vec3 cameraPos, AABB renderBounds, RopeSimulation sim, float partialTick) {
        if (contains(renderBounds, cameraPos)) {
            return false;
        }
        sim.prepareRender(partialTick);
        int nodeCount = sim.nodeCount();
        if (nodeCount <= 0) {
            return false;
        }
        int last = nodeCount - 1;
        for (int sample = 0; sample < OCCLUSION_SAMPLES; sample++) {
            int idx = (int) Math.round(last * (sample / (double) (OCCLUSION_SAMPLES - 1)));
            if (hasLineOfSight(level, sourceEntity, cameraPos,
                    sim.renderX(idx), sim.renderY(idx), sim.renderZ(idx))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLineOfSight(Level level, Entity sourceEntity, Vec3 cameraPos,
            double tx, double ty, double tz) {
        double dx = tx - cameraPos.x;
        double dy = ty - cameraPos.y;
        double dz = tz - cameraPos.z;
        double distSqr = dx * dx + dy * dy + dz * dz;
        if (distSqr <= MIN_RAY_DISTANCE_SQR) {
            return true;
        }
        double dist = Math.sqrt(distSqr);
        double bias = Math.min(VISIBILITY_RAY_TARGET_BIAS, dist * 0.25D);
        if (bias > 0.0D) {
            double invDist = 1.0D / dist;
            tx -= dx * invDist * bias;
            ty -= dy * invDist * bias;
            tz -= dz * invDist * bias;
        }
        return !rayHitsOpaque(level, cameraPos.x, cameraPos.y, cameraPos.z, tx, ty, tz);
    }

    /**
     * Voxel-DDA walk along the ray (camera → target) that treats only blocks
     * satisfying
     * {@link BlockState#canOcclude()} AND {@link BlockState#isSolidRender()} as
     * occluders.
     * This keeps glass, leaves, slabs/stairs (have non-empty outline shapes),
     * trapdoors, doors,
     * ice, slime, honey, beacons, sea lanterns, etc. transparent to rope visibility
     * — the
     * vanilla {@code ClipContext.Block.OUTLINE} clip would otherwise stop on every
     * one of them
     * because they have a full cube outline.
     * <p>
     * The {@code sourceEntity} parameter is intentionally unused: rope visibility
     * is purely a
     * world-block test so it ignores entity hitboxes.
     */
    private static boolean rayHitsOpaque(Level level, double sx, double sy, double sz,
            double tx, double ty, double tz) {
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        // Standard Amanatides-Woo voxel traversal between two world points.
        int x = (int) Math.floor(sx);
        int y = (int) Math.floor(sy);
        int z = (int) Math.floor(sz);
        int endX = (int) Math.floor(tx);
        int endY = (int) Math.floor(ty);
        int endZ = (int) Math.floor(tz);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (isOpaqueOccluder(level, cursor.set(x, y, z))) {
            return true;
        }
        if (x == endX && y == endY && z == endZ) {
            return false;
        }
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX != 0 ? Math.abs(1.0D / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0D / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0D / dz) : Double.POSITIVE_INFINITY;
        double tMaxX = stepX > 0 ? ((x + 1) - sx) / dx
                : (stepX < 0 ? (x - sx) / dx : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? ((y + 1) - sy) / dy
                : (stepY < 0 ? (y - sy) / dy : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? ((z + 1) - sz) / dz
                : (stepZ < 0 ? (z - sz) / dz : Double.POSITIVE_INFINITY);
        // Hard cap: rope render distance is bounded so a few hundred steps is plenty
        // even at
        // the diagonal extreme; this guards against any pathological NaN-fuelled loop.
        for (int guard = 0; guard < 512; guard++) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0D)
                        return false;
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ > 1.0D)
                        return false;
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1.0D)
                    return false;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0D)
                    return false;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (isOpaqueOccluder(level, cursor.set(x, y, z))) {
                return true;
            }
            if (x == endX && y == endY && z == endZ) {
                return false;
            }
        }
        return false;
    }

    private static boolean isOpaqueOccluder(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            // Treat unloaded chunks as transparent so far ropes near a chunk border don't
            // flicker depending on chunk-load timing.
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return false;
        // canOcclude → "renders as a solid block face" (false for glass, leaves, slabs
        // unless
        // top half, etc.). isSolidRender → "fully opaque on every face". Combining them
        // filters
        // out non-opaque shapes that nevertheless have a full collision/outline cube.
        return state.canOcclude() && state.isSolidRender();
    }

    private static boolean contains(AABB box, Vec3 p) {
        return p.x >= box.minX && p.x <= box.maxX
                && p.y >= box.minY && p.y <= box.maxY
                && p.z >= box.minZ && p.z <= box.maxZ;
    }
}
