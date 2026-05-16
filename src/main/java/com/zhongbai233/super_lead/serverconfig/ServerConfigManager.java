package com.zhongbai233.super_lead.serverconfig;

import com.zhongbai233.super_lead.Config;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.neoforge.network.PacketDistributor;

/** Handles OP-gated runtime server configuration sync and mutation requests. */
public final class ServerConfigManager {
    private ServerConfigManager() {
    }

    private static final Permission.HasCommandLevel OP = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final Permission.HasCommandLevel DANGEROUS_OP = new Permission.HasCommandLevel(
            PermissionLevel.OWNERS);

    /**
     * Runtime knobs that can multiply tick work, transfer bursts, or rope scan
     * fan-out. They stay visible to OP2 admins, but mutation requires OP4.
     */
    private static final Set<String> DANGEROUS_KEYS = Set.of(
            "energy.tier_max_level",
            "energy.base_transfer_per_tick",
            "network.max_leash_distance",
            "network.item_tier_max",
            "network.fluid_tier_max",
            "network.pressurized_tier_max",
            "network.pressurized_batch_amount",
            "network.thermal_tier_max",
            "network.thermal_transfer_per_tick",
            "network.item_transfer_interval_ticks",
            "network.fluid_bucket_amount",
            "network.max_ropes_per_block_face");

    public static boolean isOp(ServerPlayer player) {
        return player != null && player.permissions().hasPermission(OP);
    }

    public static boolean isDangerousKey(String key) {
        return DANGEROUS_KEYS.contains(key);
    }

    public static Permission.HasCommandLevel dangerousPermission() {
        return DANGEROUS_OP;
    }

    public static boolean canEditKey(ServerPlayer player, String key) {
        return isOp(player)
                && (!isDangerousKey(key) || player.permissions().hasPermission(DANGEROUS_OP));
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (!isOp(player))
            return;
        PacketDistributor.sendToPlayer(player, new ServerConfigSnapshot(Config.snapshot()));
    }

    public static void handleSet(ServerPlayer player, ServerConfigSet payload) {
        if (!isOp(player))
            return;
        if (!canEditKey(player, payload.key())) {
            player.sendSystemMessage(
                    Component.translatable("message.super_lead.server_config.op4_required", payload.key())
                            .withStyle(ChatFormatting.RED));
            sendSnapshot(player);
            return;
        }
        if (Config.applyRuntime(payload.key(), payload.value())) {
            sendSnapshot(player);
        }
    }
}
