package com.zhongbai233.super_lead.lead;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;

record PlayerKey(UUID playerId, boolean clientSide) {
    static PlayerKey of(Player player) {
        return new PlayerKey(player.getUUID(), player.level().isClientSide());
    }
}
