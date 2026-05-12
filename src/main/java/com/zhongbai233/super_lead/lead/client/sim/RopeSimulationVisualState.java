package com.zhongbai233.super_lead.lead.client.sim;

import net.minecraft.world.phys.Vec3;

abstract class RopeSimulationVisualState extends RopeSimulationRenderCache {
    protected RopeSimulationVisualState(Vec3 a, Vec3 b, long seed, boolean tight, RopeTuning tuning) {
        super(a, b, seed, tight, tuning);
    }

    // ============================================================================================
    // Visual / cosmetic paths (no physics)
    // ============================================================================================
    public void updateVisualLeash(Vec3 a, Vec3 b, long currentTick, float smoothing) {
        lastTouchTick = currentTick;
        double sag = Math.min(0.55D, a.distanceTo(b) * 0.055D);
        boolean bend = contactT >= 0.0F;
        double bendWindow = 0.28D;
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double tx = a.x + (b.x - a.x) * t;
            double ty = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double tz = a.z + (b.z - a.z) * t;
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

    protected void setCatenary(Vec3 a, Vec3 b, double sagFactor) {
        double sag = Math.min(0.55D, a.distanceTo(b) * sagFactor);
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double nx = a.x + (b.x - a.x) * t;
            double ny = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double nz = a.z + (b.z - a.z) * t;
            x[i] = nx;
            y[i] = ny;
            z[i] = nz;
            xLastTick[i] = nx;
            yLastTick[i] = ny;
            zLastTick[i] = nz;
            vx[i] = vy[i] = vz[i] = 0.0D;
        }
        markBoundsDirty();
    }

    public void resetCatenary(Vec3 a, Vec3 b, double sagFactor) {
        setCatenary(a, b, sagFactor);
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
        if (t < 0.0F) {
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
        return contactT >= 0.0F && (currentTick - contactRefreshTick) <= 5L;
    }

    // ============================================================================================
    // Server Verlet snapshot (coarse-to-fine sync)
    // ============================================================================================
    /**
     * Push the latest server-side Verlet shape for this rope. {@code interior} is
     * the
     * interleaved xyz triples of the server's interior nodes (length must be
     * {@code (segments-1)*3}). Pass {@code segments <= 0} or null array to disable.
     */
    public void setServerNodes(long currentTick, int segments, float[] interior) {
        if (segments <= 0 || interior == null || interior.length != Math.max(0, segments - 1) * 3) {
            serverNodesSegments = 0;
            serverInterior = null;
            serverNodesRefreshTick = Long.MIN_VALUE;
            return;
        }
        serverNodesSegments = segments;
        serverInterior = java.util.Arrays.copyOf(interior, interior.length);
        serverNodesRefreshTick = currentTick;
    }

    public void clearServerNodes() {
        serverNodesSegments = 0;
        serverInterior = null;
        serverNodesRefreshTick = Long.MIN_VALUE;
    }

    protected boolean hasFreshServerNodes(long currentTick) {
        return serverNodesSegments > 0
                && serverInterior != null
                && (currentTick - serverNodesRefreshTick) <= SERVER_BLEND_STALE_TICKS;
    }

    /**
     * Soft pull of every interior node toward its corresponding point on the server
     * polyline.
     * Called once per game tick; XPBD constraints subsequently smooth and
     * re-tighten the chain.
     */
    protected void applyServerNodeBlend(Vec3 a, Vec3 b, long currentTick) {
        if (!hasFreshServerNodes(currentTick))
            return;
        int sSeg = serverNodesSegments;
        // Walk client interior nodes 1..nodes-2.
        for (int j = 1; j < nodes - 1; j++) {
            if (pinned[j])
                continue;
            double tj = j / (double) segments;
            // Locate the server segment containing tj.
            double sPos = tj * sSeg;
            int sIdx = (int) Math.floor(sPos);
            if (sIdx < 0)
                sIdx = 0;
            if (sIdx > sSeg - 1)
                sIdx = sSeg - 1;
            double frac = sPos - sIdx;
            // Server polyline points: P0 = a, P1..P_{sSeg-1} = interior, P_{sSeg} = b.
            double p0x, p0y, p0z, p1x, p1y, p1z;
            if (sIdx == 0) {
                p0x = a.x;
                p0y = a.y;
                p0z = a.z;
            } else {
                int o = (sIdx - 1) * 3;
                p0x = serverInterior[o];
                p0y = serverInterior[o + 1];
                p0z = serverInterior[o + 2];
            }
            int next = sIdx + 1;
            if (next >= sSeg) {
                p1x = b.x;
                p1y = b.y;
                p1z = b.z;
            } else {
                int o = (next - 1) * 3;
                p1x = serverInterior[o];
                p1y = serverInterior[o + 1];
                p1z = serverInterior[o + 2];
            }
            double tx = p0x + (p1x - p0x) * frac;
            double ty = p0y + (p1y - p0y) * frac;
            double tz = p0z + (p1z - p0z) * frac;
            x[j] += (tx - x[j]) * SERVER_BLEND_ALPHA;
            y[j] += (ty - y[j]) * SERVER_BLEND_ALPHA;
            z[j] += (tz - z[j]) * SERVER_BLEND_ALPHA;
        }
    }

    /**
     * Apply the contact as a soft pull on the segment containing {@code contactT}.
     * Called once per game-tick from {@link #step}; XPBD distance constraints
     * subsequently
     * propagate the deformation along the rope.
     */
    protected void applyExternalContactPush() {
        if (contactT < 0.0F)
            return;
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
            x[i] += contactDx * wi * CONTACT_PUSH_GAIN;
            y[i] += contactDy * wi * CONTACT_PUSH_GAIN;
            z[i] += contactDz * wi * CONTACT_PUSH_GAIN;
        }
        if (!pinned[j]) {
            x[j] += contactDx * wj * CONTACT_PUSH_GAIN;
            y[j] += contactDy * wj * CONTACT_PUSH_GAIN;
            z[j] += contactDz * wj * CONTACT_PUSH_GAIN;
        }
    }
}
