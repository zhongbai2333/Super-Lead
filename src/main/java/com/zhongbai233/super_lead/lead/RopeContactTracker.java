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
    private static final double FALLBACK_PUSH_SCALE = 0.60D;
    private static final double INPUT_AWAY_PUSH_BOOST = 0.80D;
    private static final double CONTACT_IMPULSE_DEADZONE = 0.01D;
    private static final double CONTACT_LEGACY_MAX_RECOIL_SCALE = 1.50D;
    private static final double CONTACT_SIDE_SOLID_RADIUS = 0.065D;
    private static final double CONTACT_SIDE_SPRING_SCALE = 0.55D;
    private static final double CONTACT_SIDE_MAX_RECOIL_SCALE = 0.70D;
    private static final double CONTACT_SIDE_DAMPING_SCALE = 0.12D;
    private static final double CONTACT_SLACK_DEPTH_ABSORB = 0.65D;
    private static final double CONTACT_SLACK_SOFTENING = 1.25D;
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
        double impulseScale = 1.0D;
        if (plausibleReport) {
            Vec3 normal = normalize(report.normalX(), report.normalY(), report.normalZ());
            Vec3 tangent = sanitizeTangent(report.tangentX(), report.tangentY(), report.tangentZ(), a, b);
            if (normal != null) {
                normal = orthogonalizeNormal(normal, tangent);
                normal = orientTowardPlayer(normal, reportedPoint, playerCenter);
                double slack = clamp(report.slack(), 0.0D, radius * 3.0D);
                contact = new ContactEstimate(clamp01(report.t()), reportedPoint, normal, tangent, reportDepth, slack);
            }
        }

        if (contact == null) {
            contact = fallbackContact(a, b, playerBox, radius, tuning, reportDepth * 0.50D);
            impulseScale = FALLBACK_PUSH_SCALE;
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

        double inputAway = inputAwayFactor(report.inputX(), report.inputZ(), contact.normal());
        // Only sync-zone ropes send contact reports; pass syncZone=true here.
        // If this method were ever called for a non-sync rope (preset blank),
        // the early-return above prevents reaching this point.
        AppliedPush push = applyVelocityThisTick
                ? applyContactImpulse(player, contact.normal(), contact.tangent(),
                        contact.depth(), contact.slack(), radius, tuning, impulseScale, inputAway, true)
                : (previous == null ? AppliedPush.NONE : previous.push());

        double visualMag = Math.min(Math.max(contact.depth() * 0.75D, push.mag() * 0.35D), VISUAL_MAX_DEFLECT);
        Vec3 n = contact.normal();
        dimContacts.put(key, new AcceptedClientContact(connection.id(), player.getUUID(), now,
                (float) contact.t(),
                (float) (-n.x * visualMag),
                (float) (-n.y * visualMag),
                (float) (-n.z * visualMag),
                push));
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
     * Applies a 3D contact impulse to the player.
     * <p>
     * Sync-zone ropes use a slack-aware, compressible-shell model.
     * Non-sync-zone ropes (legacy path) use the original simple formula
     * with no slack softening and a higher max-recoil cap.
     *
     * @param syncZone true when the rope belongs to a synced physics zone
     */
    private static AppliedPush applyContactImpulse(ServerPlayer player, Vec3 normal, Vec3 tangent,
            double depth, double slack, double radius, ServerPhysicsTuning tuning,
            double impulseScale, double inputAway, boolean syncZone) {
        if (!syncZone)
            return applyLegacyContactImpulse(player, normal, depth, radius, tuning, impulseScale, inputAway);
        return applySyncZoneContactImpulse(player, normal, depth, slack, radius, tuning, impulseScale, inputAway);
    }

    /** Original simple formula — used for ropes outside synced physics zones. */
    private static AppliedPush applyLegacyContactImpulse(ServerPlayer player, Vec3 normal,
            double depth, double radius, ServerPhysicsTuning tuning,
            double impulseScale, double inputAway) {
        if (depth <= 0.0D || radius <= 1.0e-6D)
            return AppliedPush.NONE;
        Vec3 n = normalize(normal.x, normal.y, normal.z);
        if (n == null)
            return AppliedPush.NONE;

        double depthRatio = clamp01(depth / radius);
        Vec3 v = player.getDeltaMovement();
        double normalSpeed = v.x * n.x + v.y * n.y + v.z * n.z;
        double cancelMag = Math.max(0.0D, -normalSpeed);
        double damping = clamp(tuning.velocityDamping(), 0.0D, 2.0D);
        cancelMag *= clamp01(damping * depthRatio);

        double pushBoost = 1.0D + Math.max(0.0D, inputAway) * INPUT_AWAY_PUSH_BOOST;
        double targetSeparationSpeed = Math.max(0.0D, tuning.springK()) * depthRatio * pushBoost * impulseScale;
        double currentSeparationSpeed = Math.max(0.0D, normalSpeed);
        double neededSeparation = Math.max(0.0D, targetSeparationSpeed - currentSeparationSpeed);
        if (neededSeparation < CONTACT_IMPULSE_DEADZONE)
            neededSeparation = 0.0D;

        double maxRecoil = Math.max(0.0D, tuning.maxRecoilPerTick()) * impulseScale * CONTACT_LEGACY_MAX_RECOIL_SCALE;
        double impulseMag = Math.min(cancelMag + neededSeparation, maxRecoil);

        double px = n.x * impulseMag;
        double py = n.y * impulseMag;
        double pz = n.z * impulseMag;
        double nextX = v.x + px;
        double nextY = v.y + py;
        double nextZ = v.z + pz;

        double totalPx = nextX - v.x;
        double totalPy = nextY - v.y;
        double totalPz = nextZ - v.z;
        double totalImpulse = Math.sqrt(totalPx * totalPx + totalPy * totalPy + totalPz * totalPz);
        if (totalImpulse <= 1.0e-6D)
            return AppliedPush.NONE;

        player.setDeltaMovement(nextX, nextY, nextZ);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        return new AppliedPush((float) totalPx, (float) totalPz, (float) totalImpulse, (float) depthRatio);
    }

    /**
     * Slack-aware, compressible-shell model — used for ropes inside synced physics
     * zones.
     */
    private static AppliedPush applySyncZoneContactImpulse(ServerPlayer player, Vec3 normal,
            double depth, double slack, double radius, ServerPhysicsTuning tuning,
            double impulseScale, double inputAway) {
        if (depth <= 0.0D || radius <= 1.0e-6D)
            return AppliedPush.NONE;
        Vec3 n = normalize(normal.x, normal.y, normal.z);
        if (n == null)
            return AppliedPush.NONE;

        double slackAllowance = clamp(slack, 0.0D, radius * 3.0D);
        double sideCompressionAllowance = Math.max(0.0D, radius - CONTACT_SIDE_SOLID_RADIUS);
        double effectiveDepth = Math.max(0.0D,
                depth - sideCompressionAllowance - slackAllowance * CONTACT_SLACK_DEPTH_ABSORB);
        double responseRadius = Math.max(CONTACT_SIDE_SOLID_RADIUS, radius * 0.25D);
        double depthRatio = clamp01(effectiveDepth / responseRadius);
        double rawDepthRatio = clamp01(depth / radius);
        double slackRatio = clamp01(slackAllowance / Math.max(radius, 1.0e-6D));
        double stiffness = 1.0D / (1.0D + slackRatio * CONTACT_SLACK_SOFTENING);

        Vec3 v = player.getDeltaMovement();
        double normalSpeed = v.x * n.x + v.y * n.y + v.z * n.z;
        double cancelMag = Math.max(0.0D, -normalSpeed);
        double damping = clamp(tuning.velocityDamping(), 0.0D, 2.0D);
        cancelMag *= clamp01(damping * depthRatio * CONTACT_SIDE_DAMPING_SCALE * stiffness);

        double pushBoost = 1.0D + Math.max(0.0D, inputAway) * INPUT_AWAY_PUSH_BOOST;
        double springGain = smoothstep(depthRatio);
        double targetSeparationSpeed = Math.max(0.0D, tuning.springK()) * springGain * stiffness
                * CONTACT_SIDE_SPRING_SCALE * pushBoost * impulseScale;
        double currentSeparationSpeed = Math.max(0.0D, normalSpeed);
        double neededSeparation = Math.max(0.0D, targetSeparationSpeed - currentSeparationSpeed);
        if (neededSeparation < CONTACT_IMPULSE_DEADZONE) {
            neededSeparation = 0.0D;
        }

        double baseMaxRecoil = Math.max(0.0D, tuning.maxRecoilPerTick()) * impulseScale;
        double maxRecoil = baseMaxRecoil * CONTACT_SIDE_MAX_RECOIL_SCALE;
        double impulseMag = Math.min(cancelMag + neededSeparation, maxRecoil);

        double px = n.x * impulseMag;
        double py = n.y * impulseMag;
        double pz = n.z * impulseMag;
        double nextX = v.x + px;
        double nextY = v.y + py;
        double nextZ = v.z + pz;

        double totalPx = nextX - v.x;
        double totalPy = nextY - v.y;
        double totalPz = nextZ - v.z;
        double totalImpulse = Math.sqrt(totalPx * totalPx + totalPy * totalPy + totalPz * totalPz);
        if (totalImpulse <= 1.0e-6D)
            return AppliedPush.NONE;

        player.setDeltaMovement(nextX, nextY, nextZ);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        return new AppliedPush((float) totalPx, (float) totalPz, (float) totalImpulse, (float) rawDepthRatio);
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
        return new ContactEstimate(t, point, box.normal(), fallbackTangent(a, b), depth,
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
        double dist = a.distanceTo(b);
        double freeSlack = Math.max(0.0D, dist * (tuning.slackTight() - 1.0D));
        double midspan = Math.sin(Math.PI * clamp01(t));
        return clamp(freeSlack * midspan, 0.0D, radius * 3.0D);
    }

    private static double smoothstep(double x) {
        x = clamp01(x);
        return x * x * x * (x * (x * 6.0D - 15.0D) + 10.0D);
    }

    private static double inputAwayFactor(double inputX, double inputZ, Vec3 normal) {
        double inputLen = Math.sqrt(inputX * inputX + inputZ * inputZ);
        double normalLen = Math.sqrt(normal.x * normal.x + normal.z * normal.z);
        if (inputLen < NORMAL_EPSILON || normalLen < NORMAL_EPSILON)
            return 0.0D;
        return clamp01((inputX / inputLen) * (normal.x / normalLen)
                + (inputZ / inputLen) * (normal.z / normalLen));
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

    private record ContactEstimate(double t, Vec3 point, Vec3 normal, Vec3 tangent, double depth, double slack) {
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
