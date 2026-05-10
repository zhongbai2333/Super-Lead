package com.zhongbai233.super_lead.serverconfig;

import com.zhongbai233.super_lead.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerConfigManager {
    private ServerConfigManager() {}

    private static final Permission.HasCommandLevel OP =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    public static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(OP);
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (!isOp(player)) return;
        PacketDistributor.sendToPlayer(player, new ServerConfigSnapshot(Config.snapshot()));
    }

    public static void handleSet(ServerPlayer player, ServerConfigSet payload) {
        if (!isOp(player)) return;
        if (Config.applyRuntime(payload.key(), payload.value())) {
            sendSnapshot(player);
        }
    }
}
