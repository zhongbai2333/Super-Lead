package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side, per-player shears zone-selection session. */
public final class PhysicsZoneSelectionManager {
    private static final Permission.HasCommandLevel OP =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private PhysicsZoneSelectionManager() {}

    public static boolean isSelecting(ServerPlayer player) {
        return SELECTIONS.containsKey(player.getUUID());
    }

    public static void toggle(ServerPlayer player) {
        if (isSelecting(player)) cancel(player);
        else start(player);
    }

    public static void start(ServerPlayer player) {
        if (!canUse(player)) return;
        SELECTIONS.put(player.getUUID(), new Selection(null));
        sync(player);
        player.sendSystemMessage(Component.literal(
                "Super Lead: 区域选择已开启。拿剪刀 Shift+右键方块选择两个角。")
            .withStyle(ChatFormatting.GREEN));
    }

    public static void cancel(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
        sync(player);
        player.sendSystemMessage(Component.literal("Super Lead: 区域选择已取消。")
            .withStyle(ChatFormatting.YELLOW));
    }

    public static void clear(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
    }

    public static void clearAndSync(ServerPlayer player) {
        if (SELECTIONS.remove(player.getUUID()) != null) {
            sync(player);
        }
    }

    public static void handleClick(ServerPlayer player, BlockPos pos) {
        if (!canUse(player)) return;
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null) return;
        if (!player.getMainHandItem().is(Items.SHEARS) && !player.getOffhandItem().is(Items.SHEARS)) {
            return;
        }
        if (selection.first == null) {
            SELECTIONS.put(player.getUUID(), new Selection(pos.immutable()));
            sync(player);
            player.sendSystemMessage(Component.literal("Super Lead: 第一个角已选择，继续 Shift+右键选择第二个角。")
                    .withStyle(ChatFormatting.AQUA));
            return;
        }

        BlockPos first = selection.first;
        SELECTIONS.remove(player.getUUID());
        sync(player);
        PacketDistributor.sendToPlayer(player, new OpenZoneCreateScreen(first, pos.immutable()));
    }

    public static void createZone(ServerPlayer player, ZoneCreateRequest request) {
        if (!canUse(player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        String name = request.name() == null ? "" : request.name().trim();
        String preset = request.presetName() == null ? "" : request.presetName().trim();
        AABB area = areaFromCorners(request.from(), request.to());
        boolean ok = PresetServerManager.addZone(level, name, preset, area);
        if (ok) {
            player.sendSystemMessage(Component.literal("Super Lead: 已创建区域 '" + name + "' -> '" + preset + "'.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.literal("Super Lead: 创建区域失败（名称非法、预设不存在或功能被禁用）。")
                    .withStyle(ChatFormatting.RED));
        }
    }

    public static AABB areaFromCorners(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0D,
                Math.max(a.getY(), b.getY()) + 1.0D,
                Math.max(a.getZ(), b.getZ()) + 1.0D);
    }

    public static void sync(ServerPlayer player) {
        Selection selection = SELECTIONS.get(player.getUUID());
        boolean active = selection != null;
        BlockPos first = selection == null ? null : selection.first;
        boolean hasFirst = first != null;
        PacketDistributor.sendToPlayer(player, new ZoneSelectionState(active, hasFirst,
            hasFirst ? first : BlockPos.ZERO));
    }

    private static boolean canUse(ServerPlayer player) {
        return Config.allowOpVisualPresets() && player.permissions().hasPermission(OP);
    }

    private record Selection(BlockPos first) {}
}