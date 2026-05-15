package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.lead.cargo.CargoManifestData;
import com.zhongbai233.super_lead.lead.integration.ae2.AE2NetworkBridge;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismChemicalBridge;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismHeatBridge;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class SuperLeadNetwork {
    public static final double MAX_LEASH_DISTANCE = 12.0D;
    private static final double SERVER_CONFIRMED_PICK_RADIUS = 0.95D;
    /**
     * Wider envelope for attachment removal so multi-rope stacks don't foil the
     * server check.
     */
    private static final double SERVER_CONFIRMED_ATTACH_REMOVAL_RADIUS = 2.50D;
    private static final double SERVER_CONFIRMED_PICK_REACH = MAX_LEASH_DISTANCE + 1.5D;
    private static final int SERVER_CONFIRMED_CURVE_SAMPLES = 12;
    private static final double SERVER_CONFIRMED_CURVE_SAG_PER_BLOCK = 0.065D;
    private static final double SERVER_CONFIRMED_CURVE_MAX_SAG = 0.70D;
    private static final double SERVER_CLAIM_RAY_RADIUS = 1.15D;
    private static final double SERVER_CLAIM_ROPE_RADIUS = 1.35D;
    private static final double SERVER_CLAIM_T_SLACK = 0.35D;
    private static final double SERVER_CLAIM_BLOCK_SLACK = 1.10D;
    private static final Map<NetworkKey, List<LeadConnection>> CONNECTIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, LeadConnection>> CLIENT_CONNECTIONS_BY_ID = new HashMap<>();
    private static final Map<NetworkKey, Map<Long, Set<UUID>>> CLIENT_CHUNK_CONNECTIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, Integer>> CLIENT_CONNECTION_REFCOUNTS = new HashMap<>();
    private static final Map<PlayerKey, PendingLead> PENDING_LEADS = new HashMap<>();
    private static final Map<UUID, Long> INTERIOR_BLOCKED_SINCE = new HashMap<>();
    // Sticky "recently active" deadline per ENERGY connection (gameTime tick at
    // which power expires).
    // Avoids visual flicker when transfer is intermittent (e.g. source pulses,
    // target capped).
    private static final Map<UUID, Long> ENERGY_ACTIVE_UNTIL = new HashMap<>();
    private static final long ENERGY_STICKY_TICKS = 40L;
    // Round-robin cursor per ITEM extract source position. Keyed by BlockPos so
    // that ropes
    // anchored to different faces of the same source block share one queue (a "rope
    // knot").
    private static final Map<BlockPos, Integer> ITEM_RR_CURSOR = new HashMap<>();
    private static final Map<BlockPos, Integer> FLUID_RR_CURSOR = new HashMap<>();
    private static final Map<BlockPos, Integer> PRESSURIZED_RR_CURSOR = new HashMap<>();
    private static final int MAX_TRANSFER_SEARCH_DEPTH = 64;
    private static final int ITEM_PULSE_DURATION_TICKS = 10;
    private static final long STUCK_CHECK_INTERVAL_TICKS = 5L;
    private static final long STUCK_BREAK_TICKS = 100L;
    private static final double STUCK_SAMPLE_STEP = 0.20D;
    private static final double STUCK_ENDPOINT_IGNORE_DISTANCE = 0.35D;
    private static final double STUCK_INSIDE_EPS = 1.0e-4D;
    public static final int ITEM_TIER_MAX = 6;
    public static final int FLUID_TIER_MAX = 4;
    private static final ThreadLocal<Boolean> SUPPRESS_LEAD_SIGNALS = ThreadLocal.withInitial(() -> false);

    private SuperLeadNetwork() {
    }

    public static boolean canModifyRopes(Player player) {
        return player != null && player.mayBuild();
    }

    public static boolean canStartRopePlacement(Level level, Player player, LeadAnchor anchor) {
        if (canModifyRopes(player))
            return true;
        if (player == null || player.isSpectator())
            return false;
        if (!(level instanceof ServerLevel serverLevel))
            return true;
        Vec3 point = anchor.attachmentPoint(level);
        if (PresetServerManager.canAdventureStartAt(serverLevel, point))
            return true;
        Vec3 blockCenter = Vec3.atCenterOf(anchor.pos());
        return PresetServerManager.canAdventureStartAt(serverLevel, blockCenter);
    }

    public static boolean canCreateRopePlacement(Level level, Player player, LeadAnchor from, LeadAnchor to) {
        if (canModifyRopes(player))
            return true;
        if (player == null || player.isSpectator())
            return false;
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return true;
        }
        return PresetServerManager.canAdventurePlaceConnection(serverLevel, serverPlayer, from, to);
    }

    public static Optional<LeadAnchor> pendingAnchor(Player player) {
        return pendingLead(player).map(PendingLead::anchor);
    }

    public static Optional<LeadKind> pendingKind(Player player) {
        return pendingLead(player).map(PendingLead::kind);
    }

    private static Optional<PendingLead> pendingLead(Player player) {
        return Optional.ofNullable(PENDING_LEADS.get(PlayerKey.of(player)));
    }

    public static void setPendingAnchor(Player player, LeadAnchor anchor) {
        setPendingAnchor(player, anchor, LeadKind.NORMAL);
    }

    public static void setPendingAnchor(Player player, LeadAnchor anchor, LeadKind kind) {
        PENDING_LEADS.put(PlayerKey.of(player), new PendingLead(anchor, kind));
    }

    public static void clearPendingAnchor(Player player) {
        PENDING_LEADS.remove(PlayerKey.of(player));
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to) {
        return connect(level, from, to, LeadKind.NORMAL);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return connect(level, from, to, kind, null);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to, LeadKind kind, Player player) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            if (countConnectionsAtAnchor(serverLevel, from) >= Config.maxRopesPerBlockFace()
                    || countConnectionsAtAnchor(serverLevel, to) >= Config.maxRopesPerBlockFace()) {
                spawnBlockFaceLimitParticles(serverLevel, countConnectionsAtAnchor(serverLevel, from) >= Config
                        .maxRopesPerBlockFace() ? from : to);
                return null;
            }
            LeadConnection connection = LeadConnection.create(from, to, kind);
            connection = connection.withPhysicsPreset(
                    PresetServerManager.resolvePresetForConnection(serverLevel, connection));
            if (player != null && !canModifyRopes(player)) {
                connection = connection.withAdventureOwner(player.getUUID());
            }
            ensureFenceKnot(serverLevel, from);
            ensureFenceKnot(serverLevel, to);
            SuperLeadSavedData.get(serverLevel).add(connection);
            notifyRedstoneChange(serverLevel, connection);
            PresetServerManager.syncDimensionPresets(serverLevel);
            SuperLeadPayloads.sendToDimension(serverLevel);
            return connection;
        }
        // Client-side right-click handling only maintains the local pending-anchor
        // state. The
        // actual rope must arrive from the server via SyncRopeChunk; otherwise the
        // client creates
        // a random-UUID prediction that cannot be removed or saved.
        return LeadConnection.create(from, to, kind);
    }

    public static List<LeadConnection> connections(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).connections();
        }
        return CONNECTIONS.getOrDefault(NetworkKey.of(level), List.of());
    }

    private static List<LeadConnection> connectionsOfKind(Level level, LeadKind kind) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).connectionsOfKind(kind);
        }
        ArrayList<LeadConnection> out = new ArrayList<>();
        for (LeadConnection connection : connections(level)) {
            if (connection.kind() == kind) {
                out.add(connection);
            }
        }
        return out;
    }

    public static void replaceConnections(Level level, List<LeadConnection> connections) {
        NetworkKey key = NetworkKey.of(level);
        CONNECTIONS.put(key, new ArrayList<>(connections));
        Map<UUID, LeadConnection> byId = new LinkedHashMap<>();
        for (LeadConnection connection : connections) {
            byId.put(connection.id(), connection);
        }
        CLIENT_CONNECTIONS_BY_ID.put(key, byId);
        CLIENT_CHUNK_CONNECTIONS.remove(key);
        CLIENT_CONNECTION_REFCOUNTS.remove(key);
    }

    public static void applyConnectionChanges(Level level, List<UUID> removed, List<LeadConnection> upserts) {
        NetworkKey key = NetworkKey.of(level);
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.computeIfAbsent(key, ignored -> {
            Map<UUID, LeadConnection> out = new LinkedHashMap<>();
            for (LeadConnection connection : CONNECTIONS.getOrDefault(key, List.of())) {
                out.put(connection.id(), connection);
            }
            return out;
        });
        if (!removed.isEmpty()) {
            for (UUID id : removed) {
                byId.remove(id);
            }
        }

        for (LeadConnection upsert : upserts) {
            byId.put(upsert.id(), upsert);
        }
        rebuildClientConnectionList(key);
    }

    public static void replaceChunkConnections(Level level, ChunkPos chunk, List<LeadConnection> connections) {
        NetworkKey key = NetworkKey.of(level);
        long chunkKey = SuperLeadSavedData.chunkKey(chunk);
        Map<Long, Set<UUID>> byChunk = CLIENT_CHUNK_CONNECTIONS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, Integer> refCounts = CLIENT_CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.computeIfAbsent(key,
                ignored -> new LinkedHashMap<>());

        Set<UUID> oldIds = byChunk.remove(chunkKey);
        if (oldIds != null) {
            for (UUID id : oldIds) {
                decrementClientRef(byId, refCounts, id);
            }
        }

        LinkedHashSet<UUID> newIds = new LinkedHashSet<>();
        for (LeadConnection connection : connections) {
            if (!newIds.add(connection.id()))
                continue;
            byId.put(connection.id(), connection);
            refCounts.put(connection.id(), refCounts.getOrDefault(connection.id(), 0) + 1);
        }
        if (!newIds.isEmpty()) {
            byChunk.put(chunkKey, newIds);
        }
        pruneUnreferencedClientConnections(key);
        rebuildClientConnectionList(key);
    }

    public static void unloadChunkConnections(Level level, ChunkPos chunk) {
        NetworkKey key = NetworkKey.of(level);
        Map<Long, Set<UUID>> byChunk = CLIENT_CHUNK_CONNECTIONS.get(key);
        if (byChunk == null) {
            return;
        }
        Set<UUID> oldIds = byChunk.remove(SuperLeadSavedData.chunkKey(chunk));
        if (oldIds == null || oldIds.isEmpty()) {
            return;
        }
        Map<UUID, Integer> refCounts = CLIENT_CONNECTION_REFCOUNTS.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.computeIfAbsent(key,
                ignored -> new LinkedHashMap<>());
        for (UUID id : oldIds) {
            decrementClientRef(byId, refCounts, id);
        }
        pruneUnreferencedClientConnections(key);
        rebuildClientConnectionList(key);
    }

    private static void decrementClientRef(Map<UUID, LeadConnection> byId, Map<UUID, Integer> refCounts, UUID id) {
        int next = refCounts.getOrDefault(id, 0) - 1;
        if (next <= 0) {
            refCounts.remove(id);
            byId.remove(id);
        } else {
            refCounts.put(id, next);
        }
    }

    private static void pruneUnreferencedClientConnections(NetworkKey key) {
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.get(key);
        Map<UUID, Integer> refCounts = CLIENT_CONNECTION_REFCOUNTS.get(key);
        if (byId == null || refCounts == null) {
            return;
        }
        byId.keySet().removeIf(id -> refCounts.getOrDefault(id, 0) <= 0);
    }

    private static void rebuildClientConnectionList(NetworkKey key) {
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.get(key);
        CONNECTIONS.put(key, byId == null ? new ArrayList<>() : new ArrayList<>(byId.values()));
    }

    public static void pruneInvalid(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            SuperLeadSavedData data = SuperLeadSavedData.get(serverLevel);
            List<LeadConnection> invalidConnections = new ArrayList<>();
            for (LeadConnection connection : data.connections()) {
                if (invalid(level, connection)) {
                    invalidConnections.add(connection);
                }
            }
            if (invalidConnections.isEmpty()) {
                ensureFenceKnots(serverLevel);
                return;
            }

            Set<UUID> invalidIds = new HashSet<>();
            for (LeadConnection connection : invalidConnections) {
                invalidIds.add(connection.id());
            }
            boolean removed = data.removeIf(connection -> invalidIds.contains(connection.id()));
            if (!removed) {
                ensureFenceKnots(serverLevel);
                return;
            }

            for (LeadConnection connection : invalidConnections) {
                Vec3 midpoint = midpoint(level, connection);
                dropConnectionDrops(serverLevel, connection, midpoint, null);
                cleanupFenceKnot(serverLevel, connection.from());
                cleanupFenceKnot(serverLevel, connection.to());
                notifyRedstoneChange(serverLevel, connection);
            }
            ensureFenceKnots(serverLevel);
            SuperLeadPayloads.sendToDimension(serverLevel);
            PresetServerManager.syncDimensionPresets(serverLevel);
            return;
        }

        // Clients receive authoritative chunk-scoped rope data from the server. Do not
        // prune here:
        // a cross-chunk rope can reference an endpoint chunk that has not streamed in
        // yet, and
        // treating that unloaded endpoint as air would incorrectly delete a valid
        // visible rope.
    }

    private static boolean invalid(Level level, LeadConnection connection) {
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        return level.getBlockState(connection.from().pos()).isAir()
                || level.getBlockState(connection.to().pos()).isAir()
                || endpoints.from().distanceTo(endpoints.to()) > MAX_LEASH_DISTANCE;
    }

    public static void tickStuckBreaks(ServerLevel level) {
        long now = level.getGameTime();
        if (now % STUCK_CHECK_INTERVAL_TICKS != 0L) {
            return;
        }

        List<LeadConnection> connections = SuperLeadSavedData.get(level).connections();
        Set<UUID> liveIds = new HashSet<>();
        List<LeadConnection> broken = new ArrayList<>();
        for (LeadConnection connection : connections) {
            liveIds.add(connection.id());
            if (!hasInteriorBlockage(level, connection)) {
                INTERIOR_BLOCKED_SINCE.remove(connection.id());
                continue;
            }

            long since = INTERIOR_BLOCKED_SINCE.computeIfAbsent(connection.id(), id -> now);
            if (now - since >= STUCK_BREAK_TICKS) {
                broken.add(connection);
            }
        }

        INTERIOR_BLOCKED_SINCE.keySet().retainAll(liveIds);
        if (broken.isEmpty()) {
            return;
        }

        Set<UUID> brokenIds = new HashSet<>();
        for (LeadConnection connection : broken) {
            brokenIds.add(connection.id());
        }
        boolean removed = SuperLeadSavedData.get(level).removeIf(connection -> brokenIds.contains(connection.id()));
        if (!removed) {
            return;
        }

        for (LeadConnection connection : broken) {
            INTERIOR_BLOCKED_SINCE.remove(connection.id());
            dropConnectionDrops(level, connection, midpoint(level, connection), null);
            cleanupFenceKnot(level, connection.from());
            cleanupFenceKnot(level, connection.to());
            notifyRedstoneChange(level, connection);
        }
        SuperLeadPayloads.sendToDimension(level);
        PresetServerManager.syncDimensionPresets(level);
    }

    private static boolean hasInteriorBlockage(Level level, LeadConnection connection) {
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double distance = a.distanceTo(b);
        if (distance < 1.0e-6D) {
            return false;
        }

        int samples = Math.max(2, (int) Math.ceil(distance / STUCK_SAMPLE_STEP));
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            if (t * distance <= STUCK_ENDPOINT_IGNORE_DISTANCE
                    || (1.0D - t) * distance <= STUCK_ENDPOINT_IGNORE_DISTANCE) {
                continue;
            }
            Vec3 point = a.lerp(b, t);
            if (isPointInsideCollision(level, point)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPointInsideCollision(Level level, Vec3 point) {
        int baseX = (int) Math.floor(point.x);
        int baseY = (int) Math.floor(point.y);
        int baseZ = (int) Math.floor(point.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int bx = baseX - 1; bx <= baseX + 1; bx++) {
            for (int by = baseY - 1; by <= baseY + 1; by++) {
                for (int bz = baseZ - 1; bz <= baseZ + 1; bz++) {
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    VoxelShape shape = state.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double ox = cursor.getX();
                    double oy = cursor.getY();
                    double oz = cursor.getZ();
                    for (AABB box : shape.toAabbs()) {
                        if (containsStrict(box.move(ox, oy, oz), point)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsStrict(AABB box, Vec3 point) {
        return point.x > box.minX + STUCK_INSIDE_EPS && point.x < box.maxX - STUCK_INSIDE_EPS
                && point.y > box.minY + STUCK_INSIDE_EPS && point.y < box.maxY - STUCK_INSIDE_EPS
                && point.z > box.minZ + STUCK_INSIDE_EPS && point.z < box.maxZ - STUCK_INSIDE_EPS;
    }

    public static boolean hasUpgradeableConnectionNear(Level level, Vec3 point, double maxDistance) {
        return nearestConnection(level, point, maxDistance, connection -> connection.kind() != LeadKind.REDSTONE)
                .isPresent();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance,
            Predicate<LeadConnection> predicate) {
        return nearestConnection(level, point, maxDistance, predicate).isPresent();
    }

    public static Optional<LeadConnection> findConnectionInView(ServerLevel level, Player player, double radius) {
        return nearestConnectionInView(level, player, radius, connection -> true)
                .map(ConnectionPick::connection);
    }

    public static boolean hasConnectionInView(Level level, Player player, double radius,
            Predicate<LeadConnection> predicate) {
        return nearestConnectionInView(level, player, radius, predicate).isPresent();
    }

    public static boolean upgradeNearestToRedstone(Level level, Vec3 point, Player player) {
        return upgradeNearestToKind(level, point, 0.75D, LeadKind.REDSTONE);
    }

    public static boolean upgradeNearestToRedstoneInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.REDSTONE);
    }

    public static boolean upgradeNearestToEnergyInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.ENERGY);
    }

    public static boolean upgradeNearestToItemInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.ITEM);
    }

    public static boolean upgradeNearestToFluidInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.FLUID);
    }

    public static boolean upgradeNearestToPressurizedInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.PRESSURIZED);
    }

    public static boolean upgradeNearestToThermalInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.THERMAL);
    }

    public static boolean upgradeNearestToAeNetworkInView(Level level, Player player, double radius) {
        return upgradeNearestToKindInView(level, player, radius, LeadKind.AE_NETWORK);
    }

    /**
     * Toggle the extract-source anchor for any ITEM connection attached at the
     * given block position.
     * Returns true if at least one connection was updated.
     */
    public static boolean toggleItemExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.ITEM);
    }

    public static boolean toggleFluidExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.FLUID);
    }

    public static boolean togglePressurizedExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.PRESSURIZED);
    }

    private static boolean toggleExtractAt(Level level, BlockPos pos, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SuperLeadSavedData data = SuperLeadSavedData.get(serverLevel);
        boolean any = false;
        for (LeadConnection connection : new ArrayList<>(data.connectionsOfKind(kind))) {
            int newExtract;
            if (connection.from().pos().equals(pos)) {
                newExtract = connection.extractAnchor() == 1 ? 0 : 1;
            } else if (connection.to().pos().equals(pos)) {
                newExtract = connection.extractAnchor() == 2 ? 0 : 2;
            } else {
                continue;
            }
            data.update(connection.id(), c -> c.withExtractAnchor(newExtract), false);
            any = true;
        }
        if (any) {
            SuperLeadPayloads.sendToDimension(serverLevel);
        }
        return any;
    }

    public static boolean hasItemConnectionAt(Level level, BlockPos pos) {
        return hasKindConnectionAt(level, pos, LeadKind.ITEM);
    }

    public static boolean hasFluidConnectionAt(Level level, BlockPos pos) {
        return hasKindConnectionAt(level, pos, LeadKind.FLUID);
    }

    public static boolean hasPressurizedConnectionAt(Level level, BlockPos pos) {
        return hasKindConnectionAt(level, pos, LeadKind.PRESSURIZED);
    }

    private static boolean hasKindConnectionAt(Level level, BlockPos pos, LeadKind kind) {
        for (LeadConnection connection : connectionsOfKind(level, kind)) {
            if (connection.from().pos().equals(pos) || connection.to().pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean upgradeNearestItemTierInView(Level level, Player player, double radius) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.ITEM, Config.itemTierMax(), Items.CHEST);
    }

    public static boolean upgradeNearestFluidTierInView(Level level, Player player, double radius) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.FLUID, Config.fluidTierMax(), Items.BUCKET);
    }

    public static boolean upgradeNearestPressurizedTierInView(Level level, Player player, double radius,
            Predicate<ItemStack> costMatcher) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.PRESSURIZED,
                Config.pressurizedTierMax(), costMatcher);
    }

    public static boolean upgradeNearestThermalTierInView(Level level, Player player, double radius,
            Predicate<ItemStack> costMatcher) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.THERMAL,
                Config.thermalTierMax(), costMatcher);
    }

    public static boolean upgradeNearestAeChannelTierInView(Level level, Player player, double radius,
            Predicate<ItemStack> costMatcher) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.AE_NETWORK,
                Config.aeChannelTierMax(), costMatcher);
    }

    /** Find a known connection by id in the given level (server or client). */
    public static Optional<LeadConnection> findConnectionById(Level level, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).find(id);
        }
        Map<UUID, LeadConnection> byId = CLIENT_CONNECTIONS_BY_ID.get(NetworkKey.of(level));
        if (byId != null) {
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.empty();
    }

    /**
     * Broad server-side view check used only to consume the vanilla block
     * interaction.
     */
    public static boolean canUseClientPickedConnection(ServerLevel level, Player player, LeadConnection connection) {
        return canAimAtConnectionEnvelope(level, player, connection);
    }

    /**
     * Server-side validation for a client-confirmed rope target.
     *
     * The client picks against the rendered rope simulation, which can sag or be
     * pushed by blocks.
     * The packet therefore carries the exact rendered hit point and an approximate
     * rope parameter.
     * The server verifies that the hit is still in the player's view ray and inside
     * a generous
     * capsule/envelope around the same rope instead of doing a fresh nearest-rope
     * pick.
     */
    public static boolean canUseClientPickedConnection(ServerLevel level, Player player,
            LeadConnection connection, Vec3 claimedHitPoint, double claimedT) {
        if (!isFinite(claimedHitPoint) || !Double.isFinite(claimedT)) {
            return false;
        }

        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double reach = clippedAimReach(level, player, origin, direction,
                SERVER_CONFIRMED_PICK_REACH, SERVER_CLAIM_BLOCK_SLACK);

        Vec3 originToHit = claimedHitPoint.subtract(origin);
        double along = originToHit.dot(direction);
        if (along < -0.25D || along > reach) {
            return false;
        }

        Vec3 rayPoint = origin.add(direction.scale(Math.max(0.0D, along)));
        if (claimedHitPoint.distanceToSqr(rayPoint) > SERVER_CLAIM_RAY_RADIUS * SERVER_CLAIM_RAY_RADIUS) {
            return false;
        }

        return isClaimedHitNearConnection(level, connection, a, b, claimedHitPoint, clamp01(claimedT));
    }

    /**
     * Looser variant of {@link #canUseClientPickedConnection} for attachment
     * placement /
     * removal / form-toggle. The client physics rope can sag noticeably below the
     * chord
     * while the server's straight-line + half-sine approximation cannot (server's
     * max sag
     * ~0.7 blocks, client up to several blocks for long, mostly-horizontal ropes).
     * The
     * precise hit position {@code t} is computed by the client picker on the actual
     * simulated polyline, so the server only needs a coarse "ray points at the
     * rope's
     * bounding envelope within reach" anti-cheese gate. We therefore intersect the
     * player's
     * view ray with an AABB that contains the chord plus the worst-case sagged
     * shape.
     */
    public static boolean canTouchConnectionForAttachment(ServerLevel level, Player player, LeadConnection connection) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        net.minecraft.world.phys.AABB envelope = connectionEnvelope(a, b, SERVER_CONFIRMED_PICK_RADIUS);
        Vec3 reachEnd = origin.add(direction.scale(SERVER_CONFIRMED_PICK_REACH));
        return envelope.clip(origin, reachEnd).isPresent();
    }

    /**
     * Much wider envelope for attachment removal. When multiple ropes stack on the
     * same block face the envelope of a single rope may be too narrow for the
     * server's ray-vs-AABB test even though the client picker already confirmed
     * the player's aim. This version accepts a larger surrounding box so the
     * server doesn't veto a removal that the client already highlighted.
     */
    public static boolean canTouchConnectionForAttachmentRemoval(ServerLevel level, Player player,
            LeadConnection connection) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        net.minecraft.world.phys.AABB envelope = connectionEnvelope(a, b, SERVER_CONFIRMED_ATTACH_REMOVAL_RADIUS);
        Vec3 reachEnd = origin.add(direction.scale(SERVER_CONFIRMED_PICK_REACH));
        return envelope.clip(origin, reachEnd).isPresent();
    }

    private static boolean canAimAtConnectionEnvelope(ServerLevel level, Player player, LeadConnection connection) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double reach = clippedAimReach(level, player, origin, direction,
                SERVER_CONFIRMED_PICK_REACH, SERVER_CLAIM_BLOCK_SLACK);
        return connectionEnvelope(a, b, SERVER_CLAIM_ROPE_RADIUS)
                .clip(origin, origin.add(direction.scale(reach)))
                .isPresent();
    }

    private static double clippedAimReach(ServerLevel level, Player player, Vec3 origin, Vec3 direction,
            double baseReach, double blockSlack) {
        Vec3 reachEnd = origin.add(direction.scale(baseReach));
        net.minecraft.world.level.ClipContext clip = new net.minecraft.world.level.ClipContext(
                origin, reachEnd,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player);
        net.minecraft.world.phys.BlockHitResult blockHit = level.clip(clip);
        if (blockHit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return baseReach;
        }
        return Math.min(baseReach, blockHit.getLocation().distanceTo(origin) + blockSlack);
    }

    private static boolean isClaimedHitNearConnection(ServerLevel level, LeadConnection connection,
            Vec3 a, Vec3 b, Vec3 hit, double hitT) {
        double radiusSqr = SERVER_CLAIM_ROPE_RADIUS * SERVER_CLAIM_ROPE_RADIUS;
        if (distancePointToSegmentSqr(hit, a, b) <= radiusSqr) {
            return true;
        }
        if (!connection.physicsPreset().isBlank()) {
            double[] closest = new double[4];
            if (ServerRopeCurve.distancePointToCurveSqr(ServerRopeCurve.from(level, connection, a, b), hit,
                    closest) <= radiusSqr) {
                return true;
            }
        }
        if (distancePointToApproximateSaggedCurveSqr(hit, a, b) <= radiusSqr) {
            return true;
        }
        if (!connectionEnvelope(a, b, SERVER_CLAIM_ROPE_RADIUS).contains(hit)) {
            return false;
        }
        return isInsideProjectedRopeTube(a, b, hit, hitT, SERVER_CLAIM_ROPE_RADIUS);
    }

    private static boolean isInsideProjectedRopeTube(Vec3 a, Vec3 b, Vec3 hit, double hitT, double radius) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double horizontalLenSqr = dx * dx + dz * dz;
        double projectionT;
        double sideSqr;
        if (horizontalLenSqr > 1.0e-8D) {
            projectionT = ((hit.x - a.x) * dx + (hit.z - a.z) * dz) / horizontalLenSqr;
            projectionT = clamp01(projectionT);
            double cx = a.x + dx * projectionT;
            double cz = a.z + dz * projectionT;
            double sideX = hit.x - cx;
            double sideZ = hit.z - cz;
            sideSqr = sideX * sideX + sideZ * sideZ;
        } else {
            projectionT = closestSegmentParameter(a, b, hit);
            double sideX = hit.x - a.x;
            double sideZ = hit.z - a.z;
            sideSqr = sideX * sideX + sideZ * sideZ;
        }
        if (sideSqr > radius * radius) {
            return false;
        }

        if (Math.abs(projectionT - hitT) > SERVER_CLAIM_T_SLACK) {
            double endpointRadius = radius + 0.35D;
            double endpointRadiusSqr = endpointRadius * endpointRadius;
            if (hit.distanceToSqr(a) > endpointRadiusSqr && hit.distanceToSqr(b) > endpointRadiusSqr) {
                return false;
            }
        }

        double sagDown = broadSagDown(a, b, radius);
        return hit.y >= Math.min(a.y, b.y) - sagDown
                && hit.y <= Math.max(a.y, b.y) + radius;
    }

    private static net.minecraft.world.phys.AABB connectionEnvelope(Vec3 a, Vec3 b, double radius) {
        double sagDown = broadSagDown(a, b, radius);
        return new net.minecraft.world.phys.AABB(
                Math.min(a.x, b.x) - radius,
                Math.min(a.y, b.y) - sagDown,
                Math.min(a.z, b.z) - radius,
                Math.max(a.x, b.x) + radius,
                Math.max(a.y, b.y) + radius,
                Math.max(a.z, b.z) + radius);
    }

    private static double broadSagDown(Vec3 a, Vec3 b, double radius) {
        double chord = a.distanceTo(b);
        return Math.max(SERVER_CONFIRMED_CURVE_MAX_SAG + radius, chord * 0.55D + radius);
    }

    public static boolean hasClientPickCompatibleConnectionInView(ServerLevel level, Player player,
            Predicate<LeadConnection> predicate) {
        for (LeadConnection connection : connections(level)) {
            if (predicate.test(connection) && canUseClientPickedConnection(level, player, connection)) {
                return true;
            }
        }
        return false;
    }

    /** Per-connection: change kind. */
    public static boolean upgradeConnectionKind(ServerLevel level, LeadConnection connection, LeadKind newKind) {
        if (connection.kind() == newKind)
            return false;
        return updateConnectionKind(level, connection, newKind);
    }

    /**
     * Per-connection: increase tier by 1, charging (1 << tier) of costItem (less
     * the 1 the caller will shrink).
     */
    public static boolean upgradeConnectionTier(ServerLevel level, Player player, LeadConnection connection,
            int maxTier, net.minecraft.world.item.Item costItem) {
        return upgradeConnectionTier(level, player, connection, maxTier, stack -> stack.is(costItem));
    }

    public static boolean upgradeConnectionTier(ServerLevel level, Player player, LeadConnection connection,
            int maxTier, Predicate<ItemStack> costMatcher) {
        if (connection.tier() >= maxTier)
            return false;
        int totalCost = 1 << Math.min(maxTier, connection.tier());
        int extraCost = totalCost - 1;
        if (!player.isCreative() && extraCost > 0 && !consumeMatchingFromInventory(player, costMatcher, extraCost)) {
            return false;
        }
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.withTier(c.tier() + 1), true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    /** Per-connection: cut and drop a lead item at the connection midpoint. */
    public static boolean cutConnection(ServerLevel level, Player player, LeadConnection connection) {
        boolean removed = SuperLeadSavedData.get(level).removeIf(c -> c.id().equals(connection.id()));
        if (!removed)
            return false;
        dropConnectionDrops(level, connection, midpoint(level, connection), player);
        cleanupFenceKnot(level, connection.from());
        cleanupFenceKnot(level, connection.to());
        notifyRedstoneChange(level, connection);
        SuperLeadPayloads.sendToDimension(level);
        PresetServerManager.syncDimensionPresets(level);
        return true;
    }

    /**
     * Adds a new attachment to {@code connection}. The caller is responsible for
     * shrinking the
     * player's held stack on success.
     */
    public static boolean addAttachment(ServerLevel level, LeadConnection connection, double t,
            net.minecraft.world.item.ItemStack stack) {
        return addAttachment(level, connection, t, stack, 1);
    }

    public static boolean addAttachment(ServerLevel level, LeadConnection connection, double t,
            net.minecraft.world.item.ItemStack stack, int frontSide) {
        if (stack.isEmpty())
            return false;
        RopeAttachment attachment = RopeAttachment.create(t, stack, frontSide);
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.addAttachment(attachment), true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    /**
     * Removes attachment {@code attachmentId} from {@code connection}. The removed
     * item is
     * given to non-creative {@code player} (or dropped at their feet if their
     * inventory is full).
     */
    public static boolean removeAttachment(ServerLevel level, LeadConnection connection, java.util.UUID attachmentId,
            Player player) {
        RopeAttachment removed = null;
        for (RopeAttachment a : connection.attachments()) {
            if (a.id().equals(attachmentId)) {
                removed = a;
                break;
            }
        }
        if (removed == null)
            return false;
        final RopeAttachment removedFinal = removed;
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.removeAttachment(attachmentId), true);
        if (!ok)
            return false;
        if (player != null && player.isCreative()) {
            SuperLeadPayloads.sendToDimension(level);
            return true;
        }
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 point = endpoints.from().lerp(endpoints.to(), removedFinal.t());
        net.minecraft.world.item.ItemStack drop = prepareAttachmentDropStack(level, removedFinal, point);
        if (player != null && !player.getInventory().add(drop)) {
            player.drop(drop, false);
        } else if (player == null) {
            net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(level,
                    point.x, point.y, point.z, drop);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        SuperLeadPayloads.sendToDimension(level);
        return true;
    }

    /**
     * Toggle a single attachment between block-shaped and item-shaped rendering.
     * Only
     * meaningful for BlockItem stacks; non-block items are silently ignored.
     */
    public static boolean toggleAttachmentForm(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId) {
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.toggleAttachmentForm(attachmentId), true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    /**
     * Update the stored text component of a sign attachment without touching any
     * real block.
     */
    public static boolean updateAttachmentSignText(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId, boolean frontText, java.util.List<String> lines) {
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(), c -> {
            if (c.attachments().isEmpty())
                return c;
            List<RopeAttachment> updated = new ArrayList<>(c.attachments().size());
            boolean changed = false;
            for (RopeAttachment attachment : c.attachments()) {
                if (!attachment.id().equals(attachmentId)) {
                    updated.add(attachment);
                    continue;
                }
                ItemStack stack = withSignText(level, attachment.stack(), frontText, lines);
                if (ItemStack.isSameItemSameComponents(stack, attachment.stack())) {
                    updated.add(attachment);
                } else {
                    updated.add(attachment.withStack(stack));
                    changed = true;
                }
            }
            return changed ? c.withAttachments(updated) : c;
        }, true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    public static boolean updateAttachmentStack(ServerLevel level, java.util.UUID connectionId,
            java.util.UUID attachmentId, java.util.function.UnaryOperator<ItemStack> updater, boolean sync) {
        if (updater == null)
            return false;
        boolean ok = SuperLeadSavedData.get(level).update(connectionId, c -> {
            if (c.attachments().isEmpty())
                return c;
            List<RopeAttachment> updated = new ArrayList<>(c.attachments().size());
            boolean changed = false;
            for (RopeAttachment attachment : c.attachments()) {
                if (!attachment.id().equals(attachmentId)) {
                    updated.add(attachment);
                    continue;
                }
                ItemStack stack = updater.apply(attachment.stack().copyWithCount(1));
                if (stack == null || stack.isEmpty()) {
                    updated.add(attachment);
                    continue;
                }
                stack = stack.copyWithCount(1);
                if (ItemStack.isSameItemSameComponents(stack, attachment.stack())) {
                    updated.add(attachment);
                } else {
                    updated.add(attachment.withStack(stack));
                    changed = true;
                }
            }
            return changed ? c.withAttachments(updated) : c;
        }, true);
        if (ok && sync) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    private static ItemStack withSignText(ServerLevel level, ItemStack original,
            boolean frontText, java.util.List<String> lines) {
        if (!(original.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
            return original;
        }

        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        boolean hanging = original.getItem() instanceof net.minecraft.world.item.HangingSignItem
                || blockItem.getBlock() instanceof net.minecraft.world.level.block.HangingSignBlock;
        net.minecraft.world.level.block.entity.SignBlockEntity sign = hanging
                ? new net.minecraft.world.level.block.entity.HangingSignBlockEntity(BlockPos.ZERO, state)
                : new net.minecraft.world.level.block.entity.SignBlockEntity(BlockPos.ZERO, state);

        net.minecraft.world.item.component.TypedEntityData<net.minecraft.world.level.block.entity.BlockEntityType<?>> data = original
                .get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        if (data != null && data.type() == sign.getType()) {
            data.loadInto(sign, level.registryAccess());
            tag = data.copyTagWithoutId();
        }
        sign.applyComponentsFromItemStack(original);

        net.minecraft.world.level.block.entity.SignText text = sign.getText(frontText);
        for (int i = 0; i < 4; i++) {
            String line = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
            text = text.setMessage(i, net.minecraft.network.chat.Component.literal(line));
        }
        tag.store(frontText ? "front_text" : "back_text",
                net.minecraft.world.level.block.entity.SignText.DIRECT_CODEC, text);

        ItemStack updated = original.copyWithCount(1);
        updated.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.TypedEntityData.of(sign.getType(), tag));
        return updated;
    }

    public static boolean applySignDye(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId, net.minecraft.world.item.DyeColor color, boolean frontText) {
        return updateSignComponent(level, connection, attachmentId, frontText,
                text -> text.setColor(color));
    }

    public static boolean applySignGlow(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId, boolean frontText) {
        return updateSignComponent(level, connection, attachmentId, frontText,
                text -> text.setHasGlowingText(!text.hasGlowingText()));
    }

    private static boolean updateSignComponent(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId, boolean frontText,
            java.util.function.UnaryOperator<net.minecraft.world.level.block.entity.SignText> op) {
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(), c -> {
            if (c.attachments().isEmpty())
                return c;
            List<RopeAttachment> updated = new ArrayList<>(c.attachments().size());
            boolean changed = false;
            for (RopeAttachment attachment : c.attachments()) {
                if (!attachment.id().equals(attachmentId)) {
                    updated.add(attachment);
                    continue;
                }
                ItemStack stack = applySignOp(level, attachment.stack(), frontText, op);
                if (ItemStack.isSameItemSameComponents(stack, attachment.stack())) {
                    updated.add(attachment);
                } else {
                    updated.add(attachment.withStack(stack));
                    changed = true;
                }
            }
            return changed ? c.withAttachments(updated) : c;
        }, true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    private static ItemStack applySignOp(ServerLevel level, ItemStack original, boolean frontText,
            java.util.function.UnaryOperator<net.minecraft.world.level.block.entity.SignText> op) {
        if (!(original.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
            return original;
        }
        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        boolean hanging = original.getItem() instanceof net.minecraft.world.item.HangingSignItem
                || blockItem.getBlock() instanceof net.minecraft.world.level.block.HangingSignBlock;
        net.minecraft.world.level.block.entity.SignBlockEntity sign = hanging
                ? new net.minecraft.world.level.block.entity.HangingSignBlockEntity(BlockPos.ZERO, state)
                : new net.minecraft.world.level.block.entity.SignBlockEntity(BlockPos.ZERO, state);

        net.minecraft.world.item.component.TypedEntityData<net.minecraft.world.level.block.entity.BlockEntityType<?>> data = original
                .get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        if (data != null && data.type() == sign.getType()) {
            data.loadInto(sign, level.registryAccess());
            tag = data.copyTagWithoutId();
        }
        sign.applyComponentsFromItemStack(original);

        net.minecraft.world.level.block.entity.SignText front = sign.getFrontText();
        net.minecraft.world.level.block.entity.SignText back = sign.getBackText();
        if (frontText) {
            front = op.apply(front);
        } else {
            back = op.apply(back);
        }
        tag.store("front_text", net.minecraft.world.level.block.entity.SignText.DIRECT_CODEC, front);
        tag.store("back_text", net.minecraft.world.level.block.entity.SignText.DIRECT_CODEC, back);

        ItemStack updated = original.copyWithCount(1);
        updated.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.TypedEntityData.of(sign.getType(), tag));
        return updated;
    }

    private static boolean upgradeNearestKindTierInView(Level level, Player player, double radius,
            LeadKind kind, int maxTier, net.minecraft.world.item.Item costItem) {
        return upgradeNearestKindTierInView(level, player, radius, kind, maxTier, stack -> stack.is(costItem));
    }

    private static boolean upgradeNearestKindTierInView(Level level, Player player, double radius,
            LeadKind kind, int maxTier, Predicate<ItemStack> costMatcher) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        Optional<ConnectionPick> pick = nearestConnectionInView(serverLevel, player, radius,
                connection -> connection.kind() == kind && connection.tier() < maxTier);
        if (pick.isEmpty()) {
            return false;
        }
        LeadConnection target = pick.get().connection();
        int totalCost = 1 << Math.min(maxTier, target.tier());
        int extraCost = totalCost - 1;
        if (!player.isCreative() && extraCost > 0 && !consumeMatchingFromInventory(player, costMatcher, extraCost)) {
            return false;
        }
        SuperLeadSavedData.get(serverLevel).update(target.id(),
                connection -> connection.withTier(connection.tier() + 1), true);
        SuperLeadPayloads.sendToDimension(serverLevel);
        return true;
    }

    public static boolean upgradeNearestEnergyTierInView(Level level, Player player, double radius) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        int maxTier = Config.energyTierMaxLevel();
        Optional<ConnectionPick> pick = nearestConnectionInView(serverLevel, player, radius,
                connection -> connection.kind() == LeadKind.ENERGY && connection.tier() < maxTier);
        if (pick.isEmpty()) {
            return false;
        }

        LeadConnection target = pick.get().connection();
        // Held block is shrunk by consumeSuccessfulUse (1). Pull the remainder from
        // inventory.
        int totalCost = 1 << Math.min(30, target.tier());
        int extraCost = totalCost - 1;
        if (!player.isCreative() && extraCost > 0 && !consumeRedstoneBlocks(player, extraCost)) {
            return false;
        }

        SuperLeadSavedData.get(serverLevel).update(target.id(),
                connection -> connection.withTier(connection.tier() + 1), true);
        SuperLeadPayloads.sendToDimension(serverLevel);
        return true;
    }

    public static boolean canUpgradeNearestEnergyTierInView(Level level, Player player, double radius) {
        int maxTier = Config.energyTierMaxLevel();
        return hasConnectionInView(level, player, radius,
                connection -> connection.kind() == LeadKind.ENERGY && connection.tier() < maxTier);
    }

    private static boolean consumeRedstoneBlocks(Player player, int amount) {
        return consumeFromInventory(player, Items.REDSTONE_BLOCK, amount);
    }

    private static boolean consumeFromInventory(Player player, net.minecraft.world.item.Item item, int amount) {
        return consumeMatchingFromInventory(player, stack -> stack.is(item), amount);
    }

    private static boolean consumeMatchingFromInventory(Player player, Predicate<ItemStack> matcher, int amount) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
                if (total >= amount) {
                    break;
                }
            }
        }
        if (total < amount) {
            return false;
        }

        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
        }
        return true;
    }

    private static boolean upgradeNearestToKind(Level level, Vec3 point, double maxDistance, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<LeadConnection> closest = nearestConnection(serverLevel, point, maxDistance,
                connection -> connection.kind() != kind);
        return closest.filter(connection -> updateConnectionKind(serverLevel, connection, kind)).isPresent();
    }

    private static boolean upgradeNearestToKindInView(Level level, Player player, double radius, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<ConnectionPick> pick = nearestConnectionInView(serverLevel, player, radius,
                connection -> connection.kind() != kind);
        return pick.filter(connectionPick -> updateConnectionKind(serverLevel, connectionPick.connection(), kind))
                .isPresent();
    }

    private static boolean updateConnectionKind(ServerLevel serverLevel, LeadConnection oldConnection, LeadKind kind) {
        boolean changed = SuperLeadSavedData.get(serverLevel).update(
                oldConnection.id(),
                connection -> connection.withKind(kind),
                true);
        if (!changed) {
            return false;
        }

        LeadConnection upgraded = oldConnection.withKind(kind);
        notifyRedstoneChange(serverLevel, oldConnection);
        notifyRedstoneChange(serverLevel, upgraded);
        SuperLeadPayloads.sendToDimension(serverLevel);
        return true;
    }

    public static void tickRedstone(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> redstoneConnections = data.connectionsOfKind(LeadKind.REDSTONE);
        if (redstoneConnections.isEmpty()) {
            return;
        }

        boolean changed = false;
        boolean[] visited = new boolean[redstoneConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(redstoneConnections);
        RedstoneTraversalCache redstoneCache = new RedstoneTraversalCache(level);
        for (int i = 0; i < redstoneConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;
            int power = 0;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = redstoneConnections.get(component.get(cursor));
                power = Math.max(power, redstoneCache.externalSignalAt(current.from()));
                power = Math.max(power, redstoneCache.externalSignalAt(current.to()));

                addUnvisitedNeighborsBridge(redstoneCache, current.from(), connectionsByAnchor, visited, component);
                addUnvisitedNeighborsBridge(redstoneCache, current.to(), connectionsByAnchor, visited, component);
            }

            final int componentPower = power;
            for (int index : component) {
                LeadConnection connection = redstoneConnections.get(index);
                if (connection.power() == componentPower) {
                    continue;
                }

                changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(componentPower),
                        false);
                notifyRedstoneChange(level, connection.withPower(componentPower));
            }
        }

        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    public static void tickEnergy(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> energyConnections = data.connectionsOfKind(LeadKind.ENERGY);
        if (energyConnections.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        EnergyHandlerCache energyHandlers = new EnergyHandlerCache();
        Set<UUID> transferredIds = new HashSet<>();
        boolean[] visited = new boolean[energyConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(energyConnections);
        RedstoneTraversalCache bridgeCache = new RedstoneTraversalCache(level);
        for (int i = 0; i < energyConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = energyConnections.get(component.get(cursor));
                addUnvisitedNeighborsBridge(bridgeCache, current.from(), connectionsByAnchor, visited, component);
                addUnvisitedNeighborsBridge(bridgeCache, current.to(), connectionsByAnchor, visited, component);
            }

            transferEnergyComponent(level, component, energyConnections, transferredIds, energyHandlers);
        }

        // Refresh sticky deadlines for connections that actually moved energy this
        // tick.
        for (UUID id : transferredIds) {
            ENERGY_ACTIVE_UNTIL.put(id, now + ENERGY_STICKY_TICKS);
        }

        boolean changed = false;
        Set<UUID> currentIds = new HashSet<>();
        for (LeadConnection connection : energyConnections) {
            currentIds.add(connection.id());
            Long until = ENERGY_ACTIVE_UNTIL.get(connection.id());
            int newPower = (until != null && until >= now) ? 1 : 0;
            if (connection.power() != newPower) {
                changed |= data.update(connection.id(), c -> c.withPower(newPower), false);
            }
        }
        // Drop deadlines for connections that no longer exist.
        ENERGY_ACTIVE_UNTIL.keySet().retainAll(currentIds);
        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    private static void transferEnergyComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> energyConnections, Set<UUID> transferredIds, EnergyHandlerCache energyHandlers) {
        List<EnergyEndpoint> endpoints = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();
        int componentRate = 0;
        long base = Config.energyBaseTransfer();
        for (int index : component) {
            LeadConnection connection = energyConnections.get(index);
            int tier = Math.min(30, connection.tier());
            long rate = base << tier;
            componentRate = (int) Math.min(Integer.MAX_VALUE, (long) componentRate + rate);
            addEnergyEndpoint(level, connection.from(), endpoints, seenAnchors, energyHandlers);
            addEnergyEndpoint(level, connection.to(), endpoints, seenAnchors, energyHandlers);
        }

        boolean componentMoved = false;
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                EnergyEndpoint a = endpoints.get(i);
                EnergyEndpoint b = endpoints.get(j);
                double fillA = energyFillRatio(a.handler());
                double fillB = energyFillRatio(b.handler());
                int moved;
                if (fillA > fillB + 1.0e-6D) {
                    moved = transferEnergy(a.handler(), b.handler(), componentRate);
                } else if (fillB > fillA + 1.0e-6D) {
                    moved = transferEnergy(b.handler(), a.handler(), componentRate);
                } else {
                    moved = 0;
                }
                if (moved > 0) {
                    componentMoved = true;
                }
            }
        }
        if (componentMoved) {
            for (int index : component) {
                transferredIds.add(energyConnections.get(index).id());
            }
        }
    }

    private static void addEnergyEndpoint(ServerLevel level, LeadAnchor anchor, List<EnergyEndpoint> endpoints,
            Set<LeadAnchor> seenAnchors, EnergyHandlerCache energyHandlers) {
        if (!seenAnchors.add(anchor)) {
            return;
        }

        EnergyHandler handler = energyHandlers.get(level, anchor);
        if (handler != null && handler.getCapacityAsLong() > 0L) {
            endpoints.add(new EnergyEndpoint(anchor, handler));
        }
    }

    private static double energyFillRatio(EnergyHandler handler) {
        long capacity = handler.getCapacityAsLong();
        if (capacity <= 0L) {
            return 0.0D;
        }
        return handler.getAmountAsLong() / (double) capacity;
    }

    private static int transferEnergy(EnergyHandler source, EnergyHandler target, int maxAmount) {
        int transferable;
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = source.extract(maxAmount, transaction);
            if (extracted <= 0) {
                return 0;
            }
            transferable = target.insert(extracted, transaction);
        }

        if (transferable <= 0) {
            return 0;
        }

        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = source.extract(transferable, transaction);
            if (extracted <= 0) {
                return 0;
            }
            int inserted = target.insert(extracted, transaction);
            if (inserted != extracted) {
                return 0;
            }

            transaction.commit();
            return inserted;
        }
    }

    public static void tickItem(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L) {
            return;
        }
        tickTransfer(level, LeadKind.ITEM, Capabilities.Item.BLOCK, ITEM_RR_CURSOR,
                rope -> Math.min(64, 1 << Math.min(Config.itemTierMax(), rope.tier())));
    }

    public static void tickFluid(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L) {
            return;
        }
        tickTransfer(level, LeadKind.FLUID, Capabilities.Fluid.BLOCK, FLUID_RR_CURSOR,
                rope -> Config.fluidBucketAmount() * (1 << Math.min(Config.fluidTierMax(), rope.tier())));
    }

    public static void tickPressurized(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L || !isMekanismLoaded()) {
            return;
        }
        tickPressurizedTransfer(level);
    }

    public static void tickThermal(ServerLevel level) {
        if (!isMekanismLoaded()) {
            return;
        }

        List<LeadConnection> thermalConnections = SuperLeadSavedData.get(level).connectionsOfKind(LeadKind.THERMAL);
        if (thermalConnections.isEmpty()) {
            return;
        }

        MekanismHeatBridge.HandlerCache heatHandlers = new MekanismHeatBridge.HandlerCache();
        boolean[] visited = new boolean[thermalConnections.size()];
        Map<BlockPos, List<Integer>> connectionsByPos = indexConnectionsByBlockPos(thermalConnections);
        for (int i = 0; i < thermalConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = thermalConnections.get(component.get(cursor));
                addUnvisitedNeighborsByPos(current.from().pos(), connectionsByPos, visited, component);
                addUnvisitedNeighborsByPos(current.to().pos(), connectionsByPos, visited, component);
            }

            balanceThermalComponent(level, component, thermalConnections, heatHandlers);
        }
    }

    public static void tickAeNetwork(ServerLevel level) {
        if (!isAe2Loaded()) {
            return;
        }
        List<LeadConnection> aeConnections = SuperLeadSavedData.get(level).connectionsOfKind(LeadKind.AE_NETWORK);
        AE2NetworkBridge.reconcile(level, aeConnections);
    }

    private static void tickPressurizedTransfer(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);

        List<LeadConnection> pressurizedConnections = data.connectionsOfKind(LeadKind.PRESSURIZED);
        if (pressurizedConnections.isEmpty()) {
            return;
        }

        MekanismChemicalBridge.HandlerCache chemicalHandlers = new MekanismChemicalBridge.HandlerCache();
        Map<BlockPos, List<LeadConnection>> ropesAt = new HashMap<>();
        Map<BlockPos, List<LeadConnection>> startsBySource = new HashMap<>();
        for (LeadConnection c : pressurizedConnections) {
            BlockPos a = c.from().pos().immutable();
            BlockPos b = c.to().pos().immutable();
            ropesAt.computeIfAbsent(a, k -> new ArrayList<>()).add(c);
            if (!a.equals(b)) {
                ropesAt.computeIfAbsent(b, k -> new ArrayList<>()).add(c);
            }

            LeadAnchor src = c.extractSource();
            if (src != null) {
                startsBySource.computeIfAbsent(src.pos().immutable(), k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<BlockPos, List<LeadConnection>> entry : startsBySource.entrySet()) {
            BlockPos sourcePos = entry.getKey();
            List<LeadConnection> ropes = entry.getValue();
            if (ropes.isEmpty()) {
                continue;
            }

            int n = ropes.size();
            int start = PRESSURIZED_RR_CURSOR.getOrDefault(sourcePos, 0) % n;

            for (int step = 0; step < n; step++) {
                int idx = (start + step) % n;
                LeadConnection rope = ropes.get(idx);
                LeadAnchor sourceAnchor = rope.extractSource();
                LeadAnchor firstFar = rope.extractTarget();
                if (sourceAnchor == null || firstFar == null
                        || !chemicalHandlers.has(level, sourceAnchor)) {
                    continue;
                }

                long batch = pressurizedBatch(rope);
                List<PathStep> path = new ArrayList<>();
                List<RrChoice> rrChoices = new ArrayList<>();
                Set<UUID> visited = new HashSet<>();
                visited.add(rope.id());
                path.add(new PathStep(rope, rope.extractAnchor() == 2));

                if (walkAndTransferPressurized(level, sourceAnchor, batch, firstFar, ropesAt,
                        PRESSURIZED_RR_CURSOR, chemicalHandlers, visited, path, rrChoices, 1)) {
                    long now = level.getGameTime();
                    for (int i = 0; i < path.size(); i++) {
                        PathStep s = path.get(i);
                        long startTick = now + (long) i * ITEM_PULSE_DURATION_TICKS;
                        SuperLeadPayloads.sendItemPulse(level,
                                new ItemPulse(s.rope.id(), s.reverse, startTick, ITEM_PULSE_DURATION_TICKS));
                    }
                    PRESSURIZED_RR_CURSOR.put(sourcePos, (idx + 1) % n);
                    for (RrChoice rc : rrChoices) {
                        PRESSURIZED_RR_CURSOR.put(rc.knot, (rc.idx + 1) % rc.n);
                    }
                    break;
                }
            }
        }
    }

    private static long pressurizedBatch(LeadConnection rope) {
        int tier = Math.min(Config.pressurizedTierMax(), rope.tier());
        long multiplier = 1L << Math.min(30, tier);
        return Math.max(1L, Math.min(Integer.MAX_VALUE, (long) Config.pressurizedBatchAmount() * multiplier));
    }

    private static void balanceThermalComponent(ServerLevel level, List<Integer> component,
            List<LeadConnection> thermalConnections, MekanismHeatBridge.HandlerCache heatHandlers) {
        List<LeadAnchor> endpoints = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();
        double componentRate = 0.0D;
        for (int index : component) {
            LeadConnection connection = thermalConnections.get(index);
            componentRate = Math.min(1.0e12D,
                    componentRate + Config.thermalBaseTransfer() * connection.speedMultiplier());
            addThermalEndpoint(level, connection.from(), endpoints, seenAnchors, heatHandlers);
            addThermalEndpoint(level, connection.to(), endpoints, seenAnchors, heatHandlers);
        }
        if (endpoints.size() < 2 || componentRate <= 0.0D) {
            return;
        }

        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                heatHandlers.balance(level, endpoints.get(i), endpoints.get(j), componentRate);
            }
        }
    }

    private static void addThermalEndpoint(ServerLevel level, LeadAnchor anchor, List<LeadAnchor> endpoints,
            Set<LeadAnchor> seenAnchors, MekanismHeatBridge.HandlerCache heatHandlers) {
        if (seenAnchors.add(anchor) && heatHandlers.has(level, anchor)) {
            endpoints.add(anchor);
        }
    }

    private static boolean isMekanismLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded("mekanism");
    }

    private static boolean isAe2Loaded() {
        return net.neoforged.fml.ModList.get().isLoaded("ae2");
    }

    private static <R extends Resource> void tickTransfer(
            ServerLevel level,
            LeadKind kind,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            Map<BlockPos, Integer> rrCursor,
            java.util.function.ToIntFunction<LeadConnection> batchOf) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        List<LeadConnection> transferConnections = data.connectionsOfKind(kind);
        if (transferConnections.isEmpty()) {
            return;
        }

        // Index every rope of this kind by both endpoint positions so we can walk
        // through
        // fence-knot junctions where multiple ropes share a BlockPos.
        ResourceHandlerCache<R> handlers = new ResourceHandlerCache<>();
        Map<BlockPos, List<LeadConnection>> ropesAt = new HashMap<>();
        Map<BlockPos, List<LeadConnection>> startsBySource = new HashMap<>();
        for (LeadConnection c : transferConnections) {
            BlockPos a = c.from().pos().immutable();
            BlockPos b = c.to().pos().immutable();
            ropesAt.computeIfAbsent(a, k -> new ArrayList<>()).add(c);
            if (!a.equals(b)) {
                ropesAt.computeIfAbsent(b, k -> new ArrayList<>()).add(c);
            }

            if (c.extractAnchor() == 0) {
                continue;
            }
            LeadAnchor src = c.extractSource();
            if (src == null)
                continue;
            startsBySource.computeIfAbsent(src.pos().immutable(), k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<BlockPos, List<LeadConnection>> entry : startsBySource.entrySet()) {
            BlockPos sourcePos = entry.getKey();
            List<LeadConnection> ropes = entry.getValue();
            if (ropes.isEmpty())
                continue;

            int n = ropes.size();
            int start = rrCursor.getOrDefault(sourcePos, 0) % n;

            for (int step = 0; step < n; step++) {
                int idx = (start + step) % n;
                LeadConnection rope = ropes.get(idx);
                LeadAnchor sourceAnchor = rope.extractSource();
                LeadAnchor firstFar = rope.extractTarget();
                if (sourceAnchor == null || firstFar == null)
                    continue;

                ResourceHandler<R> sourceHandler = handlers.get(level, sourceAnchor, cap);
                if (sourceHandler == null)
                    continue;

                int batch = Math.max(1, batchOf.applyAsInt(rope));

                List<PathStep> path = new ArrayList<>();
                List<RrChoice> rrChoices = new ArrayList<>();
                Set<UUID> visited = new HashSet<>();
                visited.add(rope.id());
                path.add(new PathStep(rope, rope.extractAnchor() == 2));

                if (walkAndTransfer(level, cap, handlers, sourceHandler, batch, firstFar, ropesAt, rrCursor, visited,
                        path, rrChoices, 1)) {
                    long now = level.getGameTime();
                    for (int i = 0; i < path.size(); i++) {
                        PathStep s = path.get(i);
                        long startTick = now + (long) i * ITEM_PULSE_DURATION_TICKS;
                        SuperLeadPayloads.sendItemPulse(level,
                                new ItemPulse(s.rope.id(), s.reverse, startTick, ITEM_PULSE_DURATION_TICKS));
                    }
                    rrCursor.put(sourcePos, (idx + 1) % n);
                    for (RrChoice rc : rrChoices) {
                        rrCursor.put(rc.knot, (rc.idx + 1) % rc.n);
                    }
                    break;
                }
            }
        }
    }

    private record PathStep(LeadConnection rope, boolean reverse) {
    }

    private record RrChoice(BlockPos knot, int idx, int n) {
    }

    /**
     * DFS through the rope graph starting at {@code current}. If {@code current}
     * hosts a handler,
     * attempt a single transfer from {@code sourceHandler}. Otherwise treat
     * {@code current.pos()}
     * as a knot and round-robin through its unvisited ropes. Returns true only when
     * a transfer
     * was actually committed.
     */
    private static <R extends Resource> boolean walkAndTransfer(
            ServerLevel level,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            ResourceHandlerCache<R> handlers,
            ResourceHandler<R> sourceHandler,
            int batch,
            LeadAnchor current,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, Integer> rrCursor,
            Set<UUID> visited,
            List<PathStep> path,
            List<RrChoice> rrChoices,
            int depth) {
        if (depth > MAX_TRANSFER_SEARCH_DEPTH) {
            return false;
        }
        ResourceHandler<R> h = handlers.get(level, current, cap);
        if (h != null) {
            return transferOne(sourceHandler, h, batch, resource -> pathAllowsResource(path, resource));
        }

        BlockPos knot = current.pos().immutable();
        List<LeadConnection> all = ropesAt.getOrDefault(knot, List.of());
        List<LeadConnection> branches = new ArrayList<>();
        for (LeadConnection b : all) {
            if (!visited.contains(b.id())) {
                branches.add(b);
            }
        }
        if (branches.isEmpty()) {
            return false;
        }

        int n = branches.size();
        int rrStart = rrCursor.getOrDefault(knot, 0) % n;
        for (int step = 0; step < n; step++) {
            int idx = (rrStart + step) % n;
            LeadConnection branch = branches.get(idx);
            boolean enteredFromSide = branch.from().pos().equals(knot);
            LeadAnchor far = enteredFromSide ? branch.to() : branch.from();
            boolean reverse = !enteredFromSide;

            visited.add(branch.id());
            path.add(new PathStep(branch, reverse));
            rrChoices.add(new RrChoice(knot, idx, n));

            if (walkAndTransfer(level, cap, handlers, sourceHandler, batch, far, ropesAt, rrCursor, visited, path,
                    rrChoices, depth + 1)) {
                return true;
            }

            path.remove(path.size() - 1);
            rrChoices.remove(rrChoices.size() - 1);
            visited.remove(branch.id());
        }
        return false;
    }

    private static boolean walkAndTransferPressurized(
            ServerLevel level,
            LeadAnchor sourceAnchor,
            long batch,
            LeadAnchor current,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, Integer> rrCursor,
            MekanismChemicalBridge.HandlerCache chemicalHandlers,
            Set<UUID> visited,
            List<PathStep> path,
            List<RrChoice> rrChoices,
            int depth) {
        if (depth > MAX_TRANSFER_SEARCH_DEPTH) {
            return false;
        }
        if (chemicalHandlers.has(level, current)) {
            return chemicalHandlers.transferOne(level, sourceAnchor, current, batch,
                    MekanismChemicalBridge.ChemicalFilter.ANY, pathConnections(path)) > 0L;
        }

        BlockPos knot = current.pos().immutable();
        List<LeadConnection> all = ropesAt.getOrDefault(knot, List.of());
        List<LeadConnection> branches = new ArrayList<>();
        for (LeadConnection b : all) {
            if (!visited.contains(b.id())) {
                branches.add(b);
            }
        }
        if (branches.isEmpty()) {
            return false;
        }

        int n = branches.size();
        int rrStart = rrCursor.getOrDefault(knot, 0) % n;
        for (int step = 0; step < n; step++) {
            int idx = (rrStart + step) % n;
            LeadConnection branch = branches.get(idx);
            boolean enteredFromSide = branch.from().pos().equals(knot);
            LeadAnchor far = enteredFromSide ? branch.to() : branch.from();
            boolean reverse = !enteredFromSide;

            visited.add(branch.id());
            path.add(new PathStep(branch, reverse));
            rrChoices.add(new RrChoice(knot, idx, n));

            if (walkAndTransferPressurized(level, sourceAnchor, batch, far, ropesAt, rrCursor,
                    chemicalHandlers, visited, path, rrChoices, depth + 1)) {
                return true;
            }

            path.remove(path.size() - 1);
            rrChoices.remove(rrChoices.size() - 1);
            visited.remove(branch.id());
        }
        return false;
    }

    private static <R extends Resource> ResourceHandler<R> handler(
            ServerLevel level, LeadAnchor anchor, BlockCapability<ResourceHandler<R>, Direction> cap) {
        ResourceHandler<R> h = level.getCapability(cap, anchor.pos(), anchor.face());
        if (h == null) {
            h = level.getCapability(cap, anchor.pos(), null);
        }
        return h;
    }

    private static EnergyHandler energyHandler(ServerLevel level, LeadAnchor anchor) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), null);
        }
        return handler;
    }

    private static LeadAnchor cacheKey(LeadAnchor anchor) {
        return new LeadAnchor(anchor.pos().immutable(), anchor.face());
    }

    private static List<LeadConnection> pathConnections(List<PathStep> path) {
        List<LeadConnection> connections = new ArrayList<>(path.size());
        for (PathStep step : path) {
            connections.add(step.rope());
        }
        return connections;
    }

    private static <R extends Resource> boolean pathAllowsResource(List<PathStep> path, R resource) {
        for (PathStep step : path) {
            if (!connectionAllowsResource(step.rope(), resource)) {
                return false;
            }
        }
        return true;
    }

    private static <R extends Resource> boolean connectionAllowsResource(LeadConnection connection, R resource) {
        if (connection == null || resource == null || resource.isEmpty() || connection.attachments().isEmpty()) {
            return true;
        }
        if (resource instanceof ItemResource itemResource) {
            return connectionAllowsItemResource(connection, itemResource);
        }
        if (resource instanceof FluidResource fluidResource) {
            return connectionAllowsFluidResource(connection, fluidResource);
        }
        return true;
    }

    private static boolean connectionAllowsItemResource(LeadConnection connection, ItemResource resource) {
        boolean hasItemFilter = false;
        ItemStack candidate = null;
        for (RopeAttachment attachment : connection.attachments()) {
            ItemStack sample = attachment.stack();
            if (sample.isEmpty()) {
                continue;
            }
            if (CargoManifestData.isManifestStack(sample)) {
                hasItemFilter = true;
                if (candidate == null) {
                    candidate = resource.toStack(1);
                }
                if (CargoManifestData.matches(sample, candidate)) {
                    return true;
                }
                continue;
            }
            hasItemFilter = true;
            if (resource.matches(sample)) {
                return true;
            }
        }
        return !hasItemFilter;
    }

    private static boolean connectionAllowsFluidResource(LeadConnection connection, FluidResource resource) {
        boolean hasFluidFilter = false;
        for (RopeAttachment attachment : connection.attachments()) {
            var sample = FluidUtil.getFirstStackContained(attachment.stack());
            if (sample.isEmpty()) {
                continue;
            }
            hasFluidFilter = true;
            if (resource.matches(sample)) {
                return true;
            }
        }
        return !hasFluidFilter;
    }

    /**
     * Try to extract up to {@code batch} units from any source slot whose contents
     * the target
     * accepts entirely. Returns true if anything was moved.
     */
    private static <R extends Resource> boolean transferOne(ResourceHandler<R> source, ResourceHandler<R> target,
            int batch, Predicate<R> filter) {
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            R res = source.getResource(slot);
            if (res == null || res.isEmpty())
                continue;
            if (filter != null && !filter.test(res))
                continue;
            long avail = source.getAmountAsLong(slot);
            if (avail <= 0L)
                continue;

            int requested = (int) Math.min(batch, avail);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = source.extract(slot, res, requested, tx);
                if (extracted <= 0)
                    continue;
                int inserted = target.insert(res, extracted, tx);
                if (inserted != extracted)
                    continue;
                tx.commit();
                return true;
            }
        }
        return false;
    }

    private static Map<LeadAnchor, List<Integer>> indexConnectionsByAnchor(List<LeadConnection> connections) {
        Map<LeadAnchor, List<Integer>> byAnchor = new HashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            LeadConnection connection = connections.get(i);
            addAnchorIndex(byAnchor, connection.from(), i);
            if (!connection.to().equals(connection.from())) {
                addAnchorIndex(byAnchor, connection.to(), i);
            }
        }
        return byAnchor;
    }

    private static Map<BlockPos, List<Integer>> indexConnectionsByBlockPos(List<LeadConnection> connections) {
        Map<BlockPos, List<Integer>> byPos = new HashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            LeadConnection connection = connections.get(i);
            addBlockPosIndex(byPos, connection.from().pos(), i);
            if (!connection.to().pos().equals(connection.from().pos())) {
                addBlockPosIndex(byPos, connection.to().pos(), i);
            }
        }
        return byPos;
    }

    private static void addAnchorIndex(Map<LeadAnchor, List<Integer>> byAnchor, LeadAnchor anchor, int index) {
        byAnchor.computeIfAbsent(anchor, key -> new ArrayList<>()).add(index);
    }

    private static void addBlockPosIndex(Map<BlockPos, List<Integer>> byPos, BlockPos pos, int index) {
        byPos.computeIfAbsent(pos.immutable(), key -> new ArrayList<>()).add(index);
    }

    private static void addUnvisitedNeighbors(LeadAnchor anchor, Map<LeadAnchor, List<Integer>> byAnchor,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byAnchor.get(anchor);
        if (neighbors != null) {
            for (int index : neighbors) {
                if (!visited[index]) {
                    visited[index] = true;
                    component.add(index);
                }
            }
        }
    }

    private static void addUnvisitedNeighborsByPos(BlockPos pos, Map<BlockPos, List<Integer>> byPos,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byPos.get(pos);
        if (neighbors != null) {
            for (int index : neighbors) {
                if (!visited[index]) {
                    visited[index] = true;
                    component.add(index);
                }
            }
        }
    }

    private static void addUnvisitedNeighborsBridge(RedstoneTraversalCache cache, LeadAnchor anchor,
            Map<LeadAnchor, List<Integer>> byAnchor, boolean[] visited, List<Integer> component) {
        addUnvisitedNeighbors(anchor, byAnchor, visited, component);
        if (!cache.signalBridgeEnabled(anchor.pos())) {
            return;
        }
        for (Direction face : Direction.values()) {
            if (face == anchor.face())
                continue;
            LeadAnchor otherFace = new LeadAnchor(anchor.pos(), face);
            addUnvisitedNeighbors(otherFace, byAnchor, visited, component);
        }
    }

    public static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        int signal = 0;
        List<LeadConnection> redstoneConnections = level instanceof ServerLevel serverLevel
                ? SuperLeadSavedData.get(serverLevel).connectionsOfKind(LeadKind.REDSTONE)
                : connections(level);
        for (LeadConnection connection : redstoneConnections) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            if (isRedstoneOutputPosition(connection.from(), pos) || isRedstoneOutputPosition(connection.to(), pos)) {
                signal = Math.max(signal, connection.power());
                if (signal >= 15) {
                    return 15;
                }
            }
        }
        return signal;
    }

    private static Optional<LeadConnection> nearestConnection(Level level, Vec3 point, double maxDistance,
            Predicate<LeadConnection> predicate) {
        LeadConnection closest = null;
        double closestDistance = maxDistance * maxDistance;
        for (LeadConnection connection : connections(level)) {
            if (!predicate.test(connection)) {
                continue;
            }
            double distance = distanceToConnectionSqr(level, connection, point);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = connection;
            }
        }
        return Optional.ofNullable(closest);
    }

    private static Optional<ConnectionPick> nearestConnectionInView(Level level, Player player, double radius,
            Predicate<LeadConnection> predicate) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        double maxDistance = MAX_LEASH_DISTANCE;
        double radiusSqr = radius * radius;
        ConnectionPick best = null;

        for (LeadConnection connection : connections(level)) {
            if (!predicate.test(connection)) {
                continue;
            }

            LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
            Vec3 a = endpoints.from();
            Vec3 b = endpoints.to();
            ConnectionPick pick = pickSegment(connection, a, b, origin, direction, maxDistance);
            if (pick == null || pick.distanceSqr() > radiusSqr) {
                continue;
            }
            if (best == null
                    || pick.distanceSqr() < best.distanceSqr()
                    || (Math.abs(pick.distanceSqr() - best.distanceSqr()) < 1.0e-6D && pick.along() < best.along())) {
                best = pick;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double distancePointToApproximateSaggedCurveSqr(Vec3 point, Vec3 a, Vec3 b) {
        double sag = Math.min(SERVER_CONFIRMED_CURVE_MAX_SAG,
                a.distanceTo(b) * SERVER_CONFIRMED_CURVE_SAG_PER_BLOCK);
        if (sag <= 1.0e-6D) {
            return distancePointToSegmentSqr(point, a, b);
        }

        double best = Double.POSITIVE_INFINITY;
        Vec3 previous = a;
        for (int i = 1; i <= SERVER_CONFIRMED_CURVE_SAMPLES; i++) {
            double t = i / (double) SERVER_CONFIRMED_CURVE_SAMPLES;
            Vec3 current = a.lerp(b, t).add(0.0D, -Math.sin(Math.PI * t) * sag, 0.0D);
            best = Math.min(best, distancePointToSegmentSqr(point, previous, current));
            previous = current;
        }
        return best;
    }

    private static ConnectionPick pickSegment(LeadConnection connection, Vec3 a, Vec3 b, Vec3 origin, Vec3 direction,
            double maxDistance) {
        Vec3 segment = b.subtract(a);
        double segLenSqr = segment.lengthSqr();
        if (segLenSqr < 1.0e-8D) {
            double along = a.subtract(origin).dot(direction);
            if (along < 0.0D || along > maxDistance) {
                return null;
            }
            Vec3 rayPoint = origin.add(direction.scale(along));
            return new ConnectionPick(connection, a, a.distanceToSqr(rayPoint), along);
        }

        Vec3 w = a.subtract(origin);
        double segDotRay = segment.dot(direction);
        double segDotW = segment.dot(w);
        double rayDotW = direction.dot(w);
        double denom = segLenSqr - segDotRay * segDotRay;
        double s = denom < 1.0e-8D ? 0.0D : (segDotRay * rayDotW - segDotW) / denom;
        s = Math.max(0.0D, Math.min(1.0D, s));
        double along = segment.scale(s).add(w).dot(direction);
        along = Math.max(0.0D, Math.min(maxDistance, along));

        // 重新用裁剪后的射线参数回算一次 segment 参数，保证端点/近距离情况下也稳定。
        s = direction.scale(along).subtract(w).dot(segment) / segLenSqr;
        s = Math.max(0.0D, Math.min(1.0D, s));
        Vec3 ropePoint = a.add(segment.scale(s));
        Vec3 rayPoint = origin.add(direction.scale(along));
        return new ConnectionPick(connection, ropePoint, ropePoint.distanceToSqr(rayPoint), along);
    }

    private static double distancePointToSegmentSqr(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 segment = b.subtract(a);
        double lenSqr = segment.lengthSqr();
        if (lenSqr < 1.0e-8D) {
            return point.distanceToSqr(a);
        }
        double t = point.subtract(a).dot(segment) / lenSqr;
        t = clamp01(t);
        return point.distanceToSqr(a.add(segment.scale(t)));
    }

    private static double closestSegmentParameter(Vec3 a, Vec3 b, Vec3 point) {
        Vec3 segment = b.subtract(a);
        double lenSqr = segment.lengthSqr();
        if (lenSqr < 1.0e-8D) {
            return 0.0D;
        }
        return clamp01(point.subtract(a).dot(segment) / lenSqr);
    }

    private static boolean isFinite(Vec3 point) {
        return point != null
                && Double.isFinite(point.x)
                && Double.isFinite(point.y)
                && Double.isFinite(point.z);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private static int externalSignalAt(ServerLevel level, LeadAnchor anchor) {
        return withoutLeadSignals(() -> {
            int power = 0;
            power = Math.max(power, level.getSignal(anchor.pos(), anchor.face()));
            power = Math.max(power, level.getSignal(anchor.pos(), anchor.face().getOpposite()));
            power = Math.max(power, level.getBestNeighborSignal(anchor.pos()));
            power = Math.max(power, level.getBestNeighborSignal(anchor.pos().relative(anchor.face())));
            return Math.min(power, 15);
        });
    }

    private static int withoutLeadSignals(IntSupplier supplier) {
        boolean old = SUPPRESS_LEAD_SIGNALS.get();
        SUPPRESS_LEAD_SIGNALS.set(true);
        try {
            return supplier.getAsInt();
        } finally {
            SUPPRESS_LEAD_SIGNALS.set(old);
        }
    }

    private static boolean isRedstoneOutputPosition(LeadAnchor anchor, BlockPos pos) {
        return anchor.pos().equals(pos) || anchor.pos().relative(anchor.face()).equals(pos);
    }

    private static void notifyRedstoneChange(ServerLevel level, LeadConnection connection) {
        if (connection.kind() != LeadKind.REDSTONE) {
            return;
        }
        notifyRedstoneAnchor(level, connection.from());
        notifyRedstoneAnchor(level, connection.to());
    }

    private static void notifyRedstoneAnchor(ServerLevel level, LeadAnchor anchor) {
        BlockPos pos = anchor.pos();
        level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
        level.updateNeighborsAt(pos.relative(anchor.face()), Blocks.AIR);
    }

    public static boolean cutNearest(Level level, Vec3 point, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<LeadConnection> closest = nearestConnection(serverLevel, point, 0.75D, connection -> true);
        if (closest.isEmpty()) {
            return false;
        }

        LeadConnection removedConnection = closest.get();
        boolean removed = SuperLeadSavedData.get(serverLevel)
                .removeIf(connection -> connection.id().equals(removedConnection.id()));
        if (!removed) {
            return false;
        }

        dropConnectionDrops(serverLevel, removedConnection, point, player);
        cleanupFenceKnot(serverLevel, removedConnection.from());
        cleanupFenceKnot(serverLevel, removedConnection.to());
        notifyRedstoneChange(serverLevel, removedConnection);
        SuperLeadPayloads.sendToDimension(serverLevel);
        PresetServerManager.syncDimensionPresets(serverLevel);
        return true;
    }

    public static boolean cutNearestInView(Level level, Player player, double radius) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<ConnectionPick> pick = nearestConnectionInView(serverLevel, player, radius, connection -> true);
        if (pick.isEmpty()) {
            return false;
        }

        LeadConnection removedConnection = pick.get().connection();
        boolean removed = SuperLeadSavedData.get(serverLevel)
                .removeIf(connection -> connection.id().equals(removedConnection.id()));
        if (!removed) {
            return false;
        }

        dropConnectionDrops(serverLevel, removedConnection, pick.get().point(), player);
        cleanupFenceKnot(serverLevel, removedConnection.from());
        cleanupFenceKnot(serverLevel, removedConnection.to());
        notifyRedstoneChange(serverLevel, removedConnection);
        SuperLeadPayloads.sendToDimension(serverLevel);
        PresetServerManager.syncDimensionPresets(serverLevel);
        return true;
    }

    public static int cutAttachedTo(Level level, LeadAnchor anchor, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        List<LeadConnection> connections = SuperLeadSavedData.get(serverLevel).connections();
        List<LeadConnection> removedConnections = new ArrayList<>();
        for (LeadConnection connection : connections) {
            if (connection.from().equals(anchor) || connection.to().equals(anchor)) {
                removedConnections.add(connection);
            }
        }
        if (removedConnections.isEmpty()) {
            return 0;
        }

        SuperLeadSavedData.get(serverLevel)
                .removeIf(connection -> connection.from().equals(anchor) || connection.to().equals(anchor));
        Vec3 dropPoint = anchor.attachmentPoint(level);
        for (LeadConnection connection : removedConnections) {
            dropConnectionDrops(serverLevel, connection, dropPoint, player);
            cleanupFenceKnot(serverLevel, connection.from());
            cleanupFenceKnot(serverLevel, connection.to());
            notifyRedstoneChange(serverLevel, connection);
        }
        SuperLeadPayloads.sendToDimension(serverLevel);
        PresetServerManager.syncDimensionPresets(serverLevel);
        return removedConnections.size();
    }

    public static int removeConnectionsWithoutDrops(ServerLevel level, Predicate<LeadConnection> predicate) {
        List<LeadConnection> removedConnections = new ArrayList<>();
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            if (predicate.test(connection)) {
                removedConnections.add(connection);
            }
        }
        if (removedConnections.isEmpty()) {
            return 0;
        }

        Set<UUID> removedIds = new HashSet<>();
        for (LeadConnection connection : removedConnections) {
            removedIds.add(connection.id());
        }
        boolean removed = SuperLeadSavedData.get(level)
                .removeIf(connection -> removedIds.contains(connection.id()));
        if (!removed) {
            return 0;
        }

        for (LeadConnection connection : removedConnections) {
            cleanupFenceKnot(level, connection.from());
            cleanupFenceKnot(level, connection.to());
            notifyRedstoneChange(level, connection);
        }
        SuperLeadPayloads.sendToDimension(level);
        PresetServerManager.syncDimensionPresets(level);
        return removedConnections.size();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance) {
        double maxDistanceSqr = maxDistance * maxDistance;
        for (LeadConnection connection : connections(level)) {
            if (distanceToConnectionSqr(level, connection, point) <= maxDistanceSqr) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasConnectionAttachedTo(Level level, LeadAnchor anchor) {
        for (LeadConnection connection : connections(level)) {
            if (connection.from().equals(anchor) || connection.to().equals(anchor)) {
                return true;
            }
        }
        return false;
    }

    public static int countConnectionsAtAnchor(Level level, LeadAnchor anchor) {
        int count = 0;
        for (LeadConnection connection : connections(level)) {
            if (connection.from().equals(anchor))
                count++;
            if (connection.to().equals(anchor))
                count++;
        }
        return count;
    }

    private static void spawnBlockFaceLimitParticles(ServerLevel level, LeadAnchor anchor) {
        Vec3 center = Vec3.atCenterOf(anchor.pos());
        Direction face = anchor.face();
        Vec3 particlePos = center.add(
                face.getStepX() * 0.55D,
                face.getStepY() * 0.55D,
                face.getStepZ() * 0.55D);
        level.sendParticles(
                new DustParticleOptions(0xFF2222, 1.0F),
                particlePos.x, particlePos.y, particlePos.z,
                12, 0.15D, 0.15D, 0.15D, 0.05D);
    }

    private static double distanceToConnectionSqr(Level level, LeadConnection connection, Vec3 point) {
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        Vec3 ab = b.subtract(a);
        double lenSqr = ab.lengthSqr();
        if (lenSqr < 1.0e-8D) {
            return point.distanceToSqr(a);
        }
        double t = point.subtract(a).dot(ab) / lenSqr;
        t = Math.max(0.0D, Math.min(1.0D, t));
        Vec3 closest = a.add(ab.scale(t));
        return point.distanceToSqr(closest);
    }

    private static void ensureFenceKnots(ServerLevel level) {
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            ensureFenceKnot(level, connection.from());
            ensureFenceKnot(level, connection.to());
        }
    }

    private static void ensureFenceKnot(ServerLevel level, LeadAnchor anchor) {
        if (!(level.getBlockState(anchor.pos()).getBlock() instanceof FenceBlock)) {
            return;
        }
        if (LeashFenceKnotEntity.getKnot(level, anchor.pos()).isEmpty()) {
            LeashFenceKnotEntity.createKnot(level, anchor.pos()).playPlacementSound();
        }
    }

    private static void cleanupFenceKnot(ServerLevel level, LeadAnchor anchor) {
        if (!(level.getBlockState(anchor.pos()).getBlock() instanceof FenceBlock)) {
            return;
        }
        boolean stillUsed = SuperLeadSavedData.get(level).connections().stream()
                .anyMatch(connection -> connection.from().equals(anchor) || connection.to().equals(anchor));
        if (stillUsed) {
            return;
        }
        LeashFenceKnotEntity.getKnot(level, anchor.pos()).ifPresent(knot -> {
            if (Leashable.leashableLeashedTo(knot).isEmpty()) {
                knot.discard();
            }
        });
    }

    private static Vec3 midpoint(Level level, LeadConnection connection) {
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        return endpoints.from().add(endpoints.to()).scale(0.5D);
    }

    private static void dropConnectionDrops(ServerLevel level, LeadConnection connection, Vec3 leadDropPoint,
            Player player) {
        dropLeads(level, leadDropPoint, player, 1);
        dropAttachments(level, connection, player);
    }

    private static void dropAttachments(ServerLevel level, LeadConnection connection, Player player) {
        if (player != null && player.isCreative()) {
            return;
        }
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        for (RopeAttachment attachment : connection.attachments()) {
            Vec3 point = a.lerp(b, attachment.t());
            ItemEntity drop = new ItemEntity(level, point.x, point.y, point.z,
                    prepareAttachmentDropStack(level, attachment, point));
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
    }

    private static ItemStack prepareAttachmentDropStack(ServerLevel level, RopeAttachment attachment, Vec3 point) {
        ItemStack stack = attachment.stack().copy();
        if (!net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            return stack;
        }
        try {
            return com.zhongbai233.super_lead.lead.integration.ae2.AE2NetworkBridge
                    .dropStoredContents(level, stack, point);
        } catch (RuntimeException | LinkageError ignored) {
            return stack;
        }
    }

    private static void dropLeads(ServerLevel level, Vec3 point, Player player, int count) {
        if ((player != null && player.isCreative()) || count <= 0) {
            return;
        }
        level.addFreshEntity(new ItemEntity(level, point.x, point.y, point.z,
                new ItemStack(SuperLeadItems.SUPER_LEAD.asItem(), count)));
    }

    private static LeadEndpointLayout.Endpoints endpoints(Level level, LeadConnection connection) {
        return LeadEndpointLayout.endpoints(level, connection, connections(level));
    }

    private record NetworkKey(ResourceKey<Level> dimension, boolean clientSide) {
        static NetworkKey of(Level level) {
            return new NetworkKey(level.dimension(), level.isClientSide());
        }
    }

    private record PlayerKey(UUID playerId, boolean clientSide) {
        static PlayerKey of(Player player) {
            return new PlayerKey(player.getUUID(), player.level().isClientSide());
        }
    }

    private record ConnectionPick(LeadConnection connection, Vec3 point, double distanceSqr, double along) {
    }

    private static final class ResourceHandlerCache<R extends Resource> {
        private final Map<LeadAnchor, ResourceHandler<R>> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        private ResourceHandler<R> get(ServerLevel level, LeadAnchor anchor,
                BlockCapability<ResourceHandler<R>, Direction> cap) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            ResourceHandler<R> cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            ResourceHandler<R> found = handler(level, key, cap);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }

    private static final class EnergyHandlerCache {
        private final Map<LeadAnchor, EnergyHandler> hits = new HashMap<>();
        private final Set<LeadAnchor> misses = new HashSet<>();

        private EnergyHandler get(ServerLevel level, LeadAnchor anchor) {
            if (anchor == null) {
                return null;
            }
            LeadAnchor key = cacheKey(anchor);
            EnergyHandler cached = hits.get(key);
            if (cached != null || misses.contains(key)) {
                return cached;
            }
            EnergyHandler found = energyHandler(level, key);
            if (found == null) {
                misses.add(key);
            } else {
                hits.put(key, found);
            }
            return found;
        }
    }

    private static final class RedstoneTraversalCache {
        private final ServerLevel level;
        private final Map<LeadAnchor, Integer> externalSignals = new HashMap<>();
        private final Map<BlockPos, Boolean> signalBridgeEnabled = new HashMap<>();

        private RedstoneTraversalCache(ServerLevel level) {
            this.level = level;
        }

        private int externalSignalAt(LeadAnchor anchor) {
            LeadAnchor key = cacheKey(anchor);
            Integer cached = externalSignals.get(key);
            if (cached != null) {
                return cached.intValue();
            }
            int signal = SuperLeadNetwork.externalSignalAt(level, key);
            externalSignals.put(key, signal);
            return signal;
        }

        private boolean signalBridgeEnabled(BlockPos pos) {
            BlockPos key = pos.immutable();
            Boolean cached = signalBridgeEnabled.get(key);
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean enabled = com.zhongbai233.super_lead.data.BlockPropertyRegistry.signalBridgeEnabled(
                    level.getBlockState(key).getBlock());
            signalBridgeEnabled.put(key, enabled);
            return enabled;
        }
    }

    private record EnergyEndpoint(LeadAnchor anchor, EnergyHandler handler) {
    }

    private record PendingLead(LeadAnchor anchor, LeadKind kind) {
    }
}
