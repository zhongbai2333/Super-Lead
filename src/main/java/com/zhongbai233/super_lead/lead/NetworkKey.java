package com.zhongbai233.super_lead.lead;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record NetworkKey(ResourceKey<Level> dimension, boolean clientSide) {
    static NetworkKey of(Level level) {
        return new NetworkKey(level.dimension(), level.isClientSide());
    }
}
