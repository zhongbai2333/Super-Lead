package com.zhongbai233.super_lead.lead;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lets parrots use synced-physics ropes as lightweight perches. The server owns
 * the actual entity state; clients infer the visual rope weight from the
 * parrot's
 * replicated position.
 */
public final class ParrotRopePerchController {
    private static final double SEARCH_RADIUS = 10.0D;
    private static final double SEARCH_RADIUS_SQR = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final double ARRIVE_DISTANCE_SQR = 0.32D * 0.32D;
    private static final double FINAL_APPROACH_DISTANCE_SQR = 1.35D * 1.35D;
    private static final double PERCH_Y_OFFSET = 0.055D;
    private static final double APPROACH_SPEED = 0.105D;
    private static final double MAX_APPROACH_SPEED = 0.22D;
    private static final double OCCUPIED_T_DISTANCE = 0.075D;
    private static final double MAX_TANGENT_Y = 0.58D;
    private static final double SPOOK_PLAYER_RADIUS_SQR = 0.85D * 0.85D;
    private static final double SPOOK_PLAYER_SPEED_SQR = 0.08D * 0.08D;
    private static final double LAND_ATTEMPT_CHANCE = 0.45D;
    private static final double BOOSTED_LAND_ATTEMPT_CHANCE = 0.90D;
    private static final double RANDOM_TAKEOFF_CHANCE = 1.0D / 520.0D;
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_ROPE_SAMPLES = 14;
    private static final int MIN_ROPE_SAMPLES = 5;
    private static final int LAND_RETRY_COOLDOWN_TICKS = 120;
    private static final int SOFT_RELEASE_COOLDOWN_TICKS = 160;
    private static final int STARTLED_COOLDOWN_TICKS = 180;
    private static final int STARTLED_COOLDOWN_TICKS_NAV_FAIL = 300;
    private static final int RANDOM_TAKEOFF_COOLDOWN_TICKS = 260;
    private static final int RANDOM_TAKEOFF_MIN_PERCH_TICKS = 100;
    private static final int APPROACH_NAVIGATION_GRACE_TICKS = 20;
    private static final int APPROACH_TIMEOUT_TICKS = 200;
    private static final int SCAN_APPROACHING_GRACE_TICKS = 120;

    private static final Map<ResourceKey<Level>, Map<UUID, PerchState>> PERCHES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> COOLDOWNS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> APPROACH_START_TICK = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> BOOSTED_ROPES = new HashMap<>();
    private static final int BOOST_DURATION_TICKS = 600;
    private static final EntityTypeTest<Entity, Parrot> PARROTS = EntityTypeTest.forClass(Parrot.class);

    private ParrotRopePerchController() {
    }

    public static void tick(ServerLevel level) {
        Map<UUID, PerchState> states = PERCHES.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Map<UUID, Long> cooldowns = COOLDOWNS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        Map<UUID, LeadConnection> byId = byId(connections);
        long tick = level.getGameTime();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= tick);
        cleanupBoostedRopes(level, tick);

        updateExisting(level, connections, byId, states, cooldowns, tick);
        if (tick % SCAN_INTERVAL_TICKS == 0L) {
            acquireNewPerches(level, connections, states, cooldowns, tick);
        }
        if (states.isEmpty()) {
            PERCHES.remove(level.dimension());
        }
        if (cooldowns.isEmpty()) {
            COOLDOWNS.remove(level.dimension());
        }
        cleanupApproachStarts(level);
    }

    public static void disturb(ServerLevel level, UUID connectionId, Vec3 point) {
        if (level == null || connectionId == null) {
            return;
        }
        Map<UUID, PerchState> states = PERCHES.get(level.dimension());
        if (states == null || states.isEmpty()) {
            return;
        }
        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        Map<UUID, LeadConnection> byId = byId(connections);
        Map<UUID, Long> cooldowns = COOLDOWNS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        long tick = level.getGameTime();
        ArrayList<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, PerchState> entry : states.entrySet()) {
            PerchState state = entry.getValue();
            if (!state.connectionId().equals(connectionId)) {
                continue;
            }
            Entity entity = level.getEntityInAnyDimension(entry.getKey());
            if (!(entity instanceof Parrot parrot)) {
                remove.add(entry.getKey());
                continue;
            }
            LeadConnection connection = byId.get(connectionId);
            Vec3 perchPoint = connection == null ? parrot.position()
                    : sample(level, connection, connections, state.t()).position();
            releaseStartled(parrot, state, point == null ? perchPoint : point);
            setCooldown(cooldowns, parrot.getUUID(), tick, STARTLED_COOLDOWN_TICKS);
            setApproachStart(level, parrot.getUUID(), Long.MIN_VALUE);
            remove.add(entry.getKey());
        }
        for (UUID id : remove) {
            states.remove(id);
        }
        if (states.isEmpty()) {
            PERCHES.remove(level.dimension());
        }
        if (cooldowns.isEmpty()) {
            COOLDOWNS.remove(level.dimension());
        }
        cleanupApproachStarts(level);
    }

    private static void updateExisting(ServerLevel level, List<LeadConnection> connections,
            Map<UUID, LeadConnection> byId, Map<UUID, PerchState> states, Map<UUID, Long> cooldowns, long tick) {
        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, PerchState> entry : states.entrySet()) {
            Entity entity = level.getEntityInAnyDimension(entry.getKey());
            if (!(entity instanceof Parrot parrot) || parrot.level() != level || !parrot.isAlive()
                    || parrot.isRemoved()) {
                remove.add(entry.getKey());
                continue;
            }
            PerchState state = entry.getValue();
            LeadConnection connection = byId.get(state.connectionId());
            if (connection == null || !canPerchOn(level, connection) || !canUseParrot(parrot)) {
                release(parrot, state);
                setCooldown(cooldowns, parrot.getUUID(), tick, SOFT_RELEASE_COOLDOWN_TICKS);
                clearApproachStart(level, entry.getKey());
                remove.add(entry.getKey());
                continue;
            }

            RopePoint point = sample(level, connection, connections, state.t());
            Vec3 perchPos = point.position().add(0.0D, PERCH_Y_OFFSET, 0.0D);
            if (!hasPerchClearance(level, parrot, perchPos)) {
                release(parrot, state);
                setCooldown(cooldowns, parrot.getUUID(), tick, SOFT_RELEASE_COOLDOWN_TICKS);
                clearApproachStart(level, entry.getKey());
                remove.add(entry.getKey());
                continue;
            }
            if (!state.settled() && !state.finalApproach()) {
                double distSqr = parrot.position().distanceToSqr(perchPos);
                if (distSqr > FINAL_APPROACH_DISTANCE_SQR) {
                    long approachAge = tick - state.startTick();
                    if (approachAge > APPROACH_TIMEOUT_TICKS
                            || (approachAge > APPROACH_NAVIGATION_GRACE_TICKS && parrot.getNavigation().isDone())) {
                        release(parrot, state);
                        setCooldown(cooldowns, parrot.getUUID(), tick, STARTLED_COOLDOWN_TICKS_NAV_FAIL);
                        clearApproachStart(level, entry.getKey());
                        remove.add(entry.getKey());
                    }
                    continue;
                }
                // Only the final alignment is custom-controlled. The long approach is
                // left to vanilla FlyingPathNavigation so we don't keep stealing an
                // already-flying parrot's path every tick.
                state.setFinalApproach(true);
                parrot.setNoGravity(true);
                parrot.getNavigation().stop();
            }
            if (state.settled() && shouldRandomTakeoff(parrot, state, tick)) {
                releaseVoluntary(parrot, state);
                setCooldown(cooldowns, parrot.getUUID(), tick, RANDOM_TAKEOFF_COOLDOWN_TICKS);
                clearApproachStart(level, entry.getKey());
                remove.add(entry.getKey());
                continue;
            }
            if (state.settled() && shouldSpook(level, parrot, perchPos)) {
                releaseStartled(parrot, state, perchPos);
                setCooldown(cooldowns, parrot.getUUID(), tick, STARTLED_COOLDOWN_TICKS);
                clearApproachStart(level, entry.getKey());
                remove.add(entry.getKey());
                continue;
            }
            driveParrot(level, parrot, state, point, perchPos, tick, states);
        }
        for (UUID id : remove) {
            states.remove(id);
        }
    }

    private static void acquireNewPerches(ServerLevel level, List<LeadConnection> connections,
            Map<UUID, PerchState> states, Map<UUID, Long> cooldowns, long tick) {
        for (LeadConnection connection : connections) {
            if (!canPerchOn(level, connection)) {
                continue;
            }
            AABB search = ropeEnvelope(level, connection, connections).inflate(SEARCH_RADIUS);
            List<Parrot> parrots = level.getEntities(PARROTS, search,
                    parrot -> !states.containsKey(parrot.getUUID()) && canUseParrot(parrot));
            if (parrots.isEmpty()) {
                continue;
            }
            for (Parrot parrot : parrots) {
                if (states.containsKey(parrot.getUUID())) {
                    continue;
                }
                if (isApproaching(level, parrot.getUUID(), tick)) {
                    continue;
                }
                if (isCoolingDown(cooldowns, parrot.getUUID(), tick)) {
                    continue;
                }
                double chance = isRopeBoosted(level, connection.id(), tick)
                        ? BOOSTED_LAND_ATTEMPT_CHANCE
                        : LAND_ATTEMPT_CHANCE;
                if (parrot.getRandom().nextDouble() > chance) {
                    setCooldown(cooldowns, parrot.getUUID(), tick, LAND_RETRY_COOLDOWN_TICKS);
                    continue;
                }
                Candidate candidate = bestCandidate(level, parrot, connection, connections, states);
                if (candidate != null) {
                    PerchState state = new PerchState(connection.id(), candidate.t(), false, parrot.isNoGravity(),
                            tick);
                    if (!parrot.getNavigation().moveTo(candidate.perchPos().x, candidate.perchPos().y,
                            candidate.perchPos().z, 1.12D)) {
                        setCooldown(cooldowns, parrot.getUUID(), tick, LAND_RETRY_COOLDOWN_TICKS);
                        continue;
                    }
                    states.put(parrot.getUUID(), state);
                    setApproachStart(level, parrot.getUUID(), tick);
                    faceToward(parrot, candidate.perchPos());
                }
            }
        }
    }

    private static Candidate bestCandidate(ServerLevel level, Parrot parrot, LeadConnection connection,
            List<LeadConnection> connections, Map<UUID, PerchState> states) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        double distance = endpoints.from().distanceTo(endpoints.to());
        int samples = Mth.clamp((int) Math.ceil(distance * 1.35D), MIN_ROPE_SAMPLES, MAX_ROPE_SAMPLES);
        Vec3 parrotPos = parrot.position();
        Candidate best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < samples; i++) {
            double t = 0.14D + (0.72D * i) / Math.max(1, samples - 1);
            if (isOccupied(connection.id(), t, states)) {
                continue;
            }
            RopePoint point = sample(level, connection, connections, t);
            if (Math.abs(point.tangent().y) > MAX_TANGENT_Y) {
                continue;
            }
            Vec3 perchPos = point.position().add(0.0D, PERCH_Y_OFFSET, 0.0D);
            double distSqr = parrotPos.distanceToSqr(perchPos);
            if (distSqr > SEARCH_RADIUS_SQR || !hasPerchClearance(level, parrot, perchPos)) {
                continue;
            }
            if (distSqr < bestScore) {
                bestScore = distSqr;
                best = new Candidate(t, perchPos);
            }
        }
        return best;
    }

    private static boolean isOccupied(UUID connectionId, double t, Map<UUID, PerchState> states) {
        for (PerchState state : states.values()) {
            if (state.connectionId().equals(connectionId) && Math.abs(state.t() - t) < OCCUPIED_T_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private static void driveParrot(ServerLevel level, Parrot parrot, PerchState state, RopePoint point,
            Vec3 perchPos, long tick, Map<UUID, PerchState> states) {
        parrot.setNoGravity(true);
        parrot.getNavigation().stop();

        if (!state.settled()) {
            Vec3 delta = perchPos.subtract(parrot.position());
            if (delta.lengthSqr() <= ARRIVE_DISTANCE_SQR) {
                state.setSettled(true);
            } else {
                Vec3 velocity = delta.normalize().scale(APPROACH_SPEED);
                if (velocity.lengthSqr() > MAX_APPROACH_SPEED * MAX_APPROACH_SPEED) {
                    velocity = velocity.normalize().scale(MAX_APPROACH_SPEED);
                }
                parrot.setOnGround(false);
                parrot.setDeltaMovement(velocity);
                faceToward(parrot, perchPos);
                parrot.hurtMarked = true;
                return;
            }
        }

        parrot.setDeltaMovement(Vec3.ZERO);
        parrot.setPos(perchPos);
        parrot.setOnGround(true);
        facePerpendicular(level, parrot, point.tangent(), perchPos, state.connectionId(), states);
        glanceAtNearby(level, parrot, state, perchPos, tick);
        parrot.hurtMarked = true;
    }

    private static void glanceAtNearby(ServerLevel level, Parrot parrot, PerchState state,
            Vec3 perchPos, long tick) {
        if (tick >= state.lastGlanceStart && tick < state.lastGlanceStart + state.glanceDuration) {
            if (state.glanceTarget != null) {
                lookAt(parrot, perchPos, state.glanceTarget);
            }
            return;
        }
        if (tick < state.lastGlanceStart + state.glanceDuration + state.glanceCooldown) {
            return;
        }
        LivingEntity best = null;
        double bestSqr = 7.0D * 7.0D;
        for (var player : level.players()) {
            if (player.isSpectator() || player.isInvisible()) {
                continue;
            }
            double d = player.position().distanceToSqr(perchPos);
            if (d < bestSqr) {
                bestSqr = d;
                best = player;
            }
        }
        if (best == null) {
            return;
        }
        state.glanceDuration = 30 + parrot.getRandom().nextInt(60);
        state.glanceCooldown = 60 + parrot.getRandom().nextInt(180);
        state.lastGlanceStart = tick;
        state.glanceTarget = best.getEyePosition();
        lookAt(parrot, perchPos, state.glanceTarget);
    }

    private static void lookAt(Parrot parrot, Vec3 origin, Vec3 target) {
        double dx = target.x - origin.x;
        double dy = target.y - origin.y;
        double dz = target.z - origin.z;
        double hSqr = dx * dx + dz * dz;
        if (hSqr < 1.0e-8D) {
            return;
        }
        double headYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        float wrappedYaw = Mth.wrapDegrees((float) headYaw);
        parrot.setYHeadRot(wrappedYaw);
        double horiz = Math.sqrt(hSqr);
        double pitch = Math.toDegrees(-Math.atan2(dy, horiz));
        parrot.setXRot(Mth.clamp((float) pitch, -45.0F, 45.0F));
    }

    private static void faceToward(Parrot parrot, Vec3 target) {
        Vec3 delta = target.subtract(parrot.position());
        double lenSqr = delta.x * delta.x + delta.z * delta.z;
        if (lenSqr < 1.0e-8D) {
            return;
        }
        double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D;
        float wrapped = Mth.wrapDegrees((float) yaw);
        parrot.setYRot(wrapped);
        parrot.setYBodyRot(wrapped);
        parrot.setYHeadRot(wrapped);
    }

    private static void facePerpendicular(ServerLevel level, Parrot parrot, Vec3 tangent, Vec3 perchPos,
            UUID connectionId, Map<UUID, PerchState> states) {
        Vec3 side = perpendicularHorizontal(tangent);
        // Prefer to face the same direction as companions already on this rope.
        PerchState self = states.get(parrot.getUUID());
        if (self != null) {
            int dir = self.facingSide();
            if (dir == 0) {
                int peerDir = peerFacingDirection(connectionId, states, parrot.getUUID());
                if (peerDir != 0 && parrot.getRandom().nextDouble() < 0.75D) {
                    dir = peerDir;
                } else if (peerDir != 0) {
                    dir = -peerDir;
                } else {
                    dir = parrot.getRandom().nextBoolean() ? 1 : -1;
                }
                self.setFacingSide(dir);
            }
            if (dir < 0) {
                side = side.scale(-1.0D);
            }
        }
        double yaw = Math.toDegrees(Math.atan2(side.z, side.x)) - 90.0D;
        float wrapped = Mth.wrapDegrees((float) yaw);
        parrot.setYRot(wrapped);
        parrot.setYBodyRot(wrapped);
        // yHeadRot is managed by glanceAtNearby, do not override it here.
    }

    private static int peerFacingDirection(UUID connectionId, Map<UUID, PerchState> states, UUID excludeId) {
        int countPos = 0;
        int countNeg = 0;
        for (PerchState state : states.values()) {
            if (state.facingSide() == 0) {
                continue;
            }
            if (!state.connectionId().equals(connectionId)) {
                continue;
            }
            if (state.facingSide() > 0) {
                countPos++;
            } else {
                countNeg++;
            }
        }
        if (countPos > countNeg) {
            return 1;
        }
        if (countNeg > countPos) {
            return -1;
        }
        return 0;
    }

    private static Vec3 perpendicularHorizontal(Vec3 tangent) {
        double lenSqr = tangent.x * tangent.x + tangent.z * tangent.z;
        if (lenSqr < 1.0e-8D) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        double inv = 1.0D / Math.sqrt(lenSqr);
        return new Vec3(-tangent.z * inv, 0.0D, tangent.x * inv);
    }

    private static void release(Parrot parrot, PerchState state) {
        if (parrot != null && parrot.isAlive() && !parrot.isRemoved()) {
            parrot.setNoGravity(state.wasNoGravity());
            parrot.setOnGround(false);
        }
    }

    private static void releaseStartled(Parrot parrot, PerchState state, Vec3 awayFrom) {
        if (parrot == null || !parrot.isAlive() || parrot.isRemoved()) {
            return;
        }
        release(parrot, state);
        Vec3 away = parrot.position().subtract(awayFrom);
        if (away.lengthSqr() < 1.0e-6D) {
            away = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            away = away.normalize();
        }
        parrot.setDeltaMovement(away.x * 0.22D, 0.24D, away.z * 0.22D);
        parrot.getNavigation().stop();
        parrot.hurtMarked = true;
    }

    private static void releaseVoluntary(Parrot parrot, PerchState state) {
        if (parrot == null || !parrot.isAlive() || parrot.isRemoved()) {
            return;
        }
        release(parrot, state);
        double angle = parrot.getRandom().nextDouble() * Math.PI * 2.0D;
        parrot.setDeltaMovement(Math.cos(angle) * 0.12D, 0.16D, Math.sin(angle) * 0.12D);
        parrot.getNavigation().stop();
        parrot.hurtMarked = true;
    }

    private static boolean shouldRandomTakeoff(Parrot parrot, PerchState state, long tick) {
        return tick - state.startTick() >= RANDOM_TAKEOFF_MIN_PERCH_TICKS
                && parrot.getRandom().nextDouble() < RANDOM_TAKEOFF_CHANCE;
    }

    private static boolean shouldSpook(ServerLevel level, Parrot parrot, Vec3 perchPos) {
        for (var player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.position().distanceToSqr(perchPos) <= SPOOK_PLAYER_RADIUS_SQR
                    && player.getDeltaMovement().horizontalDistanceSqr() > SPOOK_PLAYER_SPEED_SQR) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoolingDown(Map<UUID, Long> cooldowns, UUID parrotId, long tick) {
        return cooldowns.getOrDefault(parrotId, Long.MIN_VALUE) > tick;
    }

    private static boolean isRopeBoosted(ServerLevel level, UUID ropeId, long tick) {
        Map<UUID, Long> map = BOOSTED_ROPES.get(level.dimension());
        if (map == null) {
            return false;
        }
        return map.getOrDefault(ropeId, Long.MIN_VALUE) > tick;
    }

    private static void cleanupBoostedRopes(ServerLevel level, long tick) {
        Map<UUID, Long> map = BOOSTED_ROPES.get(level.dimension());
        if (map == null) {
            return;
        }
        map.entrySet().removeIf(e -> e.getValue() <= tick);
        if (map.isEmpty()) {
            BOOSTED_ROPES.remove(level.dimension());
        }
    }

    private static void setCooldown(Map<UUID, Long> cooldowns, UUID parrotId, long tick, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return;
        }
        cooldowns.put(parrotId, Math.max(cooldowns.getOrDefault(parrotId, Long.MIN_VALUE), tick + cooldownTicks));
    }

    private static boolean isApproaching(ServerLevel level, UUID parrotId, long tick) {
        Map<UUID, Long> map = APPROACH_START_TICK.get(level.dimension());
        if (map == null) {
            return false;
        }
        Long start = map.get(parrotId);
        if (start == null || start == Long.MIN_VALUE) {
            return false;
        }
        return tick - start < SCAN_APPROACHING_GRACE_TICKS;
    }

    private static void setApproachStart(ServerLevel level, UUID parrotId, long tick) {
        APPROACH_START_TICK.computeIfAbsent(level.dimension(), ignored -> new HashMap<>()).put(parrotId, tick);
    }

    private static void clearApproachStart(ServerLevel level, UUID parrotId) {
        Map<UUID, Long> map = APPROACH_START_TICK.get(level.dimension());
        if (map != null) {
            map.put(parrotId, Long.MIN_VALUE);
        }
    }

    private static void cleanupApproachStarts(ServerLevel level) {
        Map<UUID, Long> map = APPROACH_START_TICK.get(level.dimension());
        if (map == null) {
            return;
        }
        map.entrySet().removeIf(e -> e.getValue() == Long.MIN_VALUE);
        if (map.isEmpty()) {
            APPROACH_START_TICK.remove(level.dimension());
        }
    }

    public static boolean forcePerchNearby(ServerLevel level, Vec3 origin, double radius) {
        if (level == null || origin == null) {
            return false;
        }
        Map<UUID, PerchState> states = PERCHES.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Map<UUID, Long> cooldowns = COOLDOWNS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        List<LeadConnection> connections = SuperLeadNetwork.connections(level);
        long tick = level.getGameTime();
        AABB search = AABB.ofSize(origin, radius * 2.0D, radius * 2.0D, radius * 2.0D);
        List<Parrot> parrots = level.getEntities(PARROTS, search,
                parrot -> !states.containsKey(parrot.getUUID()) && canUseParrot(parrot));
        if (parrots.isEmpty()) {
            return false;
        }
        for (LeadConnection connection : connections) {
            if (!canPerchOn(level, connection)) {
                continue;
            }
            for (Parrot parrot : parrots) {
                if (states.containsKey(parrot.getUUID())) {
                    continue;
                }
                Candidate candidate = bestCandidate(level, parrot, connection, connections, states);
                if (candidate != null) {
                    release(parrot, new PerchState(connection.id(), 0.0D, false, parrot.isNoGravity(), tick));
                    parrot.setNoGravity(true);
                    parrot.getNavigation().stop();
                    parrot.setPos(candidate.perchPos());
                    parrot.setDeltaMovement(Vec3.ZERO);
                    parrot.setOnGround(true);
                    RopePoint point = sample(level, connection, connections, candidate.t());
                    facePerpendicular(level, parrot, point.tangent(), candidate.perchPos(),
                            connection.id(), states);
                    parrot.hurtMarked = true;
                    PerchState state = new PerchState(connection.id(), candidate.t(), true, false, tick);
                    state.setFinalApproach(true);
                    states.put(parrot.getUUID(), state);
                    setCooldown(cooldowns, parrot.getUUID(), tick, RANDOM_TAKEOFF_COOLDOWN_TICKS);
                    return true;
                }
            }
        }
        return false;
    }

    public static void boostRope(ServerLevel level, UUID connectionId) {
        if (level == null || connectionId == null) {
            return;
        }
        BOOSTED_ROPES.computeIfAbsent(level.dimension(), ignored -> new HashMap<>())
                .put(connectionId, level.getGameTime() + BOOST_DURATION_TICKS);
    }

    private static boolean canUseParrot(Parrot parrot) {
        return parrot.isAlive()
                && !parrot.isRemoved()
                && !parrot.isPassenger()
                && !parrot.isVehicle()
                && !parrot.isOrderedToSit()
                && parrot.hurtTime <= 0;
    }

    private static boolean canPerchOn(ServerLevel level, LeadConnection connection) {
        if (connection == null || connection.physicsPreset().isBlank()) {
            return false;
        }
        return ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset()).physicsEnabled();
    }

    private static boolean hasPerchClearance(ServerLevel level, Parrot parrot, Vec3 feet) {
        AABB box = new AABB(feet.x - 0.22D, feet.y, feet.z - 0.22D,
                feet.x + 0.22D, feet.y + 0.75D, feet.z + 0.22D);
        return level.noCollision(parrot, box);
    }

    private static AABB ropeEnvelope(ServerLevel level, LeadConnection connection, List<LeadConnection> connections) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        return new AABB(
                Math.min(endpoints.from().x, endpoints.to().x), Math.min(endpoints.from().y, endpoints.to().y),
                Math.min(endpoints.from().z, endpoints.to().z),
                Math.max(endpoints.from().x, endpoints.to().x), Math.max(endpoints.from().y, endpoints.to().y),
                Math.max(endpoints.from().z, endpoints.to().z)).inflate(0.75D);
    }

    private static RopePoint sample(ServerLevel level, LeadConnection connection, List<LeadConnection> connections,
            double t) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double sag = sag(level, connection, a, b);
        double clamped = Mth.clamp(t, 0.0D, 1.0D);
        Vec3 position = new Vec3(
                a.x + (b.x - a.x) * clamped,
                a.y + (b.y - a.y) * clamped - Math.sin(Math.PI * clamped) * sag,
                a.z + (b.z - a.z) * clamped);
        Vec3 tangent = new Vec3(
                b.x - a.x,
                b.y - a.y - Math.PI * Math.cos(Math.PI * clamped) * sag,
                b.z - a.z);
        if (tangent.lengthSqr() < 1.0e-8D) {
            tangent = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            tangent = tangent.normalize();
        }
        return new RopePoint(position, tangent);
    }

    private static double sag(ServerLevel level, LeadConnection connection, Vec3 a, Vec3 b) {
        ServerPhysicsTuning tuning = ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset());
        double dist = a.distanceTo(b);
        if (dist < 1.0e-6D || Math.abs(tuning.gravity()) < 1.0e-9D) {
            return 0.0D;
        }
        double slackExtra = Math.max(0.0D, tuning.slackTight() - 1.0D);
        return Math.min(1.35D, dist * (0.055D + slackExtra * 2.0D));
    }

    private static Map<UUID, LeadConnection> byId(List<LeadConnection> connections) {
        Map<UUID, LeadConnection> out = new HashMap<>();
        for (LeadConnection connection : connections) {
            out.put(connection.id(), connection);
        }
        return out;
    }

    private record Candidate(double t, Vec3 perchPos) {
    }

    private record RopePoint(Vec3 position, Vec3 tangent) {
    }

    private static final class PerchState {
        private final UUID connectionId;
        private final double t;
        private final boolean wasNoGravity;
        private final long startTick;
        private boolean settled;
        private boolean finalApproach;
        private int facingSide;
        private long lastGlanceStart;
        private int glanceDuration;
        private int glanceCooldown;
        private Vec3 glanceTarget;

        private PerchState(UUID connectionId, double t, boolean settled, boolean wasNoGravity, long startTick) {
            this.connectionId = connectionId;
            this.t = t;
            this.settled = settled;
            this.wasNoGravity = wasNoGravity;
            this.startTick = startTick;
        }

        UUID connectionId() {
            return connectionId;
        }

        double t() {
            return t;
        }

        boolean settled() {
            return settled;
        }

        void setSettled(boolean settled) {
            this.settled = settled;
        }

        boolean finalApproach() {
            return finalApproach;
        }

        void setFinalApproach(boolean finalApproach) {
            this.finalApproach = finalApproach;
        }

        boolean wasNoGravity() {
            return wasNoGravity;
        }

        int facingSide() {
            return facingSide;
        }

        void setFacingSide(int facingSide) {
            this.facingSide = facingSide;
        }

        @SuppressWarnings("unused")
        long startTick() {
            return startTick;
        }
    }
}