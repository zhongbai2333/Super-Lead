package com.zhongbai233.super_lead.preset;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.Config;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Server-side coordinator for {@link RopePreset} application.
 * <p>
 * Presets are no longer attached to individual players. Instead OPs define a
 * {@link PhysicsZone} per dimension; when a player's position falls inside a zone, the
 * zone's preset is applied to that player. On leaving every zone, the preset is cleared.
 * The per-tick scan is cheap (small zone count, simple AABB tests).
 */
public final class PresetServerManager {
    private static final Logger LOG = LogUtils.getLogger();
    /** How often (in server ticks) the player→zone tracker re-evaluates. 4 = 5 Hz, plenty. */
    private static final long ZONE_TICK_INTERVAL = 4L;

    /** In-memory state: which preset (if any) is currently applied to a given player. Lives only
     *  for the session; on rejoin it's recomputed from position. */
    private static final Map<UUID, String> CURRENT_PRESET = new HashMap<>();

    private PresetServerManager() {}

    public static RopePresetLibrary library(MinecraftServer server) {
        return RopePresetLibrary.forServer(server);
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
        broadcastZones(level);
        retickAllPlayers(server);
        return true;
    }

    public static boolean removeZone(ServerLevel level, String name) {
        PhysicsZoneSavedData data = PhysicsZoneSavedData.get(level);
        if (!data.remove(name)) return false;
        broadcastZones(level);
        MinecraftServer server = level.getServer();
        if (server != null) retickAllPlayers(server);
        return true;
    }

    public static List<PhysicsZone> listZones(ServerLevel level) {
        return PhysicsZoneSavedData.get(level).zones();
    }

    private static void broadcastZones(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, new SyncPhysicsZones(zoneEntries(level)));
    }

    public static void sendZones(ServerPlayer player, ServerLevel level) {
        PacketDistributor.sendToPlayer(player, new SyncPhysicsZones(zoneEntries(level)));
    }

    private static List<SyncPhysicsZones.Entry> zoneEntries(ServerLevel level) {
        List<PhysicsZone> zones = PhysicsZoneSavedData.get(level).zones();
        List<SyncPhysicsZones.Entry> entries = new java.util.ArrayList<>(zones.size());
        RopePresetLibrary lib = library(level.getServer());
        for (PhysicsZone z : zones) {
            Map<String, String> overrides = lib.load(z.presetName())
                    .map(RopePreset::overrides)
                    .orElse(Map.of());
            entries.add(SyncPhysicsZones.Entry.of(z, overrides));
        }
        return entries;
    }

    /** Force every online player through the zone evaluator (e.g. after a CRUD operation). */
    private static void retickAllPlayers(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            evaluatePlayer(p);
        }
    }

    // =============================================================================================
    // Per-tick player tracking
    // =============================================================================================
    /** Called from a LevelTickEvent every server tick; cheap when there are no zones. */
    public static void tickPlayerZones(MinecraftServer server) {
        if (!Config.allowOpVisualPresets()) return;
        if ((server.getTickCount() % ZONE_TICK_INTERVAL) != 0L) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            evaluatePlayer(p);
        }
    }

    /** Decide which preset (if any) the player should currently have, and only send a
     *  packet when it differs from what's already applied. Called per-tick, on login, and on
     *  dimension change. */
    public static void evaluatePlayer(ServerPlayer player) {
        if (!Config.allowOpVisualPresets()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        Optional<PhysicsZone> zone = PhysicsZoneSavedData.get(level)
                .findContaining(player.getX(), player.getY(), player.getZ());
        UUID id = player.getUUID();
        String currentApplied = CURRENT_PRESET.get(id);

        if (zone.isEmpty()) {
            if (currentApplied != null) {
                CURRENT_PRESET.remove(id);
                sendZones(player, level);
                PacketDistributor.sendToPlayer(player, PresetClearOverrides.INSTANCE);
            }
            return;
        }
        String presetName = zone.get().presetName();
        if (presetName.equals(currentApplied)) return;
        Optional<RopePreset> preset = library(level.getServer()).load(presetName);
        if (preset.isEmpty()) {
            // Zone references a missing preset; treat as no-zone.
            if (currentApplied != null) {
                CURRENT_PRESET.remove(id);
                PacketDistributor.sendToPlayer(player, PresetClearOverrides.INSTANCE);
            }
            return;
        }
        CURRENT_PRESET.put(id, presetName);
        sendZones(player, level);
        PacketDistributor.sendToPlayer(player, new PresetApplyOverrides(presetName, preset.get().overrides()));
    }

    // =============================================================================================
    // Lifecycle hooks
    // =============================================================================================
    /** Called on player login: send the dimension's zone list, then evaluate zone membership. */
    public static void onLogin(ServerPlayer player) {
        if (!Config.allowOpVisualPresets()) return;
        if (player.level() instanceof ServerLevel level) {
            sendZones(player, level);
        }
        evaluatePlayer(player);
    }

    public static void onChangedDimension(ServerPlayer player) {
        // Remove the cache entry so the next evaluation always re-applies (the override semantics
        // depend on dimension-local zone data which the client must also refresh).
        CURRENT_PRESET.remove(player.getUUID());
        if (player.level() instanceof ServerLevel level) {
            sendZones(player, level);
        }
        evaluatePlayer(player);
    }

    public static void onLogout(ServerPlayer player) {
        CURRENT_PRESET.remove(player.getUUID());
    }

    // =============================================================================================
    // Preset library mutation (still OP-driven, unchanged user-facing flow)
    // =============================================================================================
    public static boolean editKey(MinecraftServer server, PresetEditKey edit) {
        if (!Config.allowOpVisualPresets()) return false;
        if (!RopePresetLibrary.isValidName(edit.presetName())) return false;
        RopePresetLibrary lib = library(server);
        RopePreset preset = lib.load(edit.presetName())
                .orElse(new RopePreset(edit.presetName(), java.util.Map.of()));
        RopePreset updated = edit.clear()
                ? preset.withoutOverride(edit.keyId())
                : preset.withOverride(edit.keyId(), edit.value());
        if (!lib.save(updated)) return false;

        refreshPresetUsage(server, updated.name());
        return true;
    }

    /** Re-broadcast zone metadata and currently-applied player overrides after a preset is saved. */
    public static void refreshPresetUsage(MinecraftServer server, String presetName) {
        if (!Config.allowOpVisualPresets()) return;
        if (!RopePresetLibrary.isValidName(presetName)) return;
        Optional<RopePreset> updated = library(server).load(presetName);

        // Keep every observer's zone→preset cache fresh, including players outside the zone who
        // can still see ropes inside it.
        for (ServerLevel level : server.getAllLevels()) {
            boolean usesPreset = false;
            for (PhysicsZone z : PhysicsZoneSavedData.get(level).zones()) {
                if (z.presetName().equals(presetName)) { usesPreset = true; break; }
            }
            if (usesPreset) broadcastZones(level);
        }

        // Re-push the updated overrides to every player currently inside a zone bound to this preset.
        Set<UUID> targets = new HashSet<>();
        for (Map.Entry<UUID, String> e : CURRENT_PRESET.entrySet()) {
            if (e.getValue().equals(presetName)) targets.add(e.getKey());
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (targets.contains(p.getUUID()) && updated.isPresent()) {
                PacketDistributor.sendToPlayer(p, new PresetApplyOverrides(updated.get().name(), updated.get().overrides()));
            }
        }
    }

    public static void handleListRequest(ServerPlayer player) {
        Permission.HasCommandLevel op = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
        if (!player.permissions().hasPermission(op)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        PacketDistributor.sendToPlayer(player, new PresetListResponse(library(server).list()));
    }

    public static void handleDetailsRequest(ServerPlayer player, String name) {
        Permission.HasCommandLevel op = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
        if (!player.permissions().hasPermission(op)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        Optional<RopePreset> opt = library(server).load(name);
        PresetDetailsResponse resp = opt
                .map(p -> new PresetDetailsResponse(name, true, p.overrides()))
                .orElseGet(() -> new PresetDetailsResponse(name, false, java.util.Map.of()));
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
        return library(server).delete(name);
    }

    /** No-op: prompt-based opt-in is gone. Kept so the existing payload handler still compiles. */
    public static void handleResponse(ServerPlayer player, PresetPromptResponse payload) {
        // intentionally empty: zones decide application now.
    }

    public static void warnDisabled() {
        LOG.warn("[super_lead] preset apply rejected: presets.allow_op_visual_presets=false");
    }
}
