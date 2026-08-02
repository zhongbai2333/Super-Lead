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
        double progress = scheduledVisualProgress(
                currentTick, scheduledRenderStartTick, scheduledRenderDurationTicks);
        for (int i = 0; i < nodes; i++) {
            if (renderCacheValid) {
                // Continue from pixels that were actually submitted. Advancing the old
                // interval to integer currentTick can skip its unseen remainder when
                // the previous display frame occurred at partialTick 0.1-0.3, which
                // makes each new solve appear as a 50-150ms freeze followed by a jump.
                scheduledRenderX[i] = renderX[i];
                scheduledRenderY[i] = renderY[i];
                scheduledRenderZ[i] = renderZ[i];
            } else if (scheduledRenderActive) {
                // No frame has consumed this state yet (headless test, hidden rope or
                // delayed first render). Generate a bounded theoretical handoff so the
                // interval still progresses instead of restarting from stale nodes.
                double nodeProgress = nodeVisualProgressForHandoff(i, progress);
                scheduledRenderX[i] += (x[i] - scheduledRenderX[i]) * nodeProgress;
                scheduledRenderY[i] += (y[i] - scheduledRenderY[i]) * nodeProgress;
                scheduledRenderZ[i] += (z[i] - scheduledRenderZ[i]) * nodeProgress;
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
        invalidateRenderCacheState();
    }

    /**
     * Returns rendering to the ordinary previous-tick/current-tick interpolation
     * used when physics publishes every client tick.
     */
    public void prepareDirectRenderStep() {
        scheduledRenderActive = false;
        scheduledRenderStartTick = UNINIT;
        scheduledRenderDurationTicks = 1;
        renderStable = false;
        // A direct solve can legitimately early-out after snapshotting xLastTick to x.
        // Always invalidate here, even when we were already in direct mode, so that
        // the resulting stable frame cannot reuse an older partial-tick snapshot.
        invalidateRenderCacheState();
    }

    private double nodeVisualProgressForHandoff(int node, double progress) {
        if (progress <= 1.0D || node <= 0 || node >= nodes - 1) {
            return Math.min(1.0D, progress);
        }
        double dx = x[node] - scheduledRenderX[node];
        double dy = y[node] - scheduledRenderY[node];
        double dz = z[node] - scheduledRenderZ[node];
        double displacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (displacement <= 1.0e-9D) {
            return 1.0D;
        }
        return 1.0D + Math.min(progress - 1.0D, 0.08D / displacement);
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
        if (interval <= 1) {
            prepareDirectRenderStep();
            return;
        }
        for (int i = 0; i < nodes; i++) {
            scheduledRenderX[i] = renderX[i];
            scheduledRenderY[i] = renderY[i];
            scheduledRenderZ[i] = renderZ[i];
        }
        scheduledRenderStartTick = currentTick;
        scheduledRenderDurationTicks = Math.max(1, interval);
        scheduledRenderActive = true;
        renderStable = false;
        invalidateRenderCacheState();
    }

    public void setRenderFrameTick(long currentTick) {
        // Keep the last prepared visual nodes available as the handoff origin for a
        // scheduled solve later in this client tick. Production discovers visible
        // ropes (and calls this method) before stepConnectionEntry calls
        // prepareScheduledRenderStep; invalidating here discarded the last pixels
        // and made each interval restart from a theoretical integer-tick position.
        // prepareRender cannot incorrectly reuse this cache across ticks because its
        // cache key also requires renderCacheFrameTick == renderFrameTick.
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
     * Freezes the hidden live simulation at the exact polyline accepted into the
     * chunk mesh. Unlike a refinement restore this remains settled: no skipped time
     * or stale velocity may accumulate while the baked pixels are visible.
     */
    public void freezeAtStaticMesh(
            double[] sourceX, double[] sourceY, double[] sourceZ,
            Vec3 a, Vec3 b, long currentTick) {
        restoreShapeForRefinement(sourceX, sourceY, sourceZ, a, b);
        settledTicks = settleThresholdTicks;
        quietTicks = Math.max(quietTicks, settleThresholdTicks);
        ropeStackQuietTicks = Math.max(ropeStackQuietTicks, settleThresholdTicks);
        lastTouchTick = currentTick;
        lastSteppedTick = currentTick;
        endpointInit = true;
        lastAx = a.x;
        lastAy = a.y;
        lastAz = a.z;
        lastBx = b.x;
        lastBy = b.y;
        lastBz = b.z;
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
        // Merely leaving the mesh does not provide a dynamic target yet. The solve
        // may be deferred by the shared physics deadline; consuming the visual
        // interval now would finish it against an unchanged target and make the
        // eventual publication teleport. Keep the visible mesh frozen until the
        // driver explicitly publishes the first completed dynamic solve.
        renderTransitionStartTime = UNINIT;
        renderTransitionActive = true;
        renderTransitionTargetReady = false;
        scheduledRenderActive = false;
        renderStable = false;
        invalidateRenderCacheState();
    }

    /** Starts the mesh-to-dynamic interpolation after its first target is solved. */
    public void publishMeshCollisionRenderTarget(long currentTick, float partialTick) {
        if (!renderTransitionActive || renderTransitionTargetReady) {
            return;
        }
        renderTransitionStartTime = currentTick + Math.max(0.0F, Math.min(1.0F, partialTick));
        renderTransitionTargetReady = true;
        renderStable = false;
        invalidateRenderCacheState();
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
