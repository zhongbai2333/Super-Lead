package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadConnectionAction;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class SuperLeadClientEvents {
    private static final double PICK_RADIUS = 0.30D;

    private static final Map<UUID, RopeSimulation> SIMS = new HashMap<>();
    private static RopeSimulation previewSim;
    private static LeadAnchor previewAnchor;
    private static long lastRepelTick = Long.MIN_VALUE;

    private SuperLeadClientEvents() {}

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            SIMS.clear();
            previewSim = null;
            previewAnchor = null;
            return;
        }

        SuperLeadNetwork.pruneInvalid(level);
        long tick = level.getGameTime();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();

        Set<UUID> active = new HashSet<>();
        List<RenderEntry> renderEntries = new ArrayList<>();
        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            active.add(connection.id());
            Vec3 a = connection.from().attachmentPoint(level);
            Vec3 b = connection.to().attachmentPoint(level);
            RopeSimulation sim = SIMS.get(connection.id());
            if (sim == null || !sim.matchesLength(a, b)) {
                sim = new RopeSimulation(a, b, connection.id().getLeastSignificantBits(), true);
                SIMS.put(connection.id(), sim);
            }
            sim.stepUpTo(level, a, b, tick);
            renderEntries.add(new RenderEntry(connection, sim, a, b));
        }

        // 推开仅在 tick 推进时跑一次：避免帧率越高被推得越多导致两绳间互相过推→震荡。
        if (tick != lastRepelTick) {
            lastRepelTick = tick;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < renderEntries.size(); i++) {
                    for (int j = i + 1; j < renderEntries.size(); j++) {
                        renderEntries.get(i).sim().repelFrom(renderEntries.get(j).sim());
                    }
                }
            }
        }

        ConnectionHighlight highlight = pickHighlightedConnection(minecraft, renderEntries, partialTick, cameraPos);

        for (RenderEntry entry : renderEntries) {
            Vec3 a = entry.a();
            Vec3 b = entry.b();
            RopeSimulation sim = entry.sim();

            BlockPos lightA = BlockPos.containing(a);
            BlockPos lightB = BlockPos.containing(b);
            int blockA = level.getBrightness(LightLayer.BLOCK, lightA);
            int blockB = level.getBrightness(LightLayer.BLOCK, lightB);
            int skyA = level.getBrightness(LightLayer.SKY, lightA);
            int skyB = level.getBrightness(LightLayer.SKY, lightB);
                int highlightColor = highlight != null && entry.connection().id().equals(highlight.id())
                    ? highlight.color()
                    : LeashBuilder.NO_HIGHLIGHT;
            LeashBuilder.submit(event.getSubmitNodeCollector(), cameraPos, sim, partialTick,
                    blockA, blockB, skyA, skyB, highlightColor,
                    entry.connection().kind(), entry.connection().powered());
                spawnRedstoneParticles(level, sim, partialTick, entry.connection());
        }
        SIMS.keySet().retainAll(active);

        Player player = minecraft.player;
        if (player != null) {
            renderPreview(event, level, cameraPos, partialTick, tick, player);
        } else {
            previewSim = null;
            previewAnchor = null;
        }
    }

    private static void renderPreview(SubmitCustomGeometryEvent event, ClientLevel level, Vec3 cameraPos, float partialTick, long tick, Player player) {
        LeadAnchor anchor = SuperLeadNetwork.pendingAnchor(player).orElse(null);
        if (anchor == null) {
            previewSim = null;
            previewAnchor = null;
            return;
        }
        Vec3 a = anchor.attachmentPoint(level);
        Vec3 b = player.getRopeHoldPosition(partialTick);
        if (a.distanceTo(b) > SuperLeadNetwork.MAX_LEASH_DISTANCE) {
            return;
        }
        if (previewSim == null || !anchor.equals(previewAnchor)) {
            previewSim = new RopeSimulation(a, b);
            previewAnchor = anchor;
        }
        previewSim.stepUpTo(level, a, b, tick);

        BlockPos lightA = BlockPos.containing(a);
        BlockPos lightB = BlockPos.containing(b);
        LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(LeadKind.NORMAL);
        int blockA = level.getBrightness(LightLayer.BLOCK, lightA);
        int blockB = level.getBrightness(LightLayer.BLOCK, lightB);
        int skyA = level.getBrightness(LightLayer.SKY, lightA);
        int skyB = level.getBrightness(LightLayer.SKY, lightB);
        LeashBuilder.submit(event.getSubmitNodeCollector(), cameraPos, previewSim, partialTick,
                blockA, blockB, skyA, skyB, false, kind, false);
    }

    private static void spawnRedstoneParticles(ClientLevel level, RopeSimulation sim, float partialTick, LeadConnection connection) {
        RandomSource random = level.getRandom();
        if (connection.kind() != LeadKind.REDSTONE || !connection.powered() || random.nextFloat() > 0.035F) {
            return;
        }

        int segment = random.nextInt(Math.max(1, sim.nodeCount() - 1));
        Vec3 a = sim.nodeAt(segment, partialTick);
        Vec3 b = sim.nodeAt(segment + 1, partialTick);
        double t = random.nextDouble();
        Vec3 point = a.add(b.subtract(a).scale(t));
        double jitter = 0.035D;
        level.addParticle(
                DustParticleOptions.REDSTONE,
                point.x + (random.nextDouble() - 0.5D) * jitter,
                point.y + (random.nextDouble() - 0.5D) * jitter,
                point.z + (random.nextDouble() - 0.5D) * jitter,
                0.0D,
                0.0D,
                0.0D);
    }

    private static ConnectionHighlight pickHighlightedConnection(Minecraft minecraft, List<RenderEntry> entries, float partialTick, Vec3 cameraPos) {
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        LeadConnectionAction action = LeadConnectionAction.fromHeldItems(player).orElse(null);
        if (action == null) {
            return null;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        Vec3 rayDir = new Vec3(forward.x(), forward.y(), forward.z()).normalize();
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        double best = PICK_RADIUS * PICK_RADIUS;
        UUID bestId = null;

        for (RenderEntry entry : entries) {
            if (!action.canTarget(entry.connection())) {
                continue;
            }
            RopeSimulation sim = entry.sim();
            for (int i = 0; i < sim.nodeCount() - 1; i++) {
                Vec3 a = sim.nodeAt(i, partialTick);
                Vec3 b = sim.nodeAt(i + 1, partialTick);
                for (int sample = 0; sample <= 4; sample++) {
                    double t = sample / 4.0D;
                    Vec3 point = a.add(b.subtract(a).scale(t));
                    double distance = distancePointToRaySqr(point, cameraPos, rayDir, maxDistance);
                    if (distance < best) {
                        best = distance;
                        bestId = entry.connection().id();
                    }
                }
            }
        }
        return bestId == null ? null : new ConnectionHighlight(bestId, action.previewColor());
    }

    private static double distancePointToRaySqr(Vec3 point, Vec3 origin, Vec3 direction, double maxDistance) {
        Vec3 offset = point.subtract(origin);
        double along = offset.dot(direction);
        if (along < 0.0D || along > maxDistance) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 closest = origin.add(direction.scale(along));
        return point.distanceToSqr(closest);
    }

    private record RenderEntry(LeadConnection connection, RopeSimulation sim, Vec3 a, Vec3 b) {}

    private record ConnectionHighlight(UUID id, int color) {}
}
