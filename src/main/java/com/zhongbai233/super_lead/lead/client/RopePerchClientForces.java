package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.sim.RopeForceField;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only visual rope loading caused by parrots perched on synced ropes.
 */
public final class RopePerchClientForces {
    private static final EntityTypeTest<Entity, Parrot> PARROTS = EntityTypeTest.forClass(Parrot.class);
    private static final double DETECT_RADIUS = 0.55D;
    private static final double DETECT_RADIUS_SQR = DETECT_RADIUS * DETECT_RADIUS;
    private static final double FORCE_RADIUS = 0.35D;
    private static final double FORCE_RADIUS_SQR = FORCE_RADIUS * FORCE_RADIUS;
    private static final double PARROT_WEIGHT_ACCEL = 0.10D;

    private RopePerchClientForces() {
    }

    public static List<RopeForceField> forConnection(ClientLevel level, LeadConnection connection, RopeSimulation sim) {
        if (level == null || connection == null || sim == null || connection.physicsPreset().isBlank()
                || !sim.physicsEnabled()) {
            return List.of();
        }
        AABB query = sim.currentBounds().inflate(DETECT_RADIUS + 0.35D);
        List<Parrot> parrots = level.getEntities(PARROTS, query,
                parrot -> parrot.isAlive() && !parrot.isRemoved() && !parrot.isPassenger());
        if (parrots.isEmpty()) {
            return List.of();
        }
        ArrayList<RopeForceField> out = new ArrayList<>(parrots.size());
        for (Parrot parrot : parrots) {
            RopeForceField field = forceForParrot(sim, parrot);
            if (field != null) {
                out.add(field);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static RopeForceField forceForParrot(RopeSimulation sim, Parrot parrot) {
        Vec3 pos = parrot.position();
        // Anchor the downward force at the parrot's actual world position so the
        // force tracks the bird even after the rope sags. Using a per-frame-
        // sampled node position would drift apart as soon as the rope bends.
        if (sim.currentBounds().distanceToSqr(pos) > DETECT_RADIUS_SQR * 4.0D) {
            return null;
        }
        return new NodeWeightForce(pos.x, pos.y, pos.z);
    }

    private record NodeWeightForce(double x, double y, double z) implements RopeForceField {
        @Override
        public void sample(double wx, double wy, double wz, long tick, double[] out3) {
            double dx = wx - x;
            double dy = wy - y;
            double dz = wz - z;
            double dSqr = dx * dx + dy * dy + dz * dz;
            if (dSqr > FORCE_RADIUS_SQR) {
                return;
            }
            double falloff = 1.0D - dSqr / FORCE_RADIUS_SQR;
            out3[1] -= PARROT_WEIGHT_ACCEL * falloff;
        }
    }
}