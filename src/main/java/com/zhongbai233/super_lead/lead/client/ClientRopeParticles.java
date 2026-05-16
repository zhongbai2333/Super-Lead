package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.ItemPulse;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.chunk.RopeSectionSnapshot;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;

/** Client-only rope particle and item-flow pulse helpers. */
final class ClientRopeParticles {
    private static final double PARTICLE_DISTANCE = 32.0D;
    private static final double PARTICLE_DISTANCE_SQR = PARTICLE_DISTANCE * PARTICLE_DISTANCE;
    private static final double PARTICLE_FADE_START = 16.0D;
    private static final float[] EMPTY_PULSES = new float[0];
    private static final DustParticleOptions ENERGY_DUST = new DustParticleOptions(0xFFEE55, 1.0F);

    private ClientRopeParticles() {
    }

    static float[] computeItemPulses(LeadConnection connection, long currentTick, float partialTick) {
        if (connection.kind() != LeadKind.ITEM && connection.kind() != LeadKind.FLUID
                && connection.kind() != LeadKind.PRESSURIZED) {
            return EMPTY_PULSES;
        }
        Iterable<ItemPulse> active = ItemFlowAnimator.activePulses(connection.id(), currentTick, partialTick);
        ArrayList<Float> list = new ArrayList<>(4);
        for (ItemPulse p : active) {
            float age = (currentTick - p.startTick()) + partialTick;
            float t = age / Math.max(1, p.durationTicks());
            if (t < 0F || t > 1F) {
                continue;
            }
            float pos = p.reverse() ? 1F - t : t;
            list.add(pos);
        }
        if (list.isEmpty()) {
            return EMPTY_PULSES;
        }
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    static void spawnRedstone(ClientLevel level, RopeSimulation sim, float partialTick,
            LeadConnection connection, double lodDistSqr) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.REDSTONE || !connection.powered()
                || particleScale <= 0.0D || random.nextFloat() > 0.035F * particleScale) {
            return;
        }

        sim.prepareRender(partialTick);
        int segment = random.nextInt(Math.max(1, sim.nodeCount() - 1));
        double t = random.nextDouble();
        double px = sim.renderX(segment) + (sim.renderX(segment + 1) - sim.renderX(segment)) * t;
        double py = sim.renderY(segment) + (sim.renderY(segment + 1) - sim.renderY(segment)) * t;
        double pz = sim.renderZ(segment) + (sim.renderZ(segment + 1) - sim.renderZ(segment)) * t;
        double jitter = 0.035D;
        level.addParticle(
                DustParticleOptions.REDSTONE,
                px + (random.nextDouble() - 0.5D) * jitter,
                py + (random.nextDouble() - 0.5D) * jitter,
                pz + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    static void spawnRedstone(ClientLevel level, RopeSectionSnapshot snapshot,
            LeadConnection connection, double lodDistSqr) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.REDSTONE || !connection.powered()
                || particleScale <= 0.0D || random.nextFloat() > 0.035F * particleScale) {
            return;
        }

        StaticParticlePoint point = sampleStaticParticlePoint(snapshot, random);
        if (point == null) {
            return;
        }
        double jitter = 0.035D;
        level.addParticle(
                DustParticleOptions.REDSTONE,
                point.x() + (random.nextDouble() - 0.5D) * jitter,
                point.y() + (random.nextDouble() - 0.5D) * jitter,
                point.z() + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    static void spawnEnergy(ClientLevel level, RopeSimulation sim, float partialTick,
            LeadConnection connection, double lodDistSqr) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.ENERGY || !connection.powered() || particleScale <= 0.0D) {
            return;
        }
        float density = (float) ((0.04F + Math.min(0.18F, connection.tier() * 0.04F)) * particleScale);
        if (random.nextFloat() > density) {
            return;
        }

        sim.prepareRender(partialTick);
        int segment = random.nextInt(Math.max(1, sim.nodeCount() - 1));
        double t = random.nextDouble();
        double px = sim.renderX(segment) + (sim.renderX(segment + 1) - sim.renderX(segment)) * t;
        double py = sim.renderY(segment) + (sim.renderY(segment + 1) - sim.renderY(segment)) * t;
        double pz = sim.renderZ(segment) + (sim.renderZ(segment + 1) - sim.renderZ(segment)) * t;
        double jitter = 0.045D;
        level.addParticle(
                ENERGY_DUST,
                px + (random.nextDouble() - 0.5D) * jitter,
                py + (random.nextDouble() - 0.5D) * jitter,
                pz + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    static void spawnEnergy(ClientLevel level, RopeSectionSnapshot snapshot, LeadConnection connection,
            double lodDistSqr) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        RandomSource random = level.getRandom();
        double particleScale = particleDistanceScale(lodDistSqr);
        if (connection.kind() != LeadKind.ENERGY || !connection.powered() || particleScale <= 0.0D) {
            return;
        }
        float density = (float) ((0.04F + Math.min(0.18F, connection.tier() * 0.04F)) * particleScale);
        if (random.nextFloat() > density) {
            return;
        }

        StaticParticlePoint point = sampleStaticParticlePoint(snapshot, random);
        if (point == null) {
            return;
        }
        double jitter = 0.045D;
        level.addParticle(
                ENERGY_DUST,
                point.x() + (random.nextDouble() - 0.5D) * jitter,
                point.y() + (random.nextDouble() - 0.5D) * jitter,
                point.z() + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    private static StaticParticlePoint sampleStaticParticlePoint(RopeSectionSnapshot snapshot, RandomSource random) {
        if (snapshot == null || snapshot.nodeCount < 2) {
            return null;
        }
        int segment = random.nextInt(Math.max(1, snapshot.nodeCount - 1));
        int next = Math.min(snapshot.nodeCount - 1, segment + 1);
        double t = random.nextDouble();
        double px = snapshot.x[segment] + (snapshot.x[next] - snapshot.x[segment]) * t;
        double py = snapshot.y[segment] + (snapshot.y[next] - snapshot.y[segment]) * t;
        double pz = snapshot.z[segment] + (snapshot.z[next] - snapshot.z[segment]) * t;
        return new StaticParticlePoint(px, py, pz);
    }

    private static double particleDistanceScale(double lodDistSqr) {
        if (lodDistSqr > PARTICLE_DISTANCE_SQR) {
            return 0.0D;
        }
        double distance = Math.sqrt(lodDistSqr);
        if (distance <= PARTICLE_FADE_START) {
            return 1.0D;
        }
        return Math.max(0.0D, 1.0D - (distance - PARTICLE_FADE_START) / (PARTICLE_DISTANCE - PARTICLE_FADE_START));
    }

    private record StaticParticlePoint(double x, double y, double z) {
    }
}
