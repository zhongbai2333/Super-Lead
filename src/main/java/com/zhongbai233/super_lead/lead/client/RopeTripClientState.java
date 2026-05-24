package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.ClientRopeTripWakeRequest;
import com.zhongbai233.super_lead.lead.SyncRopeTripState;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-side half of the rope trip lock: keep crawling visual and suppress
 * local movement.
 *
 * <p>
 * Maintains per-entity trip state keyed by entity ID so that every nearby
 * player sees the fall-forward animation, not just the tripped player.
 */
@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class RopeTripClientState {
    /** Per-entity trip state keyed by entity ID. */
    private static final Map<Integer, TripEntry> TRIPS = new HashMap<>();

    /** Local-player-only state (camera, wake, movement lock). */
    private static boolean impactParticlesSpawned;
    private static boolean wakeRequested;
    private static boolean movementWakeArmed;
    private static boolean cameraSwitched;
    private static CameraType previousCameraType;
    private static float lockedBodyRot;

    private RopeTripClientState() {
    }

    public static void apply(SyncRopeTripState payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!payload.active()) {
            TRIPS.remove(payload.entityId());
            if (isLocal(payload.entityId())) {
                clearLocal(minecraft.player);
            }
            return;
        }
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        TripEntry entry = new TripEntry(
                now + Math.max(1, payload.remainingTicks()) + 2L,
                now,
                Math.max(0, payload.fallTicks()),
                payload.startX(),
                payload.startZ(),
                payload.lockX(),
                payload.lockZ());
        TRIPS.put(payload.entityId(), entry);

        if (isLocal(payload.entityId())) {
            impactParticlesSpawned = false;
            wakeRequested = false;
            movementWakeArmed = minecraft.player == null || minecraft.player.input == null
                    || !hasMovementInput(minecraft.player.input.keyPresses);
            lockedBodyRot = minecraft.player == null ? 0.0F : minecraft.player.getYRot();
            switchToTripCamera(minecraft);
            enforceLocal(minecraft.player, entry);
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
            return;
        TripEntry entry = TRIPS.get(minecraft.player.getId());
        if (entry == null || minecraft.level == null || !entry.isActive(minecraft.level))
            return;
        requestWakeIfInput(event.getInput().keyPresses);
        if (TRIPS.containsKey(minecraft.player.getId())) {
            event.getInput().keyPresses = Input.EMPTY;
            if (event.getEntity() instanceof LocalPlayer player) {
                player.xxa = 0.0F;
                player.zza = 0.0F;
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null)
            return;

        long now = minecraft.level.getGameTime();
        TRIPS.entrySet().removeIf(e -> now > e.getValue().activeUntilTick);

        TripEntry localEntry = TRIPS.get(minecraft.player.getId());
        if (localEntry == null) {
            clearLocal(minecraft.player);
            return;
        }
        enforceLocal(minecraft.player, localEntry);
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        Minecraft minecraft = Minecraft.getInstance();
        int entityId = event.getRenderState().id;
        TripEntry entry = TRIPS.get(entityId);
        if (entry == null || minecraft.level == null || !entry.isActive(minecraft.level)) {
            if (entry != null) {
                TRIPS.remove(entityId);
            }
            return;
        }

        Vec3 visual = entry.visualPosition(minecraft.level, event.getPartialTick());
        event.getRenderState().x = visual.x;
        event.getRenderState().z = visual.z;
        event.getRenderState().pose = Pose.STANDING;
        event.getRenderState().isCrouching = false;
        event.getRenderState().isVisuallySwimming = false;
        event.getRenderState().isInWater = false;
        event.getRenderState().swimAmount = entry.renderFallAmount(minecraft.level, event.getPartialTick());
        event.getRenderState().walkAnimationSpeed = 0.0F;
        event.getRenderState().walkAnimationPos = 0.0F;

        if (isLocal(entityId)) {
            event.getRenderState().bodyRot = lockedBodyRot;
        }
        event.getRenderState().yRot = 0.0F;
        event.getRenderState().xRot = 0.0F;
    }

    // ---- public query helpers ----

    public static boolean isTripping(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        TripEntry entry = TRIPS.get(entityId);
        return entry != null && minecraft.level != null && entry.isActive(minecraft.level);
    }

    public static float fallProgress(int entityId, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        TripEntry entry = TRIPS.get(entityId);
        if (entry == null || minecraft.level == null || entry.fallTicks <= 0) {
            return 1.0F;
        }
        double elapsed = minecraft.level.getGameTime() - entry.fallStartTick + partialTick;
        return (float) Math.max(0.0D, Math.min(1.0D, elapsed / entry.fallTicks));
    }

    public static float renderFallAmount(int entityId, float partialTick) {
        float raw = fallProgress(entityId, partialTick);
        return raw * raw * (3.0F - 2.0F * raw);
    }

    public static void requestWakeIfInput(Input input) {
        if (input == null)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        TripEntry entry = minecraft.player == null ? null : TRIPS.get(minecraft.player.getId());
        if (entry == null || minecraft.level == null || !entry.isWakeable(minecraft.level))
            return;
        if (!hasMovementInput(input)) {
            movementWakeArmed = true;
            return;
        }
        if (movementWakeArmed) {
            requestWake();
        }
    }

    // ---- local-player-only helpers ----

    private static boolean isLocal(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getId() == entityId;
    }

    private static void enforceLocal(LocalPlayer player, TripEntry entry) {
        if (player == null)
            return;
        player.setForcedPose(Pose.SWIMMING);
        player.setSprinting(false);
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 target = entry.visualPosition(minecraft.level, 0.0F);
        double targetX = target.x;
        double targetZ = target.z;
        boolean falling = entry.isFalling(minecraft.level);
        if (falling) {
            Vec3 previous = entry.visualPosition(minecraft.level, -1.0F);
            player.xo = previous.x;
            player.xOld = previous.x;
            player.zo = previous.z;
            player.zOld = previous.z;
        }
        double tolerance = falling ? 0.001D : 0.08D;
        if (Math.abs(player.getX() - targetX) > tolerance || Math.abs(player.getZ() - targetZ) > tolerance) {
            player.setPos(targetX, player.getY(), targetZ);
        }
        if (!falling && !impactParticlesSpawned) {
            impactParticlesSpawned = true;
            spawnImpactParticles(player);
        }
    }

    private static void requestWake() {
        if (wakeRequested) {
            return;
        }
        wakeRequested = true;
        ClientPacketDistributor.sendToServer(ClientRopeTripWakeRequest.INSTANCE);
        clearLocal(Minecraft.getInstance().player);
    }

    private static void switchToTripCamera(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null || cameraSwitched) {
            return;
        }
        previousCameraType = minecraft.options.getCameraType();
        if (previousCameraType != CameraType.THIRD_PERSON_BACK) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            cameraSwitched = true;
        }
    }

    private static void restoreCamera(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null || !cameraSwitched) {
            return;
        }
        minecraft.options.setCameraType(previousCameraType == null ? CameraType.FIRST_PERSON : previousCameraType);
        cameraSwitched = false;
        previousCameraType = null;
    }

    private static void spawnImpactParticles(LocalPlayer player) {
        if (player == null || player.level() == null) {
            return;
        }
        var random = player.getRandom();
        for (int i = 0; i < 7; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double speed = 0.035D + random.nextDouble() * 0.055D;
            double px = player.getX() + (random.nextDouble() - 0.5D) * 0.85D;
            double py = player.getY() + 0.08D + random.nextDouble() * 0.04D;
            double pz = player.getZ() + (random.nextDouble() - 0.5D) * 0.85D;
            player.level().addParticle(ParticleTypes.POOF, px, py, pz,
                    Math.cos(angle) * speed, 0.015D + random.nextDouble() * 0.025D, Math.sin(angle) * speed);
        }
    }

    private static void clearLocal(LocalPlayer player) {
        impactParticlesSpawned = false;
        wakeRequested = false;
        movementWakeArmed = false;
        restoreCamera(Minecraft.getInstance());
        if (player != null && player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
    }

    private static boolean hasMovementInput(Input input) {
        return input != null && (input.forward() || input.backward() || input.left() || input.right()
                || input.jump() || input.shift() || input.sprint());
    }

    // ---- per-entity trip state ----

    private static final class TripEntry {
        final long activeUntilTick;
        final long fallStartTick;
        final int fallTicks;
        final double startX;
        final double startZ;
        final double lockX;
        final double lockZ;

        TripEntry(long activeUntilTick, long fallStartTick, int fallTicks,
                double startX, double startZ, double lockX, double lockZ) {
            this.activeUntilTick = activeUntilTick;
            this.fallStartTick = fallStartTick;
            this.fallTicks = fallTicks;
            this.startX = startX;
            this.startZ = startZ;
            this.lockX = lockX;
            this.lockZ = lockZ;
        }

        boolean isActive(Level level) {
            return level != null && level.getGameTime() <= activeUntilTick;
        }

        boolean isFalling(Level level) {
            return level != null && fallTicks > 0 && level.getGameTime() - fallStartTick < fallTicks;
        }

        boolean isWakeable(Level level) {
            return isActive(level) && !isFalling(level);
        }

        float fallProgress(Level level, float partialTick) {
            if (level == null || fallTicks <= 0) {
                return 1.0F;
            }
            double elapsed = level.getGameTime() - fallStartTick + partialTick;
            return (float) Math.max(0.0D, Math.min(1.0D, elapsed / fallTicks));
        }

        float renderFallAmount(Level level, float partialTick) {
            float raw = fallProgress(level, partialTick);
            return raw * raw * (3.0F - 2.0F * raw);
        }

        Vec3 visualPosition(Level level, float partialTick) {
            if (level == null || fallTicks <= 0) {
                return new Vec3(lockX, 0.0D, lockZ);
            }
            double elapsed = level.getGameTime() - fallStartTick + partialTick;
            double progress = Math.max(0.0D, Math.min(1.0D, elapsed / fallTicks));
            progress = progress * progress * (3.0D - 2.0D * progress);
            double x = startX + (lockX - startX) * progress;
            double z = startZ + (lockZ - startZ) * progress;
            return new Vec3(x, 0.0D, z);
        }
    }
}
