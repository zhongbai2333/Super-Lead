package com.zhongbai233.super_lead.lead.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SyncZiplines;
import com.zhongbai233.super_lead.lead.ZiplineController;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Client zipline snapshot plus the folded chain visual hanging over the rope. */
public final class ZiplineClientState {
    private static final Map<Integer, Entry> ACTIVE = new HashMap<>();
    private static final Map<Integer, Boolean> PREVIOUS_NO_PHYSICS = new HashMap<>();
    private static final int CHAIN_COLOR = 0xFF6F7780;
    private static final int CHAIN_DARK_COLOR = 0xFF30363D;
    private static final float CHAIN_HALF_THICKNESS = 0.026F;
    private static final double CHAIN_LINK_STEP = 0.145D;
    private static final double CHAIN_LINK_LENGTH = 0.105D;
    private static final int MAX_LINKS_PER_SPAN = 28;
    private static final double VISUAL_HANG_HEIGHT = 1.97D;
    private static final PoseStack IDENTITY_POSE = new PoseStack();

    private ZiplineClientState() {
    }

    public static void apply(SyncZiplines payload) {
        Set<Integer> seen = new HashSet<>();
        for (SyncZiplines.Entry entry : payload.entries()) {
            seen.add(entry.entityId());
            enableClientNoPhysics(entry.entityId());
            Entry previous = ACTIVE.get(entry.entityId());
            if (previous != null && previous.connectionId().equals(entry.connectionId())) {
                previous.update(entry.t());
            } else {
                ACTIVE.put(entry.entityId(), new Entry(entry.connectionId(), entry.t()));
            }
        }
        ACTIVE.keySet().removeIf(id -> !seen.contains(id));
        restoreInactiveNoPhysics(seen);
    }

    public static void clear() {
        restoreAllNoPhysics();
        ACTIVE.clear();
    }

    public static boolean isZiplining(int entityId) {
        return ACTIVE.containsKey(entityId);
    }

    public static boolean shouldHideMainHandChain(int entityId, ItemStack stack) {
        return isZiplining(entityId) && ZiplineController.isChain(stack);
    }

    public static boolean hasRiderOn(UUID connectionId) {
        if (ACTIVE.isEmpty()) {
            return false;
        }
        for (Entry entry : ACTIVE.values()) {
            if (entry.connectionId().equals(connectionId)) {
                return true;
            }
        }
        return false;
    }

    private static void enableClientNoPhysics(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        PREVIOUS_NO_PHYSICS.putIfAbsent(entityId, entity.noPhysics);
        entity.noPhysics = true;
    }

    private static void restoreInactiveNoPhysics(Set<Integer> activeIds) {
        if (PREVIOUS_NO_PHYSICS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        PREVIOUS_NO_PHYSICS.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) {
                return false;
            }
            if (level != null) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity != null) {
                    entity.noPhysics = entry.getValue();
                }
            }
            return true;
        });
    }

    private static void restoreAllNoPhysics() {
        if (PREVIOUS_NO_PHYSICS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            for (Map.Entry<Integer, Boolean> entry : PREVIOUS_NO_PHYSICS.entrySet()) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity != null) {
                    entity.noPhysics = entry.getValue();
                }
            }
        }
        PREVIOUS_NO_PHYSICS.clear();
    }

    public static void submitVisuals(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            float partialTick,
            Function<UUID, RopeSimulation> simLookup) {
        if (ACTIVE.isEmpty()) {
            return;
        }

        collector.submitCustomGeometry(IDENTITY_POSE, RenderTypes.textBackground(), (pose, buffer) -> {
            for (Map.Entry<Integer, Entry> active : ACTIVE.entrySet()) {
                Entity entity = level.getEntity(active.getKey());
                if (entity == null) {
                    continue;
                }
                LeadConnection connection = SuperLeadNetwork.findConnectionById(level, active.getValue().connectionId())
                        .orElse(null);
                if (connection == null) {
                    continue;
                }
                RopePoint ropePoint = ropePoint(level, connection, active.getValue(), entity, partialTick, simLookup);
                if (ropePoint == null) {
                    continue;
                }
                emitFoldedChain(buffer, cameraPos, entity, partialTick, ropePoint.point(), ropePoint.tangent());
            }
        });
    }

    private static RopePoint ropePoint(ClientLevel level, LeadConnection connection, Entry state,
            Entity entity, float partialTick, Function<UUID, RopeSimulation> simLookup) {
        RopeSimulation sim = simLookup.apply(state.connectionId());
        double stateT = state.renderT(partialTick);
        Vec3 riderRopePoint = entity.getPosition(partialTick).add(0.0D, VISUAL_HANG_HEIGHT, 0.0D);
        if (sim != null) {
            double total = sim.prepareRender(partialTick);
            if (total > 1.0e-6D) {
                return projectOntoRenderedRope(sim, riderRopePoint, stateT);
            }
        }

        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                SuperLeadNetwork.connections(level));
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double t = clamp01(stateT);
        double sag = Math.min(0.70D, a.distanceTo(b) * 0.055D);
        Vec3 tangent = new Vec3(
                b.x - a.x,
                b.y - a.y - Math.PI * Math.cos(Math.PI * t) * sag,
                b.z - a.z);
        if (tangent.lengthSqr() < 1.0e-8D) {
            tangent = new Vec3(1.0D, 0.0D, 0.0D);
        }
        // No active sim (static/LOD fallback): bind the folded chain to the current
        // rendered rider position so it does not wait for the next network zipline
        // snapshot before catching up to the player's head.
        return new RopePoint(riderRopePoint, tangent.normalize());
    }

    private static RopePoint projectOntoRenderedRope(RopeSimulation sim, Vec3 target, double hintT) {
        double bestScore = Double.POSITIVE_INFINITY;
        Vec3 bestPoint = null;
        Vec3 bestTangent = null;
        int last = sim.nodeCount() - 1;
        double total = Math.max(1.0e-6D, sim.renderLength(last));
        for (int i = 0; i < last; i++) {
            Vec3 a = new Vec3(sim.renderX(i), sim.renderY(i), sim.renderZ(i));
            Vec3 b = new Vec3(sim.renderX(i + 1), sim.renderY(i + 1), sim.renderZ(i + 1));
            Vec3 ab = b.subtract(a);
            double lenSqr = ab.lengthSqr();
            if (lenSqr < 1.0e-9D) {
                continue;
            }
            double frac = clamp01(target.subtract(a).dot(ab) / lenSqr);
            Vec3 point = a.add(ab.scale(frac));
            double arc = sim.renderLength(i) + (sim.renderLength(i + 1) - sim.renderLength(i)) * frac;
            double t = clamp01(arc / total);
            double hintPenalty = Math.abs(t - hintT) * 0.18D;
            double score = point.distanceToSqr(target) + hintPenalty;
            if (score < bestScore) {
                bestScore = score;
                bestPoint = point;
                bestTangent = ab;
            }
        }
        if (bestPoint == null || bestTangent == null || bestTangent.lengthSqr() < 1.0e-9D) {
            return new RopePoint(target, new Vec3(1.0D, 0.0D, 0.0D));
        }
        return new RopePoint(bestPoint, bestTangent.normalize());
    }

    private static void emitFoldedChain(VertexConsumer buffer, Vec3 cameraPos, Entity entity,
            float partialTick, Vec3 ropePoint, Vec3 ropeTangent) {
        Vec3 base = entity.getPosition(partialTick);
        double yaw = Math.toRadians(entity.getYRot(partialTick));
        Vec3 side = new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw));
        if (side.lengthSqr() < 1.0e-8D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        side = side.normalize();
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 handCenter = base.add(forward.scale(0.05D)).add(0.0D, entity.getBbHeight() * 0.92D, 0.0D);
        Vec3 leftHand = handCenter.add(side.scale(0.34D));
        Vec3 rightHand = handCenter.add(side.scale(-0.34D));

        Vec3 topSide = side;
        if (Math.abs(ropeTangent.dot(topSide)) > 0.85D) {
            topSide = new Vec3(-ropeTangent.z, 0.0D, ropeTangent.x);
            if (topSide.lengthSqr() < 1.0e-8D) {
                topSide = side;
            } else {
                topSide = topSide.normalize();
            }
        }
        Vec3 overLeft = ropePoint.add(topSide.scale(0.16D)).add(0.0D, 0.035D, 0.0D);
        Vec3 overRight = ropePoint.add(topSide.scale(-0.16D)).add(0.0D, 0.035D, 0.0D);
        Vec3 bend = ropePoint.add(0.0D, -0.055D, 0.0D);

        emitChain(buffer, cameraPos, overLeft, bend, 0);
        emitChain(buffer, cameraPos, bend, overRight, 1);
        emitChain(buffer, cameraPos, leftHand, overLeft, 2);
        emitChain(buffer, cameraPos, rightHand, overRight, 3);
    }

    private static void emitChain(VertexConsumer buffer, Vec3 cameraPos, Vec3 a, Vec3 b, int phase) {
        double length = a.distanceTo(b);
        if (length < 1.0e-5D) {
            return;
        }
        int links = Math.max(1, Math.min(MAX_LINKS_PER_SPAN, (int) Math.ceil(length / CHAIN_LINK_STEP)));
        Vec3 dir = b.subtract(a).scale(1.0D / length);
        double half = Math.min(CHAIN_LINK_LENGTH * 0.5D, length / links * 0.42D);
        for (int i = 0; i < links; i++) {
            double centerDistance = (i + 0.5D) * length / links;
            Vec3 p0 = a.add(dir.scale(centerDistance - half));
            Vec3 p1 = a.add(dir.scale(centerDistance + half));
            int color = ((i + phase) & 1) == 0 ? CHAIN_COLOR : CHAIN_DARK_COLOR;
            emitLink(buffer, cameraPos, p0, p1, color, (i + phase) & 1);
        }
    }

    private static void emitLink(VertexConsumer buffer, Vec3 cameraPos, Vec3 a, Vec3 b, int color, int twist) {
        float ax = (float) (a.x - cameraPos.x);
        float ay = (float) (a.y - cameraPos.y);
        float az = (float) (a.z - cameraPos.z);
        float bx = (float) (b.x - cameraPos.x);
        float by = (float) (b.y - cameraPos.y);
        float bz = (float) (b.z - cameraPos.z);
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5F) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        float uz = dz / len;
        float nx;
        float ny;
        float nz;
        if (Math.abs(uy) > 0.96F) {
            nx = 1.0F;
            ny = 0.0F;
            nz = 0.0F;
        } else {
            nx = uz;
            ny = 0.0F;
            nz = -ux;
            float nLen = (float) Math.sqrt(nx * nx + nz * nz);
            nx /= nLen;
            nz /= nLen;
        }
        float mx = uy * nz - uz * ny;
        float my = uz * nx - ux * nz;
        float mz = ux * ny - uy * nx;
        float w = CHAIN_HALF_THICKNESS;
        nx *= w;
        ny *= w;
        nz *= w;
        mx *= w;
        my *= w;
        mz *= w;
        int light = LightCoordsUtil.pack(15, 15);
        if (twist == 0) {
            quad(buffer, ax - nx, ay - ny, az - nz, bx - nx, by - ny, bz - nz,
                bx + nx, by + ny, bz + nz, ax + nx, ay + ny, az + nz, color, light);
            quad(buffer, ax - mx * 0.55F, ay - my * 0.55F, az - mz * 0.55F,
                bx - mx * 0.55F, by - my * 0.55F, bz - mz * 0.55F,
                bx + mx * 0.55F, by + my * 0.55F, bz + mz * 0.55F,
                ax + mx * 0.55F, ay + my * 0.55F, az + mz * 0.55F, color, light);
        } else {
            quad(buffer, ax - mx, ay - my, az - mz, bx - mx, by - my, bz - mz,
                bx + mx, by + my, bz + mz, ax + mx, ay + my, az + mz, color, light);
            quad(buffer, ax - nx * 0.55F, ay - ny * 0.55F, az - nz * 0.55F,
                bx - nx * 0.55F, by - ny * 0.55F, bz - nz * 0.55F,
                bx + nx * 0.55F, by + ny * 0.55F, bz + nz * 0.55F,
                ax + nx * 0.55F, ay + ny * 0.55F, az + nz * 0.55F, color, light);
        }
    }

    private static void quad(VertexConsumer buffer,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            int color, int light) {
        float nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
        float ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
        float nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 1.0e-6F) {
            nx = 0.0F;
            ny = 1.0F;
            nz = 0.0F;
        } else {
            nx /= nLen;
            ny /= nLen;
            nz /= nLen;
        }
        vertex(buffer, ax, ay, az, color, light);
        vertex(buffer, bx, by, bz, color, light);
        vertex(buffer, cx, cy, cz, color, light);
        vertex(buffer, dx, dy, dz, color, light);
        vertex(buffer, dx, dy, dz, color, light);
        vertex(buffer, cx, cy, cz, color, light);
        vertex(buffer, bx, by, bz, color, light);
        vertex(buffer, ax, ay, az, color, light);
    }

    private static void vertex(VertexConsumer buffer, float x, float y, float z, int color, int light) {
        buffer.addVertex(x, y, z).setColor(color).setLight(light);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private static final class Entry {
        private final UUID connectionId;
        private float previousT;
        private float currentT;

        private Entry(UUID connectionId, float t) {
            this.connectionId = connectionId;
            this.previousT = t;
            this.currentT = t;
        }

        private UUID connectionId() {
            return connectionId;
        }

        private void update(float t) {
            this.previousT = this.currentT;
            this.currentT = t;
        }

        private double renderT(float partialTick) {
            double delta = currentT - previousT;
            if (Math.abs(delta) > 0.65D) {
                return currentT;
            }
            return previousT + delta * partialTick;
        }
    }

    private record RopePoint(Vec3 point, Vec3 tangent) {
    }
}