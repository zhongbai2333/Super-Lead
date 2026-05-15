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
        if (t < 0.0F || !visualPushEnabled() || contactPushGain <= 0.0D) {
            contactT = -1.0F;
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
        return visualPushEnabled() && contactPushGain > 0.0D
                && contactT >= 0.0F && (currentTick - contactRefreshTick) <= 5L;
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
        int seg = (int) Math.floor(ct * segments);
        if (seg >= segments)
            seg = segments - 1;
        if (seg < 0)
            seg = 0;
        double frac = ct * segments - seg;
        int i = seg, j = seg + 1;
        double wi = 1.0D - frac, wj = frac;
        if (!pinned[i]) {
            x[i] += contactDx * wi * contactPushGain;
            y[i] += contactDy * wi * contactPushGain;
            z[i] += contactDz * wi * contactPushGain;
        }
        if (!pinned[j]) {
            x[j] += contactDx * wj * contactPushGain;
            y[j] += contactDy * wj * contactPushGain;
            z[j] += contactDz * wj * contactPushGain;
        }
    }
}
