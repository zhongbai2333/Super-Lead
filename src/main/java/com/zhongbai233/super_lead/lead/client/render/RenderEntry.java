package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record RenderEntry(
        LeadConnection connection,
        RopeSimulation sim,
        Vec3 a,
        Vec3 b,
        double midDistSqr,
        AABB bounds,
        AABB physicsBounds) {}
