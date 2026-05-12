package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.preset.OpenZoneCreateScreen;
import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.preset.ZoneSelectionClick;
import com.zhongbai233.super_lead.preset.ZoneSelectionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class ZoneSelectionClient {
    private static final DustParticleOptions SELECT_DUST = new DustParticleOptions(0x55FFAA, 1.0F);
    private static final DustParticleOptions MANAGED_DUST = new DustParticleOptions(0x66AAFF, 1.0F);
    private static final int MAX_EDGE_PARTICLES = 192;

    private static boolean active;
    private static BlockPos firstCorner;
    private static SyncPhysicsZones.Entry managedPreview;
    private static AABB createPreview;

    private ZoneSelectionClient() {
    }

    public static void apply(ZoneSelectionState state) {
        active = state.active();
        firstCorner = state.hasFirst() ? state.first() : null;
        if (active) {
            managedPreview = null;
            createPreview = null;
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static void openCreate(OpenZoneCreateScreen payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            createPreview = areaBox(payload.from(), payload.to());
            mc.setScreen(new ZoneCreateScreen(mc.screen, payload.from(), payload.to()));
        });
    }

    public static boolean tryHandleBlockClick(Player player, InteractionHand hand, BlockPos pos) {
        if (!active || player == null || !player.isShiftKeyDown())
            return false;
        if (!player.getItemInHand(hand).is(Items.SHEARS))
            return false;
        ClientPacketDistributor.sendToServer(new ZoneSelectionClick(pos.immutable()));
        return true;
    }

    public static void previewZone(SyncPhysicsZones.Entry zone) {
        managedPreview = zone;
        active = false;
        firstCorner = null;
    }

    public static void clearManagedPreview() {
        managedPreview = null;
    }

    public static void clearCreatePreview() {
        createPreview = null;
    }

    public static void clearAllPreviews() {
        active = false;
        firstCorner = null;
        managedPreview = null;
        createPreview = null;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAllPreviews();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clearAllPreviews();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.options.hideGui)
            return;

        if (managedPreview != null) {
            drawBox(level, managedPreview.toAabb(), MANAGED_DUST);
        }

        if (createPreview != null) {
            drawBox(level, createPreview, SELECT_DUST);
        }

        if (mc.screen != null)
            return;

        if (!active)
            return;
        BlockPos target = currentTargetBlock(mc);
        if (target == null && firstCorner == null)
            return;
        if (firstCorner == null) {
            drawBox(level, blockBox(target), SELECT_DUST);
        } else {
            drawBox(level, areaBox(firstCorner, target == null ? firstCorner : target), SELECT_DUST);
        }
    }

    private static BlockPos currentTargetBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static AABB blockBox(BlockPos p) {
        return new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1.0D, p.getY() + 1.0D, p.getZ() + 1.0D);
    }

    private static AABB areaBox(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0D,
                Math.max(a.getY(), b.getY()) + 1.0D,
                Math.max(a.getZ(), b.getZ()) + 1.0D);
    }

    private static void drawBox(ClientLevel level, AABB box, DustParticleOptions dust) {
        double sx = box.maxX - box.minX;
        double sy = box.maxY - box.minY;
        double sz = box.maxZ - box.minZ;
        double perimeter = Math.max(1.0D, 4.0D * (sx + sy + sz));
        double step = Math.max(0.35D, perimeter / MAX_EDGE_PARTICLES);
        drawEdge(level, dust, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, step);
        drawEdge(level, dust, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, step);
        drawEdge(level, dust, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, step);
        drawEdge(level, dust, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, step);

        drawEdge(level, dust, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, step);
        drawEdge(level, dust, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, step);
        drawEdge(level, dust, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, step);
        drawEdge(level, dust, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, step);

        drawEdge(level, dust, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, step);
        drawEdge(level, dust, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, step);
        drawEdge(level, dust, box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, step);
        drawEdge(level, dust, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, step);
    }

    private static void drawEdge(ClientLevel level, DustParticleOptions dust,
            double ax, double ay, double az, double bx, double by, double bz, double step) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            level.addParticle(dust, ax + dx * t, ay + dy * t, az + dz * t, 0.0D, 0.0D, 0.0D);
        }
    }
}