package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class SuperLeadNetwork {
    public static double maxLeashDistance() { return Config.maxLeashDistance(); }
    public static int itemTierMax() { return Config.itemTierMax(); }
    public static int fluidTierMax() { return Config.fluidTierMax(); }
    private static double serverConfirmedPickReach() { return Config.maxLeashDistance() + 1.5D; }
    private static final double SERVER_CONFIRMED_PICK_RADIUS = 0.95D;
    private static final int SERVER_CONFIRMED_CURVE_SAMPLES = 12;
    private static final double SERVER_CONFIRMED_CURVE_SAG_PER_BLOCK = 0.065D;
    private static final double SERVER_CONFIRMED_CURVE_MAX_SAG = 0.70D;
    private static final Map<NetworkKey, List<LeadConnection>> CONNECTIONS = new HashMap<>();
    private static final Map<NetworkKey, Map<BlockPos, Integer>> REDSTONE_SIGNAL_INDEX = new HashMap<>();
    private static final Map<PlayerKey, PendingLead> PENDING_LEADS = new HashMap<>();
    private static final Map<UUID, Long> INTERIOR_BLOCKED_SINCE = new HashMap<>();
    // Sticky "recently active" deadline per ENERGY connection (gameTime tick at which power expires).
    // Avoids visual flicker when transfer is intermittent (e.g. source pulses, target capped).
    private static final Map<UUID, Long> ENERGY_ACTIVE_UNTIL = new HashMap<>();
    private static final long ENERGY_STICKY_TICKS = 40L;
    // Round-robin cursor per ITEM extract source position. Keyed by BlockPos so that ropes
    // anchored to different faces of the same source block share one queue (a "rope knot").
    private static final Map<BlockPos, Integer> ITEM_RR_CURSOR = new HashMap<>();
    private static final Map<BlockPos, Integer> FLUID_RR_CURSOR = new HashMap<>();
    private static final int ITEM_PULSE_DURATION_TICKS = 10;
    private static final long STUCK_CHECK_INTERVAL_TICKS = 5L;
    private static final double STUCK_SAMPLE_STEP = 0.20D;
    private static final double STUCK_ENDPOINT_IGNORE_DISTANCE = 0.35D;
    private static final double STUCK_INSIDE_EPS = 1.0e-4D;
    private static final ThreadLocal<Boolean> SUPPRESS_LEAD_SIGNALS = ThreadLocal.withInitial(() -> false);

    private SuperLeadNetwork() {}

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
        LeadConnection connection = LeadConnection.create(from, to, kind);
        // Server-authoritative: only the logical server records the connection. The client
        // mirror is populated by SyncConnections / SyncConnectionChanges. Writing on the
        // client too would create a phantom entry with a different UUID (in single-player
        // both sides share this static call path), which the server cannot cut.
        if (level instanceof ServerLevel serverLevel && !level.isClientSide()) {
            ensureFenceKnot(serverLevel, from);
            ensureFenceKnot(serverLevel, to);
            SuperLeadSavedData.get(serverLevel).add(connection);
            notifyRedstoneChange(serverLevel, connection);
            SuperLeadPayloads.sendToDimension(serverLevel);
        }
        return connection;
    }

    public static List<LeadConnection> connections(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).connections();
        }
        return CONNECTIONS.getOrDefault(NetworkKey.of(level), List.of());
    }

    public static void replaceConnections(Level level, List<LeadConnection> connections) {
        CONNECTIONS.put(NetworkKey.of(level), new ArrayList<>(connections));
        invalidateRedstoneIndex(level);
        if (level.isClientSide()) {
            com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry.get()
                    .onConnectionsReplaced(level, connections);
        }
    }

    public static void applyConnectionChanges(Level level, List<UUID> removed, List<LeadConnection> upserts) {
        ArrayList<LeadConnection> connections = new ArrayList<>(connections(level));
        if (!removed.isEmpty()) {
            Set<UUID> removedSet = new HashSet<>(removed);
            connections.removeIf(connection -> removedSet.contains(connection.id()));
        }
        for (LeadConnection upsert : upserts) {
            boolean replaced = false;
            for (int i = 0; i < connections.size(); i++) {
                if (connections.get(i).id().equals(upsert.id())) {
                    connections.set(i, upsert);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                connections.add(upsert);
            }
        }
        CONNECTIONS.put(NetworkKey.of(level), connections);
        invalidateRedstoneIndex(level);
        if (level.isClientSide()) {
            com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry.get()
                    .onConnectionsReplaced(level, connections);
        }
    }

    public static void invalidateRedstoneIndex(Level level) {
        if (level != null) {
            REDSTONE_SIGNAL_INDEX.remove(NetworkKey.of(level));
        }
    }

    public static void pruneInvalid(Level level) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            boolean removed = SuperLeadSavedData.get(serverLevel).removeIf(connection -> invalid(level, connection));
            ensureFenceKnots(serverLevel);
            if (removed) {
                SuperLeadPayloads.sendToDimension(serverLevel);
            }
            return;
        }

        List<LeadConnection> connections = CONNECTIONS.get(NetworkKey.of(level));
        if (connections == null) {
            return;
        }

        boolean removed = connections.removeIf(connection -> invalid(level, connection));
        if (removed) {
            invalidateRedstoneIndex(level);
        }
    }

    private static boolean invalid(Level level, LeadConnection connection) {
        return level.getBlockState(connection.from().pos()).isAir()
                || level.getBlockState(connection.to().pos()).isAir()
                || connection.from().attachmentPoint(level).distanceTo(connection.to().attachmentPoint(level)) > maxLeashDistance();
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
            if (now - since >= Config.stuckBreakTicks()) {
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
            Vec3 midpoint = connection.from().attachmentPoint(level)
                    .add(connection.to().attachmentPoint(level)).scale(0.5D);
            dropLeads(level, midpoint, null, 1);
            cleanupFenceKnot(level, connection.from());
            cleanupFenceKnot(level, connection.to());
            notifyRedstoneChange(level, connection);
        }
        SuperLeadPayloads.sendToDimension(level);
    }

    private static boolean hasInteriorBlockage(Level level, LeadConnection connection) {
        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
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
        return nearestConnection(level, point, maxDistance, connection -> connection.kind() != LeadKind.REDSTONE).isPresent();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance, Predicate<LeadConnection> predicate) {
        return nearestConnection(level, point, maxDistance, predicate).isPresent();
    }

    public static boolean hasConnectionInView(Level level, Player player, double radius, Predicate<LeadConnection> predicate) {
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

    /**
     * Toggle the extract-source anchor for any ITEM connection attached at the given block position.
     * Returns true if at least one connection was updated.
     */
    public static boolean toggleItemExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.ITEM);
    }

    public static boolean toggleFluidExtractAt(Level level, BlockPos pos) {
        return toggleExtractAt(level, pos, LeadKind.FLUID);
    }

    private static boolean toggleExtractAt(Level level, BlockPos pos, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SuperLeadSavedData data = SuperLeadSavedData.get(serverLevel);
        boolean any = false;
        for (LeadConnection connection : new ArrayList<>(data.connections())) {
            if (connection.kind() != kind) continue;
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

    private static boolean hasKindConnectionAt(Level level, BlockPos pos, LeadKind kind) {
        for (LeadConnection connection : connections(level)) {
            if (connection.kind() == kind
                    && (connection.from().pos().equals(pos) || connection.to().pos().equals(pos))) {
                return true;
            }
        }
        return false;
    }

    public static boolean upgradeNearestItemTierInView(Level level, Player player, double radius) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.ITEM, itemTierMax(), Items.CHEST);
    }

    public static boolean upgradeNearestFluidTierInView(Level level, Player player, double radius) {
        return upgradeNearestKindTierInView(level, player, radius, LeadKind.FLUID, fluidTierMax(), Items.BUCKET);
    }

    /** Find a known connection by id in the given level (server or client). */
    public static Optional<LeadConnection> findConnectionById(Level level, UUID id) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            return SuperLeadSavedData.get(serverLevel).find(id);
        }
        for (LeadConnection connection : connections(level)) {
            if (connection.id().equals(id)) {
                return Optional.of(connection);
            }
        }
        return Optional.empty();
    }

    /**
     * Server-side validation for a client-confirmed rope target.
     *
     * The client picks against the rendered rope simulation, which can sag or be pushed by blocks.
     * The server only has the two anchors, so this intentionally checks a wider envelope around the
     * straight anchor segment plus a conservative sagged curve. This keeps the packet tied to the
     * exact client-highlighted connection without going back to server-side "nearest rope" picking.
     */
    public static boolean canUseClientPickedConnection(ServerLevel level, Player player, LeadConnection connection) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
        double radiusSqr = SERVER_CONFIRMED_PICK_RADIUS * SERVER_CONFIRMED_PICK_RADIUS;

        // Clamp pick reach to whatever block the player is actually aiming at, so a rope that lies
        // along the view direction past a closer block does not get treated as targeted.
        double reach = serverConfirmedPickReach();
        Vec3 reachEnd = origin.add(direction.scale(reach));
        net.minecraft.world.level.ClipContext clip = new net.minecraft.world.level.ClipContext(
                origin, reachEnd,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player);
        net.minecraft.world.phys.BlockHitResult blockHit = level.clip(clip);
        if (blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            reach = Math.min(reach,
                    blockHit.getLocation().distanceTo(origin) + SERVER_CONFIRMED_PICK_RADIUS);
        }

        ConnectionPick straightPick = pickSegment(connection, a, b, origin, direction, reach);
        if (straightPick != null && straightPick.distanceSqr() <= radiusSqr) {
            return true;
        }

        return pickApproximateSaggedCurve(connection, a, b, origin, direction, reach)
                .map(ConnectionPick::distanceSqr)
                .orElse(Double.POSITIVE_INFINITY) <= radiusSqr;
    }

    /** Looser variant of {@link #canUseClientPickedConnection} for attachment placement /
     *  removal / form-toggle. The client physics rope can sag noticeably below the chord
     *  while the server's straight-line + half-sine approximation cannot (server's max sag
     *  ~0.7 blocks, client up to several blocks for long, mostly-horizontal ropes). The
     *  precise hit position {@code t} is computed by the client picker on the actual
     *  simulated polyline, so the server only needs a coarse "ray points at the rope's
     *  bounding envelope within reach" anti-cheese gate. We therefore intersect the player's
     *  view ray with an AABB that contains the chord plus the worst-case sagged shape. */
    public static boolean canTouchConnectionForAttachment(ServerLevel level, Player player, LeadConnection connection) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
        double chord = a.distanceTo(b);
        // Worst-case sag depth of a free-hanging chain of length L between equal-height
        // anchors is L/2; clamp to half the chord plus a generous floor so short ropes also
        // pass even when the slack-driven catenary depth is smaller than the radius.
        double sagDown = Math.max(SERVER_CONFIRMED_CURVE_MAX_SAG + SERVER_CONFIRMED_PICK_RADIUS,
                chord * 0.5D + SERVER_CONFIRMED_PICK_RADIUS);
        net.minecraft.world.phys.AABB envelope = new net.minecraft.world.phys.AABB(
                Math.min(a.x, b.x) - SERVER_CONFIRMED_PICK_RADIUS,
                Math.min(a.y, b.y) - sagDown,
                Math.min(a.z, b.z) - SERVER_CONFIRMED_PICK_RADIUS,
                Math.max(a.x, b.x) + SERVER_CONFIRMED_PICK_RADIUS,
                Math.max(a.y, b.y) + SERVER_CONFIRMED_PICK_RADIUS,
                Math.max(a.z, b.z) + SERVER_CONFIRMED_PICK_RADIUS);
        Vec3 reachEnd = origin.add(direction.scale(serverConfirmedPickReach()));
        return envelope.clip(origin, reachEnd).isPresent();
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
        if (connection.kind() == newKind) return false;
        return updateConnectionKind(level, connection, newKind);
    }

    /** Per-connection: increase tier by 1, charging (1 << tier) of costItem (less the 1 the caller will shrink). */
    public static boolean upgradeConnectionTier(ServerLevel level, Player player, LeadConnection connection,
            int maxTier, net.minecraft.world.item.Item costItem) {
        if (connection.tier() >= maxTier) return false;
        int totalCost = 1 << Math.min(maxTier, connection.tier());
        int extraCost = totalCost - 1;
        if (!player.isCreative() && extraCost > 0 && !consumeFromInventory(player, costItem, extraCost)) {
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
        if (!removed) return false;
        Vec3 midpoint = connection.from().attachmentPoint(level)
                .add(connection.to().attachmentPoint(level)).scale(0.5D);
        dropLeads(level, midpoint, player, 1);
        // Drop any attached items along with the rope.
        for (RopeAttachment attachment : connection.attachments()) {
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    level, midpoint.x, midpoint.y, midpoint.z, attachment.stack().copy());
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        cleanupFenceKnot(level, connection.from());
        cleanupFenceKnot(level, connection.to());
        SuperLeadPayloads.sendToDimension(level);
        return true;
    }

    /** Adds a new attachment to {@code connection}. The caller is responsible for shrinking the
     *  player's held stack on success. */
    public static boolean addAttachment(ServerLevel level, LeadConnection connection, double t, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        RopeAttachment attachment = RopeAttachment.create(t, stack);
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.addAttachment(attachment), true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    /** Removes attachment {@code attachmentId} from {@code connection}. The removed item is
     *  given to {@code player} (or dropped at their feet if their inventory is full). */
    public static boolean removeAttachment(ServerLevel level, LeadConnection connection, java.util.UUID attachmentId, Player player) {
        RopeAttachment removed = null;
        for (RopeAttachment a : connection.attachments()) {
            if (a.id().equals(attachmentId)) { removed = a; break; }
        }
        if (removed == null) return false;
        final RopeAttachment removedFinal = removed;
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.removeAttachment(attachmentId), true);
        if (!ok) return false;
        net.minecraft.world.item.ItemStack drop = removedFinal.stack().copy();
        if (player != null && !player.getInventory().add(drop)) {
            player.drop(drop, false);
        } else if (player == null) {
            Vec3 mid = connection.from().attachmentPoint(level)
                    .add(connection.to().attachmentPoint(level)).scale(0.5D);
            net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(level, mid.x, mid.y, mid.z, drop);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        SuperLeadPayloads.sendToDimension(level);
        return true;
    }

    /** Toggle a single attachment between block-shaped and item-shaped rendering. Only
     *  meaningful for BlockItem stacks; non-block items are silently ignored. */
    public static boolean toggleAttachmentForm(ServerLevel level, LeadConnection connection, java.util.UUID attachmentId) {
        boolean ok = SuperLeadSavedData.get(level).update(connection.id(),
                c -> c.toggleAttachmentForm(attachmentId), true);
        if (ok) {
            SuperLeadPayloads.sendToDimension(level);
        }
        return ok;
    }

    private static boolean upgradeNearestKindTierInView(Level level, Player player, double radius,
            LeadKind kind, int maxTier, net.minecraft.world.item.Item costItem) {
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
        if (!player.isCreative() && extraCost > 0 && !consumeFromInventory(player, costItem, extraCost)) {
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
        // Held block is shrunk by consumeSuccessfulUse (1). Pull the remainder from inventory.
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
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
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
            if (!stack.is(item)) {
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

        Optional<LeadConnection> closest = nearestConnection(serverLevel, point, maxDistance, connection -> connection.kind() != kind);
        return closest.filter(connection -> updateConnectionKind(serverLevel, connection, kind)).isPresent();
    }

    private static boolean upgradeNearestToKindInView(Level level, Player player, double radius, LeadKind kind) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<ConnectionPick> pick = nearestConnectionInView(serverLevel, player, radius, connection -> connection.kind() != kind);
        return pick.filter(connectionPick -> updateConnectionKind(serverLevel, connectionPick.connection(), kind)).isPresent();
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
        List<LeadConnection> redstoneConnections = new ArrayList<>();
        for (LeadConnection connection : data.connections()) {
            if (connection.kind() == LeadKind.REDSTONE) {
                redstoneConnections.add(connection);
            }
        }
        if (redstoneConnections.isEmpty()) {
            return;
        }

        boolean changed = false;
        boolean[] visited = new boolean[redstoneConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(redstoneConnections);
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
                power = Math.max(power, externalSignalAt(level, current.from()));
                power = Math.max(power, externalSignalAt(level, current.to()));

                addUnvisitedNeighbors(current, connectionsByAnchor, visited, component);
            }

            final int componentPower = power;
            for (int index : component) {
                LeadConnection connection = redstoneConnections.get(index);
                if (connection.power() == componentPower) {
                    continue;
                }

                changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(componentPower), false);
                notifyRedstoneChange(level, connection.withPower(componentPower));
            }
        }

        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    public static void tickEnergy(ServerLevel level) {
        List<LeadConnection> energyConnections = new ArrayList<>();
        for (LeadConnection connection : SuperLeadSavedData.get(level).connections()) {
            if (connection.kind() == LeadKind.ENERGY) {
                energyConnections.add(connection);
            }
        }
        if (energyConnections.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        Set<UUID> transferredIds = new HashSet<>();
        boolean[] visited = new boolean[energyConnections.size()];
        Map<LeadAnchor, List<Integer>> connectionsByAnchor = indexConnectionsByAnchor(energyConnections);
        for (int i = 0; i < energyConnections.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            component.add(i);
            visited[i] = true;

            for (int cursor = 0; cursor < component.size(); cursor++) {
                LeadConnection current = energyConnections.get(component.get(cursor));
                addUnvisitedNeighbors(current, connectionsByAnchor, visited, component);
            }

            transferEnergyComponent(level, component, energyConnections, transferredIds);
        }

        // Refresh sticky deadlines for connections that actually moved energy this tick.
        for (UUID id : transferredIds) {
            ENERGY_ACTIVE_UNTIL.put(id, now + ENERGY_STICKY_TICKS);
        }

        SuperLeadSavedData data = SuperLeadSavedData.get(level);
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

    private static void transferEnergyComponent(ServerLevel level, List<Integer> component, List<LeadConnection> energyConnections, Set<UUID> transferredIds) {
        List<EnergyEndpoint> endpoints = new ArrayList<>();
        Set<LeadAnchor> seenAnchors = new HashSet<>();
        int componentRate = 0;
        long base = Config.energyBaseTransfer();
        for (int index : component) {
            LeadConnection connection = energyConnections.get(index);
            int tier = Math.min(30, connection.tier());
            long rate = base << tier;
            componentRate = (int) Math.min(Integer.MAX_VALUE, (long) componentRate + rate);
            addEnergyEndpoint(level, connection.from(), endpoints, seenAnchors);
            addEnergyEndpoint(level, connection.to(), endpoints, seenAnchors);
        }

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
                    for (int index : component) {
                        transferredIds.add(energyConnections.get(index).id());
                    }
                }
            }
        }
    }

    private static void addEnergyEndpoint(ServerLevel level, LeadAnchor anchor, List<EnergyEndpoint> endpoints, Set<LeadAnchor> seenAnchors) {
        if (!seenAnchors.add(anchor)) {
            return;
        }

        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(Capabilities.Energy.BLOCK, anchor.pos(), null);
        }
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
                rope -> Math.min(64, 1 << Math.min(itemTierMax(), rope.tier())));
    }

    public static void tickFluid(ServerLevel level) {
        if (level.getGameTime() % Config.itemTransferIntervalTicks() != 0L) {
            return;
        }
        tickTransfer(level, LeadKind.FLUID, Capabilities.Fluid.BLOCK, FLUID_RR_CURSOR,
                rope -> Config.fluidBucketAmount() * (1 << Math.min(fluidTierMax(), rope.tier())));
    }

    private static <R extends Resource> void tickTransfer(
            ServerLevel level,
            LeadKind kind,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            Map<BlockPos, Integer> rrCursor,
            java.util.function.ToIntFunction<LeadConnection> batchOf) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);

        // Index every rope of this kind by both endpoint positions so we can walk through
        // fence-knot junctions where multiple ropes share a BlockPos.
        Map<BlockPos, List<LeadConnection>> ropesAt = new HashMap<>();
        Map<BlockPos, List<LeadConnection>> startsBySource = new HashMap<>();
        for (LeadConnection c : data.connections()) {
            if (c.kind() != kind) {
                continue;
            }

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
            if (src == null) continue;
            startsBySource.computeIfAbsent(src.pos().immutable(), k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<BlockPos, List<LeadConnection>> entry : startsBySource.entrySet()) {
            BlockPos sourcePos = entry.getKey();
            List<LeadConnection> ropes = entry.getValue();
            if (ropes.isEmpty()) continue;

            int n = ropes.size();
            int start = rrCursor.getOrDefault(sourcePos, 0) % n;

            for (int step = 0; step < n; step++) {
                int idx = (start + step) % n;
                LeadConnection rope = ropes.get(idx);
                LeadAnchor sourceAnchor = rope.extractSource();
                LeadAnchor firstFar = rope.extractTarget();
                if (sourceAnchor == null || firstFar == null) continue;

                ResourceHandler<R> sourceHandler = handler(level, sourceAnchor, cap);
                if (sourceHandler == null) continue;

                int batch = Math.max(1, batchOf.applyAsInt(rope));

                List<PathStep> path = new ArrayList<>();
                List<RrChoice> rrChoices = new ArrayList<>();
                Set<UUID> visited = new HashSet<>();
                visited.add(rope.id());
                path.add(new PathStep(rope, rope.extractAnchor() == 2));

                if (walkAndTransfer(level, cap, sourceHandler, batch, firstFar, ropesAt, rrCursor, visited, path, rrChoices)) {
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

    /**
     * DFS through the rope graph starting at {@code current}. If {@code current} hosts a handler,
     * attempt a single transfer from {@code sourceHandler}. Otherwise treat {@code current.pos()}
     * as a knot and round-robin through its unvisited ropes. Returns true only when a transfer
     * was actually committed.
     */
    private static <R extends Resource> boolean walkAndTransfer(
            ServerLevel level,
            BlockCapability<ResourceHandler<R>, Direction> cap,
            ResourceHandler<R> sourceHandler,
            int batch,
            LeadAnchor current,
            Map<BlockPos, List<LeadConnection>> ropesAt,
            Map<BlockPos, Integer> rrCursor,
            Set<UUID> visited,
            List<PathStep> path,
            List<RrChoice> rrChoices) {
        ResourceHandler<R> h = handler(level, current, cap);
        if (h != null) {
            return transferOne(sourceHandler, h, batch);
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

            if (walkAndTransfer(level, cap, sourceHandler, batch, far, ropesAt, rrCursor, visited, path, rrChoices)) {
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

    /**
     * Try to extract up to {@code batch} units from any source slot whose contents the target
     * accepts entirely. Returns true if anything was moved.
     */
    private static <R extends Resource> boolean transferOne(ResourceHandler<R> source, ResourceHandler<R> target, int batch) {
        int slots = source.size();
        for (int slot = 0; slot < slots; slot++) {
            R res = source.getResource(slot);
            if (res == null || res.isEmpty()) continue;
            long avail = source.getAmountAsLong(slot);
            if (avail <= 0L) continue;

            int requested = (int) Math.min(batch, avail);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = source.extract(slot, res, requested, tx);
                if (extracted <= 0) continue;
                int inserted = target.insert(res, extracted, tx);
                if (inserted != extracted) continue;
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

    private static void addAnchorIndex(Map<LeadAnchor, List<Integer>> byAnchor, LeadAnchor anchor, int index) {
        byAnchor.computeIfAbsent(anchor, key -> new ArrayList<>()).add(index);
    }

    private static void addUnvisitedNeighbors(LeadConnection connection, Map<LeadAnchor, List<Integer>> byAnchor,
            boolean[] visited, List<Integer> component) {
        addUnvisitedNeighbors(connection.from(), byAnchor, visited, component);
        if (!connection.to().equals(connection.from())) {
            addUnvisitedNeighbors(connection.to(), byAnchor, visited, component);
        }
    }

    private static void addUnvisitedNeighbors(LeadAnchor anchor, Map<LeadAnchor, List<Integer>> byAnchor,
            boolean[] visited, List<Integer> component) {
        List<Integer> neighbors = byAnchor.get(anchor);
        if (neighbors == null) {
            return;
        }

        for (int index : neighbors) {
            if (visited[index]) {
                continue;
            }
            visited[index] = true;
            component.add(index);
        }
    }

    public static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }
        return redstoneSignalIndex(level).getOrDefault(pos, 0);
    }

    private static Map<BlockPos, Integer> redstoneSignalIndex(Level level) {
        return REDSTONE_SIGNAL_INDEX.computeIfAbsent(NetworkKey.of(level),
                ignored -> buildRedstoneSignalIndex(level));
    }

    private static Map<BlockPos, Integer> buildRedstoneSignalIndex(Level level) {
        Map<BlockPos, Integer> signals = new HashMap<>();
        for (LeadConnection connection : connections(level)) {
            if (connection.kind() != LeadKind.REDSTONE || connection.power() <= 0) {
                continue;
            }
            addRedstoneSignal(signals, connection.from(), connection.power());
            addRedstoneSignal(signals, connection.to(), connection.power());
        }
        return signals.isEmpty() ? Map.of() : Map.copyOf(signals);
    }

    private static void addRedstoneSignal(Map<BlockPos, Integer> signals, LeadAnchor anchor, int power) {
        addRedstoneSignal(signals, anchor.pos().immutable(), power);
        addRedstoneSignal(signals, anchor.pos().relative(anchor.face()).immutable(), power);
    }

    private static void addRedstoneSignal(Map<BlockPos, Integer> signals, BlockPos pos, int power) {
        signals.merge(pos, Math.min(15, power), Math::max);
    }

    private static Optional<LeadConnection> nearestConnection(Level level, Vec3 point, double maxDistance, Predicate<LeadConnection> predicate) {
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

    private static Optional<ConnectionPick> nearestConnectionInView(Level level, Player player, double radius, Predicate<LeadConnection> predicate) {
        Vec3 origin = player.getEyePosition(1.0F);
        Vec3 direction = player.getViewVector(1.0F).normalize();
        double maxDistance = maxLeashDistance();
        double radiusSqr = radius * radius;
        ConnectionPick best = null;

        for (LeadConnection connection : connections(level)) {
            if (!predicate.test(connection)) {
                continue;
            }

            Vec3 a = connection.from().attachmentPoint(level);
            Vec3 b = connection.to().attachmentPoint(level);
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

    private static Optional<ConnectionPick> pickApproximateSaggedCurve(LeadConnection connection, Vec3 a, Vec3 b,
            Vec3 origin, Vec3 direction, double maxDistance) {
        double sag = Math.min(SERVER_CONFIRMED_CURVE_MAX_SAG,
                a.distanceTo(b) * SERVER_CONFIRMED_CURVE_SAG_PER_BLOCK);
        if (sag <= 1.0e-6D) {
            return Optional.empty();
        }

        ConnectionPick best = null;
        Vec3 previous = a;
        for (int i = 1; i <= SERVER_CONFIRMED_CURVE_SAMPLES; i++) {
            double t = i / (double) SERVER_CONFIRMED_CURVE_SAMPLES;
            Vec3 current = a.lerp(b, t).add(0.0D, -Math.sin(Math.PI * t) * sag, 0.0D);
            ConnectionPick pick = pickSegment(connection, previous, current, origin, direction, maxDistance);
            if (pick != null && (best == null
                    || pick.distanceSqr() < best.distanceSqr()
                    || (Math.abs(pick.distanceSqr() - best.distanceSqr()) < 1.0e-6D && pick.along() < best.along()))) {
                best = pick;
            }
            previous = current;
        }
        return Optional.ofNullable(best);
    }

    private static ConnectionPick pickSegment(LeadConnection connection, Vec3 a, Vec3 b, Vec3 origin, Vec3 direction, double maxDistance) {
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

        s = direction.scale(along).subtract(w).dot(segment) / segLenSqr;
        s = Math.max(0.0D, Math.min(1.0D, s));
        Vec3 ropePoint = a.add(segment.scale(s));
        Vec3 rayPoint = origin.add(direction.scale(along));
        return new ConnectionPick(connection, ropePoint, ropePoint.distanceToSqr(rayPoint), along);
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
        invalidateRedstoneIndex(level);
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
        boolean removed = SuperLeadSavedData.get(serverLevel).removeIf(connection -> connection.id().equals(removedConnection.id()));
        if (!removed) {
            return false;
        }

        dropLeads(serverLevel, point, player, 1);
        cleanupFenceKnot(serverLevel, removedConnection.from());
        cleanupFenceKnot(serverLevel, removedConnection.to());
        SuperLeadPayloads.sendToDimension(serverLevel);
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
        boolean removed = SuperLeadSavedData.get(serverLevel).removeIf(connection -> connection.id().equals(removedConnection.id()));
        if (!removed) {
            return false;
        }

        dropLeads(serverLevel, pick.get().point(), player, 1);
        cleanupFenceKnot(serverLevel, removedConnection.from());
        cleanupFenceKnot(serverLevel, removedConnection.to());
        SuperLeadPayloads.sendToDimension(serverLevel);
        return true;
    }

    public static int cutAttachedTo(Level level, LeadAnchor anchor, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        List<LeadConnection> connections = SuperLeadSavedData.get(serverLevel).connections();
        int count = 0;
        for (LeadConnection connection : connections) {
            if (connection.from().equals(anchor) || connection.to().equals(anchor)) {
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }

        SuperLeadSavedData.get(serverLevel).removeIf(connection -> connection.from().equals(anchor) || connection.to().equals(anchor));
        dropLeads(serverLevel, anchor.attachmentPoint(level), player, count);
        cleanupFenceKnot(serverLevel, anchor);
        SuperLeadPayloads.sendToDimension(serverLevel);
        return count;
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

    private static double distanceToConnectionSqr(Level level, LeadConnection connection, Vec3 point) {
        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
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

    private static void dropLeads(ServerLevel level, Vec3 point, Player player, int count) {
        if ((player != null && player.isCreative()) || count <= 0) {
            return;
        }
        level.addFreshEntity(new ItemEntity(level, point.x, point.y, point.z, new ItemStack(SuperLeadItems.SUPER_LEAD.asItem(), count)));
    }

}
