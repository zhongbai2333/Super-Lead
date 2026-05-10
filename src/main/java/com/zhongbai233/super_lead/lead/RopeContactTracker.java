package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.preset.PhysicsZone;
import com.zhongbai233.super_lead.preset.PhysicsZoneSavedData;
import com.zhongbai233.super_lead.preset.RopePreset;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative rope-vs-player contact tracker.
 * <p>
 * Keeps a per-connection {@link ServerRopeVerlet} alive only while a player is nearby; that
 * sim is the source of truth for rope geometry that the broadcast pulse references, so all
 * observers see the same bend regardless of their local client physics state.
 * Players are pushed back proportionally to penetration depth (with several gates against
 * the "phantom wall" feeling), and a {@link RopeContactPulse} carrying the deflection of
 * every active contact is broadcast to the dimension when the set changes.
 */
public final class RopeContactTracker {
    // Geometry tuning (in blocks)
    private static final double ROPE_RADIUS         = 0.10D;
    /** Soft gameplay thickness around the rope when testing it against the player's AABB. */
    private static final double CONTACT_RADIUS      = ROPE_RADIUS + 0.08D;
    private static final double VISUAL_MAX_DEFLECT  = 0.50D;

    // Push-back tuning. A walking player should just feel slowed when leaning into the rope,
    // not knocked back like a wall.
    private static final double SPRING_K            = ClientTuning.CONTACT_SPRING.defaultValue;
    private static final double VELOCITY_DAMPING    = ClientTuning.CONTACT_VELOCITY_DAMPING.defaultValue;
    private static final double MAX_RECOIL_PER_TICK = ClientTuning.CONTACT_MAX_RECOIL_PER_TICK.defaultValue;
    private static final double ROPE_CONTACT_DEFLECT_GAIN = 1.25D;
    private static final double MIN_PUSH_DEPTH      = 0.01D;
    private static final double MIN_APPROACH_SPEED  = 0.005D;

    // A rope is "active" (its Verlet sim is stepped) only when a player is within
    // ACTIVE_RADIUS_PADDING + halfRopeLength of the rope's midpoint. Keeps per-tick cost
    // proportional to ropes near players, not the total in the world.
    private static final double ACTIVE_RADIUS_PADDING = 16.0D;
    // After leaving range a sim is kept alive for a few ticks so brief LOD-out / LOD-in doesn't
    // tear down and re-create state every other second.
    private static final long   IDLE_RETENTION_TICKS = 40L;

    // Per-dimension memory of the last broadcast: send an "all-clear" exactly once after the
    // last contact ends instead of spamming empty pulses every tick.
    private static final Map<NetworkKey, Integer> LAST_SENT_COUNT = new HashMap<>();
    // Per-connection live Verlet sim and tick of its last use.
    private static final Map<UUID, Cached> SIMS = new HashMap<>();
    // Rope-player contact side memory. Once the rope penetrates the player's AABB the nearest
    // face can flip every tick around the player centre; keeping the last contact normal makes
    // the authoritative rope stay on one side until contact is actually released.
    private static final Map<ContactKey, ContactMemory> CONTACT_MEMORY = new HashMap<>();
    private static final long CONTACT_MEMORY_TTL_TICKS = 8L;

    // Per-player debug particle viewers. This draws the authoritative server Verlet rope, not
    // the client simulation, so operators can compare "what the server collides against" with
    // what the client renders.
    private static final Map<UUID, DebugView> DEBUG_VIEWERS = new HashMap<>();
    private static final DustParticleOptions DEBUG_SEGMENT_DUST = new DustParticleOptions(0xFF44FF, 1.0F);
    private static final DustParticleOptions DEBUG_NODE_DUST = new DustParticleOptions(0xFFFF55, 1.0F);
    private static final DustParticleOptions DEBUG_START_DUST = new DustParticleOptions(0x55FF55, 1.25F);
    private static final DustParticleOptions DEBUG_END_DUST = new DustParticleOptions(0x55AAFF, 1.25F);
    private static final double DEBUG_PARTICLE_STEP = 0.25D;
    private static final int DEBUG_MAX_ROPES_PER_VIEWER = 96;
    private static final int DEBUG_MAX_PARTICLES_PER_VIEWER = 2400;

    // Server Verlet shape is broadcast to clients every NODES_BROADCAST_INTERVAL ticks. The
    // client interpolates between snapshots via its own physics, so even 10 Hz feels smooth.
    // At 8 segments / 7 interior nodes that's ~100 B per active rope.
    private static final int NODES_BROADCAST_INTERVAL = 2;
    // Per-dimension memory of how many ropes were in the last nodes pulse, so we can send one
    // empty pulse to clear stale snapshots when activity drops to zero.
    private static final Map<NetworkKey, Integer> LAST_NODES_COUNT = new HashMap<>();

    // ---- Bench instrumentation -------------------------------------------------------------
    // Cheap stats consumed by the /superlead bench command. Overwritten once per server tick.
    private static volatile long lastTickNanos;
    private static volatile int  lastActiveSims;
    private static volatile int  lastSteppedSims;
    private static volatile int  lastContacts;

    public static long lastTickNanos()  { return lastTickNanos; }
    public static int  lastActiveSims() { return lastActiveSims; }
    public static int  lastSteppedSims(){ return lastSteppedSims; }
    public static int  lastContacts()   { return lastContacts; }

    private RopeContactTracker() {}

    public static void tickRopeContacts(ServerLevel level) {
        long t0 = System.nanoTime();
        try {
            tickInternal(level);
        } finally {
            lastTickNanos = System.nanoTime() - t0;
        }
    }

    private static void tickInternal(ServerLevel level) {
        long now = level.getGameTime();
        List<LeadConnection> connections = SuperLeadSavedData.get(level).connections();
        List<ServerPlayer> players = level.players();
        boolean broadcastNodes = (now % NODES_BROADCAST_INTERVAL) == 0L;

        if (!Config.allowOpVisualPresets()) {
            SIMS.clear();
            CONTACT_MEMORY.clear();
            maybeBroadcastEmpty(level);
            if (broadcastNodes) maybeBroadcastEmptyNodes(level);
            lastActiveSims = SIMS.size();
            lastSteppedSims = 0;
            lastContacts = 0;
            return;
        }

        List<PhysicsZone> zones = PhysicsZoneSavedData.get(level).zones();

        if (players.isEmpty()) {
            evictIdle(now);
            CONTACT_MEMORY.clear();
            maybeBroadcastEmpty(level);
            if (broadcastNodes) maybeBroadcastEmptyNodes(level);
            lastActiveSims = SIMS.size();
            lastSteppedSims = 0;
            lastContacts = 0;
            return;
        }

        if (zones.isEmpty()) {
            dropLevelSims(connections);
            CONTACT_MEMORY.clear();
            maybeBroadcastEmpty(level);
            if (broadcastNodes) maybeBroadcastEmptyNodes(level);
            lastActiveSims = SIMS.size();
            lastSteppedSims = 0;
            lastContacts = 0;
            return;
        }

        Set<UUID> connectionIds = new HashSet<>(connections.size() * 2);
        List<RopeContactPulse.Entry> pulse = new ArrayList<>();
        List<RopeNodesPulse.Entry> nodesPulse = broadcastNodes ? new ArrayList<>() : null;
        int nodesSegments = ServerRopeVerlet.DEFAULT_SEGMENTS;
        int interiorCount = (nodesSegments - 1) * 3;
        int stepped = 0;
        Map<String, ServerPhysicsTuning> tuningByPreset = new HashMap<>();

        double[] scratch = new double[9];
        for (LeadConnection connection : connections) {
            if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE) continue;
            connectionIds.add(connection.id());

            Vec3 a = connection.from().attachmentPoint(level);
            Vec3 b = connection.to().attachmentPoint(level);
            if (a == null || b == null) continue;
            double dist = a.distanceTo(b);
            if (dist < 1.0e-3D) continue;

            double activeR  = ACTIVE_RADIUS_PADDING + dist * 0.5D;
            double activeR2 = activeR * activeR;
            Vec3 mid = a.add(b).scale(0.5D);
            PhysicsZone zone = findZoneForRope(zones, a, b);
            if (zone == null) {
                SIMS.remove(connection.id());
                removeContactMemory(connection.id());
                continue;
            }
            ServerPhysicsTuning tuning = tuningByPreset.computeIfAbsent(zone.presetName(),
                    preset -> loadServerPhysicsTuning(level, preset));
            if (!tuning.physicsEnabled()) {
                SIMS.remove(connection.id());
                removeContactMemory(connection.id());
                continue;
            }
            boolean active = false;
            for (ServerPlayer p : players) {
                if (p.distanceToSqr(mid.x, mid.y, mid.z) <= activeR2) { active = true; break; }
            }
            Cached cached = SIMS.get(connection.id());
            if (!active) {
                if (cached != null && now - cached.lastSeenTick > IDLE_RETENTION_TICKS) {
                    SIMS.remove(connection.id());
                    removeContactMemory(connection.id());
                }
                continue;
            }
            if (cached == null) {
                cached = new Cached(new ServerRopeVerlet());
                cached.sim.reset(a, b);
                SIMS.put(connection.id(), cached);
            }
            cached.lastSeenTick = now;

            cached.sim.step(a, b, tuning.gravity(), tuning.damping(), tuning.slackFactor(a, b), tuning.iterations());
            stepped++;

            // Coarse rope AABB for cheap player-vs-rope reject.
            double contactRadius = tuning.contactRadius();
            double pad = contactRadius + 0.5D;
            double sagBudget = dist * 0.10D + 0.5D;
            AABB ropeAabb = new AABB(
                    Math.min(a.x, b.x) - pad, Math.min(a.y, b.y) - sagBudget - pad, Math.min(a.z, b.z) - pad,
                    Math.max(a.x, b.x) + pad, Math.max(a.y, b.y) + pad,             Math.max(a.z, b.z) + pad);

            for (ServerPlayer player : players) {
                if (player.isSpectator()) continue;
                AABB pb = player.getBoundingBox();
                if (!ropeAabb.intersects(pb)) continue;

                double dSqr = closestPointToPlayerAabb(cached.sim, pb, scratch);
                if (dSqr == Double.POSITIVE_INFINITY) continue;
                double separation = scratch[8];
                if (separation >= contactRadius) continue;
                if (!zone.contains(scratch[0], scratch[1], scratch[2])) continue;

                stabilizeContactNormal(connection.id(), player, scratch, now);
                double t  = scratch[3];
                double nx = scratch[4];
                double nz = scratch[5];
                if (nx == 0.0D && nz == 0.0D) continue;

                double depth = contactRadius - separation;

                Vec3 v = player.getDeltaMovement();
                double inwardSpeed = Math.max(0.0D, -(v.x * nx + v.z * nz));
                if (tuning.pushbackEnabled() && (depth > MIN_PUSH_DEPTH || inwardSpeed > MIN_APPROACH_SPEED)) {
                    double mag = Math.min(depth * tuning.springK() + inwardSpeed * tuning.velocityDamping(),
                        tuning.maxRecoilPerTick());
                    player.setDeltaMovement(v.x + nx * mag, v.y, v.z + nz * mag);
                    player.hurtMarked = true;
                    player.connection.send(new ClientboundSetEntityMotionPacket(player));
                }

                double visualMag = Math.min(depth * 2.25D, VISUAL_MAX_DEFLECT);
                // Once the rope is already inside the player's box, limiting server deflection
                // to the cosmetic pulse cap is not enough to get it out again. Allow a deeper
                // authoritative correction while keeping the rendered impulse conservative.
                double ropeDeflectCap = Math.max(VISUAL_MAX_DEFLECT, contactRadius * 2.75D);
                double ropeDeflect = Math.min(depth * ROPE_CONTACT_DEFLECT_GAIN, ropeDeflectCap);
                // The normal points from rope -> player. Move the authoritative server rope in
                // the opposite direction so future collision tests use the visibly displaced rope
                // instead of a straight hidden stick.
                cached.sim.applyContact(a, b, t, -nx * ropeDeflect, 0.0D, -nz * ropeDeflect,
                        tuning.iterations());
                pulse.add(new RopeContactPulse.Entry(
                        connection.id(),
                        (float) t,
                        (float) (-nx * visualMag),
                        0.0F,
                        (float) (-nz * visualMag)));
            }

            if (nodesPulse != null) {
                addNodesPulse(nodesPulse, connection.id(), cached.sim, nodesSegments, interiorCount);
            }
        }

        // Drop sims whose connection is gone.
        Iterator<Map.Entry<UUID, Cached>> it = SIMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Cached> e = it.next();
            if (!connectionIds.contains(e.getKey())
                    && now - e.getValue().lastSeenTick > IDLE_RETENTION_TICKS) {
                it.remove();
                removeContactMemory(e.getKey());
            }
        }

        pruneContactMemory(now);

        broadcast(level, pulse);
        if (nodesPulse != null) broadcastNodes(level, nodesSegments, nodesPulse);
        emitDebugParticles(level, now);

        lastActiveSims  = SIMS.size();
        lastSteppedSims = stepped;
        lastContacts    = pulse.size();
    }

    public static void enableServerRopeDebug(ServerPlayer player, double range, int intervalTicks) {
        double clampedRange = Math.max(2.0D, Math.min(256.0D, range));
        int clampedInterval = Math.max(1, Math.min(40, intervalTicks));
        DEBUG_VIEWERS.put(player.getUUID(), new DebugView(clampedRange, clampedInterval));
    }

    public static boolean disableServerRopeDebug(ServerPlayer player) {
        return DEBUG_VIEWERS.remove(player.getUUID()) != null;
    }

    public static DebugInfo serverRopeDebugInfo(ServerPlayer player) {
        DebugView view = DEBUG_VIEWERS.get(player.getUUID());
        return new DebugInfo(view != null, view == null ? 0.0D : view.range(),
                view == null ? 0 : view.intervalTicks(), SIMS.size());
    }

    private static void emitDebugParticles(ServerLevel level, long now) {
        if (DEBUG_VIEWERS.isEmpty() || SIMS.isEmpty()) return;
        for (ServerPlayer viewer : level.players()) {
            DebugView view = DEBUG_VIEWERS.get(viewer.getUUID());
            if (view == null || (now % view.intervalTicks()) != 0L) continue;
            double rangeSqr = view.range() * view.range();
            int ropes = 0;
            int particles = 0;
            for (Cached cached : SIMS.values()) {
                ServerRopeVerlet sim = cached.sim;
                if (viewer.distanceToSqr(simMidX(sim), simMidY(sim), simMidZ(sim)) > rangeSqr) continue;
                particles += drawServerRope(level, viewer, sim, DEBUG_MAX_PARTICLES_PER_VIEWER - particles);
                ropes++;
                if (ropes >= DEBUG_MAX_ROPES_PER_VIEWER || particles >= DEBUG_MAX_PARTICLES_PER_VIEWER) break;
            }
        }
    }

    private static int drawServerRope(ServerLevel level, ServerPlayer viewer, ServerRopeVerlet sim, int budget) {
        if (budget <= 0) return 0;
        int emitted = 0;
        for (int i = 0; i < sim.nodes(); i++) {
            DustParticleOptions dust = i == 0 ? DEBUG_START_DUST
                    : (i == sim.nodes() - 1 ? DEBUG_END_DUST : DEBUG_NODE_DUST);
            if (sendParticle(level, viewer, dust, sim.nodeX(i), sim.nodeY(i), sim.nodeZ(i))) emitted++;
            if (emitted >= budget) return emitted;
        }
        for (int i = 0; i < sim.nodes() - 1; i++) {
            emitted += drawLine(level, viewer,
                    sim.nodeX(i), sim.nodeY(i), sim.nodeZ(i),
                    sim.nodeX(i + 1), sim.nodeY(i + 1), sim.nodeZ(i + 1),
                    budget - emitted);
            if (emitted >= budget) return emitted;
        }
        return emitted;
    }

    private static int drawLine(ServerLevel level, ServerPlayer viewer,
            double ax, double ay, double az, double bx, double by, double bz, int budget) {
        if (budget <= 0) return 0;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / DEBUG_PARTICLE_STEP));
        int emitted = 0;
        for (int i = 0; i <= steps && emitted < budget; i++) {
            double t = i / (double) steps;
            if (sendParticle(level, viewer, DEBUG_SEGMENT_DUST,
                    ax + dx * t, ay + dy * t, az + dz * t)) {
                emitted++;
            }
        }
        return emitted;
    }

    private static boolean sendParticle(ServerLevel level, ServerPlayer viewer, DustParticleOptions dust,
            double x, double y, double z) {
        return level.sendParticles(dust, true, true, x, y, z,
            1, 0.0D, 0.0D, 0.0D, 0.0D) > 0;
    }

    private static double simMidX(ServerRopeVerlet sim) {
        int last = sim.nodes() - 1;
        return (sim.nodeX(0) + sim.nodeX(last)) * 0.5D;
    }

    private static double simMidY(ServerRopeVerlet sim) {
        int last = sim.nodes() - 1;
        return (sim.nodeY(0) + sim.nodeY(last)) * 0.5D;
    }

    private static double simMidZ(ServerRopeVerlet sim) {
        int last = sim.nodes() - 1;
        return (sim.nodeZ(0) + sim.nodeZ(last)) * 0.5D;
    }

    private static void addNodesPulse(List<RopeNodesPulse.Entry> nodesPulse, UUID id,
            ServerRopeVerlet sim, int nodesSegments, int interiorCount) {
        // Only sims at the canonical segment count fit into a single-shape pulse; right now
        // everything uses the default, so this is just an assertion. If we ever vary segment
        // counts per rope we'll need a per-entry segments field.
        if (sim.segments() != nodesSegments) return;
        float[] arr = new float[interiorCount];
        for (int n = 1, w = 0; n < sim.nodes() - 1; n++) {
            arr[w++] = (float) sim.nodeX(n);
            arr[w++] = (float) sim.nodeY(n);
            arr[w++] = (float) sim.nodeZ(n);
        }
        nodesPulse.add(new RopeNodesPulse.Entry(id, arr));
    }

    /**
     * Closest point from the server rope polyline to the player's full collision box.
     * <p>
     * The old point-vs-circle test used the player's centre at one fixed height, which made
     * diagonal/corner approaches and low/high ropes miss unless the angle was oddly perfect.
     * This tests against the real AABB instead and returns a horizontal normal that pushes the
     * player away from the rope.
     *
    * @param out [ropeX, ropeY, ropeZ, ropeT, normalX, normalZ, closestBoxX, closestBoxZ,
    *            signedSeparation]. The separation is negative when the rope point is already
    *            inside the player's AABB, with magnitude equal to the shortest horizontal exit.
     */
    private static double closestPointToPlayerAabb(ServerRopeVerlet sim, AABB box, double[] out) {
        double totalLen = 0.0D;
        for (int s = 0; s < sim.segments(); s++) {
            double sx = sim.nodeX(s + 1) - sim.nodeX(s);
            double sy = sim.nodeY(s + 1) - sim.nodeY(s);
            double sz = sim.nodeZ(s + 1) - sim.nodeZ(s);
            totalLen += Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        if (totalLen < 1.0e-6D) return Double.POSITIVE_INFINITY;

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double walked = 0.0D;
        double bestDistSqr = Double.POSITIVE_INFINITY;
        double bestSegDx = 0.0D;
        double bestSegDz = 0.0D;

        for (int s = 0; s < sim.segments(); s++) {
            double ax = sim.nodeX(s), ay = sim.nodeY(s), az = sim.nodeZ(s);
            double bx = sim.nodeX(s + 1), by = sim.nodeY(s + 1), bz = sim.nodeZ(s + 1);
            double sx = bx - ax, sy = by - ay, sz = bz - az;
            double segLenSqr = sx * sx + sy * sy + sz * sz;
            double segLen = Math.sqrt(segLenSqr);
            double u = 0.0D;
            if (segLenSqr > 1.0e-9D) {
                u = ((centerX - ax) * sx + (centerY - ay) * sy + (centerZ - az) * sz) / segLenSqr;
                u = clamp01(u);
            }

            double qx = ax, qy = ay, qz = az;
            double cx = centerX, cy = centerY, cz = centerZ;
            for (int it = 0; it < 4; it++) {
                qx = ax + sx * u;
                qy = ay + sy * u;
                qz = az + sz * u;
                cx = clamp(qx, box.minX, box.maxX);
                cy = clamp(qy, box.minY, box.maxY);
                cz = clamp(qz, box.minZ, box.maxZ);
                if (segLenSqr <= 1.0e-9D) break;
                double next = ((cx - ax) * sx + (cy - ay) * sy + (cz - az) * sz) / segLenSqr;
                next = clamp01(next);
                if (Math.abs(next - u) < 1.0e-6D) {
                    u = next;
                    qx = ax + sx * u;
                    qy = ay + sy * u;
                    qz = az + sz * u;
                    cx = clamp(qx, box.minX, box.maxX);
                    cy = clamp(qy, box.minY, box.maxY);
                    cz = clamp(qz, box.minZ, box.maxZ);
                    break;
                }
                u = next;
            }

            double dx = cx - qx;
            double dy = cy - qy;
            double dz = cz - qz;
            double dSqr = dx * dx + dy * dy + dz * dz;
            if (dSqr < bestDistSqr) {
                bestDistSqr = dSqr;
                out[0] = qx;
                out[1] = qy;
                out[2] = qz;
                out[3] = (walked + segLen * u) / totalLen;
                out[6] = cx;
                out[7] = cz;
                bestSegDx = sx;
                bestSegDz = sz;
            }
            walked += segLen;
        }

        if (bestDistSqr == Double.POSITIVE_INFINITY) return bestDistSqr;
    boolean inside = out[0] >= box.minX && out[0] <= box.maxX
        && out[1] >= box.minY && out[1] <= box.maxY
        && out[2] >= box.minZ && out[2] <= box.maxZ;
    if (inside) {
        double toMinX = out[0] - box.minX;
        double toMaxX = box.maxX - out[0];
        double toMinZ = out[2] - box.minZ;
        double toMaxZ = box.maxZ - out[2];
        double exit = toMinX;
        double nx = 1.0D;
        double nz = 0.0D;
        if (toMaxX < exit) { exit = toMaxX; nx = -1.0D; nz = 0.0D; }
        if (toMinZ < exit) { exit = toMinZ; nx = 0.0D; nz = 1.0D; }
        if (toMaxZ < exit) { exit = toMaxZ; nx = 0.0D; nz = -1.0D; }
        out[4] = nx;
        out[5] = nz;
        out[8] = -Math.max(0.0D, exit);
        return bestDistSqr;
    }

        double nx = out[6] - out[0];
        double nz = out[7] - out[2];
        double nLen = Math.sqrt(nx * nx + nz * nz);
        if (nLen < 1.0e-5D) {
            nx = centerX - out[0];
            nz = centerZ - out[2];
            nLen = Math.sqrt(nx * nx + nz * nz);
        }
        if (nLen < 1.0e-5D) {
            double segLen = Math.sqrt(bestSegDx * bestSegDx + bestSegDz * bestSegDz);
            if (segLen > 1.0e-5D) {
                nx = -bestSegDz / segLen;
                nz = bestSegDx / segLen;
                nLen = 1.0D;
            }
        }
        if (nLen < 1.0e-5D) {
            out[4] = 0.0D;
            out[5] = 0.0D;
        } else {
            out[4] = nx / nLen;
            out[5] = nz / nLen;
        }
        out[8] = Math.sqrt(bestDistSqr);
        return bestDistSqr;
    }

    private static void stabilizeContactNormal(UUID ropeId, ServerPlayer player, double[] scratch, long now) {
        double nx = scratch[4];
        double nz = scratch[5];
        double nLen = Math.sqrt(nx * nx + nz * nz);
        ContactKey key = new ContactKey(ropeId, player.getUUID());
        ContactMemory previous = CONTACT_MEMORY.get(key);

        if (nLen < 1.0e-5D) {
            if (previous != null && now - previous.lastTick() <= CONTACT_MEMORY_TTL_TICKS) {
                scratch[4] = previous.nx();
                scratch[5] = previous.nz();
                CONTACT_MEMORY.put(key, new ContactMemory(previous.nx(), previous.nz(), now));
            }
            return;
        }

        nx /= nLen;
        nz /= nLen;

        Vec3 velocity = player.getDeltaMovement();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (previous == null && scratch[8] < 0.0D && speed > 1.0e-4D) {
            // If the first sampled contact is already inside the player, prefer the side the
            // player came from over an arbitrary nearest-face tie.
            nx = -velocity.x / speed;
            nz = -velocity.z / speed;
        } else if (previous != null && now - previous.lastTick() <= CONTACT_MEMORY_TTL_TICKS) {
            double dot = nx * previous.nx() + nz * previous.nz();
            if (scratch[8] < 0.0D || dot < 0.25D) {
                nx = previous.nx();
                nz = previous.nz();
            } else if (dot < 0.985D) {
                nx = previous.nx() * 0.65D + nx * 0.35D;
                nz = previous.nz() * 0.65D + nz * 0.35D;
                double len = Math.sqrt(nx * nx + nz * nz);
                if (len > 1.0e-5D) {
                    nx /= len;
                    nz /= len;
                }
            }
        }

        scratch[4] = nx;
        scratch[5] = nz;
        CONTACT_MEMORY.put(key, new ContactMemory(nx, nz, now));
    }

    private static void removeContactMemory(UUID ropeId) {
        CONTACT_MEMORY.keySet().removeIf(key -> key.ropeId().equals(ropeId));
    }

    private static void pruneContactMemory(long now) {
        CONTACT_MEMORY.entrySet().removeIf(e -> now - e.getValue().lastTick() > CONTACT_MEMORY_TTL_TICKS);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
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

    private static boolean contains(AABB box, Vec3 p) {
        return p.x >= box.minX && p.x < box.maxX
                && p.y >= box.minY && p.y < box.maxY
                && p.z >= box.minZ && p.z < box.maxZ;
    }

    private record ServerPhysicsTuning(
            boolean physicsEnabled,
            double gravity,
            double damping,
            double slackTight,
            int iterations,
            boolean pushbackEnabled,
            double contactRadius,
            double springK,
            double velocityDamping,
            double maxRecoilPerTick) {
        double slackFactor(Vec3 a, Vec3 b) {
            // Match the client-side rule: gravity=0 means taut/straight even if a slack value is
            // configured, otherwise the extra rest length is what lets the server rope actually sag.
            if (Math.abs(gravity) < 1.0e-9D) return 1.0D;
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double dist = a.distanceTo(b);
            if (dist < 1.0e-6D) return 1.0D;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double t = Math.min(1.0D, horizontal / (dist * 0.45D));
            return 1.0D + (Math.max(1.0D, slackTight) - 1.0D) * t;
        }
    }

    private static ServerPhysicsTuning loadServerPhysicsTuning(ServerLevel level, String presetName) {
        Map<String, String> overrides = RopePresetLibrary.forServer(level.getServer())
                .load(presetName)
                .map(RopePreset::overrides)
                .orElse(Map.of());
        boolean physicsEnabled = parseBool(overrides.get(ClientTuning.MODE_PHYSICS.id),
                ClientTuning.MODE_PHYSICS.defaultValue);
        double gravity = parseServerGravity(overrides.get(ClientTuning.GRAVITY.id));
        double damping = parseDouble(overrides.get(ClientTuning.DAMPING.id),
                ClientTuning.DAMPING, ServerRopeVerlet.DEFAULT_DAMPING);
        double slackTight = parseDouble(overrides.get(ClientTuning.SLACK_TIGHT.id),
                ClientTuning.SLACK_TIGHT, ClientTuning.SLACK_TIGHT.defaultValue);
        int iterations = parseInt(overrides.get(ClientTuning.ITER_CONTACT.id),
                ClientTuning.ITER_CONTACT, ServerRopeVerlet.DEFAULT_ITERATIONS);
        boolean pushbackEnabled = parseBool(overrides.get(ClientTuning.CONTACT_PUSHBACK.id),
                ClientTuning.CONTACT_PUSHBACK.defaultValue);
        double contactRadius = parseDouble(overrides.get(ClientTuning.CONTACT_RADIUS.id),
                ClientTuning.CONTACT_RADIUS, CONTACT_RADIUS);
        double springK = parseDouble(overrides.get(ClientTuning.CONTACT_SPRING.id),
                ClientTuning.CONTACT_SPRING, SPRING_K);
        double velocityDamping = parseDouble(overrides.get(ClientTuning.CONTACT_VELOCITY_DAMPING.id),
                ClientTuning.CONTACT_VELOCITY_DAMPING, VELOCITY_DAMPING);
        double maxRecoilPerTick = parseDouble(overrides.get(ClientTuning.CONTACT_MAX_RECOIL_PER_TICK.id),
                ClientTuning.CONTACT_MAX_RECOIL_PER_TICK, MAX_RECOIL_PER_TICK);
        return new ServerPhysicsTuning(physicsEnabled, gravity, damping, slackTight, iterations,
                pushbackEnabled, contactRadius, springK, velocityDamping, maxRecoilPerTick);
    }

    private static double parseDouble(String raw, TuningKey<Double> key, double fallback) {
        if (raw == null) return fallback;
        try {
            double value = key.type.parse(raw);
            return key.type.validate(value) ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseInt(String raw, TuningKey<Integer> key, int fallback) {
        if (raw == null) return fallback;
        try {
            int value = key.type.parse(raw);
            return key.type.validate(value) ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseServerGravity(String raw) {
        if (raw == null) return ServerRopeVerlet.DEFAULT_GRAVITY;
        try {
            double clientGravity = ClientTuning.GRAVITY.type.parse(raw);
            double clientDefault = ClientTuning.GRAVITY.defaultValue;
            if (Math.abs(clientDefault) < 1.0e-9D) return clientGravity;
            return clientGravity * (ServerRopeVerlet.DEFAULT_GRAVITY / clientDefault);
        } catch (Exception ignored) {
            return ServerRopeVerlet.DEFAULT_GRAVITY;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null) return fallback;
        try {
            return ClientTuning.MODE_PHYSICS.type.parse(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void dropLevelSims(List<LeadConnection> connections) {
        for (LeadConnection connection : connections) {
            if (connection.kind() == LeadKind.NORMAL || connection.kind() == LeadKind.REDSTONE) {
                SIMS.remove(connection.id());
                removeContactMemory(connection.id());
            }
        }
    }

    private static void broadcastNodes(ServerLevel level, int segments, List<RopeNodesPulse.Entry> entries) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_NODES_COUNT.get(key);
        if (entries.isEmpty() && (last == null || last == 0)) return;
        LAST_NODES_COUNT.put(key, entries.size());
        PacketDistributor.sendToPlayersInDimension(level, new RopeNodesPulse(segments, entries));
    }

    private static void maybeBroadcastEmptyNodes(ServerLevel level) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_NODES_COUNT.get(key);
        if (last != null && last > 0) {
            LAST_NODES_COUNT.put(key, 0);
            PacketDistributor.sendToPlayersInDimension(
                    level, new RopeNodesPulse(ServerRopeVerlet.DEFAULT_SEGMENTS, List.of()));
        }
    }

    private static void evictIdle(long now) {
        Iterator<Map.Entry<UUID, Cached>> it = SIMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Cached> e = it.next();
            if (now - e.getValue().lastSeenTick > IDLE_RETENTION_TICKS) {
                it.remove();
                removeContactMemory(e.getKey());
            }
        }
    }

    private static void maybeBroadcastEmpty(ServerLevel level) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_SENT_COUNT.get(key);
        if (last != null && last > 0) {
            LAST_SENT_COUNT.put(key, 0);
            PacketDistributor.sendToPlayersInDimension(level, new RopeContactPulse(List.of()));
        }
    }

    private static void broadcast(ServerLevel level, List<RopeContactPulse.Entry> pulse) {
        NetworkKey key = NetworkKey.of(level);
        Integer last = LAST_SENT_COUNT.get(key);
        if (pulse.isEmpty() && (last == null || last == 0)) return;
        LAST_SENT_COUNT.put(key, pulse.size());
        PacketDistributor.sendToPlayersInDimension(level, new RopeContactPulse(pulse));
    }

    /** Forget per-dimension state for a level (e.g. on shutdown). */
    public static void clear(ServerLevel level) {
        NetworkKey key = NetworkKey.of(level);
        LAST_SENT_COUNT.remove(key);
        LAST_NODES_COUNT.remove(key);
        CONTACT_MEMORY.clear();
    }

    /** Drop all cached sims (used by the bench harness so it can reset between runs). */
    public static void clearAllSims() {
        SIMS.clear();
        CONTACT_MEMORY.clear();
    }

    private static final class Cached {
        final ServerRopeVerlet sim;
        long lastSeenTick;
        Cached(ServerRopeVerlet sim) { this.sim = sim; }
    }

    private record DebugView(double range, int intervalTicks) {}

    private record ContactKey(UUID ropeId, UUID playerId) {}

    private record ContactMemory(double nx, double nz, long lastTick) {}

    public record DebugInfo(boolean enabled, double range, int intervalTicks, int activeServerRopes) {}
}
