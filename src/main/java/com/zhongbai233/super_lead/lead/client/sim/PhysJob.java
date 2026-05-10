package com.zhongbai233.super_lead.lead.client.sim;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class PhysJob {
    public final RopeSimulation sim;
    public final Vec3 a;
    public final Vec3 b;
    public final List<RopeSimulation> nbrs;
    public final List<AABB> eboxes;

    public PhysJob(RopeSimulation sim, Vec3 a, Vec3 b, List<RopeSimulation> nbrs, List<AABB> eboxes) {
        this.sim = sim;
        this.a = a;
        this.b = b;
        this.nbrs = nbrs;
        this.eboxes = eboxes;
    }
}
