package com.zhongbai233.super_lead.lead;

import net.minecraft.world.phys.Vec3;

record ConnectionPick(LeadConnection connection, Vec3 point, double distanceSqr, double along) {}
