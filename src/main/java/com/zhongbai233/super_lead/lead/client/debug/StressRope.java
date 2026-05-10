package com.zhongbai233.super_lead.lead.client.debug;

import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

record StressRope(
        UUID id,
        RopeSimulation sim,
        Vec3 baseA,
        Vec3 baseB,
        double moveX,
        double moveY,
        double moveZ,
        double phase,
        double amplitude,
        double speed,
        boolean moving) {
    Vec3 a(long tick) {
        if (!moving) return baseA;
        double offset = Math.sin(tick * speed + phase) * amplitude;
        return new Vec3(baseA.x + moveX * offset, baseA.y + moveY * offset, baseA.z + moveZ * offset);
    }

    Vec3 b(long tick) {
        if (!moving) return baseB;
        double offset = Math.sin(tick * speed + phase + Math.PI) * amplitude;
        return new Vec3(baseB.x + moveX * offset, baseB.y + moveY * offset, baseB.z + moveZ * offset);
    }

    AABB bounds(long tick) {
        Vec3 a = a(tick);
        Vec3 b = b(tick);
        return new AABB(
                Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z))
                .minmax(sim.currentBounds());
    }
}
