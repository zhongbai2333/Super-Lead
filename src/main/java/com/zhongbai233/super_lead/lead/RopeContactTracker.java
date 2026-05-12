package com.zhongbai233.super_lead.lead;

import static com.zhongbai233.super_lead.lead.RopeContactGeometry.*;
import static com.zhongbai233.super_lead.lead.ServerPhysicsTuning.loadServerPhysicsTuning;

import com.zhongbai233.super_lead.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side adjudicator for client-reported rope contacts.
 *
 * Clients own the high-fidelity rope shape because it depends on local physics,
 * LOD and terrain
 * settling. The server only validates that a reported contact is plausible for
 * the authoritative
 * rope anchors, applies a clamped one-sided player push, and rebroadcasts the
 * accepted visual
 * deflection so observers and LOD/static ropes see the same pushed shape.
 */
public final class RopeContactTracker {
    private static final double VISUAL_MAX_DEFLECT = 0.50D;
    private static final double CLIENT_REPORT_BASE_TOLERANCE = 0.85D;
    private static final double CLIENT_REPORT_MIN_DEFLECTION_ALLOWANCE = 1.75D;
    private static final double CLIENT_REPORT_MAX_DEFLECTION_ALLOWANCE = 4.0D;
    private static final double CLIENT_REPORT_DEFLECTION_FRACTION = 0.35D;
    private static final double CLIENT_REPORT_MAX_DEPTH = 0.75D;
    private static final double SUPPORT_MIN_HEIGHT = 0.28D;
    private static final double SUPPORT_MAX_HEIGHT = 0.55D;
    private static final double SUPPORT_MAX_UPWARD_SPEED = 0.08D;
    private static final long CLIENT_CONTACT_TTL_TICKS = 5L;
    private static final double PUSH_MIN_TRAVEL_BLOCKS = 0.70D;
    private static final double PUSH_MAX_TRAVEL_BLOCKS = 2.65D;
    private static final double PUSH_TRAVEL_LENGTH_FRACTION = 0.22D;
    private static final double PUSH_SOFT_START_RATIO = 0.08D;
    private static final double PUSH_HARD_START_RATIO = 0.58D;
    private static final double PUSH_DAMPING_SCALE = 0.72D;
    private static final double PUSH_SPRING_SCALE = 0.42D;
    private static final double PUSH_EXIT_RESTORE_SCALE = 1.08D;
    private static final double PUSH_EXIT_RESTORE_CAP_SCALE = 1.35D;
    private static final double PUSH_EXIT_TARGET_SPEED_SCALE = 1.10D;
    private static final double PUSH_EXIT_MIN_RESTORE_GAIN = 0.85D;
    private static final double PUSH_EXIT_FULL_DEPTH_RATIO = 1.30D;
    private static final double PUSH_EXIT_MAX_MOVE_SPEED = 0.50D;
    private static final double PUSH_MAX_CANCEL_FRACTION = 0.82D;

    private static final Map<NetworkKey, Integer> LAST_SENT_COUNT = new HashMap<>();
    private static final Map<NetworkKey, Map<ContactKey, AcceptedClientContact>> CLIENT_CONTACTS = new HashMap<>();

    private static volatile long lastTickNanos;
    private static volatile int lastContacts;

    public static long lastTickNanos() {
        return lastTickNanos;
    }

    public static int lastActiveSims() {
        return 0;
    }

    public static int lastSteppedSims() {
        return 0;
    }

    public static int lastContacts() {
        return lastContacts;
    }

    private RopeContactTracker() {
    }

    public static void tickRopeContacts(ServerLevel level) {
        long t0 = System.nanoTime();
        try {
            tickInternal(level);
        } finally {
            lastTickNanos = System.nanoTime() - t0;
        }
    }

    public static void acceptClientContact(ServerLevel level, ServerPlayer player, ClientRopeContactReport report) {
        if (!Config.allowOpVisualPresets())
            return;
        if (player.isSpectator())
            return;
        if (!finite(report.t(), report.pointX(), report.pointY(), report.pointZ(),
                report.normalX(), report.normalZ(), report.inputX(), report.inputZ(), report.depth()))
            return;
        if (report.t() < -0.05F || report.t() > 1.05F)
            return;

        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, report.ropeId());
        if (opt.isEmpty())
            return;
        LeadConnection connection = opt.get();
        if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE)
            return;

        Vec3 a = connection.from().attachmentPoint(level);
        Vec3 b = connection.to().attachmentPoint(level);
        if (a == null || b == null)
            return;
        double dist = a.distanceTo(b);
        if (dist < 1.0e-3D || dist > Config.maxLeashDistance() + 1.0D)
            return;

        if (connection.physicsPreset().isBlank())
            return;
        ServerPhysicsTuning tuning = loadServerPhysicsTuning(level, connection.physicsPreset());
        if (!tuning.physicsEnabled() || !tuning.pushbackEnabled())
            return;

        Vec3 point = new Vec3(report.pointX(), report.pointY(), report.pointZ());

        AABB playerBox = player.getBoundingBox();
        double radius = tuning.contactRadius();
        double speed = player.getDeltaMovement().horizontalDistance();
        double tolerance = CLIENT_REPORT_BASE_TOLERANCE + radius + speed * 2.0D;
        if (!playerBox.inflate(tolerance).contains(point))
            return;

        double[] closest = new double[4];
        double dSqr = closestPointOnPlausibleClientRope(a, b, point, tuning, closest);
        double deflectionAllowance = clamp(
                dist * CLIENT_REPORT_DEFLECTION_FRACTION,
                CLIENT_REPORT_MIN_DEFLECTION_ALLOWANCE,
                CLIENT_REPORT_MAX_DEFLECTION_ALLOWANCE);
        double shapeTolerance = tolerance + deflectionAllowance;
        if (dSqr > shapeTolerance * shapeTolerance)
            return;

        double t = clamp01(report.t());
        double depth = clamp(report.depth(), 0.0D, Math.min(CLIENT_REPORT_MAX_DEPTH, radius * 2.75D));
        if (depth <= 1.0e-4D)
            return;

        if (report.support()) {
            acceptSupportContact(level, player, connection, point, closest, t, depth, radius);
            return;
        }

        double nx = report.normalX();
        double nz = report.normalZ();
        double nLen = Math.sqrt(nx * nx + nz * nz);
        if (nLen < 1.0e-4D)
            return;
        nx /= nLen;
        nz /= nLen;

        Vec3 playerCenter = playerBox.getCenter();
        double toPlayerX = playerCenter.x - point.x;
        double toPlayerZ = playerCenter.z - point.z;
        if (toPlayerX * toPlayerX + toPlayerZ * toPlayerZ > 1.0e-6D
                && toPlayerX * nx + toPlayerZ * nz < 0.0D) {
            nx = -nx;
            nz = -nz;
        }

        // When the rope hits in the upper half of the player's body, redirect a
        // portion of the horizontal push into a gentle upward lift so the player
        // slides under the rope.  When it hits the lower half, push downward so
        // the player steps over it.  Both avoid the rope simply clipping through
        // the model.
        double bodyHeight = playerBox.maxY - playerBox.minY;
        double midY = playerBox.minY + bodyHeight * 0.5D;
        if (bodyHeight > 1.0e-6D) {
            double verticalFraction = (point.y - midY) / (bodyHeight * 0.5D);
            double verticalScale = (depth / Math.max(radius, 1.0e-6D)) * 0.12D;
            Vec3 v = player.getDeltaMovement();
            if (verticalFraction > 0.0D) {
                double lift = verticalScale * clamp01(verticalFraction);
                player.setDeltaMovement(v.x, Math.max(v.y, lift), v.z);
            } else if (verticalFraction < 0.0D) {
                double pushDown = verticalScale * clamp01(-verticalFraction);
                player.setDeltaMovement(v.x, v.y - pushDown, v.z);
            }
            player.hurtMarked = true;
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }

        long now = level.getGameTime();
        NetworkKey dim = NetworkKey.of(level);
        ContactKey key = new ContactKey(connection.id(), player.getUUID());
        Map<ContactKey, AcceptedClientContact> dimContacts = CLIENT_CONTACTS.computeIfAbsent(dim,
                ignored -> new HashMap<>());
        AcceptedClientContact previous = dimContacts.get(key);
        boolean applyVelocityThisTick = previous == null || previous.tick() < now;

        double ropeDeflection = contactDeflectionAwayFromPlayer(point, closest, nx, nz);
        AppliedPush push = applyVelocityThisTick
                ? applySoftPushback(player, nx, nz, report.inputX(), report.inputZ(),
                        depth, ropeDeflection, radius, dist, tuning)
                : (previous == null ? AppliedPush.NONE : previous.push());

        double visualMag = Math.min(Math.max(ropeDeflection, depth * push.gain() * 0.15D), VISUAL_MAX_DEFLECT);
        dimContacts.put(key, new AcceptedClientContact(connection.id(), player.getUUID(), now,
                (float) t, (float) (-nx * visualMag), 0.0F, (float) (-nz * visualMag), push));
    }

    private static void acceptSupportContact(ServerLevel level, ServerPlayer player, LeadConnection connection,
            Vec3 point, double[] restPoint, double t, double depth, double radius) {
        AABB box = player.getBoundingBox();
        double supportHeight = clamp(Math.max(radius * 1.5D, SUPPORT_MIN_HEIGHT),
                SUPPORT_MIN_HEIGHT, SUPPORT_MAX_HEIGHT);
        double verticalGap = box.minY - point.y;
        double horizontalSlack = Math.max(radius, 0.24D) + 0.12D;
        if (verticalGap < -0.10D || verticalGap > supportHeight)
            return;
        if (point.x < box.minX - horizontalSlack || point.x > box.maxX + horizontalSlack
                || point.z < box.minZ - horizontalSlack || point.z > box.maxZ + horizontalSlack) {
            return;
        }

        long now = level.getGameTime();
        NetworkKey dim = NetworkKey.of(level);
        ContactKey key = new ContactKey(connection.id(), player.getUUID());
        Map<ContactKey, AcceptedClientContact> dimContacts = CLIENT_CONTACTS.computeIfAbsent(dim,
                ignored -> new HashMap<>());
        AcceptedClientContact previous = dimContacts.get(key);
        boolean applyVelocityThisTick = previous == null || previous.tick() < now;

        AppliedPush push;
        if (applyVelocityThisTick) {
            push = applyVerticalSupport(player, verticalGap);
        } else {
            push = previous == null ? AppliedPush.NONE : previous.push();
            // Add light Y damping every tick while supported (not just the push tick) to
            // kill the pushback-vs-gravity oscillation that makes the player stutter.
            dampSupportedVelocity(player);
        }

        double ropeDeflection = Math.max(0.0D, restPoint[1] - point.y);
        double visualMag = Math.min(Math.max(ropeDeflection, depth * 0.35D), VISUAL_MAX_DEFLECT);
        dimContacts.put(key, new AcceptedClientContact(connection.id(), player.getUUID(), now,
                (float) t, 0.0F, (float) (-visualMag), 0.0F, push));
    }

    private static void tickInternal(ServerLevel level) {
        long now = level.getGameTime();
        NetworkKey dim = NetworkKey.of(level);
        Map<ContactKey, AcceptedClientContact> contacts = CLIENT_CONTACTS.get(dim);
        List<RopeContactPulse.Entry> pulse = new ArrayList<>();
        if (contacts != null) {
            contacts.entrySet().removeIf(e -> now - e.getValue().tick() > CLIENT_CONTACT_TTL_TICKS);
            for (AcceptedClientContact c : contacts.values()) {
                pulse.add(new RopeContactPulse.Entry(c.ropeId(), c.playerId(), c.t(), c.dx(), c.dy(), c.dz(),
                        c.push().x(), c.push().z(), c.push().mag()));
            }
            if (contacts.isEmpty()) {
                CLIENT_CONTACTS.remove(dim);
            }
        }
        broadcast(level, pulse);
        lastContacts = pulse.size();
    }

    private static AppliedPush applySoftPushback(ServerPlayer player, double nx, double nz,
            double inputX, double inputZ,
            double depth, double ropeDeflection, double radius, double ropeLength, ServerPhysicsTuning tuning) {
        if (depth <= 0.0D || radius <= 1.0e-6D)
            return AppliedPush.NONE;

        double travel = clamp(ropeLength * PUSH_TRAVEL_LENGTH_FRACTION,
                PUSH_MIN_TRAVEL_BLOCKS, PUSH_MAX_TRAVEL_BLOCKS);
        double resistance = clamp(7.0D / Math.max(ropeLength, 1.0D), 0.45D, 1.35D);
        double pressure = Math.max(depth * 0.45D, ropeDeflection + Math.max(0.0D, depth - radius) * 0.25D);
        double ratio = clamp01(pressure / travel);
        double soft = clamp01((ratio - PUSH_SOFT_START_RATIO) / (1.0D - PUSH_SOFT_START_RATIO));
        double gain = pushEaseIn(soft);
        double inputAway = inputAwayFactor(player, nx, nz, inputX, inputZ);
        double exitGain = gain;
        double exitRestoreGain = 0.0D;
        if (inputAway > 0.0D) {
            double depthExit = clamp01(depth / Math.max(radius * PUSH_EXIT_FULL_DEPTH_RATIO, 1.0e-6D));
            exitRestoreGain = Math.max(PUSH_EXIT_MIN_RESTORE_GAIN, pushEaseIn(depthExit));
            exitGain = Math.max(exitGain, exitRestoreGain);
        }
        if (gain <= 1.0e-4D && exitGain <= 1.0e-4D)
            return AppliedPush.NONE;

        Vec3 v = player.getDeltaMovement();
        double outwardSpeed = v.x * nx + v.z * nz;
        double inwardSpeed = Math.max(0.0D, -outwardSpeed);

        double damping = clamp(tuning.velocityDamping(), 0.0D, 2.0D);
        double cancelFraction = clamp(damping * gain * PUSH_DAMPING_SCALE * resistance,
                0.0D, PUSH_MAX_CANCEL_FRACTION);
        double dampMag = inwardSpeed * cancelFraction;

        double hard = clamp01((ratio - PUSH_HARD_START_RATIO) / (1.0D - PUSH_HARD_START_RATIO));
        double hardGain = pushEaseIn(hard);
        double hardPressure = Math.max(0.0D, pressure - travel * PUSH_HARD_START_RATIO);
        double spring = Math.max(0.0D, tuning.springK()) * resistance;
        double shoveMag = spring * hardPressure * hardGain * PUSH_SPRING_SCALE;

        double maxRecoil = Math.max(0.0D, tuning.maxRecoilPerTick());
        double physicalMax = maxRecoil * resistance * (0.25D + hardGain * 1.75D);
        double physicalMag = Math.min(dampMag + shoveMag, physicalMax);
        double exitRestore = 0.0D;
        if (inputAway > 0.0D) {
            double targetExitSpeed = expectedHorizontalMoveSpeed(player) * PUSH_EXIT_TARGET_SPEED_SCALE * inputAway;
            double exitSpeedAfterPhysicalPush = Math.max(0.0D, outwardSpeed) + physicalMag;
            double missingExitSpeed = Math.max(0.0D, targetExitSpeed - exitSpeedAfterPhysicalPush);
            double restoreCap = Math.min(
                    targetExitSpeed * PUSH_EXIT_RESTORE_CAP_SCALE * exitRestoreGain,
                    maxRecoil * resistance * (0.35D + inputAway * 0.65D));
            exitRestore = Math.min(missingExitSpeed * PUSH_EXIT_RESTORE_SCALE * exitRestoreGain, restoreCap);
        }
        double mag = physicalMag + exitRestore;
        if (mag <= 1.0e-5D)
            return new AppliedPush(0.0F, 0.0F, 0.0F, (float) gain);

        double px = nx * mag;
        double pz = nz * mag;
        player.setDeltaMovement(v.x + px, v.y, v.z + pz);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        return new AppliedPush((float) px, (float) pz, (float) mag, (float) Math.max(gain, exitGain));
    }

    private static double expectedHorizontalMoveSpeed(ServerPlayer player) {
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return Double.isFinite(speed) ? clamp(speed, 0.0D, PUSH_EXIT_MAX_MOVE_SPEED) : 0.10D;
    }

    private static double inputAwayFactor(ServerPlayer player, double nx, double nz,
            double clientInputX, double clientInputZ) {
        double clientAway = inputAwayFromWorldVector(clientInputX, clientInputZ, nx, nz);
        if (clientAway >= 0.0D)
            return clientAway;

        double inputX = player.xxa;
        double inputZ = player.zza;
        double inputLen = Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLen < 1.0e-4D)
            return 0.0D;

        inputX /= inputLen;
        inputZ /= inputLen;
        double yaw = Math.toRadians(player.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = inputX * cos - inputZ * sin;
        double worldZ = inputZ * cos + inputX * sin;
        return clamp01(worldX * nx + worldZ * nz);
    }

    private static double inputAwayFromWorldVector(double inputX, double inputZ, double nx, double nz) {
        if (!Double.isFinite(inputX) || !Double.isFinite(inputZ))
            return -1.0D;
        double inputLen = Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLen < 1.0e-4D)
            return -1.0D;
        inputX /= inputLen;
        inputZ /= inputLen;
        return clamp01(inputX * nx + inputZ * nz);
    }

    private static double contactDeflectionAwayFromPlayer(Vec3 point, double[] restPoint,
            double nx, double nz) {
        double dx = point.x - restPoint[0];
        double dz = point.z - restPoint[2];
        double alongPush = -(dx * nx + dz * nz);
        double lateral = Math.sqrt(dx * dx + dz * dz) * 0.35D;
        return Math.max(0.0D, Math.max(alongPush, lateral));
    }

    private static double pushEaseIn(double x) {
        x = clamp01(x);
        double x2 = x * x;
        double x4 = x2 * x2;
        return x4 * (0.25D + 0.75D * x);
    }

    private static void broadcast(ServerLevel level, List<RopeContactPulse.Entry> pulse) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_SENT_COUNT.get(key);
        if (pulse.isEmpty() && (last == null || last == 0))
            return;
        LAST_SENT_COUNT.put(key, pulse.size());
        PacketDistributor.sendToPlayersInDimension(level, new RopeContactPulse(pulse));
    }

    private static AppliedPush applyVerticalSupport(ServerPlayer player, double verticalGap) {
        Vec3 v = player.getDeltaMovement();
        // Don't override intentional upward motion (jumping / being knocked up).
        if (v.y > SUPPORT_MAX_UPWARD_SPEED) {
            dampSupportedVelocity(player);
            return AppliedPush.NONE;
        }
        // Only provide a gentle lift when the player has sunk slightly below the rope.
        // Stronger lift = more overshoot = more oscillation. Keep it barely noticeable
        // so the player feels "supported" without bouncing.
        double lift = verticalGap < 0.0D
                ? Math.min((-verticalGap) * 0.12D + 0.005D, 0.035D)
                : 0.0D;
        double nextY = Math.max(v.y * 0.55D, lift); // kill most of the downward speed
        player.setDeltaMovement(v.x, nextY, v.z);
        player.setOnGround(true);
        player.resetFallDistance();
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        return new AppliedPush(0.0F, 0.0F, (float) nextY, 1.0F);
    }

    /**
     * Light Y-velocity damping applied every tick while the player is standing on a
     * rope.
     * Removes the residual bounce energy that otherwise builds up between the
     * vertical-lift
     * push and the natural gravity-driven sink.
     */
    private static void dampSupportedVelocity(ServerPlayer player) {
        Vec3 v = player.getDeltaMovement();
        // Only damp small residual speeds. If the player is actively falling or
        // jumping,
        // leave them alone.
        if (v.y < -0.02D || v.y > 0.06D)
            return;
        double dampedY = v.y * 0.65D;
        if (Math.abs(dampedY - v.y) < 1.0e-5D)
            return;
        player.setDeltaMovement(v.x, dampedY, v.z);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    /** Forget per-dimension state for a level (e.g. on shutdown). */
    public static void clear(ServerLevel level) {
        NetworkKey key = NetworkKey.of(level);
        LAST_SENT_COUNT.remove(key);
        CLIENT_CONTACTS.remove(key);
    }

    public static void clearAllSims() {
        CLIENT_CONTACTS.clear();
        LAST_SENT_COUNT.clear();
        lastContacts = 0;
    }

    private record ContactKey(UUID ropeId, UUID playerId) {
    }

    private record AppliedPush(float x, float z, float mag, float gain) {
        private static final AppliedPush NONE = new AppliedPush(0.0F, 0.0F, 0.0F, 0.0F);
    }

    private record AcceptedClientContact(UUID ropeId, UUID playerId, long tick,
            float t, float dx, float dy, float dz, AppliedPush push) {
    }
}
