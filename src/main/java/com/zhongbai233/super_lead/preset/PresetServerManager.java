package com.zhongbai233.super_lead.preset;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import com.zhongbai233.super_lead.lead.cargo.SuperLeadDataComponents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Server-side coordinator for physics presets and OP-defined zones.
 *
 * <p>
 * Zone AABBs stay server-private for normal players. The server stamps each
 * rope with the
 * preset name resolved from the authoritative zone table, then syncs only the
 * dimension's preset
 * packages plus chunk-scoped rope data. The full zone list is sent only through
 * OP-only preview
 * requests.
 */
public final class PresetServerManager {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Permission.HasCommandLevel OP = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final Pattern PLAYER_PRESET_BASE = Pattern.compile("^[A-Za-z0-9_\\-]{1,23}$");
    private static final double BINDER_PICK_RADIUS = 0.95D;

    private PresetServerManager() {
    }

    public static RopePresetLibrary library(MinecraftServer server) {
        return RopePresetLibrary.forServer(server);
    }

    public static boolean canManage(ServerPlayer player) {
        return player != null
                && Config.allowOpVisualPresets()
                && player.permissions().hasPermission(OP);
    }

    public static boolean canEditPreset(ServerPlayer player, String presetName) {
        if (player == null || !Config.allowOpVisualPresets() || !RopePresetLibrary.isValidName(presetName)) {
            return false;
        }
        if (player.permissions().hasPermission(OP)) {
            return true;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        return library(server).load(presetName)
                .map(preset -> preset.ownedBy(player.getUUID()))
                .orElse(false);
    }

    // =============================================================================================
    // Zone CRUD
    // =============================================================================================
    /**
     * Insert or replace a zone in {@code level}. Validates that {@code presetName}
     * exists.
     */
    public static boolean addZone(ServerLevel level, String name, String presetName, AABB area) {
        if (!Config.allowOpVisualPresets())
            return false;
        if (!RopePresetLibrary.isValidName(name))
            return false;
        MinecraftServer server = level.getServer();
        if (server == null)
            return false;
        if (library(server).load(presetName).isEmpty())
            return false;
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        data.put(new PhysicsZone(name, presetName, area));
        refreshZoneUsage(level);
        return true;
    }

    public static boolean removeZone(ServerLevel level, String name) {
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        if (!data.remove(name))
            return false;
        refreshZoneUsage(level);
        return true;
    }

    public static boolean setZoneAdventureRules(ServerLevel level, String name, boolean allow, int limit) {
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        Optional<PhysicsZone> existing = data.get(name);
        if (existing.isEmpty())
            return false;
        data.put(existing.get().withAdventureRules(allow, limit));
        sendZonesToOps(level);
        return true;
    }

    public static List<PhysicsZone> listZones(ServerLevel level) {
        return PhysicsZoneSavedData.get(level).zones();
    }

    public static int adventureRopeCount(ServerLevel level, PhysicsZone zone) {
        int count = 0;
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            if (!connection.adventurePlaced())
                continue;
            PhysicsZone owner = findZoneForConnection(level, connection);
            if (owner != null && owner.name().equals(zone.name())) {
                count++;
            }
        }
        return count;
    }

    public static int clearAdventureRopes(ServerLevel level, String zoneName) {
        Optional<PhysicsZone> zone = PhysicsZoneSavedData.get(level).get(zoneName);
        if (zone.isEmpty())
            return -1;
        Predicate<LeadConnection> predicate = connection -> {
            if (!connection.adventurePlaced())
                return false;
            PhysicsZone owner = findZoneForConnection(level, connection);
            return owner != null && owner.name().equals(zoneName);
        };
        int removed = com.zhongbai233.super_lead.lead.SuperLeadNetwork.removeConnectionsWithoutDrops(level, predicate);
        if (removed > 0) {
            sendZonesToOps(level);
        }
        return removed;
    }

    public static boolean canAdventurePlaceConnection(ServerLevel level, ServerPlayer player,
            com.zhongbai233.super_lead.lead.LeadAnchor from,
            com.zhongbai233.super_lead.lead.LeadAnchor to) {
        if (!Config.allowOpVisualPresets() || player == null || player.isSpectator())
            return false;
        LeadConnection probe = LeadConnection.create(from, to);
        PhysicsZone zone = findZoneForConnection(level, probe);
        if (zone == null || !zone.adventurePlacement())
            return false;
        int limit = zone.adventureLimit();
        return limit <= 0 || adventureRopeCount(level, zone) < limit;
    }

    public static boolean canAdventureStartAt(ServerLevel level, Vec3 point) {
        if (!Config.allowOpVisualPresets())
            return false;
        for (PhysicsZone zone : PhysicsZoneSavedData.get(level).zones()) {
            if (zone.adventurePlacement() && zone.contains(point.x, point.y, point.z)) {
                return zone.adventureLimit() <= 0 || adventureRopeCount(level, zone) < zone.adventureLimit();
            }
        }
        return false;
    }

    /**
     * OP-only zone preview response. This is intentionally not sent to normal
     * clients.
     */
    public static void sendZones(ServerPlayer player, ServerLevel level) {
        if (!canManage(player))
            return;
        PacketDistributor.sendToPlayer(player, new SyncPhysicsZones(zoneEntries(level)));
    }

    private static void sendZonesToOps(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            sendZones(player, level);
        }
    }

    private static List<SyncPhysicsZones.Entry> zoneEntries(ServerLevel level) {
        List<PhysicsZone> zones = PhysicsZoneSavedData.get(level).zones();
        List<SyncPhysicsZones.Entry> entries = new ArrayList<>(zones.size());
        RopePresetLibrary lib = library(level.getServer());
        for (PhysicsZone z : zones) {
            Map<String, String> overrides = lib.load(z.presetName())
                    .map(RopePreset::overrides)
                    .orElse(Map.of());
            entries.add(SyncPhysicsZones.Entry.of(z, overrides));
        }
        return entries;
    }

    private static void refreshZoneUsage(ServerLevel level) {
        boolean changed = refreshRopePresets(level);
        syncDimensionPresets(level);
        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
        sendZonesToOps(level);
    }

    // =============================================================================================
    // Rope preset stamping and dimension preset cache
    // =============================================================================================
    public static String resolvePresetForConnection(ServerLevel level, LeadConnection connection) {
        if (!Config.allowOpVisualPresets())
            return LeadConnection.NO_PHYSICS_PRESET;
        RopePresetLibrary lib = library(level.getServer());
        if (!connection.manualPhysicsPreset().isBlank()) {
            return lib.load(connection.manualPhysicsPreset()).isPresent()
                    ? connection.manualPhysicsPreset()
                    : LeadConnection.NO_PHYSICS_PRESET;
        }
        PhysicsZone zone = findZoneForConnection(level, connection);
        if (zone == null)
            return LeadConnection.NO_PHYSICS_PRESET;
        return lib.load(zone.presetName()).isPresent()
                ? zone.presetName()
                : LeadConnection.NO_PHYSICS_PRESET;
    }

    public static PhysicsZone findZoneForConnection(ServerLevel level, LeadConnection connection) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                SuperLeadNetwork.connections(level));
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        if (a == null || b == null)
            return null;
        return findZoneForRope(PhysicsZoneSavedData.get(level).zones(), a, b);
    }

    public static boolean refreshRopePresets(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        boolean changed = false;
        for (LeadConnection connection : new ArrayList<>(data.connections())) {
            String resolved = resolvePresetForConnection(level, connection);
            if (resolved.equals(connection.physicsPreset())) {
                continue;
            }
            changed |= data.update(connection.id(), c -> c.withPhysicsPreset(resolved), true);
        }
        return changed;
    }

    public static void syncDimensionPresets(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level,
                new SyncDimensionPresets(dimensionPresetOverrides(level)));
    }

    public static void syncDimensionPresets(ServerPlayer player, ServerLevel level) {
        PacketDistributor.sendToPlayer(player, new SyncDimensionPresets(dimensionPresetOverrides(level)));
    }

    private static Map<String, Map<String, String>> dimensionPresetOverrides(ServerLevel level) {
        if (!Config.allowOpVisualPresets())
            return Map.of();

        Set<String> names = new LinkedHashSet<>();
        for (PhysicsZone zone : PhysicsZoneSavedData.get(level).zones()) {
            names.add(zone.presetName());
        }
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            if (!connection.physicsPreset().isBlank()) {
                names.add(connection.physicsPreset());
            }
            if (!connection.manualPhysicsPreset().isBlank()) {
                names.add(connection.manualPhysicsPreset());
            }
        }

        RopePresetLibrary lib = library(level.getServer());
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String name : names) {
            lib.load(name).ifPresent(preset -> out.put(name, preset.overrides()));
        }
        return out;
    }

    private static PhysicsZone findZoneForRope(List<PhysicsZone> zones, Vec3 a, Vec3 b) {
        Vec3 mid = a.add(b).scale(0.5D);
        for (PhysicsZone zone : zones) {
            if (zone.contains(mid.x, mid.y, mid.z))
                return zone;
        }
        for (PhysicsZone zone : zones) {
            if (segmentIntersects(zone.area(), a, b))
                return zone;
        }
        return null;
    }

    private static boolean segmentIntersects(AABB box, Vec3 a, Vec3 b) {
        if (contains(box, a) || contains(box, b))
            return true;
        double t0 = 0.0D;
        double t1 = 1.0D;
        double[] lo = { box.minX, box.minY, box.minZ };
        double[] hi = { box.maxX, box.maxY, box.maxZ };
        double[] p = { a.x, a.y, a.z };
        double[] d = { b.x - a.x, b.y - a.y, b.z - a.z };
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9D) {
                if (p[axis] < lo[axis] || p[axis] >= hi[axis])
                    return false;
                continue;
            }
            double inv = 1.0D / d[axis];
            double ta = (lo[axis] - p[axis]) * inv;
            double tb = (hi[axis] - p[axis]) * inv;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
            }
            if (ta > t0)
                t0 = ta;
            if (tb < t1)
                t1 = tb;
            if (t0 > t1)
                return false;
        }
        return t1 >= 0.0D && t0 <= 1.0D;
    }

    private static boolean contains(AABB box, Vec3 point) {
        return point.x >= box.minX && point.x < box.maxX
                && point.y >= box.minY && point.y < box.maxY
                && point.z >= box.minZ && point.z < box.maxZ;
    }

    // =============================================================================================
    // Lifecycle hooks
    // =============================================================================================
    public static void tickPlayerZones(MinecraftServer server) {
        // Normal clients no longer receive player-position zone application. Ropes
        // carry their
        // own preset names, and players receive only dimension preset packages.
    }

    public static void onLogin(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            boolean changed = refreshRopePresets(level);
            syncDimensionPresets(player, level);
            if (changed) {
                SuperLeadPayloads.sendToDimension(level);
            }
            sendZones(player, level);
        }
    }

    public static void onChangedDimension(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            boolean changed = refreshRopePresets(level);
            syncDimensionPresets(player, level);
            if (changed) {
                SuperLeadPayloads.sendToDimension(level);
            }
            sendZones(player, level);
        }
    }

    public static void onLogout(ServerPlayer player) {
        // No per-player preset state is retained.
    }

    // =============================================================================================
    // Preset library mutation
    // =============================================================================================
    public static boolean editKey(MinecraftServer server, PresetEditKey edit) {
        if (!Config.allowOpVisualPresets())
            return false;
        if (!RopePresetLibrary.isValidName(edit.presetName()))
            return false;
        RopePresetLibrary lib = library(server);
        RopePreset preset = lib.load(edit.presetName())
                .orElse(new RopePreset(edit.presetName(), Map.of()));
        RopePreset updated = edit.clear()
                ? preset.withoutOverride(edit.keyId())
                : preset.withOverride(edit.keyId(), edit.value());
        if (!lib.save(updated))
            return false;

        refreshPresetUsage(server, updated.name());
        return true;
    }

    public static boolean editKey(MinecraftServer server, ServerPlayer player, PresetEditKey edit) {
        if (!Config.allowOpVisualPresets())
            return false;
        if (!RopePresetLibrary.isValidName(edit.presetName()))
            return false;
        if (!canEditPreset(player, edit.presetName()))
            return false;
        RopePresetLibrary lib = library(server);
        Optional<RopePreset> existing = lib.load(edit.presetName());
        boolean op = player.permissions().hasPermission(OP);
        if (existing.isEmpty() && !op)
            return false;
        RopePreset preset = existing.orElseGet(() -> new RopePreset(edit.presetName(), Map.of()));
        RopePreset updated = edit.clear()
                ? preset.withoutOverride(edit.keyId())
                : preset.withOverride(edit.keyId(), edit.value());
        if (!lib.save(updated))
            return false;

        refreshPresetUsage(server, updated.name());
        return true;
    }

    public static Optional<String> createPlayerPreset(ServerPlayer player, ItemStack binderStack, String displayName) {
        if (!Config.allowOpVisualPresets()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.disabled")
                    .withStyle(ChatFormatting.RED));
            return Optional.empty();
        }
        if (player == null || binderStack == null || binderStack.isEmpty()) {
            return Optional.empty();
        }
        if (binderStack.has(SuperLeadDataComponents.PRESET_BINDER.get())) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.already_bound")
                    .withStyle(ChatFormatting.YELLOW));
            return Optional.empty();
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return Optional.empty();
        }

        String baseName = normalizePlayerPresetBase(displayName);
        if (baseName.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.invalid_name")
                    .withStyle(ChatFormatting.RED));
            return Optional.empty();
        }
        String suffix = player.getUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String presetName = baseName + "-" + suffix;
        RopePresetLibrary lib = library(server);
        if (lib.load(presetName).isPresent()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.name_exists", presetName)
                    .withStyle(ChatFormatting.RED));
            return Optional.empty();
        }

        RopePreset preset = new RopePreset(presetName, Map.of(), player.getUUID());
        if (!lib.save(preset)) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.create_failed")
                    .withStyle(ChatFormatting.RED));
            return Optional.empty();
        }

        binderStack.set(SuperLeadDataComponents.PRESET_BINDER.get(),
                new PresetBinderData(presetName, player.getUUID()));
        player.getInventory().setChanged();
        player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.created", presetName)
                .withStyle(ChatFormatting.GREEN));
        return Optional.of(presetName);
    }

    public static boolean toggleBoundPresetInView(ServerPlayer player, ItemStack binderStack) {
        if (!Config.allowOpVisualPresets()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.disabled")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        PresetBinderData binder = binderStack.get(SuperLeadDataComponents.PRESET_BINDER.get());
        if (binder == null || !binder.isBound()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.not_bound")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (!canEditPreset(player, binder.presetName())) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.no_permission")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(player)) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.no_permission")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionInView(level, player, BINDER_PICK_RADIUS);
        if (opt.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset_binder.no_rope")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        LeadConnection target = opt.get();
        boolean removing = binder.presetName().equals(target.manualPhysicsPreset());
        boolean changed = SuperLeadSavedData.get(level).update(target.id(), connection -> {
            LeadConnection withManual = connection.withManualPhysicsPreset(removing
                    ? LeadConnection.NO_PHYSICS_PRESET
                    : binder.presetName());
            return withManual.withPhysicsPreset(resolvePresetForConnection(level, withManual));
        }, true);
        if (!changed) {
            return false;
        }

        syncDimensionPresets(level);
        SuperLeadPayloads.sendToDimension(level);
        player.sendSystemMessage(Component.translatable(removing
                ? "message.super_lead.preset_binder.unbound_rope"
                : "message.super_lead.preset_binder.bound_rope", binder.presetName())
                .withStyle(removing ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        return true;
    }

    private static String normalizePlayerPresetBase(String displayName) {
        String base = displayName == null ? "" : displayName.trim();
        if (!PLAYER_PRESET_BASE.matcher(base).matches()) {
            return "";
        }
        return base;
    }

    /** Re-sync preset package caches and re-stamp ropes after a preset is saved. */
    public static void refreshPresetUsage(MinecraftServer server, String presetName) {
        if (!Config.allowOpVisualPresets())
            return;
        if (!RopePresetLibrary.isValidName(presetName))
            return;
        refreshAllLoadedDimensions(server);
    }

    private static void refreshAllLoadedDimensions(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            boolean changed = refreshRopePresets(level);
            syncDimensionPresets(level);
            if (changed) {
                SuperLeadPayloads.sendToDimension(level);
            }
            sendZonesToOps(level);
        }
    }

    public static void handleListRequest(ServerPlayer player) {
        if (!canManage(player))
            return;
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return;
        PacketDistributor.sendToPlayer(player, new PresetListResponse(library(server).list()));
    }

    public static void exportPresets(ServerPlayer player) {
        if (!canManage(player))
            return;
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return;
        Path exchangeDir = RopePresetLibrary.exchangeDirectory(server);
        int count = exportPresets(server);
        if (count < 0) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset.export_failed", exchangeDir)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        player.sendSystemMessage(Component.translatable("message.super_lead.preset.exported", count, exchangeDir)
                .withStyle(ChatFormatting.GREEN));
    }

    public static void importPresets(ServerPlayer player) {
        if (!canManage(player))
            return;
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return;
        Path exchangeDir = RopePresetLibrary.exchangeDirectory(server);
        int count = importPresets(server);
        if (count < 0) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset.import_failed", exchangeDir)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (count == 0) {
            player.sendSystemMessage(Component.translatable("message.super_lead.preset.import_none", exchangeDir)
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        handleListRequest(player);
        player.sendSystemMessage(Component.translatable("message.super_lead.preset.imported", count, exchangeDir)
                .withStyle(ChatFormatting.GREEN));
    }

    public static int exportPresets(MinecraftServer server) {
        if (!Config.allowOpVisualPresets())
            return -1;
        return library(server).exportAllTo(RopePresetLibrary.exchangeDirectory(server));
    }

    public static int importPresets(MinecraftServer server) {
        if (!Config.allowOpVisualPresets())
            return -1;
        int count = library(server).importAllFrom(RopePresetLibrary.exchangeDirectory(server));
        if (count > 0) {
            refreshAllLoadedDimensions(server);
        }
        return count;
    }

    public static void handleDetailsRequest(ServerPlayer player, String name) {
        boolean canView = canManage(player) || canEditPreset(player, name);
        if (!canView) {
            PacketDistributor.sendToPlayer(player, new PresetDetailsResponse(name, false, Map.of()));
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null)
            return;
        Optional<RopePreset> opt = library(server).load(name);
        PresetDetailsResponse resp = opt
                .map(p -> new PresetDetailsResponse(name, true, p.overrides()))
                .orElseGet(() -> new PresetDetailsResponse(name, false, Map.of()));
        PacketDistributor.sendToPlayer(player, resp);
    }

    /**
     * Refuse if any zone in any loaded dimension still references it; otherwise
     * drop.
     */
    public static boolean deletePreset(MinecraftServer server, String name) {
        for (ServerLevel level : server.getAllLevels()) {
            for (PhysicsZone z : PhysicsZoneSavedData.get(level).zones()) {
                if (z.presetName().equals(name)) {
                    LOG.info("[super_lead] preset delete refused: zone '{}' in dim {} still uses '{}'.",
                            z.name(), level.dimension(), name);
                    return false;
                }
            }
        }
        boolean deleted = library(server).delete(name);
        if (deleted) {
            refreshAllLoadedDimensions(server);
        }
        return deleted;
    }

    /**
     * No-op: prompt-based opt-in is gone. Kept so the existing payload handler
     * still compiles.
     */
    public static void handleResponse(ServerPlayer player, PresetPromptResponse payload) {
        // intentionally empty: ropes and zones decide application now.
    }

    public static void warnDisabled() {
        LOG.warn("[super_lead] preset apply rejected: presets.allow_op_visual_presets=false");
    }
}
