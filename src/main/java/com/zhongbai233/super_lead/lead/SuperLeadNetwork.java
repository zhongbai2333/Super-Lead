package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Server/client facade for rope gameplay operations.
 *
 * <p>
 * This class is intentionally being slimmed down into smaller collaborators.
 * Keep new stateful subsystems out of here when possible: client chunk mirrors
 * live in {@link LeadClientConnectionCache}, first-click placement state lives
 * in
 * {@link LeadPlacementState}, resource transport lives in
 * {@link LeadTransferService}, redstone/energy state lives in
 * {@link LeadSignalService}, and future attachment/removal services should
 * follow
 * the same package-private helper pattern.
 */
public final class SuperLeadNetwork {
    public static final double MAX_LEASH_DISTANCE = 12.0D;
    public static final int MAX_LENGTH_UNITS = LeadConnection.MAX_LENGTH_UNITS;
    private static final double SERVER_CONFIRMED_ATTACH_REMOVAL_RADIUS = 2.50D;
    private static final double SERVER_CONFIRMED_PICK_REACH_SLACK = 1.5D;
    private static final int SERVER_CONFIRMED_CURVE_SAMPLES = 12;
    private static final double SERVER_CONFIRMED_CURVE_SAG_PER_BLOCK = 0.065D;
    private static final double SERVER_CONFIRMED_CURVE_MAX_SAG = 0.70D;
    /** Max distance² from claimed hit to rope chord/curve before rejection. */
    private static final double SERVER_CLAIM_PROXIMITY_SQR = 2.75D * 2.75D;
    /** Per-block T-slack factor: a 40-block rope gets 40×0.025 = 1.0 slack. */
    private static final double SERVER_CLAIM_T_SLACK_PER_BLOCK = 0.025D;
    private static final double SERVER_CLAIM_T_SLACK_MAX = 0.60D;
    /**
     * Round-robin cursor keyed by (BlockPos, kind ordinal) for per-click cycling.
     */
    private static final Map<Long, Integer> EXTRACT_TOGGLE_CURSOR = new HashMap<>();
    public static final int ITEM_TIER_MAX = 6;
    public static final int FLUID_TIER_MAX = 4;

    private SuperLeadNetwork() {
    }

    public static boolean canModifyRopes(Player player) {
        return player != null && player.mayBuild();
    }

    public static double baseLeashDistance() {
        return Config.maxLeashDistance();
    }

    public static double maxLeashDistanceForUnits(int lengthUnits) {
        int normalized = Math.max(LeadConnection.MIN_LENGTH_UNITS,
                Math.min(LeadConnection.MAX_LENGTH_UNITS, lengthUnits));
        return baseLeashDistance() * normalized;
    }

    public static double maxLeashDistance(LeadConnection connection) {
        return maxLeashDistanceForUnits(connection == null ? LeadConnection.MIN_LENGTH_UNITS
                : connection.lengthUnits());
    }

    public static double maxExtendedLeashDistance() {
        return maxLeashDistanceForUnits(LeadConnection.MAX_LENGTH_UNITS);
    }

    private static double serverConfirmedPickReach() {
        return maxExtendedLeashDistance() + SERVER_CONFIRMED_PICK_REACH_SLACK;
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
        return LeadPlacementState.pendingAnchor(player);
    }

    public static Optional<LeadKind> pendingKind(Player player) {
        return LeadPlacementState.pendingKind(player);
    }

    public static int pendingLengthUnits(Player player) {
        return LeadPlacementState.pendingLengthUnits(player).orElse(LeadConnection.MIN_LENGTH_UNITS);
    }

    public static boolean extendPendingLength(Player player) {
        return LeadPlacementState.extendPendingLength(player);
    }

    public static void setPendingAnchor(Player player, LeadAnchor anchor) {
        setPendingAnchor(player, anchor, LeadKind.NORMAL);
    }

    public static void setPendingAnchor(Player player, LeadAnchor anchor, LeadKind kind) {
        LeadPlacementState.setPendingAnchor(player, anchor, kind);
    }

    public static void clearPendingAnchor(Player player) {
        LeadPlacementState.clearPendingAnchor(player);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to) {
        return connect(level, from, to, LeadKind.NORMAL);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return connect(level, from, to, kind, null);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to, LeadKind kind, Player player) {
        return connect(level, from, to, kind, player, LeadConnection.MIN_LENGTH_UNITS);
    }

    public static LeadConnection connect(Level level, LeadAnchor from, LeadAnchor to, LeadKind kind, Player player,
            int lengthUnits) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            if (countConnectionsAtAnchor(serverLevel, from) >= Config.maxRopesPerBlockFace()
                    || countConnectionsAtAnchor(serverLevel, to) >= Config.maxRopesPerBlockFace()) {
                spawnBlockFaceLimitParticles(serverLevel, countConnectionsAtAnchor(serverLevel, from) >= Config
                        .maxRopesPerBlockFace() ? from : to);
                return null;
            }
            LeadConnection connection = LeadConnection.create(from, to, kind).withLengthUnits(lengthUnits);
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
            SuperLeadPayloads.sendDirtyToDimension(serverLevel);
            return connection;
        }
        // Client-side right-click handling only maintains the local pending-anchor
        // state. The
        // actual rope must arrive from the server via SyncRopeChunk; otherwise the
        // client creates
        // a random-UUID prediction that cannot be removed or saved.
        return LeadConnection.create(from, to, kind).withLengthUnits(lengthUnits);
    }

    public static List<LeadConnection> connections(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).connections();
        }
        return LeadClientConnectionCache.connections(level);
    }

    static Iterable<LeadConnection> serverConnectionsView(ServerLevel level) {
        return SuperLeadSavedData.get(level).connectionsView();
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
        LeadClientConnectionCache.replaceAll(level, connections);
    }

    /**
     * Applies a delta payload to the client-side mirror. Server data is stored in
     * SavedData.
     */
    public static void applyConnectionChanges(Level level, List<UUID> removed, List<LeadConnection> upserts) {
        LeadClientConnectionCache.applyChanges(level, removed, upserts);
    }

    /** Replaces the client-side rope snapshot for one watched chunk. */
    public static void replaceChunkConnections(Level level, ChunkPos chunk, List<LeadConnection> connections) {
        LeadClientConnectionCache.replaceChunk(level, chunk, connections);
    }

    /**
     * Drops one watched chunk from the client mirror, preserving ropes referenced
     * by other watched chunks.
     */
    public static void unloadChunkConnections(Level level, ChunkPos chunk) {
        LeadClientConnectionCache.unloadChunk(level, chunk);
    }

    /** Clears all client connection mirrors when leaving a server or client world. */
    public static void clearClientConnections() {
        LeadClientConnectionCache.clearAll();
    }

    public static void pruneInvalid(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            SuperLeadSavedData data = SuperLeadSavedData.get(serverLevel);
            List<LeadConnection> invalidConnections = new ArrayList<>();
            for (LeadConnection connection : data.connectionsView()) {
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
            SuperLeadPayloads.sendDirtyToDimension(serverLevel);
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
                || endpoints.from().distanceTo(endpoints.to()) > maxLeashDistance(connection)
                || anchorFaceBlocked(level, connection.from())
                || anchorFaceBlocked(level, connection.to());
    }

    /**
     * Returns true when the anchor's attachment face is obstructed by a
     * solid block. Only applies to face-mounted anchors (walls, ceilings, floors);
     * fence and iron-bar knots are exposed from all sides so a block above
     * doesn't necessarily block a horizontal rope path.
     */
    private static boolean anchorFaceBlocked(Level level, LeadAnchor anchor) {
        BlockState anchorState = level.getBlockState(anchor.pos());
        if (anchorState.isAir())
            return true;

        // Fence/iron-bar knots: rope can exit in any direction from the post top,
        // so a block above doesn't reliably indicate obstruction.
        if (LeadAnchor.isKnotBlock(anchorState))
            return false;

        // Face-mounted anchors: check the block adjacent in the anchor's face direction
        BlockPos adjacent = anchor.pos().relative(anchor.face());
        return level.getBlockState(adjacent).isCollisionShapeFullBlock(level, adjacent);
    }

    public static boolean hasUpgradeableConnectionNear(Level level, Vec3 point, double maxDistance) {
        return nearestConnection(level, point, maxDistance, connection -> connection.kind() != LeadKind.REDSTONE)
                .isPresent();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance,
            Predicate<LeadConnection> predicate) {
        return nearestConnection(level, point, maxDistance, predicate).isPresent();
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

    public static boolean toggleEnergyExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.ENERGY);
    }

    private static boolean toggleExtractAt(Level level, BlockPos pos, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SuperLeadSavedData data = SuperLeadSavedData.get(serverLevel);
        // Collect only connections anchored at this position.
        List<LeadConnection> atPos = new ArrayList<>();
        for (LeadConnection c : data.connectionsOfKindView(kind)) {
            if (c.from().pos().equals(pos) || c.to().pos().equals(pos)) {
                atPos.add(c);
            }
        }
        if (atPos.isEmpty()) {
            EXTRACT_TOGGLE_CURSOR.remove(cursorKey(pos, kind));
            return false;
        }

        int n = atPos.size();
        int cursor = EXTRACT_TOGGLE_CURSOR.getOrDefault(cursorKey(pos, kind), 0) % n;
        LeadConnection target = atPos.get(cursor);

        int newExtract;
        if (target.from().pos().equals(pos)) {
            newExtract = target.extractAnchor() == 1 ? 0 : 1;
        } else {
            newExtract = target.extractAnchor() == 2 ? 0 : 2;
        }
        data.update(target.id(), c -> c.withExtractAnchor(newExtract), false);
        EXTRACT_TOGGLE_CURSOR.put(cursorKey(pos, kind), (cursor + 1) % n);

        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
        return true;
    }

    private static long cursorKey(BlockPos pos, LeadKind kind) {
        return ((long) pos.hashCode() << 8) | kind.ordinal();
    }

    /**
     * Directly set the extract anchor on a specific connection (used by the
     * crosshair-aimed toggle flow via {@link LeadConnectionAction}).
     */
    static boolean updateConnectionExtract(ServerLevel level, Player player,
            LeadConnection connection, int newExtract) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        boolean updated = data.update(connection.id(), c -> c.withExtractAnchor(newExtract), false);
        if (updated) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
        return updated;
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
        return LeadClientConnectionCache.find(level, id);
    }

    /**
     * Broad server-side view check used only to consume the vanilla block
     * interaction.
     */
    public static boolean canUseClientPickedConnection(ServerLevel level, Player player, LeadConnection connection) {
        return isPlayerNearRope(level, player, connection, serverConfirmedPickReach());
    }

    /**
     * Server-side validation for a client-confirmed rope target.
     *
     * <p>
     * The client already did precise hit detection against the rendered physics
     * rope. The server only verifies the player is reasonably close to the rope
     * and the claimed hit point is in front of the player. This replaces the
     * old 7-layer ray-re-trace that frequently rejected legitimate actions
     * because the server's straight-chord + half-sine sag model didn't match
     * the client's full Verlet simulation.
     */
    public static boolean canUseClientPickedConnection(ServerLevel level, Player player,
            LeadConnection connection, Vec3 claimedHitPoint, double claimedT) {
        if (!isFinite(claimedHitPoint) || !Double.isFinite(claimedT)) {
            return false;
        }

        Vec3 origin = player.getEyePosition(1.0F);
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();

        // 1. Hit point must be in front of player, and player must be within
        // reasonable distance of at least one rope endpoint.
        Vec3 toHit = claimedHitPoint.subtract(origin);
        if (toHit.dot(player.getViewVector(1.0F)) < -0.5D) {
            return false;
        }
        double maxReach = serverConfirmedPickReach();
        if (origin.distanceToSqr(a) > maxReach * maxReach
                && origin.distanceToSqr(b) > maxReach * maxReach) {
            return false;
        }

        // 2. Hit point must be near the rope chord or physics curve.
        return isHitNearRope(level, connection, a, b, claimedHitPoint, clamp01(claimedT));
    }

    public static boolean canTouchConnectionForAttachment(ServerLevel level, Player player, LeadConnection connection) {
        return isPlayerNearRope(level, player, connection, serverConfirmedPickReach());
    }

    /**
     * Returns true when the player is within reasonable distance of the rope.
     * Uses the same endpoint-distance gate as the 4-parameter hit validation
     * (which works reliably for cutting/upgrade actions). For the proximity
     * check we simply test whether the player's eye is inside the rope's
     * generously-sized envelope — no fragile ray-clip needed.
     */
    private static boolean isPlayerNearRope(ServerLevel level, Player player, LeadConnection connection,
            double maxReach) {
        Vec3 origin = player.getEyePosition(1.0F);
        LeadEndpointLayout.Endpoints endpoints = endpoints(level, connection);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();

        if (origin.distanceToSqr(a) > maxReach * maxReach
                && origin.distanceToSqr(b) > maxReach * maxReach) {
            return false;
        }
        // Player eye is inside the rope's AABB envelope (with generous sag margin).
        return connectionEnvelope(a, b, SERVER_CONFIRMED_ATTACH_REMOVAL_RADIUS)
                .inflate(1.0D)
                .contains(origin);
    }

    /**
     * Simple proximity check: is the hit point within a generous distance of
     * the rope chord (or physics curve when a preset is active)?
     */
    private static boolean isHitNearRope(ServerLevel level, LeadConnection connection,
            Vec3 a, Vec3 b, Vec3 hit, double hitT) {
        if (distancePointToSegmentSqr(hit, a, b) <= SERVER_CLAIM_PROXIMITY_SQR) {
            return true;
        }
        if (!connection.physicsPreset().isBlank()) {
            double[] closest = new double[4];
            if (ServerRopeCurve.distancePointToCurveSqr(
                    ServerRopeCurve.from(level, connection, a, b), hit, closest) <= SERVER_CLAIM_PROXIMITY_SQR) {
                return true;
            }
        }
        if (distancePointToApproximateSaggedCurveSqr(hit, a, b) <= SERVER_CLAIM_PROXIMITY_SQR) {
            return true;
        }
        // Last resort: scale T-slack with rope length so long-rope end-clicks pass.
        double tSlack = tSlackFor(a.distanceTo(b));
        if (Math.abs(closestSegmentParameter(a, b, hit) - hitT) <= tSlack) {
            return connectionEnvelope(a, b, Math.sqrt(SERVER_CLAIM_PROXIMITY_SQR)).contains(hit);
        }
        return false;
    }

    private static double tSlackFor(double chordLength) {
        return Math.min(chordLength * SERVER_CLAIM_T_SLACK_PER_BLOCK, SERVER_CLAIM_T_SLACK_MAX);
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
        // Extended ropes cost more to upgrade:
        // lengthUnits=2 → 2×, 3 → 4×, 4 → 6×
        if (connection.lengthUnits() > 1) {
            int mult = 2 * (connection.lengthUnits() - 1);
            extraCost *= mult;
        }
        if (!player.isCreative() && extraCost > 0 && !consumeMatchingFromInventory(player, costMatcher, extraCost)) {
            return false;
        }
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.withTier(c.tier() + 1), true);
        if (ok) {
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
        return ok;
    }

    /** Per-connection: cut and drop a lead item at the connection midpoint. */
    public static boolean cutConnection(ServerLevel level, Player player, LeadConnection connection) {
        boolean removed = SuperLeadSavedData.get(level).removeIf(c -> c.id().equals(connection.id()));
        if (!removed)
            return false;
        dropConnectionDrops(level, connection, midpoint(level, connection), player);
        dropCutRefund(level, connection, midpoint(level, connection), player);
        cleanupFenceKnot(level, connection.from());
        cleanupFenceKnot(level, connection.to());
        notifyRedstoneChange(level, connection);
        SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
        }
        return ok;
    }

    public static boolean setAttachmentDisplay(ServerLevel level, LeadConnection connection,
            java.util.UUID attachmentId, int mountOverride, int displayModeOverride,
            int hangerOverride, int piercedOverride, double hangOffsetOverride, double mountOffsetOverride,
            double hangerLengthOverride, double hangerSpacingOverride, double scaleOverride, int frontSide,
            java.util.Map<String, String> modelStateOverride) {
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
            c -> c.setAttachmentDisplay(attachmentId, mountOverride, displayModeOverride,
                hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride, hangerLengthOverride,
                hangerSpacingOverride, scaleOverride, frontSide, modelStateOverride),
                true);
        if (ok) {
            SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
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
        SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
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
            SuperLeadPayloads.sendDirtyToDimension(level);
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
        if (target.lengthUnits() > 1) {
            int mult = 2 * (target.lengthUnits() - 1);
            extraCost *= mult;
        }
        if (!player.isCreative() && extraCost > 0 && !consumeMatchingFromInventory(player, costMatcher, extraCost)) {
            return false;
        }
        SuperLeadSavedData.get(serverLevel).update(target.id(),
                connection -> connection.withTier(connection.tier() + 1), true);
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
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
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
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
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
        return true;
    }

    public static void tickRedstone(ServerLevel level) {
        LeadSignalService.tickRedstone(level);
    }

    public static void tickEnergy(ServerLevel level) {
        LeadSignalService.tickEnergy(level);
    }

    public static void tickItem(ServerLevel level) {
        LeadTransferService.tickItem(level);
    }

    public static void tickFluid(ServerLevel level) {
        LeadTransferService.tickFluid(level);
    }

    public static void tickPressurized(ServerLevel level) {
        LeadTransferService.tickPressurized(level);
    }

    public static void tickThermal(ServerLevel level) {
        LeadTransferService.tickThermal(level);
    }

    public static void tickAeNetwork(ServerLevel level) {
        LeadTransferService.tickAeNetwork(level);
    }

    public static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        return LeadSignalService.leadSignal(getter, pos, direction);
    }

    public static int leadDirectSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        return LeadSignalService.leadDirectSignal(getter, pos, direction);
    }

    public static boolean hasLeadNeighborSignal(SignalGetter getter, BlockPos pos) {
        return LeadSignalService.hasLeadNeighborSignal(getter, pos);
    }

    private static Optional<LeadConnection> nearestConnection(Level level, Vec3 point, double maxDistance,
            Predicate<LeadConnection> predicate) {
        LeadConnection closest = null;
        double closestDistance = maxDistance * maxDistance;
        Iterable<LeadConnection> candidates = level instanceof ServerLevel serverLevel
                ? serverConnectionsView(serverLevel)
                : connections(level);
        for (LeadConnection connection : candidates) {
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
        double maxDistance = maxExtendedLeashDistance();
        double radiusSqr = radius * radius;
        ConnectionPick best = null;

        Iterable<LeadConnection> candidates = level instanceof ServerLevel serverLevel
                ? serverConnectionsView(serverLevel)
                : connections(level);
        for (LeadConnection connection : candidates) {
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

    private static void notifyRedstoneChange(ServerLevel level, LeadConnection connection) {
        LeadSignalService.notifyRedstoneChange(level, connection);
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
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
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
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
        PresetServerManager.syncDimensionPresets(serverLevel);
        return true;
    }

    public static int cutAttachedTo(Level level, LeadAnchor anchor, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        List<LeadConnection> removedConnections = new ArrayList<>();
        for (LeadConnection connection : SuperLeadSavedData.get(serverLevel).connectionsView()) {
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
        SuperLeadPayloads.sendDirtyToDimension(serverLevel);
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
        SuperLeadPayloads.sendDirtyToDimension(level);
        PresetServerManager.syncDimensionPresets(level);
        return removedConnections.size();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance) {
        double maxDistanceSqr = maxDistance * maxDistance;
        Iterable<LeadConnection> candidates = level instanceof ServerLevel serverLevel
            ? serverConnectionsView(serverLevel)
            : connections(level);
        for (LeadConnection connection : candidates) {
            if (distanceToConnectionSqr(level, connection, point) <= maxDistanceSqr) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasConnectionAttachedTo(Level level, LeadAnchor anchor) {
        Iterable<LeadConnection> candidates = level instanceof ServerLevel serverLevel
            ? serverConnectionsView(serverLevel)
            : connections(level);
        for (LeadConnection connection : candidates) {
            if (connection.from().equals(anchor) || connection.to().equals(anchor)) {
                return true;
            }
        }
        return false;
    }

    public static int countConnectionsAtAnchor(Level level, LeadAnchor anchor) {
        int count = 0;
        Iterable<LeadConnection> candidates = level instanceof ServerLevel serverLevel
            ? serverConnectionsView(serverLevel)
            : connections(level);
        for (LeadConnection connection : candidates) {
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
        for (LeadConnection connection : SuperLeadSavedData.get(level).connectionsView()) {
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
        for (LeadConnection connection : SuperLeadSavedData.get(level).connectionsView()) {
            if (connection.from().equals(anchor) || connection.to().equals(anchor)) {
                return;
            }
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
        dropLeads(level, leadDropPoint, player, connection.lengthUnits());
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

    /**
     * Drop the rope item matching the connection's kind and refund a fraction of
     * upgrade materials based on tier and {@link Config#cutRefundRatio()}.
     */
    private static void dropCutRefund(ServerLevel level, LeadConnection connection, Vec3 point, Player player) {
        if (player != null && player.isCreative())
            return;
        // Drop the rope item for this connection's kind
        ItemStack ropeDrop = SuperLeadItems.stack(connection.kind());
        ropeDrop.setCount(connection.lengthUnits());
        level.addFreshEntity(new ItemEntity(level, point.x, point.y, point.z, ropeDrop));

        // Refund a fraction of tier upgrade materials
        int tier = connection.tier();
        if (tier <= 0)
            return;
        double ratio = Math.max(0.0D, Math.min(1.0D, Config.cutRefundRatio()));
        if (ratio <= 0.0D)
            return;
        Item refundItem = tierRefundItem(connection.kind());
        if (refundItem == null)
            return;
        int refundCount = (int) Math.floor(tier * ratio);
        if (refundCount <= 0)
            return;
        level.addFreshEntity(new ItemEntity(level, point.x, point.y + 0.3D, point.z,
                new ItemStack(refundItem, refundCount)));
    }

    /** The material refunded when cutting an upgraded rope of the given kind. */
    private static net.minecraft.world.item.Item tierRefundItem(LeadKind kind) {
        return switch (kind) {
            case REDSTONE, ENERGY -> net.minecraft.world.item.Items.REDSTONE;
            case ITEM -> net.minecraft.world.item.Items.CHEST;
            case FLUID -> net.minecraft.world.item.Items.BUCKET;
            case PRESSURIZED -> net.minecraft.world.item.Items.IRON_INGOT;
            case THERMAL -> net.minecraft.world.item.Items.COPPER_INGOT;
            case AE_NETWORK -> net.minecraft.world.item.Items.AMETHYST_SHARD;
            default -> null;
        };
    }

    private static LeadEndpointLayout.Endpoints endpoints(Level level, LeadConnection connection) {
        return LeadEndpointLayout.endpoints(level, connection, connections(level));
    }

    private record ConnectionPick(LeadConnection connection, Vec3 point, double distanceSqr, double along) {
    }

}
