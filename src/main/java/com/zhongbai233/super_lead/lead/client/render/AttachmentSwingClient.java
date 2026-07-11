package com.zhongbai233.super_lead.lead.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Client-only lightweight damped pendulum state for rope attachments.
 *
 * <p>Attachment bodies are not rigid bodies: nearby entities only inject angular
 * velocity into a tiny two-axis spring. This keeps the visual feature cheap and
 * avoids involving the rope solver or server collision state.
 */
public final class AttachmentSwingClient {
    private static final double COLLISION_RADIUS = 0.68D;
    private static final double COLLISION_RADIUS_SQR = COLLISION_RADIUS * COLLISION_RADIUS;
    private static final double IMPULSE_SCALE = 0.070D;
    private static final double OVERLAP_IMPULSE_SCALE = 0.035D;
    private static final double SUPPORT_MOTION_SCALE = 0.62D;
    private static final double SUPPORT_ACCEL_SCALE = 0.24D;
    private static final double SUPPORT_MIN_MOTION_SQR = 0.0015D * 0.0015D;
    private static final double SUPPORT_FILTER_ALPHA = 0.42D;
    private static final double SUPPORT_REVERSAL_DAMPING = 0.45D;
    private static final double SUPPORT_MAX_IMPULSE = Math.toRadians(4.5D);
    private static final double MAX_ANGULAR_VELOCITY = Math.toRadians(9.0D);
    private static final double MIN_SPEED_SQR = 0.03D * 0.03D;
    private static final double INTERACTION_DISTANCE_SQR = 32.0D * 32.0D;
    private static final double STIFFNESS = 0.18D;
    private static final double DAMPING = 0.82D;
    private static final double MAX_ANGLE = Math.toRadians(28.0D);
    private static final double SLEEP_ANGLE = Math.toRadians(0.35D);
    private static final double SLEEP_VELOCITY = Math.toRadians(0.25D);
    private static final double FINAL_SLEEP_DAMPING = 0.58D;
    private static final double HARD_SLEEP_ANGLE = Math.toRadians(0.035D);
    private static final double HARD_SLEEP_VELOCITY = Math.toRadians(0.035D);
    private static final int IMPULSE_COOLDOWN_TICKS = 3;
    private static final int VISIBLE_HOLD_TICKS = 8;
    private static final int RETAIN_TICKS = 80;
    private static final Map<UUID, State> STATES = new HashMap<>();

    private AttachmentSwingClient() {
    }

    public static void tickDynamic(ClientLevel level, UUID attachmentId,
            double px, double py, double pz,
            double tangentX, double tangentY, double tangentZ,
            double sideX, double sideY, double sideZ,
            double lodDistSqr, long tick) {
        tickDynamicWithSupport(level, attachmentId, px, py, pz, px, py, pz,
                tangentX, tangentY, tangentZ, sideX, sideY, sideZ, lodDistSqr, tick);
    }

    public static void tickDynamicWithSupport(ClientLevel level, UUID attachmentId,
            double collisionX, double collisionY, double collisionZ,
            double supportX, double supportY, double supportZ,
            double tangentX, double tangentY, double tangentZ,
            double sideX, double sideY, double sideZ,
            double lodDistSqr, long tick) {
        if (level == null || attachmentId == null || lodDistSqr > 48.0D * 48.0D) {
            integrateIfPresent(attachmentId, tick);
            return;
        }
        if (lodDistSqr > INTERACTION_DISTANCE_SQR) {
            integrateIfPresent(attachmentId, tick);
            return;
        }
        AABB query = new AABB(collisionX - COLLISION_RADIUS, collisionY - COLLISION_RADIUS,
                collisionZ - COLLISION_RADIUS,
                collisionX + COLLISION_RADIUS, collisionY + COLLISION_RADIUS, collisionZ + COLLISION_RADIUS);
        State state = applySupportMotion(attachmentId, STATES.get(attachmentId),
                supportX, supportY, supportZ, tangentX, tangentY, tangentZ, sideX, sideY, sideZ, tick);
        for (Entity entity : level.getEntities((Entity) null, query,
                e -> e.isAlive() && !e.isRemoved() && !e.isSpectator() && e.isPickable())) {
            if (entity instanceof net.minecraft.world.entity.animal.parrot.Parrot) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            double closestX = clamp(collisionX, box.minX, box.maxX);
            double closestY = clamp(collisionY, box.minY, box.maxY);
            double closestZ = clamp(collisionZ, box.minZ, box.maxZ);
            double dx = collisionX - closestX;
            double dy = collisionY - closestY;
            double dz = collisionZ - closestZ;
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr > COLLISION_RADIUS_SQR) {
                continue;
            }
            Vec3 velocity = entity.getDeltaMovement();
            double speedSqr = velocity.lengthSqr();
            double overlap = COLLISION_RADIUS - Math.sqrt(Math.max(0.0D, distSqr));
            if (speedSqr < MIN_SPEED_SQR && overlap <= 0.04D) {
                continue;
            }
            if (state == null) {
                state = STATES.computeIfAbsent(attachmentId, ignored -> new State());
            }
            if (tick - state.lastImpulseTick < IMPULSE_COOLDOWN_TICKS) {
                continue;
            }
            double awayX = distSqr > 1.0e-8D ? dx / Math.sqrt(distSqr) : -velocity.x;
            double awayY = distSqr > 1.0e-8D ? dy / Math.sqrt(distSqr) : -velocity.y;
            double awayZ = distSqr > 1.0e-8D ? dz / Math.sqrt(distSqr) : -velocity.z;
            state.velAlong += clamp(velocity.x * tangentX + velocity.y * tangentY + velocity.z * tangentZ,
                    -1.8D, 1.8D) * IMPULSE_SCALE
                    + clamp(awayX * tangentX + awayY * tangentY + awayZ * tangentZ,
                            -1.0D, 1.0D) * overlap * OVERLAP_IMPULSE_SCALE;
            state.velSide += clamp(velocity.x * sideX + velocity.y * sideY + velocity.z * sideZ,
                    -1.8D, 1.8D) * IMPULSE_SCALE
                    + clamp(awayX * sideX + awayY * sideY + awayZ * sideZ,
                            -1.0D, 1.0D) * overlap * OVERLAP_IMPULSE_SCALE;
            state.lastTouchedTick = tick;
            state.lastImpulseTick = tick;
        }
        if (state != null) {
            state.integrate(tick);
        }
    }

    public static void tickPassive(UUID attachmentId, long tick) {
        integrateIfPresent(attachmentId, tick);
    }

    public static Quaternionf swingRotation(UUID attachmentId, float partialTick) {
        State state = STATES.get(attachmentId);
        if (state == null) {
            return null;
        }
        double t = clamp(partialTick, 0.0D, 1.0D);
        double angleAlong = state.prevAngleAlong + (state.angleAlong - state.prevAngleAlong) * t;
        double angleSide = state.prevAngleSide + (state.angleSide - state.prevAngleSide) * t;
        if (Math.abs(angleAlong) < 1.0e-5D && Math.abs(angleSide) < 1.0e-5D) {
            return null;
        }
        return new Quaternionf()
                .rotateZ((float) angleAlong)
                .rotateX((float) -angleSide);
    }

    public static boolean hasVisibleSwing(UUID attachmentId) {
        State state = STATES.get(attachmentId);
        return state != null
                && (state.visibleSwing || state.lastTick - state.lastVisibleSwingTick <= VISIBLE_HOLD_TICKS);
    }

    public static void retainAttachments(Set<UUID> activeAttachmentIds, long tick) {
        if (STATES.isEmpty()) {
            return;
        }
        STATES.entrySet().removeIf(entry -> !activeAttachmentIds.contains(entry.getKey())
                || tick - entry.getValue().lastActiveTick > RETAIN_TICKS);
    }

    public static void clear() {
        STATES.clear();
    }

    private static void integrateIfPresent(UUID attachmentId, long tick) {
        State state = STATES.get(attachmentId);
        if (state != null) {
            state.integrate(tick);
        }
    }

    private static State applySupportMotion(UUID attachmentId, State state,
            double supportX, double supportY, double supportZ,
            double tangentX, double tangentY, double tangentZ,
            double sideX, double sideY, double sideZ,
            long tick) {
        if (state == null) {
            state = STATES.computeIfAbsent(attachmentId, ignored -> new State());
        }
        if (state.lastSupportTick == tick) {
            return state;
        }
        if (state.lastSupportTick == Long.MIN_VALUE || tick - state.lastSupportTick > 4L) {
            state.lastSupportTick = tick;
            state.supportX = supportX;
            state.supportY = supportY;
            state.supportZ = supportZ;
            state.supportVelX = state.supportVelY = state.supportVelZ = 0.0D;
            state.supportFilteredVelX = state.supportFilteredVelY = state.supportFilteredVelZ = 0.0D;
            return state;
        }

        double dt = Math.max(1.0D, tick - state.lastSupportTick);
        double vx = (supportX - state.supportX) / dt;
        double vy = (supportY - state.supportY) / dt;
        double vz = (supportZ - state.supportZ) / dt;
        double filteredVelX = state.supportFilteredVelX + (vx - state.supportFilteredVelX) * SUPPORT_FILTER_ALPHA;
        double filteredVelY = state.supportFilteredVelY + (vy - state.supportFilteredVelY) * SUPPORT_FILTER_ALPHA;
        double filteredVelZ = state.supportFilteredVelZ + (vz - state.supportFilteredVelZ) * SUPPORT_FILTER_ALPHA;
        double ax = filteredVelX - state.supportFilteredVelX;
        double ay = filteredVelY - state.supportFilteredVelY;
        double az = filteredVelZ - state.supportFilteredVelZ;
        double reversal = vx * state.supportVelX + vy * state.supportVelY + vz * state.supportVelZ < 0.0D
                ? SUPPORT_REVERSAL_DAMPING
                : 1.0D;
        if (filteredVelX * filteredVelX + filteredVelY * filteredVelY + filteredVelZ * filteredVelZ > SUPPORT_MIN_MOTION_SQR
                || ax * ax + ay * ay + az * az > SUPPORT_MIN_MOTION_SQR) {
            // Pendulum inertia: when the rope support point is shoved one way, the
            // attachment body visually lags behind in the opposite projected direction.
            double alongImpulse = -(clamp(filteredVelX * tangentX + filteredVelY * tangentY + filteredVelZ * tangentZ,
                    -0.75D, 0.75D) * SUPPORT_MOTION_SCALE
                    + clamp(ax * tangentX + ay * tangentY + az * tangentZ,
                            -0.75D, 0.75D) * SUPPORT_ACCEL_SCALE) * reversal;
            double sideImpulse = -(clamp(filteredVelX * sideX + filteredVelY * sideY + filteredVelZ * sideZ,
                    -0.75D, 0.75D) * SUPPORT_MOTION_SCALE
                    + clamp(ax * sideX + ay * sideY + az * sideZ,
                            -0.75D, 0.75D) * SUPPORT_ACCEL_SCALE) * reversal;
            state.velAlong = clamp(state.velAlong + clamp(alongImpulse, -SUPPORT_MAX_IMPULSE, SUPPORT_MAX_IMPULSE),
                    -MAX_ANGULAR_VELOCITY, MAX_ANGULAR_VELOCITY);
            state.velSide = clamp(state.velSide + clamp(sideImpulse, -SUPPORT_MAX_IMPULSE, SUPPORT_MAX_IMPULSE),
                    -MAX_ANGULAR_VELOCITY, MAX_ANGULAR_VELOCITY);
            state.lastTouchedTick = tick;
            state.visibleSwing = true;
            state.lastVisibleSwingTick = tick;
        }
        state.lastSupportTick = tick;
        state.supportX = supportX;
        state.supportY = supportY;
        state.supportZ = supportZ;
        state.supportVelX = vx;
        state.supportVelY = vy;
        state.supportVelZ = vz;
        state.supportFilteredVelX = filteredVelX;
        state.supportFilteredVelY = filteredVelY;
        state.supportFilteredVelZ = filteredVelZ;
        return state;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static final class State {
        double angleAlong;
        double angleSide;
        double prevAngleAlong;
        double prevAngleSide;
        double velAlong;
        double velSide;
        long lastTick = Long.MIN_VALUE;
        long lastTouchedTick = Long.MIN_VALUE;
        long lastImpulseTick = Long.MIN_VALUE;
        long lastActiveTick = Long.MIN_VALUE;
        long lastVisibleSwingTick = Long.MIN_VALUE;
        long lastSupportTick = Long.MIN_VALUE;
        boolean visibleSwing;
        double supportX;
        double supportY;
        double supportZ;
        double supportVelX;
        double supportVelY;
        double supportVelZ;
        double supportFilteredVelX;
        double supportFilteredVelY;
        double supportFilteredVelZ;

        void integrate(long tick) {
            if (lastTick == tick) {
                lastActiveTick = tick;
                return;
            }
            if (lastTick == Long.MIN_VALUE) {
                lastTick = tick - 1;
            }
            long delta = Math.max(1L, Math.min(4L, tick - lastTick));
            prevAngleAlong = angleAlong;
            prevAngleSide = angleSide;
            for (int i = 0; i < delta; i++) {
                velAlong += -angleAlong * STIFFNESS;
                velSide += -angleSide * STIFFNESS;
                velAlong *= DAMPING;
                velSide *= DAMPING;
                angleAlong = clamp(angleAlong + velAlong, -MAX_ANGLE, MAX_ANGLE);
                angleSide = clamp(angleSide + velSide, -MAX_ANGLE, MAX_ANGLE);
            }
            lastTick = tick;
            lastActiveTick = tick;
            boolean visiblyMoving = Math.abs(angleAlong) >= SLEEP_ANGLE || Math.abs(angleSide) >= SLEEP_ANGLE
                    || Math.abs(velAlong) >= SLEEP_VELOCITY || Math.abs(velSide) >= SLEEP_VELOCITY;
            if (visiblyMoving) {
                visibleSwing = true;
                lastVisibleSwingTick = tick;
            } else if (tick - lastTouchedTick > 10L) {
                // Fade the last tiny residual swing instead of snapping it to zero.
                // This avoids one-frame toggles between static hanger strings and the
                // dynamic swinging string while the pendulum is almost asleep.
                angleAlong *= FINAL_SLEEP_DAMPING;
                angleSide *= FINAL_SLEEP_DAMPING;
                velAlong *= FINAL_SLEEP_DAMPING;
                velSide *= FINAL_SLEEP_DAMPING;
                if (Math.abs(angleAlong) < HARD_SLEEP_ANGLE && Math.abs(angleSide) < HARD_SLEEP_ANGLE
                        && Math.abs(velAlong) < HARD_SLEEP_VELOCITY && Math.abs(velSide) < HARD_SLEEP_VELOCITY
                        && tick - lastVisibleSwingTick > VISIBLE_HOLD_TICKS) {
                    visibleSwing = false;
                    prevAngleAlong = prevAngleSide = angleAlong = angleSide = velAlong = velSide = 0.0D;
                }
            }
        }
    }
}