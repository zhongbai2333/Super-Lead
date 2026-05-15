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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side adjudicator for client-reported rope contacts.
 *
 * Clients own the high-fidelity rope shape because it depends on local physics,
 * LOD and terrain settling. The server validates the report against an
 * authoritative-endpoint sag envelope, then applies a 3D contact response that
 * separates normal push, foot support, tangent friction and slack softening.
 */
public final class RopeContactTracker {
    private static final double VISUAL_MAX_DEFLECT = 0.50D;
    private static final double CLIENT_REPORT_BASE_TOLERANCE = 0.85D;
    private static final double CLIENT_REPORT_MIN_DEFLECTION_ALLOWANCE = 1.75D;
    private static final double CLIENT_REPORT_MAX_DEFLECTION_ALLOWANCE = 4.0D;
    private static final double CLIENT_REPORT_DEFLECTION_FRACTION = 0.35D;
    private static final double CLIENT_REPORT_MAX_DEPTH = 0.75D;
    // Rigid-rope inelastic contact: vanilla jump speed for kicking off a rope perch.
    private static final double CONTACT_TOP_JUMP_SPEED = 0.42D;
    private static final double CONTACT_SIDE_HARD_DEPTH_FRACTION = 0.65D;
    private static final double CONTACT_EXIT_INPUT_DOT = 0.05D;
    private static final double NORMAL_EPSILON = 1.0e-5D;
    private static final long CLIENT_CONTACT_TTL_TICKS = 5L;

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
                report.normalX(), report.normalY(), report.normalZ(),
                report.tangentX(), report.tangentY(), report.tangentZ(),
                report.inputX(), report.inputZ(), report.depth(), report.slack()))
            return;
        if (report.t() < -0.05F || report.t() > 1.05F)
            return;

        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, report.ropeId());
        if (opt.isEmpty())
            return;
        LeadConnection connection = opt.get();
        if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE)
            return;

        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                SuperLeadNetwork.connections(level));
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        if (a == null || b == null)
            return;
        double dist = a.distanceTo(b);
        if (dist < 1.0e-3D || dist > Config.maxLeashDistance() + 1.0D)
            return;

        // Gameplay collision is deliberately limited to the synced physics-zone path.
        if (connection.physicsPreset().isBlank())
            return;
        ServerPhysicsTuning tuning = loadServerPhysicsTuning(level, connection.physicsPreset());
        if (!tuning.physicsEnabled())
            return;

        double radius = tuning.contactRadius();
        if (radius <= 1.0e-6D)
            return;

        AABB playerBox = player.getBoundingBox();
        Vec3 playerCenter = playerBox.getCenter();
        Vec3 reportedPoint = new Vec3(report.pointX(), report.pointY(), report.pointZ());
        double speed = player.getDeltaMovement().length();
        double tolerance = CLIENT_REPORT_BASE_TOLERANCE + radius + speed * 2.0D;

        double[] closest = new double[4];
        double dSqr = closestPointOnPlausibleClientRope(a, b, reportedPoint, tuning, closest);
        double deflectionAllowance = clamp(
                dist * CLIENT_REPORT_DEFLECTION_FRACTION,
                CLIENT_REPORT_MIN_DEFLECTION_ALLOWANCE,
                CLIENT_REPORT_MAX_DEFLECTION_ALLOWANCE);
        double shapeTolerance = tolerance + deflectionAllowance;
        boolean plausibleReport = playerBox.inflate(tolerance).contains(reportedPoint)
                && dSqr <= shapeTolerance * shapeTolerance;

        double reportDepth = clamp(report.depth(), 0.0D,
                Math.min(CLIENT_REPORT_MAX_DEPTH, radius * 2.75D));
        if (reportDepth <= 1.0e-4D)
            return;

        ContactEstimate contact = null;
        if (plausibleReport) {
            Vec3 normal = normalize(report.normalX(), report.normalY(), report.normalZ());
            Vec3 tangent = sanitizeTangent(report.tangentX(), report.tangentY(), report.tangentZ(), a, b);
            if (normal != null) {
                normal = orthogonalizeNormal(normal, tangent);
                normal = orientTowardPlayer(normal, reportedPoint, playerCenter);
                double slack = clamp(report.slack(), 0.0D, radius * 3.0D);
                contact = new ContactEstimate(clamp01(report.t()), reportedPoint, normal, reportDepth, slack);
            }
        }

        if (contact == null) {
            contact = fallbackContact(a, b, playerBox, radius, tuning, reportDepth * 0.50D);
        }
        if (contact == null)
            return;

        ParrotRopePerchController.disturb(level, connection.id(), contact.point());

        if (!tuning.pushbackEnabled())
            return;

        long now = level.getGameTime();
        NetworkKey dim = NetworkKey.of(level);
        ContactKey key = new ContactKey(connection.id(), player.getUUID());
        Map<ContactKey, AcceptedClientContact> dimContacts = CLIENT_CONTACTS.computeIfAbsent(dim,
                ignored -> new HashMap<>());
        AcceptedClientContact previous = dimContacts.get(key);
        boolean applyVelocityThisTick = previous == null || previous.tick() < now;

        boolean footSupportContact = isFootSupportPoint(playerBox, contact.point(), radius);
        AppliedPush push;
        if (applyVelocityThisTick) {
            push = applyRigidContact(player, contact.normal(), contact.depth(), radius, tuning,
                    report.jumpDown(), footSupportContact, report.inputX(), report.inputZ());
        } else {
            push = previous == null ? AppliedPush.NONE : previous.push();
        }

        double visualMag;
        float dx;
        float dy;
        float dz;
        if (footSupportContact) {
            visualMag = Math.min(Math.max(contact.depth(), radius), VISUAL_MAX_DEFLECT);
            dx = 0.0F;
            dy = (float) -visualMag;
            dz = 0.0F;
        } else {
            visualMag = Math.min(Math.max(contact.depth(), radius), VISUAL_MAX_DEFLECT);
            Vec3 n = contact.normal();
            dx = (float) (-n.x * visualMag);
            dy = (float) (-n.y * visualMag);
            dz = (float) (-n.z * visualMag);
        }
        dimContacts.put(key, new AcceptedClientContact(connection.id(), player.getUUID(), now,
                (float) contact.t(), dx, dy, dz, push));
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

    /**
     * Rigid inelastic contact response — used for all rope-vs-player collisions
     * inside a physics preset with pushback enabled.
     * <p>
     * Model: rope is treated as a position constraint. We do NOT apply a spring
     * impulse proportional to penetration depth. Instead we cancel the player's
     * velocity component pointing INTO the rope (normal approach speed) and
     * preserve the tangential component, so the player can slide along the rope
     * but cannot pass through it. This mirrors a real-world inextensible rope.
     * <p>
     * The single exception is an explicit jump kick when the player is standing
     * on top of a rope and presses jump — that path injects vanilla jump speed.
     */
    private static AppliedPush applyRigidContact(ServerPlayer player, Vec3 normal,
            double depth, double radius, ServerPhysicsTuning tuning,
            boolean jumpDown, boolean footSupportContact, double inputX, double inputZ) {
        if (depth <= 0.0D || radius <= 1.0e-6D)
            return AppliedPush.NONE;
        if (depth < tuning.pushbackEnableDepth())
            return AppliedPush.NONE;
        Vec3 v = player.getDeltaMovement();
        double depthRatio = clamp01(depth / radius);

        if (footSupportContact) {
            player.setOnGround(true);
            player.resetFallDistance();

            // Foot support is a vertical ground constraint, not a curved-surface
            // normal projection. Keep horizontal motion intact so the rope does not
            // behave like a slippery cylinder under the player's feet.
            if (jumpDown && v.y < CONTACT_TOP_JUMP_SPEED) {
                double impulseMag = CONTACT_TOP_JUMP_SPEED - v.y;
                player.setDeltaMovement(v.x, CONTACT_TOP_JUMP_SPEED, v.z);
                player.hurtMarked = true;
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
                return new AppliedPush(0.0F, 0.0F, (float) impulseMag, (float) depthRatio);
            }
            if (v.y < 0.0D) {
                double impulseMag = -v.y;
                player.setDeltaMovement(v.x, 0.0D, v.z);
                player.hurtMarked = true;
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
                return new AppliedPush(0.0F, 0.0F, (float) impulseMag, (float) depthRatio);
            }
            return AppliedPush.NONE;
        }

        Vec3 n = normalize(normal.x, 0.0D, normal.z);
        if (n == null)
            return AppliedPush.NONE;

        double hardDepth = radius * CONTACT_SIDE_HARD_DEPTH_FRACTION;
        if (depth < hardDepth)
            return AppliedPush.NONE;

        double vn = v.x * n.x + v.z * n.z;
        double correctionMag = Math.max(0.0D, depth - hardDepth) + 1.0e-3D;
        player.move(MoverType.SELF, new Vec3(n.x * correctionMag, 0.0D, n.z * correctionMag));
        double inputDot = inputX * n.x + inputZ * n.z;
        if (vn >= 0.0D || inputDot > CONTACT_EXIT_INPUT_DOT) {
            player.hurtMarked = true;
            return new AppliedPush(0.0F, 0.0F, 0.0F, (float) depthRatio);
        }

        // Side blocking is a horizontal rigid constraint: cancel the full inward
        // horizontal normal velocity and preserve vertical/tangential motion.
        double impulseMag = -vn;
        double px = n.x * impulseMag;
        double pz = n.z * impulseMag;
        double nextX = v.x + px;
        double nextZ = v.z + pz;

        player.setDeltaMovement(nextX, v.y, nextZ);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        return new AppliedPush((float) px, (float) pz, (float) impulseMag, (float) depthRatio);
    }



    private static ContactEstimate fallbackContact(Vec3 a, Vec3 b, AABB playerBox,
            double radius, ServerPhysicsTuning tuning, double minDepth) {
        double[] closest = new double[4];
        closestPointOnPlausibleClientRope(a, b, playerBox.getCenter(), tuning, closest);
        Vec3 point = new Vec3(closest[0], closest[1], closest[2]);
        BoxContact box = contactFromPointToBox(point, playerBox);
        if (box == null)
            return null;
        if (box.separation() >= radius)
            return null;
        double depth = clamp(Math.max(minDepth, radius - box.separation()), 0.0D,
                Math.min(CLIENT_REPORT_MAX_DEPTH, radius * 2.75D));
        if (depth <= 1.0e-4D)
            return null;
        double t = clamp01(closest[3]);
        return new ContactEstimate(t, point, box.normal(), depth,
                estimatedSlackAllowance(a, b, t, tuning, radius));
    }

    private static BoxContact contactFromPointToBox(Vec3 point, AABB box) {
        boolean inside = point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
        if (inside) {
            double exit = point.x - box.minX;
            Vec3 normal = new Vec3(1.0D, 0.0D, 0.0D);
            double toMaxX = box.maxX - point.x;
            if (toMaxX < exit) {
                exit = toMaxX;
                normal = new Vec3(-1.0D, 0.0D, 0.0D);
            }
            double toMinY = point.y - box.minY;
            if (toMinY < exit) {
                exit = toMinY;
                normal = new Vec3(0.0D, 1.0D, 0.0D);
            }
            double toMaxY = box.maxY - point.y;
            if (toMaxY < exit) {
                exit = toMaxY;
                normal = new Vec3(0.0D, -1.0D, 0.0D);
            }
            double toMinZ = point.z - box.minZ;
            if (toMinZ < exit) {
                exit = toMinZ;
                normal = new Vec3(0.0D, 0.0D, 1.0D);
            }
            double toMaxZ = box.maxZ - point.z;
            if (toMaxZ < exit) {
                exit = toMaxZ;
                normal = new Vec3(0.0D, 0.0D, -1.0D);
            }
            return new BoxContact(normal, -Math.max(0.0D, exit));
        }

        double cx = clamp(point.x, box.minX, box.maxX);
        double cy = clamp(point.y, box.minY, box.maxY);
        double cz = clamp(point.z, box.minZ, box.maxZ);
        double nx = cx - point.x;
        double ny = cy - point.y;
        double nz = cz - point.z;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < NORMAL_EPSILON) {
            Vec3 center = box.getCenter();
            nx = center.x - point.x;
            ny = center.y - point.y;
            nz = center.z - point.z;
            len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len < NORMAL_EPSILON)
            return null;
        return new BoxContact(new Vec3(nx / len, ny / len, nz / len), len);
    }

    private static Vec3 sanitizeTangent(double x, double y, double z, Vec3 a, Vec3 b) {
        Vec3 tangent = normalize(x, y, z);
        return tangent != null ? tangent : fallbackTangent(a, b);
    }

    private static Vec3 fallbackTangent(Vec3 a, Vec3 b) {
        Vec3 delta = b.subtract(a);
        if (delta.lengthSqr() < NORMAL_EPSILON * NORMAL_EPSILON)
            return new Vec3(1.0D, 0.0D, 0.0D);
        return delta.normalize();
    }

    private static Vec3 orthogonalizeNormal(Vec3 normal, Vec3 tangent) {
        Vec3 projected = normal.subtract(tangent.scale(normal.dot(tangent)));
        Vec3 n = normalize(projected.x, projected.y, projected.z);
        return n != null ? n : normal;
    }

    private static double estimatedSlackAllowance(Vec3 a, Vec3 b, double t, ServerPhysicsTuning tuning, double radius) {
        double freeSlack = ServerRopeCurve.freeSlack(a, b, tuning);
        double midspan = Math.sin(Math.PI * clamp01(t));
        return clamp(freeSlack * midspan, 0.0D, radius * 3.0D);
    }

    private static boolean isFootSupportPoint(AABB playerBox, Vec3 point, double radius) {
        double verticalBelow = Math.max(radius * 1.50D, 0.16D);
        double verticalAbove = Math.max(radius * 2.50D, 0.42D);
        if (point.y < playerBox.minY - verticalBelow || point.y > playerBox.minY + verticalAbove) {
            return false;
        }
        double margin = Math.max(radius + 0.04D, 0.12D);
        return point.x >= playerBox.minX - margin && point.x <= playerBox.maxX + margin
                && point.z >= playerBox.minZ - margin && point.z <= playerBox.maxZ + margin;
    }

    private static Vec3 orientTowardPlayer(Vec3 normal, Vec3 point, Vec3 playerCenter) {
        Vec3 toPlayer = playerCenter.subtract(point);
        if (toPlayer.lengthSqr() > NORMAL_EPSILON * NORMAL_EPSILON && normal.dot(toPlayer) < 0.0D) {
            return normal.scale(-1.0D);
        }
        return normal;
    }

    private static Vec3 normalize(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < NORMAL_EPSILON || !Double.isFinite(len))
            return null;
        return new Vec3(x / len, y / len, z / len);
    }

    private static void broadcast(ServerLevel level, List<RopeContactPulse.Entry> pulse) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_SENT_COUNT.get(key);
        if (pulse.isEmpty() && (last == null || last == 0))
            return;
        LAST_SENT_COUNT.put(key, pulse.size());
        PacketDistributor.sendToPlayersInDimension(level, new RopeContactPulse(pulse));
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

    private record ContactEstimate(double t, Vec3 point, Vec3 normal, double depth, double slack) {
    }

    private record BoxContact(Vec3 normal, double separation) {
    }

    private record AppliedPush(float x, float z, float mag, float gain) {
        private static final AppliedPush NONE = new AppliedPush(0.0F, 0.0F, 0.0F, 0.0F);
    }

    private record AcceptedClientContact(UUID ropeId, UUID playerId, long tick,
            float t, float dx, float dy, float dz, AppliedPush push) {
    }
}
