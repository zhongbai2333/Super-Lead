package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.ClientRopeTripWakeRequest;
import com.zhongbai233.super_lead.lead.SyncRopeTripState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
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
 */
@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class RopeTripClientState {
    private static long activeUntilTick;
    private static long fallStartTick;
    private static int fallTicks;
    private static double startX;
    private static double startZ;
    private static double lockX;
    private static double lockZ;
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
            clear(minecraft.player);
            return;
        }
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        activeUntilTick = now + Math.max(1, payload.remainingTicks()) + 2L;
        fallStartTick = now;
        fallTicks = Math.max(0, payload.fallTicks());
        startX = payload.startX();
        startZ = payload.startZ();
        lockX = payload.lockX();
        lockZ = payload.lockZ();
        impactParticlesSpawned = false;
        wakeRequested = false;
        movementWakeArmed = minecraft.player == null || minecraft.player.input == null
                || !hasMovementInput(minecraft.player.input.keyPresses);
        lockedBodyRot = minecraft.player == null ? 0.0F : minecraft.player.getYRot();
        switchToTripCamera(minecraft);
        enforce(minecraft.player);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isActive()) {
            return;
        }
        requestWakeIfInput(event.getInput().keyPresses);
        if (isActive()) {
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
        if (!isActive()) {
            clear(minecraft.player);
            return;
        }
        enforce(minecraft.player);
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !isActive() || event.getRenderState().id != player.getId()) {
            return;
        }
        Vec3 visual = visualPosition(event.getPartialTick());
        event.getRenderState().x = visual.x;
        event.getRenderState().z = visual.z;
        event.getRenderState().pose = Pose.STANDING;
        event.getRenderState().isCrouching = false;
        event.getRenderState().isVisuallySwimming = false;
        event.getRenderState().isInWater = false;
        event.getRenderState().swimAmount = renderFallAmount(event.getPartialTick());
        event.getRenderState().walkAnimationSpeed = 0.0F;
        event.getRenderState().walkAnimationPos = 0.0F;
        event.getRenderState().bodyRot = lockedBodyRot;
        event.getRenderState().yRot = 0.0F;
        event.getRenderState().xRot = 0.0F;
    }

    private static boolean isActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            activeUntilTick = 0L;
            return false;
        }
        if (minecraft.level.getGameTime() > activeUntilTick) {
            activeUntilTick = 0L;
            return false;
        }
        return true;
    }

    public static boolean isTripping(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getId() == entityId && isActive();
    }

    public static float fallProgress(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || fallTicks <= 0) {
            return 1.0F;
        }
        double elapsed = minecraft.level.getGameTime() - fallStartTick + partialTick;
        return (float) Math.max(0.0D, Math.min(1.0D, elapsed / fallTicks));
    }

    public static float renderFallAmount(float partialTick) {
        float raw = fallProgress(partialTick);
        return raw * raw * (3.0F - 2.0F * raw);
    }

    public static void requestWakeIfInput(Input input) {
        if (!isWakeable() || input == null) {
            return;
        }
        if (!hasMovementInput(input)) {
            movementWakeArmed = true;
            return;
        }
        if (movementWakeArmed) {
            requestWake();
        }
    }

    private static void enforce(LocalPlayer player) {
        if (player == null) {
            return;
        }
        player.setForcedPose(Pose.SWIMMING);
        player.setSprinting(false);
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);
        Vec3 target = visualPosition(0.0F);
        double targetX = target.x;
        double targetZ = target.z;
        boolean falling = isFalling();
        if (falling) {
            Vec3 previous = visualPosition(-1.0F);
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

    private static Vec3 visualPosition(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || fallTicks <= 0) {
            return new Vec3(lockX, 0.0D, lockZ);
        }
        double elapsed = minecraft.level.getGameTime() - fallStartTick + partialTick;
        double progress = Math.max(0.0D, Math.min(1.0D, elapsed / fallTicks));
        progress = progress * progress * (3.0D - 2.0D * progress);
        double x = startX + (lockX - startX) * progress;
        double z = startZ + (lockZ - startZ) * progress;
        return new Vec3(x, 0.0D, z);
    }

    private static boolean isFalling() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && fallTicks > 0
                && minecraft.level.getGameTime() - fallStartTick < fallTicks;
    }

    private static boolean isWakeable() {
        return isActive() && !isFalling();
    }

    private static boolean hasMovementInput(Input input) {
        return input != null && (input.forward() || input.backward() || input.left() || input.right()
                || input.jump() || input.shift() || input.sprint());
    }

    private static void requestWake() {
        if (wakeRequested) {
            return;
        }
        wakeRequested = true;
        ClientPacketDistributor.sendToServer(ClientRopeTripWakeRequest.INSTANCE);
        clear(Minecraft.getInstance().player);
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

    private static void clear(LocalPlayer player) {
        activeUntilTick = 0L;
        fallStartTick = 0L;
        fallTicks = 0;
        impactParticlesSpawned = false;
        wakeRequested = false;
        movementWakeArmed = false;
        restoreCamera(Minecraft.getInstance());
        if (player != null && player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
    }
}
