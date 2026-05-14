package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

public final class RopeDynamicLights {
    private static final double SOURCE_RENDER_DISTANCE = 80.0D;
    private static final double SOURCE_RENDER_DISTANCE_SQR = SOURCE_RENDER_DISTANCE * SOURCE_RENDER_DISTANCE;
    private static final double EFFECT_RADIUS = 7.75D;
    private static final double EFFECT_RADIUS_SQR = EFFECT_RADIUS * EFFECT_RADIUS;
    private static final double LIGHT_FALLOFF_PER_BLOCK = 2.0D;
    private static final double SOURCE_MOVE_DIRTY_DISTANCE_SQR = 0.25D * 0.25D;
    private static final int DIRTY_BLOCK_RADIUS = 8;
    private static final int MAX_LIGHT = 15;
    private static final int FALLBACK_NODE_COUNT = 16;
    private static final Map<BlockPos, Source> ACTIVE = new ConcurrentHashMap<>();
    private static ClientLevel activeLevel;

    private RopeDynamicLights() {
    }

    public static void update(ClientLevel level, Vec3 cameraPos, List<LeadConnection> connections,
            Function<UUID, RopeSimulation> simLookup,
            List<RopeAttachmentRenderer.BakedAttachment> staticAttachments,
            float partialTick) {
        if (level == null || cameraPos == null) {
            clear();
            return;
        }
        if (activeLevel != level) {
            clear();
            activeLevel = level;
        }

        Map<BlockPos, Source> desired = new HashMap<>();
        Set<UUID> staticConnectionIds = addStaticLights(level, cameraPos, staticAttachments, desired);

        for (LeadConnection connection : connections) {
            if (connection.attachments().isEmpty() || staticConnectionIds.contains(connection.id())) {
                continue;
            }
            RopeSimulation sim = simLookup == null ? null : simLookup.apply(connection.id());
            addConnectionLights(level, cameraPos, connections, connection, sim, partialTick, desired);
        }

        apply(level, desired);
    }

    public static void clear() {
        ClientLevel level = activeLevel;
        List<BlockPos> oldPositions = List.copyOf(ACTIVE.keySet());
        ACTIVE.clear();
        activeLevel = null;
        if (level != null) {
            markDirty(level, oldPositions);
        }
    }

    public static int boostPackedLight(BlockPos pos, int packedLight) {
        int dynamic = dynamicBlockLightAt(pos);
        if (dynamic <= LightCoordsUtil.block(packedLight)) {
            return packedLight;
        }
        return LightCoordsUtil.withBlock(packedLight, dynamic);
    }

    public static int boostBlockLight(BlockPos pos, int blockLight) {
        return Math.max(blockLight, dynamicBlockLightAt(pos));
    }

    public static int dynamicBlockLightAt(BlockPos pos) {
        if (activeLevel == null || pos == null || ACTIVE.isEmpty()) {
            return 0;
        }

        double qx = pos.getX() + 0.5D;
        double qy = pos.getY() + 0.5D;
        double qz = pos.getZ() + 0.5D;
        int best = 0;
        for (Source source : ACTIVE.values()) {
            double dx = qx - source.x;
            double dy = qy - source.y;
            double dz = qz - source.z;
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr > EFFECT_RADIUS_SQR) {
                continue;
            }
            int light = (int) Math.ceil(source.blockLight - Math.sqrt(distSqr) * LIGHT_FALLOFF_PER_BLOCK);
            if (light > best) {
                best = light;
                if (best >= MAX_LIGHT) {
                    return MAX_LIGHT;
                }
            }
        }
        return Math.max(0, Math.min(MAX_LIGHT, best));
    }

    private static Set<UUID> addStaticLights(ClientLevel level, Vec3 cameraPos,
            List<RopeAttachmentRenderer.BakedAttachment> attachments, Map<BlockPos, Source> desired) {
        if (attachments == null || attachments.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>();
        for (RopeAttachmentRenderer.BakedAttachment attachment : attachments) {
            ids.add(attachment.connectionId());
            addLight(level, cameraPos, attachment.stack(), attachment.redstonePowered(),
                    attachment.lightX(), attachment.lightY(), attachment.lightZ(), desired);
        }
        return ids;
    }

    private static void addConnectionLights(ClientLevel level, Vec3 cameraPos, List<LeadConnection> connections,
            LeadConnection connection, RopeSimulation sim, float partialTick, Map<BlockPos, Source> desired) {
        boolean redstonePowered = connection.kind() == com.zhongbai233.super_lead.lead.LeadKind.REDSTONE
                && connection.powered();
        if (sim != null) {
            double total = sim.prepareRender(partialTick);
            if (total <= 1.0e-6D || sim.nodeCount() < 2)
                return;
            for (RopeAttachment attachment : connection.attachments()) {
                addSimAttachmentLight(level, cameraPos, sim, total, attachment, redstonePowered, desired);
            }
            return;
        }

        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection, connections);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        double len = a.distanceTo(b);
        if (len <= 1.0e-6D)
            return;
        RopeTuning tuning = RopeTuning.forConnection(connection);
        Vec3 fallback = RopeSagModel.stableUnitVector(connection.id().getLeastSignificantBits());
        for (RopeAttachment attachment : connection.attachments()) {
            double t = attachment.t();
            Vec3 p = RopeSagModel.point(a, b, t, tuning.slack(), tuning.gravity(), fallback);
            double dt = 1.0D / (FALLBACK_NODE_COUNT - 1);
            double ta = Math.max(0.0D, t - dt);
            double tb = Math.min(1.0D, t + dt);
            Vec3 pa = RopeSagModel.point(a, b, ta, tuning.slack(), tuning.gravity(), fallback);
            Vec3 pb = RopeSagModel.point(a, b, tb, tuning.slack(), tuning.gravity(), fallback);
            Vec3 light = attachmentLightPosition(level, attachment.stack(), attachment.displayAsBlock(),
                attachment.frontSide(), p.x, p.y, p.z, pa.x, pa.y, pa.z, pb.x, pb.y, pb.z);
            addLight(level, cameraPos, attachment.stack(), redstonePowered,
                    light.x, light.y, light.z, desired);
        }
    }

    private static void addSimAttachmentLight(ClientLevel level, Vec3 cameraPos, RopeSimulation sim, double total,
            RopeAttachment attachment, boolean redstonePowered, Map<BlockPos, Source> desired) {
        double target = attachment.t() * total;
        int nodeCount = sim.nodeCount();
        int seg = locateSegment(sim, nodeCount, target);
        if (seg < 0)
            return;
        double l0 = sim.renderLength(seg);
        double l1 = sim.renderLength(seg + 1);
        double span = l1 - l0;
        double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
        double ax = sim.renderX(seg), ay = sim.renderY(seg), az = sim.renderZ(seg);
        double bx = sim.renderX(seg + 1), by = sim.renderY(seg + 1), bz = sim.renderZ(seg + 1);
        double px = ax + (bx - ax) * frac;
        double py = ay + (by - ay) * frac;
        double pz = az + (bz - az) * frac;
        Vec3 light = attachmentLightPosition(level, attachment.stack(), attachment.displayAsBlock(),
                attachment.frontSide(), px, py, pz, ax, ay, az, bx, by, bz);
        addLight(level, cameraPos, attachment.stack(), redstonePowered,
                light.x, light.y, light.z, desired);
    }

    private static void addLight(ClientLevel level, Vec3 cameraPos, ItemStack stack, boolean redstonePowered,
            double x, double y, double z, Map<BlockPos, Source> desired) {
        if (stack.isEmpty() || new Vec3(x, y, z).distanceToSqr(cameraPos) > SOURCE_RENDER_DISTANCE_SQR) {
            return;
        }
        BlockPos pos = lightSourcePos(level, x, y, z);
        int light = lightEmission(level, pos, stack, redstonePowered);
        if (light <= 0) {
            return;
        }
        Source source = new Source(x, y, z, light);
        desired.merge(pos, source, (a, b) -> a.blockLight >= b.blockLight ? a : b);
    }

    private static int lightEmission(ClientLevel level, BlockPos pos, ItemStack stack, boolean redstonePowered) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return 0;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (redstonePowered) {
            // Mirror the visual change applied in submitBlockForm so the dynamic light
            // level matches what the block actually looks like at this moment.
            // Redstone torches (LIT=true by default) go dark when powered.
            // Redstone lamps (LIT=false by default) light up when powered.
            if (state.hasProperty(BlockStateProperties.LIT)) {
                boolean defaultLit = state.getValue(BlockStateProperties.LIT);
                state = state.setValue(BlockStateProperties.LIT, !defaultLit);
            }
            if (state.hasProperty(BlockStateProperties.POWERED)) {
                state = state.setValue(BlockStateProperties.POWERED, Boolean.TRUE);
            }
        }
        return Math.max(0, Math.min(MAX_LIGHT, state.getLightEmission(level, pos)));
    }

    public static Vec3 attachmentLightPosition(ClientLevel level, ItemStack stack, boolean displayAsBlock,
            int frontSide,
            double px, double py, double pz,
            double ax, double ay, double az,
            double bx, double by, double bz) {
        return RopeAttachmentRenderer.attachmentBodyCenter(level, stack, displayAsBlock, frontSide,
                px, py, pz, ax, ay, az, bx, by, bz);
    }

    private static void apply(ClientLevel level, Map<BlockPos, Source> desired) {
        Set<BlockPos> dirty = new HashSet<>();
        for (Map.Entry<BlockPos, Source> entry : desired.entrySet()) {
            Source old = ACTIVE.get(entry.getKey());
            Source next = entry.getValue();
            if (old == null || old.blockLight != next.blockLight
                    || old.distanceToSqr(next) > SOURCE_MOVE_DIRTY_DISTANCE_SQR) {
                dirty.add(entry.getKey());
            }
            ACTIVE.put(entry.getKey(), next);
        }
        for (BlockPos old : List.copyOf(ACTIVE.keySet())) {
            if (!desired.containsKey(old)) {
                ACTIVE.remove(old);
                dirty.add(old);
            }
        }
        markDirty(level, dirty);
    }

    private static BlockPos lightSourcePos(ClientLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        if (canEmitFrom(level, base)) {
            return base.immutable();
        }
        for (Direction direction : Direction.values()) {
            BlockPos next = base.relative(direction);
            if (canEmitFrom(level, next)) {
                return next.immutable();
            }
        }
        return base.immutable();
    }

    private static boolean canEmitFrom(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getLightDampening() < 15;
    }

    private static void markDirty(ClientLevel level, Iterable<BlockPos> positions) {
        if (level == null || positions == null)
            return;
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer renderer = mc == null ? null : mc.levelRenderer;
        if (renderer == null)
            return;

        for (BlockPos pos : positions) {
            try {
                renderer.setBlocksDirty(
                        pos.getX() - DIRTY_BLOCK_RADIUS,
                        pos.getY() - DIRTY_BLOCK_RADIUS,
                        pos.getZ() - DIRTY_BLOCK_RADIUS,
                        pos.getX() + DIRTY_BLOCK_RADIUS,
                        pos.getY() + DIRTY_BLOCK_RADIUS,
                        pos.getZ() + DIRTY_BLOCK_RADIUS);
            } catch (RuntimeException ignored) {
                // The renderer can be between level/view-area lifetimes while disconnecting.
            }
        }
    }

    private static int locateSegment(RopeSimulation sim, int nodeCount, double target) {
        if (target <= 0.0D)
            return 0;
        for (int i = 0; i < nodeCount - 1; i++) {
            if (target <= sim.renderLength(i + 1))
                return i;
        }
        return nodeCount - 2;
    }

    private record Source(double x, double y, double z, int blockLight) {
        double distanceToSqr(Source other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
