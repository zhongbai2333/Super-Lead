package com.zhongbai233.super_lead.lead.client.sim;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Snapshot of an entity body that can push the client-side rope. */
public record RopeEntityContact(AABB box, Vec3 velocity, boolean player) {
}