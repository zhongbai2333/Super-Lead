package com.zhongbai233.super_lead.lead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.phys.Vec3;

public final class SuperLeadNetwork {
    public static final double MAX_LEASH_DISTANCE = 12.0D;
    private static final Map<NetworkKey, List<LeadConnection>> CONNECTIONS = new HashMap<>();
    private static final Map<PlayerKey, PendingLead> PENDING_LEADS = new HashMap<>();
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
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            ensureFenceKnot(serverLevel, from);
            ensureFenceKnot(serverLevel, to);
            SuperLeadSavedData.get(serverLevel).add(connection);
            notifyRedstoneChange(serverLevel, connection);
            SuperLeadPayloads.sendToDimension(serverLevel);
        } else {
            CONNECTIONS.computeIfAbsent(NetworkKey.of(level), key -> new ArrayList<>()).add(connection);
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

        connections.removeIf(connection -> invalid(level, connection));
    }

    private static boolean invalid(Level level, LeadConnection connection) {
        return level.getBlockState(connection.from().pos()).isAir()
                || level.getBlockState(connection.to().pos()).isAir()
                || connection.from().attachmentPoint(level).distanceTo(connection.to().attachmentPoint(level)) > MAX_LEASH_DISTANCE;
    }

    public static boolean hasUpgradeableConnectionNear(Level level, Vec3 point, double maxDistance) {
        return nearestConnection(level, point, maxDistance, connection -> connection.kind() != LeadKind.REDSTONE).isPresent();
    }

    public static boolean hasConnectionNear(Level level, Vec3 point, double maxDistance, Predicate<LeadConnection> predicate) {
        return nearestConnection(level, point, maxDistance, predicate).isPresent();
    }

    public static boolean upgradeNearestToRedstone(Level level, Vec3 point, Player player) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Optional<LeadConnection> closest = nearestConnection(serverLevel, point, 0.75D, connection -> connection.kind() != LeadKind.REDSTONE);
        if (closest.isEmpty()) {
            return false;
        }

        LeadConnection oldConnection = closest.get();
        boolean changed = SuperLeadSavedData.get(serverLevel).update(
                oldConnection.id(),
                connection -> connection.withKind(LeadKind.REDSTONE),
                true);
        if (!changed) {
            return false;
        }

        LeadConnection upgraded = oldConnection.withKind(LeadKind.REDSTONE);
        notifyRedstoneChange(serverLevel, upgraded);
        SuperLeadPayloads.sendToDimension(serverLevel);
        return true;
    }

    public static void tickRedstone(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        boolean changed = false;
        for (LeadConnection connection : data.connections()) {
            if (connection.kind() != LeadKind.REDSTONE) {
                continue;
            }

            int power = Math.max(externalSignalAt(level, connection.from()), externalSignalAt(level, connection.to()));
            if (power == connection.power()) {
                continue;
            }

            changed |= data.update(connection.id(), oldConnection -> oldConnection.withPower(power), false);
            notifyRedstoneChange(level, connection.withPower(power));
        }

        if (changed) {
            SuperLeadPayloads.sendToDimension(level);
        }
    }

    public static int leadSignal(SignalGetter getter, BlockPos pos, Direction direction) {
        if (SUPPRESS_LEAD_SIGNALS.get() || !(getter instanceof Level level)) {
            return 0;
        }

        int signal = 0;
        for (LeadConnection connection : connections(level)) {
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
        if (player.isCreative() || count <= 0) {
            return;
        }
        level.addFreshEntity(new ItemEntity(level, point.x, point.y, point.z, new ItemStack(Items.LEAD, count)));
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

    private record PendingLead(LeadAnchor anchor, LeadKind kind) {
    }
}
