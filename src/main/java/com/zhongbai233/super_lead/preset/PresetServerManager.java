package com.zhongbai233.super_lead.preset;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Server-side coordinator for physics presets and OP-defined zones.
 *
 * <p>Zone AABBs stay server-private for normal players. The server stamps each rope with the
 * preset name resolved from the authoritative zone table, then syncs only the dimension's preset
 * packages plus chunk-scoped rope data. The full zone list is sent only through OP-only preview
 * requests.
 */
public final class PresetServerManager {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Permission.HasCommandLevel OP =
            new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private PresetServerManager() {}

    public static RopePresetLibrary library(MinecraftServer server) {
        return RopePresetLibrary.forServer(server);
    }

    public static boolean canManage(ServerPlayer player) {
        return player != null
                && Config.allowOpVisualPresets()
                && player.permissions().hasPermission(OP);
    }

    // =============================================================================================
    // Zone CRUD
    // =============================================================================================
    /** Insert or replace a zone in {@code level}. Validates that {@code presetName} exists. */
    public static boolean addZone(ServerLevel level, String name, String presetName, AABB area) {
        if (!Config.allowOpVisualPresets()) return false;
        if (!RopePresetLibrary.isValidName(name)) return false;
        MinecraftServer server = level.getServer();
        if (server == null) return false;
        if (library(server).load(presetName).isEmpty()) return false;
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        data.put(new PhysicsZone(name, presetName, area));
        refreshZoneUsage(level);
        return true;
    }

    public static boolean removeZone(ServerLevel level, String name) {
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        if (!data.remove(name)) return false;
        refreshZoneUsage(level);
        return true;
    }

    public static List<PhysicsZone> listZones(ServerLevel level) {
        return PhysicsZoneSavedData.get(level).zones();
    }

    /** OP-only zone preview response. This is intentionally not sent to normal clients. */
    public static void sendZones(ServerPlayer player, ServerLevel level) {
        if (!canManage(player)) return;
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
        if (!Config.allowOpVisualPresets()) return LeadConnection.NO_PHYSICS_PRESET;
        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
        if (a == null || b == null) return LeadConnection.NO_PHYSICS_PRESET;
        PhysicsZone zone = findZoneForRope(PhysicsZoneSavedData.get(level).zones(), a, b);
        if (zone == null) return LeadConnection.NO_PHYSICS_PRESET;
        return library(level.getServer()).load(zone.presetName()).isPresent()
                ? zone.presetName()
                : LeadConnection.NO_PHYSICS_PRESET;
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
        if (!Config.allowOpVisualPresets()) return Map.of();

        Set<String> names = new LinkedHashSet<>();
        for (PhysicsZone zone : PhysicsZoneSavedData.get(level).zones()) {
            names.add(zone.presetName());
        }
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            if (!connection.physicsPreset().isBlank()) {
                names.add(connection.physicsPreset());
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
            if (zone.contains(mid.x, mid.y, mid.z)) return zone;
        }
        for (PhysicsZone zone : zones) {
            if (segmentIntersects(zone.area(), a, b)) return zone;
        }
        return null;
    }

    private static boolean segmentIntersects(AABB box, Vec3 a, Vec3 b) {
        if (contains(box, a) || contains(box, b)) return true;
        double t0 = 0.0D;
        double t1 = 1.0D;
        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};
        double[] p = {a.x, a.y, a.z};
        double[] d = {b.x - a.x, b.y - a.y, b.z - a.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9D) {
                if (p[axis] < lo[axis] || p[axis] >= hi[axis]) return false;
                continue;
            }
            double inv = 1.0D / d[axis];
            double ta = (lo[axis] - p[axis]) * inv;
            double tb = (hi[axis] - p[axis]) * inv;
            if (ta > tb) { double tmp = ta; ta = tb; tb = tmp; }
            if (ta > t0) t0 = ta;
            if (tb < t1) t1 = tb;
            if (t0 > t1) return false;
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
        // Normal clients no longer receive player-position zone application. Ropes carry their
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
        if (!Config.allowOpVisualPresets()) return false;
        if (!RopePresetLibrary.isValidName(edit.presetName())) return false;
        RopePresetLibrary lib = library(server);
        RopePreset preset = lib.load(edit.presetName())
                .orElse(new RopePreset(edit.presetName(), Map.of()));
        RopePreset updated = edit.clear()
                ? preset.withoutOverride(edit.keyId())
                : preset.withOverride(edit.keyId(), edit.value());
        if (!lib.save(updated)) return false;

        refreshPresetUsage(server, updated.name());
        return true;
    }

    /** Re-sync preset package caches and re-stamp ropes after a preset is saved. */
    public static void refreshPresetUsage(MinecraftServer server, String presetName) {
        if (!Config.allowOpVisualPresets()) return;
        if (!RopePresetLibrary.isValidName(presetName)) return;
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
        if (!canManage(player)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        PacketDistributor.sendToPlayer(player, new PresetListResponse(library(server).list()));
    }

    public static void handleDetailsRequest(ServerPlayer player, String name) {
        if (!canManage(player)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        Optional<RopePreset> opt = library(server).load(name);
        PresetDetailsResponse resp = opt
                .map(p -> new PresetDetailsResponse(name, true, p.overrides()))
                .orElseGet(() -> new PresetDetailsResponse(name, false, Map.of()));
        PacketDistributor.sendToPlayer(player, resp);
    }

    /** Refuse if any zone in any loaded dimension still references it; otherwise drop. */
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

    /** No-op: prompt-based opt-in is gone. Kept so the existing payload handler still compiles. */
    public static void handleResponse(ServerPlayer player, PresetPromptResponse payload) {
        // intentionally empty: ropes and zones decide application now.
    }

    public static void warnDisabled() {
        LOG.warn("[super_lead] preset apply rejected: presets.allow_op_visual_presets=false");
    }
}
