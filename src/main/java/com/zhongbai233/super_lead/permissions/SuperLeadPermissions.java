package com.zhongbai233.super_lead.permissions;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Central NeoForge permission nodes for Super Lead server-side actions.
 *
 * <p>
 * Default resolvers mirror the old vanilla OP-level gates so servers without a
 * custom permission handler keep the same behavior. Permission plugins can grant
 * these nodes independently.
 */
@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadPermissions {
    private static final Permission.HasCommandLevel OP2 = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final Permission.HasCommandLevel OP4 = new Permission.HasCommandLevel(PermissionLevel.OWNERS);

    private SuperLeadPermissions() {
    }

        public static final PermissionNode<Boolean> MANAGE = node("manage", 2);
        public static final PermissionNode<Boolean> ADMIN = node("admin", 4);
        public static final PermissionNode<Boolean> DANGEROUS_CONFIG = node("server_config.dangerous", 4);
        public static final PermissionNode<Boolean> PRESET_EDIT = node("preset.edit", 2);
        public static final PermissionNode<Boolean> PRESET_DELETE = node("preset.delete", 4);
        public static final PermissionNode<Boolean> ZONE_MANAGE = node("zone.manage", 2);
        public static final PermissionNode<Boolean> DEBUG = node("debug", 2);

    @SubscribeEvent
    public static void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(MANAGE, ADMIN, DANGEROUS_CONFIG, PRESET_EDIT, PRESET_DELETE, ZONE_MANAGE, DEBUG);
    }

    public static boolean canManage(ServerPlayer player) {
        return has(player, MANAGE);
    }

    public static boolean canAdmin(ServerPlayer player) {
        return has(player, ADMIN);
    }

    public static boolean canEditDangerousConfig(ServerPlayer player) {
        return has(player, DANGEROUS_CONFIG);
    }

    public static boolean canEditPreset(ServerPlayer player) {
        return has(player, PRESET_EDIT);
    }

    public static boolean canDeletePreset(ServerPlayer player) {
        return has(player, PRESET_DELETE);
    }

    public static boolean canManageZones(ServerPlayer player) {
        return has(player, ZONE_MANAGE);
    }

    public static boolean canDebug(ServerPlayer player) {
        return has(player, DEBUG);
    }

    public static boolean sourceCanManage(CommandSourceStack source) {
        return sourceHas(source, MANAGE);
    }

    public static boolean sourceCanAdmin(CommandSourceStack source) {
        return sourceHas(source, ADMIN);
    }

    public static boolean sourceCanEditDangerousConfig(CommandSourceStack source) {
        return sourceHas(source, DANGEROUS_CONFIG);
    }

    public static boolean sourceCanManageZones(CommandSourceStack source) {
        return sourceHas(source, ZONE_MANAGE);
    }

    public static boolean sourceCanDebug(CommandSourceStack source) {
        return sourceHas(source, DEBUG);
    }

    private static boolean sourceHas(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source == null) {
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            return has(player, node);
        }
        return source.permissions().hasPermission(requiredOpPermission(node));
    }

    private static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        return player != null && PermissionAPI.getPermission(player, node);
    }

    @SuppressWarnings("unchecked")
    private static PermissionNode<Boolean> node(String name, int fallbackOpLevel) {
        return new PermissionNode<Boolean>(Super_lead.MODID, name, PermissionTypes.BOOLEAN,
                (player, playerId, context) -> player != null
                        && player.permissions().hasPermission(fallbackOpLevel >= 4 ? OP4 : OP2))
            .setInformation(
                Component.translatable("permission.name." + Super_lead.MODID + "." + name),
                Component.translatable("permission.desc." + Super_lead.MODID + "." + name));
    }

    private static Permission.HasCommandLevel requiredOpPermission(PermissionNode<Boolean> node) {
        if (node == ADMIN || node == DANGEROUS_CONFIG || node == PRESET_DELETE) {
            return OP4;
        }
        return OP2;
    }
}
