package com.zhongbai233.super_lead.lead;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side preset effect that can trip players walking over ropes. */
final class RopeTripController {
    private static final int CRAWL_TICKS = 20 * 60;
    private static final int FALL_TICKS = 8;
    private static final double FALL_DISTANCE = 0.65D;
    private static final double MIN_WALK_SPEED_SQR = 0.015D * 0.015D;
    private static final double MAX_VERTICAL_SPEED = 0.20D;

    private static final Map<NetworkKey, Map<UUID, Long>> NEXT_ALLOWED_TICK = new HashMap<>();
    private static final Map<NetworkKey, Map<UUID, ForcedCrawl>> FORCED_CRAWLS = new HashMap<>();

    private RopeTripController() {
    }

    static void maybeTrip(ServerLevel level, ServerPlayer player, ServerPhysicsTuning tuning) {
        maybeTrip(level, player, tuning, 0.0D, 0.0D);
    }

    static void maybeTrip(ServerLevel level, ServerPlayer player, ServerPhysicsTuning tuning, double inputX,
            double inputZ) {
        if (level == null || player == null || tuning == null || !tuning.tripEnabled()) {
            return;
        }
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()
                || player.getAbilities().flying || player.getForcedPose() != null) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        double horizontalSpeedSqr = motion.x * motion.x + motion.z * motion.z;
        double inputSpeedSqr = inputX * inputX + inputZ * inputZ;
        if (horizontalSpeedSqr < MIN_WALK_SPEED_SQR && inputSpeedSqr < 0.20D * 0.20D) {
            return;
        }
        if (Math.abs(motion.y) > MAX_VERTICAL_SPEED) {
            return;
        }

        long now = level.getGameTime();
        NetworkKey dim = NetworkKey.of(level);
        Map<UUID, Long> cooldowns = NEXT_ALLOWED_TICK.computeIfAbsent(dim, ignored -> new HashMap<>());
        long next = cooldowns.getOrDefault(player.getUUID(), 0L);
        if (now < next) {
            return;
        }

        int cooldownTicks = Math.max(0, tuning.tripCooldownTicks());
        cooldowns.put(player.getUUID(), now + cooldownTicks);
        if (level.getRandom().nextDouble() >= tuning.tripChance()) {
            return;
        }

        tripNow(level, player, now, motion, inputX, inputZ);
    }

    static boolean debugForceTrip(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null || !player.isAlive() || player.isSpectator() || player.isPassenger()
                || player.getForcedPose() != null) {
            return false;
        }
        tripNow(level, player, level.getGameTime(), player.getDeltaMovement(), 0.0D, 0.0D);
        return true;
    }

    private static void tripNow(ServerLevel level, ServerPlayer player, long now, Vec3 motion, double inputX,
            double inputZ) {
        double startX = player.getX();
        double startZ = player.getZ();
        Vec3 direction = forwardDirection(player, motion, inputX, inputZ);
        Vec3 target = findFallTarget(level, player, direction);

        player.setForcedPose(Pose.SWIMMING);
        player.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        SyncRopeTripState payload = SyncRopeTripState.active(player.getId(), CRAWL_TICKS, startX, startZ,
                target.x, target.z, FALL_TICKS);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
        player.hurtServer(level, player.damageSources().fall(), 1.0F);
        player.resetFallDistance();
        FORCED_CRAWLS.computeIfAbsent(NetworkKey.of(level), ignored -> new HashMap<>())
                .put(player.getUUID(), new ForcedCrawl(now + CRAWL_TICKS, startX, startZ, target.x, target.z,
                        now + FALL_TICKS));
    }

    static void tick(ServerLevel level) {
        NetworkKey dim = NetworkKey.of(level);
        long now = level.getGameTime();
        Map<UUID, ForcedCrawl> crawls = FORCED_CRAWLS.get(dim);
        if (crawls != null) {
            crawls.entrySet().removeIf(entry -> {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player == null || player.level() != level || !player.isAlive()) {
                    if (player != null) {
                        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                                SyncRopeTripState.inactive(player.getId()));
                    }
                    return true;
                }
                ForcedCrawl crawl = entry.getValue();
                if (now < crawl.untilTick()) {
                    player.setForcedPose(Pose.SWIMMING);
                    Vec3 motion = player.getDeltaMovement();
                    double targetX = crawl.lockX();
                    double targetZ = crawl.lockZ();
                    if (now < crawl.fallUntilTick()) {
                        double progress = 1.0D - (double) (crawl.fallUntilTick() - now) / FALL_TICKS;
                        progress = Math.max(0.0D, Math.min(1.0D, progress));
                        progress = progress * progress * (3.0D - 2.0D * progress);
                        targetX = crawl.startX() + (crawl.lockX() - crawl.startX()) * progress;
                        targetZ = crawl.startZ() + (crawl.lockZ() - crawl.startZ()) * progress;
                    }
                    double tolerance = now < crawl.fallUntilTick() ? 0.18D : 0.03D;
                    if (Math.abs(player.getX() - targetX) > tolerance
                            || Math.abs(player.getZ() - targetZ) > tolerance) {
                        player.teleportTo(targetX, player.getY(), targetZ);
                    }
                    player.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);
                    player.hurtMarked = true;
                    if ((now & 3L) == 0L) {
                        player.connection.send(new ClientboundSetEntityMotionPacket(player));
                    }
                    return false;
                }
                if (player.getForcedPose() == Pose.SWIMMING) {
                    player.setForcedPose(null);
                }
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        SyncRopeTripState.inactive(player.getId()));
                return true;
            });
            if (crawls.isEmpty()) {
                FORCED_CRAWLS.remove(dim);
            }
        }

        Map<UUID, Long> cooldowns = NEXT_ALLOWED_TICK.get(dim);
        if (cooldowns != null) {
            cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 20L * 60L);
            if (cooldowns.isEmpty()) {
                NEXT_ALLOWED_TICK.remove(dim);
            }
        }
    }

    static void stopEverywhere(ServerPlayer player) {
        release(player);
    }

    static void release(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (Map<UUID, ForcedCrawl> crawls : FORCED_CRAWLS.values()) {
            crawls.remove(player.getUUID());
        }
        if (player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                SyncRopeTripState.inactive(player.getId()));
    }

    static void clear(ServerLevel level) {
        NetworkKey dim = NetworkKey.of(level);
        NEXT_ALLOWED_TICK.remove(dim);
        FORCED_CRAWLS.remove(dim);
    }

    static void clearAll() {
        NEXT_ALLOWED_TICK.clear();
        FORCED_CRAWLS.clear();
    }

    private static Vec3 forwardDirection(ServerPlayer player, Vec3 motion, double inputX, double inputZ) {
        Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
        if (horizontal.lengthSqr() < MIN_WALK_SPEED_SQR && inputX * inputX + inputZ * inputZ > 1.0E-6D) {
            horizontal = new Vec3(inputX, 0.0D, inputZ);
        }
        if (horizontal.lengthSqr() < MIN_WALK_SPEED_SQR) {
            Vec3 look = player.getLookAngle();
            horizontal = new Vec3(look.x, 0.0D, look.z);
        }
        if (horizontal.lengthSqr() < 1.0E-6D) {
            float radians = player.getYRot() * ((float) Math.PI / 180F);
            horizontal = new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
        }
        return horizontal.normalize();
    }

    private static Vec3 findFallTarget(ServerLevel level, ServerPlayer player, Vec3 direction) {
        double startX = player.getX();
        double startZ = player.getZ();
        for (int step = 8; step >= 1; step--) {
            double distance = FALL_DISTANCE * step / 8.0D;
            double dx = direction.x * distance;
            double dz = direction.z * distance;
            if (level.noCollision(player, player.getBoundingBox().move(dx, 0.0D, dz))) {
                return new Vec3(startX + dx, player.getY(), startZ + dz);
            }
        }
        return new Vec3(startX, player.getY(), startZ);
    }

    private record ForcedCrawl(long untilTick, double startX, double startZ, double lockX, double lockZ,
            long fallUntilTick) {
    }
}
