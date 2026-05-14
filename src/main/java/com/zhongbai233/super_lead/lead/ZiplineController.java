package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-authoritative zipline riding for sync-zone normal/redstone ropes. */
public final class ZiplineController {
    // Pendulum-like 1D physics along the curve: signed velocity along arc-length,
    // gravity projected onto local tangent, plus drag. Lets riders coast through dips
    // and convert kinetic energy into climbing gentle uphills naturally.
    private static final double START_SPEED = 0.18D;
    private static final double MAX_NORMAL_SPEED = 0.82D;
    private static final double MAX_POWERED_SPEED = 1.35D;
    private static final double GRAVITY_ALONG_CURVE = 0.060D;
    private static final double POWERED_ACCEL_BASE = 0.042D;
    private static final double POWERED_ACCEL_PER_SIGNAL = 0.0045D;
    private static final double POWERED_INERTIA_PRIORITY_SPEED = 0.10D;
    private static final double DRAG = 0.992D;
    private static final double REST_SPEED_EPSILON = 0.012D;
    private static final double REST_SLOPE_EPSILON = 0.020D;
    private static final double END_MARGIN = 0.025D;
    private static final int SNEAK_DISMOUNT_GRACE_TICKS = 8;
    private static final double HANG_HEIGHT = 1.97D;
    private static final double MIN_SEGMENT_LENGTH = 0.35D;
    private static final double POSITION_CORRECTION_GAIN = 0.38D;
    private static final double MAX_POSITION_CORRECTION_PER_TICK = 0.32D;
    private static final double HIGH_SPEED_CORRECTION_SCALE = 0.55D;
    private static final double HIGH_SPEED_CORRECTION_CAP = 0.78D;
    private static final double HARD_SNAP_DISTANCE_SQR = 2.25D;
    private static final double KNOT_CLEARANCE_BLOCKS = 1.10D;
    private static final double IRON_BARS_KNOT_CLEARANCE_BLOCKS = 0.10D;
    private static final double MAX_KNOT_CLEARANCE_T = 0.42D;
    private static final double KNOT_SPEED_RETAIN = 0.98D;
    private static final double KNOT_FACING_TIE_EPSILON = 0.06D;

    private static final Map<NetworkKey, Map<UUID, RiderState>> STATES = new HashMap<>();
    private static final Map<NetworkKey, Integer> LAST_SENT_COUNT = new HashMap<>();

    private ZiplineController() {
    }

    public static boolean canRideConnection(ServerLevel level, LeadConnection connection) {
        if (level == null || connection == null) {
            return false;
        }
        if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE) {
            return false;
        }
        if (connection.physicsPreset().isBlank()) {
            return false;
        }
        if (!Config.allowOpVisualPresets()) {
            return false;
        }
        return ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset()).physicsEnabled();
    }

    public static boolean start(ServerLevel level, ServerPlayer player, LeadConnection connection,
            Vec3 hitPoint, double hitT) {
        if (level == null || player == null || connection == null || hitPoint == null) {
            return false;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (!hasChain(player)) {
            return false;
        }
        if (!canRideConnection(level, connection)) {
            return false;
        }
        if (!SuperLeadNetwork.canUseClientPickedConnection(level, player, connection, hitPoint, hitT)) {
            return false;
        }

        Curve curve = curve(level, connection, clamp(hitT, END_MARGIN, 1.0D - END_MARGIN));
        int direction = initialDirection(player, connection, curve);
        // Project the player's current motion onto the chosen direction so a running jump
        // keeps its momentum instead of being clamped to START_SPEED.
        double alongSpeed = player.getDeltaMovement().dot(curve.tangent().scale(direction));
        ServerPhysicsTuning tuning = ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset());
        double maxV = maxSpeed(connection, tuning);
        double initialVelocity = clamp(direction * Math.max(START_SPEED, alongSpeed), -maxV, maxV);

        NetworkKey key = NetworkKey.of(level);
        Map<UUID, RiderState> states = STATES.computeIfAbsent(key, ignored -> new HashMap<>());
        RiderState previous = states.get(player.getUUID());
        boolean oldNoPhysics = previous == null ? player.noPhysics : previous.oldNoPhysics;
        states.put(player.getUUID(), new RiderState(player.getUUID(), connection.id(), curve.t(),
                initialVelocity, oldNoPhysics, player.isShiftKeyDown()));
        player.noPhysics = true;
        player.setOnGround(false);
        player.resetFallDistance();
        player.sendOverlayMessage(Component.translatable("message.super_lead.zipline_dismount"));
        return true;
    }

    public static void tick(ServerLevel level) {
        NetworkKey key = NetworkKey.of(level);
        Map<UUID, RiderState> states = STATES.get(key);
        if (states == null || states.isEmpty()) {
            broadcast(level, List.of());
            return;
        }

        List<UUID> remove = new ArrayList<>();
        for (RiderState state : states.values()) {
            ServerPlayer player = findPlayer(level, state.playerId);
            if (player == null || !tickOne(level, player, state)) {
                remove.add(state.playerId);
            }
        }

        for (UUID id : remove) {
            RiderState removed = states.remove(id);
            ServerPlayer player = findPlayer(level, id);
            if (removed != null && player != null) {
                finish(player, removed, Vec3.ZERO);
            }
        }
        if (states.isEmpty()) {
            STATES.remove(key);
        }
        broadcast(level, snapshot(level, states));
    }

    public static void stopEverywhere(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (Map<UUID, RiderState> states : STATES.values()) {
            RiderState removed = states.remove(player.getUUID());
            if (removed != null) {
                finish(player, removed, Vec3.ZERO);
            }
        }
    }

    public static boolean isZiplining(Player player) {
        if (player == null) {
            return false;
        }
        Map<UUID, RiderState> states = STATES.get(NetworkKey.of(player.level()));
        return states != null && states.containsKey(player.getUUID());
    }

    public static boolean isChain(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(Items.IRON_CHAIN)
                || stack.is(Items.COPPER_CHAIN.unaffected())
                || stack.is(Items.COPPER_CHAIN.exposed())
                || stack.is(Items.COPPER_CHAIN.weathered())
                || stack.is(Items.COPPER_CHAIN.oxidized())
                || stack.is(Items.COPPER_CHAIN.waxed())
                || stack.is(Items.COPPER_CHAIN.waxedExposed())
                || stack.is(Items.COPPER_CHAIN.waxedWeathered())
                || stack.is(Items.COPPER_CHAIN.waxedOxidized());
    }

    private static boolean tickOne(ServerLevel level, ServerPlayer player, RiderState state) {
        if (!player.isAlive() || player.isSpectator() || !hasChain(player)) {
            finish(player, state, currentVelocity(level, state));
            return false;
        }
        state.age++;
        if (state.startedSneaking && !player.isShiftKeyDown()) {
            state.releasedStartSneak = true;
        }
        if (state.age > SNEAK_DISMOUNT_GRACE_TICKS && player.isShiftKeyDown()
                && (!state.startedSneaking || state.releasedStartSneak)) {
            finish(player, state, currentVelocity(level, state));
            return false;
        }

        Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, state.connectionId);
        if (opt.isEmpty() || !canRideConnection(level, opt.get())) {
            finish(player, state, currentVelocity(level, state));
            return false;
        }
        LeadConnection connection = opt.get();
        Curve curve = curve(level, connection, state.t);
        if (curve.length() < MIN_SEGMENT_LENGTH) {
            finish(player, state, Vec3.ZERO);
            return false;
        }

        boolean powered = isPoweredRedstone(connection);
        Vec3 tangent = curve.tangent();
        ServerPhysicsTuning tuning = ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset());
        double maxV = maxSpeed(connection, tuning);

        // Acceleration along the curve. Gravity acts in world -Y; its component along the
        // unit tangent (which points from->to) is -G * tangent.y. This naturally produces
        // the right sign: on a downhill in the +t direction tangent.y < 0 so a > 0 (speeds
        // up forward motion); past the lowest point of a sag tangent.y > 0 so a < 0 (slows
        // forward motion or speeds up reverse motion). No more direction flipping.
        double aGrav = -GRAVITY_ALONG_CURVE * tangent.y;
        double aPower = 0.0D;
        if (powered) {
            int accelerationDirection = poweredAccelerationDirection(player, curve, state.velocity);
            aPower = accelerationDirection * (POWERED_ACCEL_BASE + connection.power() * POWERED_ACCEL_PER_SIGNAL)
                    * tuning.ziplineRedstoneAccelerationMultiplier();
        }

        double v = state.velocity * DRAG + aGrav + aPower;
        v = clamp(v, -maxV, maxV);

        // Snap to rest at the bottom of a sag so the player actually settles instead of
        // jittering due to drag/gravity numerical noise. Only when not powered and the
        // slope is shallow enough that gravity can't drag the player off again.
        if (!powered && Math.abs(v) < REST_SPEED_EPSILON
                && Math.abs(tangent.y) < REST_SLOPE_EPSILON) {
            v = 0.0D;
        }
        state.velocity = v;

        double nextT = state.t + v / curve.length();
        if (nextT <= 0.0D || nextT >= 1.0D) {
            boolean exitedAtTo = v > 0.0D;
            double overshootDistance = nextT >= 1.0D
                    ? (nextT - 1.0D) * curve.length()
                    : -nextT * curve.length();
            Vec3 exitTravel = tangent.scale(exitedAtTo ? 1 : -1);
            if (continueThroughKnot(level, player, state, connection, exitedAtTo, exitTravel, overshootDistance)) {
                Optional<LeadConnection> nextConnection = SuperLeadNetwork.findConnectionById(level,
                        state.connectionId);
                if (nextConnection.isPresent()) {
                    Curve nextCurve = curve(level, nextConnection.get(), state.t);
                    Vec3 nextMotion = nextCurve.tangent().scale(state.velocity);
                    moveRider(player, state, nextCurve, nextMotion);
                }
                return true;
            }
            finish(player, state, launchVelocity(player, tangent.scale(v)));
            return false;
        }

        state.t = clamp(nextT, END_MARGIN, 1.0D - END_MARGIN);
        curve = curve(level, connection, state.t);
        Vec3 motion = curve.tangent().scale(state.velocity);
        moveRider(player, state, curve, motion);
        return true;
    }

    private static void moveRider(ServerPlayer player, RiderState state, Curve curve, Vec3 motion) {
        Vec3 feet = curve.point().add(0.0D, -HANG_HEIGHT, 0.0D);
        Vec3 desiredMotion = smoothRideMotion(player.position(), feet, motion, correctionLimit(state));
        player.noPhysics = true;
        player.setOnGround(false);
        player.resetFallDistance();
        if (state.forceSnap || player.position().distanceToSqr(feet) > HARD_SNAP_DISTANCE_SQR || state.age <= 1) {
            player.teleportTo(feet.x, feet.y, feet.z);
            desiredMotion = motion;
            state.forceSnap = false;
        } else {
            player.move(MoverType.SELF, desiredMotion);
        }
        player.setDeltaMovement(desiredMotion);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    private static double correctionLimit(RiderState state) {
        return Math.min(HIGH_SPEED_CORRECTION_CAP,
                Math.max(MAX_POSITION_CORRECTION_PER_TICK, Math.abs(state.velocity) * HIGH_SPEED_CORRECTION_SCALE));
    }

    private static Vec3 smoothRideMotion(Vec3 currentFeet, Vec3 targetFeet, Vec3 inertialVelocity,
            double maxCorrectionPerTick) {
        Vec3 error = targetFeet.subtract(currentFeet);
        Vec3 correction = error.scale(POSITION_CORRECTION_GAIN);
        double correctionLenSqr = correction.lengthSqr();
        double maxSqr = maxCorrectionPerTick * maxCorrectionPerTick;
        if (correctionLenSqr > maxSqr) {
            correction = correction.normalize().scale(maxCorrectionPerTick);
        }
        return inertialVelocity.add(correction);
    }

    private static boolean continueThroughKnot(ServerLevel level, ServerPlayer player, RiderState state,
            LeadConnection current, boolean exitedAtTo, Vec3 incomingTravel, double overshootDistance) {
        LeadAnchor anchor = exitedAtTo ? current.to() : current.from();
        LeadConnection best = null;
        int bestDirection = 0;
        double bestFacingScore = -Double.MAX_VALUE;
        double bestInertiaScore = -Double.MAX_VALUE;

        for (LeadConnection candidate : SuperLeadNetwork.connections(level)) {
            if (candidate.id().equals(current.id()) || !canRideConnection(level, candidate)) {
                continue;
            }
            int direction;
            if (candidate.from().equals(anchor)) {
                direction = 1;
            } else if (candidate.to().equals(anchor)) {
                direction = -1;
            } else {
                continue;
            }
            double candidateT = direction > 0 ? END_MARGIN : 1.0D - END_MARGIN;
            Curve curve = curve(level, candidate, candidateT);
            if (curve.length() < MIN_SEGMENT_LENGTH) {
                continue;
            }
            Vec3 outgoing = curve.tangent().scale(direction).normalize();
            if (!isPoweredRedstone(candidate) && outgoing.y > 0.025D) {
                continue;
            }
            double facingScore = facingScore(player, outgoing);
            double inertiaScore = incomingTravel.dot(outgoing);
            if (facingScore > bestFacingScore + KNOT_FACING_TIE_EPSILON
                    || (Math.abs(facingScore - bestFacingScore) <= KNOT_FACING_TIE_EPSILON
                            && inertiaScore > bestInertiaScore)) {
                bestFacingScore = facingScore;
                bestInertiaScore = inertiaScore;
                best = candidate;
                bestDirection = direction;
            }
        }

        if (best == null) {
            return false;
        }
        state.connectionId = best.id();
        state.t = entryTAfterKnot(level, best, bestDirection, anchor, overshootDistance);
        ServerPhysicsTuning bestTuning = ServerPhysicsTuning.loadServerPhysicsTuning(level, best.physicsPreset());
        double carriedSpeed = Math.min(Math.abs(state.velocity) * KNOT_SPEED_RETAIN, maxSpeed(best, bestTuning));
        state.velocity = bestDirection * carriedSpeed;
        state.forceSnap = shouldSnapPastKnot(level, anchor);
        return true;
    }

    private static double facingScore(ServerPlayer player, Vec3 outgoing) {
        Vec3 view = player.getViewVector(1.0F);
        Vec3 flatView = new Vec3(view.x, 0.0D, view.z);
        Vec3 flatOutgoing = new Vec3(outgoing.x, 0.0D, outgoing.z);
        if (flatView.lengthSqr() > 1.0e-6D && flatOutgoing.lengthSqr() > 1.0e-6D) {
            return flatView.normalize().dot(flatOutgoing.normalize());
        }
        return outgoing.lengthSqr() < 1.0e-6D ? -1.0D : view.normalize().dot(outgoing.normalize());
    }

    private static double entryTAfterKnot(ServerLevel level, LeadConnection connection, int direction,
            LeadAnchor knot, double overshootDistance) {
        double length = curve(level, connection, 0.5D).length();
        double clearance = knotClearance(level, knot);
        double margin = clamp((clearance + Math.max(0.0D, overshootDistance))
                / Math.max(length, MIN_SEGMENT_LENGTH),
                END_MARGIN, MAX_KNOT_CLEARANCE_T);
        return direction > 0 ? margin : 1.0D - margin;
    }

    private static double knotClearance(ServerLevel level, LeadAnchor knot) {
        var state = level.getBlockState(knot.pos());
        if (LeadAnchor.isIronBarsKnotBlock(state)) {
            return IRON_BARS_KNOT_CLEARANCE_BLOCKS;
        }
        return LeadAnchor.isKnotBlock(state) ? KNOT_CLEARANCE_BLOCKS : 0.0D;
    }

    private static boolean shouldSnapPastKnot(ServerLevel level, LeadAnchor knot) {
        var state = level.getBlockState(knot.pos());
        return LeadAnchor.isKnotBlock(state) && !LeadAnchor.isIronBarsKnotBlock(state);
    }

    private static List<SyncZiplines.Entry> snapshot(ServerLevel level, Map<UUID, RiderState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        List<SyncZiplines.Entry> entries = new ArrayList<>(states.size());
        for (RiderState state : states.values()) {
            ServerPlayer player = findPlayer(level, state.playerId);
            if (player != null) {
                entries.add(new SyncZiplines.Entry(player.getId(), state.connectionId,
                        (float) clamp01(state.t)));
            }
        }
        return entries;
    }

    private static void broadcast(ServerLevel level, List<SyncZiplines.Entry> entries) {
        NetworkKey key = NetworkKey.of(level);
        int count = entries.size();
        int previous = LAST_SENT_COUNT.getOrDefault(key, 0);
        if (count == 0 && previous == 0) {
            return;
        }
        LAST_SENT_COUNT.put(key, count);
        PacketDistributor.sendToPlayersInDimension(level, new SyncZiplines(entries));
    }

    private static ServerPlayer findPlayer(ServerLevel level, UUID id) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(id)) {
                return player;
            }
        }
        return null;
    }

    private static void finish(ServerPlayer player, RiderState state, Vec3 releaseVelocity) {
        player.noPhysics = state.oldNoPhysics;
        player.resetFallDistance();
        if (releaseVelocity != null && releaseVelocity.lengthSqr() > 1.0e-6D) {
            player.setDeltaMovement(releaseVelocity);
            player.hurtMarked = true;
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }

    private static Vec3 currentVelocity(ServerLevel level, RiderState state) {
        Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, state.connectionId);
        if (opt.isEmpty()) {
            return Vec3.ZERO;
        }
        Curve curve = curve(level, opt.get(), state.t);
        return curve.length() < MIN_SEGMENT_LENGTH
                ? Vec3.ZERO
                : curve.tangent().scale(state.velocity);
    }

    private static Vec3 launchVelocity(ServerPlayer player, Vec3 ropeVelocity) {
        Vec3 ropeInertia = player.getDeltaMovement();
        if (ropeInertia == null || ropeInertia.lengthSqr() <= 1.0e-8D) {
            return ropeVelocity;
        }
        if (ropeVelocity == null || ropeVelocity.lengthSqr() <= 1.0e-8D) {
            return ropeInertia;
        }
        // The player already has one tick of motion from being carried by the rope. When
        // they leave the endpoint, preserve that on-rope inertia and add the freshly
        // computed exit tangent speed on top, so powered redstone rope chains can build up
        // enough launch velocity instead of replacing the previous motion each time.
        return ropeVelocity.add(ropeInertia);
    }

    private static boolean hasChain(Player player) {
        return isChain(player.getMainHandItem()) || isChain(player.getOffhandItem());
    }

    private static int initialDirection(ServerPlayer player, LeadConnection connection, Curve curve) {
        int fallback = downhillDirection(player, curve, 1);
        return isPoweredRedstone(connection) ? directionFromFacing(player, curve, fallback) : fallback;
    }

    private static int downhillDirection(ServerPlayer player, Curve curve, int fallback) {
        Vec3 tangent = curve.tangent();
        if (Math.abs(tangent.y) > 0.025D) {
            return tangent.y < 0.0D ? 1 : -1;
        }
        return directionFromFacing(player, curve, fallback == 0 ? 1 : fallback);
    }

    private static int directionFromFacing(ServerPlayer player, Curve curve, int fallback) {
        Vec3 view = player.getViewVector(1.0F);
        Vec3 tangent = curve.tangent();
        double dot = view.x * tangent.x + view.y * tangent.y + view.z * tangent.z;
        if (dot > 0.10D) {
            return 1;
        }
        if (dot < -0.10D) {
            return -1;
        }
        return fallback == 0 ? 1 : fallback;
    }

    private static boolean isPoweredRedstone(LeadConnection connection) {
        return connection.kind() == LeadKind.REDSTONE && connection.powered();
    }

    private static double maxSpeed(LeadConnection connection, ServerPhysicsTuning tuning) {
        double configured = tuning.ziplineSpeedLimit();
        if (Double.isNaN(configured)) {
            return isPoweredRedstone(connection) ? MAX_POWERED_SPEED : MAX_NORMAL_SPEED;
        }
        if (configured < 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return configured;
    }

    private static int poweredAccelerationDirection(ServerPlayer player, Curve curve, double velocity) {
        if (Math.abs(velocity) >= POWERED_INERTIA_PRIORITY_SPEED) {
            return velocity >= 0.0D ? 1 : -1;
        }
        return directionFromFacing(player, curve, velocity >= 0.0D ? 1 : -1);
    }

    private static Curve curve(ServerLevel level, LeadConnection connection, double rawT) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                SuperLeadNetwork.connections(level));
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double t = clamp01(rawT);
        ServerRopeCurve.Shape shape = ServerRopeCurve.from(level, connection, a, b);
        return new Curve(t, ServerRopeCurve.point(shape, t), ServerRopeCurve.tangent(shape, t),
                Math.max(shape.length(), MIN_SEGMENT_LENGTH));
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static final class RiderState {
        private final UUID playerId;
        private UUID connectionId;
        private double t;
        // Signed velocity along the curve's from->to tangent (blocks per tick).
        // Positive = moving toward `to`, negative = moving toward `from`.
        private double velocity;
        private int age;
        private final boolean oldNoPhysics;
        private final boolean startedSneaking;
        private boolean releasedStartSneak;
        private boolean forceSnap;

        private RiderState(UUID playerId, UUID connectionId, double t, double velocity,
                boolean oldNoPhysics, boolean startedSneaking) {
            this.playerId = playerId;
            this.connectionId = connectionId;
            this.t = t;
            this.velocity = velocity;
            this.oldNoPhysics = oldNoPhysics;
            this.startedSneaking = startedSneaking;
            this.releasedStartSneak = !startedSneaking;
        }
    }

    private record Curve(double t, Vec3 point, Vec3 tangent, double length) {
    }
}