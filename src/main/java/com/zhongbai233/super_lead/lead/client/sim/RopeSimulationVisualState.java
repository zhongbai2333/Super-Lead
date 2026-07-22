package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationVisualState extends RopeSimulationRenderCache {
    protected RopeSimulationVisualState(Vec3 a, Vec3 b, long seed, RopeTuning tuning) {
        super(a, b, seed, tuning);
    }

    // ============================================================================================
    // Visual / cosmetic paths (no physics)
    // ============================================================================================
    public void updateVisualLeash(Vec3 a, Vec3 b, long currentTick, float smoothing) {
        lastTouchTick = currentTick;
        double sag = RopeSagModel.midspanSag(a, b, tuning.slack(), tuning.gravity());
        Vec3 sagDir = RopeSagModel.sagDirection(a, b, tuning.gravity(), stableSeparation);
        boolean bend = hasExternalContact(currentTick);
        if (!bend && contactT >= 0.0F) {
            contactT = -1.0F;
        }
        double bendWindow = 0.28D;
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double ropeBend = Math.sin(Math.PI * t) * sag;
            double tx = a.x + (b.x - a.x) * t + sagDir.x * ropeBend;
            double ty = a.y + (b.y - a.y) * t + sagDir.y * ropeBend;
            double tz = a.z + (b.z - a.z) * t + sagDir.z * ropeBend;
            if (bend) {
                double dist = Math.abs(t - contactT);
                if (dist < bendWindow) {
                    double w = 0.5D * (1.0D + Math.cos(Math.PI * dist / bendWindow));
                    tx += contactDx * w;
                    ty += contactDy * w;
                    tz += contactDz * w;
                }
            }
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
            x[i] += (tx - x[i]) * smoothing;
            y[i] += (ty - y[i]) * smoothing;
            z[i] += (tz - z[i]) * smoothing;
            vx[i] = vy[i] = vz[i] = 0.0D;
        }
        // The LOD-off visual catenary differs from the physics steady state, so when
        // the
        // rope LOD-ins later step() must run real physics instead of taking the settled
        // early-out. Mark the sim as unsettled and invalidate the endpoint snapshot so
        // the
        // next awake check trips.
        settledTicks = 0;
        quietTicks = 0;
        endpointInit = false;
        renderStable = false;
        markBoundsDirty();
    }

    /**
     * Drops intentionally skipped simulation-time debt so the following solve
     * advances exactly one fixed physics tick. Used by adaptive scheduling and
     * sparse terrain maintenance; neither path should repay skipped LOD time later.
     */
    public void prepareSingleScheduledStep(long currentTick) {
        lastTouchTick = currentTick;
        lastSteppedTick = currentTick - 1L;
    }

    /**
     * Starts a visual interval from the shape currently on screen. Calling this
     * before every scheduled solve also makes an early HOT upshift continuous.
     */
    public void prepareScheduledRenderStep(long currentTick, int interval) {
        double progress = scheduledRenderProgress(
                currentTick, scheduledRenderStartTick, scheduledRenderDurationTicks);
        for (int i = 0; i < nodes; i++) {
            if (scheduledRenderActive) {
                scheduledRenderX[i] += (x[i] - scheduledRenderX[i]) * progress;
                scheduledRenderY[i] += (y[i] - scheduledRenderY[i]) * progress;
                scheduledRenderZ[i] += (z[i] - scheduledRenderZ[i]) * progress;
            } else {
                scheduledRenderX[i] = x[i];
                scheduledRenderY[i] = y[i];
                scheduledRenderZ[i] = z[i];
            }
        }
        scheduledRenderStartTick = currentTick;
        scheduledRenderDurationTicks = Math.max(1, interval);
        scheduledRenderActive = true;
        renderStable = false;
        renderCacheValid = false;
    }

    /**
     * Starts interpolation after an asynchronous solve is actually published.
     *
     * <p>The worker may finish several client ticks after submission. Its render
     * schedule is therefore stale by publication time. The caller prepares the old
     * on-screen shape before copying worker state; the cached render nodes remain
     * available here as the visual origin for the newly published target.
     */
    public void beginAsyncPublishedRenderStep(long currentTick, int interval) {
        for (int i = 0; i < nodes; i++) {
            scheduledRenderX[i] = renderX[i];
            scheduledRenderY[i] = renderY[i];
            scheduledRenderZ[i] = renderZ[i];
        }
        scheduledRenderStartTick = currentTick;
        scheduledRenderDurationTicks = Math.max(1, interval);
        scheduledRenderActive = true;
        renderStable = false;
        renderCacheValid = false;
    }

    public void setRenderFrameTick(long currentTick) {
        if (renderFrameTick != currentTick && scheduledRenderActive) {
            renderCacheValid = false;
        }
        renderFrameTick = currentTick;
    }

    /**
     * Invalidates settled, terrain, topology and interpolation conclusions after an
     * external lifecycle change. The next step rebuilds them from the current shape.
     */
    public void wakeForPhysicsChange() {
        invalidatePhysicsHistoryForRefinement();
    }

    /**
     * Restores the currently displayed static polyline into a newly-created
     * full-detail simulation, preserving visual continuity while allowing terrain
     * constraints to repair the shape.
     */
    public void restorePolylineForRefinement(double[] sourceX, double[] sourceY, double[] sourceZ, Vec3 a, Vec3 b) {
        restoreShapeForRefinement(sourceX, sourceY, sourceZ, a, b);
    }

    /**
     * Preserve the currently restored mesh shape as a cross-tick render origin.
     * Physics may advance immediately; rendering catches up over a few ticks instead
     * of relying on another frame occurring inside this same logical tick.
     */
    public void beginMeshCollisionRenderTransition(long currentTick, float partialTick) {
        for (int i = 0; i < nodes; i++) {
            transitionX[i] = x[i];
            transitionY[i] = y[i];
            transitionZ[i] = z[i];
        }
        renderTransitionStartTime = currentTick + Math.max(0.0F, Math.min(1.0F, partialTick));
        renderTransitionActive = true;
        scheduledRenderActive = false;
        renderStable = false;
        renderCacheValid = false;
    }

    public boolean hasMeshCollisionRenderTransition() {
        return renderTransitionActive;
    }

    protected void setCatenary(Vec3 a, Vec3 b) {
        RopeSagModel.writeCatenary(a, b, tuning.slack(), tuning.gravity(), stableSeparation, x, y, z);
        for (int i = 0; i < nodes; i++) {
            xLastTick[i] = x[i];
            yLastTick[i] = y[i];
            zLastTick[i] = z[i];
            vx[i] = vy[i] = vz[i] = 0.0D;
        }
        markBoundsDirty();
    }

    protected void setCatenary(Vec3 a, Vec3 b, double sagFactor) {
        setCatenary(a, b);
    }

    public void resetCatenary(Vec3 a, Vec3 b) {
        setCatenary(a, b);
    }

    public void resetCatenary(Vec3 a, Vec3 b, double sagFactor) {
        setCatenary(a, b);
    }

    // ============================================================================================
    // External impulse hooks (reserved for future interactions)
    // ============================================================================================
    public void disturb(Vec3 dir, double strength) {
        for (int i = 1; i < nodes - 1; i++) {
            double s = Math.sin(Math.PI * i / (double) (nodes - 1)) * strength;
            vx[i] += dir.x * s;
            vy[i] += dir.y * s;
            vz[i] += dir.z * s;
        }
    }

    /**
     * Add a falloff-weighted velocity impulse around a world position. Useful for
     * "rope hit" effects.
     */
    public void applyImpulseAt(Vec3 worldPos, Vec3 impulse, double radius) {
        if (radius <= 0.0D)
            return;
        double r2 = radius * radius;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - worldPos.x;
            double dy = y[i] - worldPos.y;
            double dz = z[i] - worldPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2)
                continue;
            double falloff = 1.0D - Math.sqrt(d2) / radius;
            vx[i] += impulse.x * falloff;
            vy[i] += impulse.y * falloff;
            vz[i] += impulse.z * falloff;
        }
    }

    // ============================================================================================
    // External contact (server-broadcast push from a player walking into the rope)
    // ============================================================================================
    /** Set or refresh the contact for this rope. Pass {@code t < 0} to clear. */
    public void setExternalContact(long currentTick, float t, double dx, double dy, double dz) {
        if (!Float.isFinite(t) || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || t < 0.0F || !visualPushEnabled() || contactPushGain <= 0.0D) {
            clearExternalContact();
            return;
        }
        contactT = t;
        contactDx = dx;
        contactDy = dy;
        contactDz = dz;
        contactRefreshTick = currentTick;
    }

    public void clearExternalContact() {
        contactT = -1.0F;
    }

    /**
     * Active iff a contact was set within the last few ticks (handles dropped
     * packets).
     */
    public boolean hasExternalContact(long currentTick) {
        long age = currentTick - contactRefreshTick;
        return visualPushEnabled() && contactPushGain > 0.0D
                && contactT >= 0.0F && age >= 0L && age <= 5L;
    }

    /**
     * Apply the contact as a soft pull on the segment containing {@code contactT}.
     * Called once per game-tick from {@link #step}; XPBD distance constraints
     * subsequently
     * propagate the deformation along the rope.
     */
    protected void applyExternalContactPush(long currentTick) {
        if (!hasExternalContact(currentTick)) {
            contactT = -1.0F;
            return;
        }
        float ct = contactT < 0.0F ? 0.0F : (contactT > 1.0F ? 1.0F : contactT);
        RopeContactResponseModel.Weights response = RopeContactResponseModel.weights(tuning.slack());
        if (!response.hasFlexible()) {
            return;
        }
        int seg = (int) Math.floor(ct * segments);
        if (seg >= segments)
            seg = segments - 1;
        if (seg < 0)
            seg = 0;
        double frac = ct * segments - seg;
        int i = seg, j = seg + 1;
        double wi = 1.0D - frac, wj = frac;
        if (!pinned[i]) {
            x[i] += contactDx * wi * contactPushGain * response.flexible();
            y[i] += contactDy * wi * contactPushGain * response.flexible();
            z[i] += contactDz * wi * contactPushGain * response.flexible();
        }
        if (!pinned[j]) {
            x[j] += contactDx * wj * contactPushGain * response.flexible();
            y[j] += contactDy * wj * contactPushGain * response.flexible();
            z[j] += contactDz * wj * contactPushGain * response.flexible();
        }
    }
}
