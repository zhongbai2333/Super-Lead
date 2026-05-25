package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.AddRopeAttachment;
import com.zhongbai233.super_lead.lead.BoostRopePerch;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadConnectionAction;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.OpenRopeAeTerminal;
import com.zhongbai233.super_lead.lead.RemoveRopeAttachment;
import com.zhongbai233.super_lead.lead.StartZipline;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.ToggleRopeAttachmentForm;
import com.zhongbai233.super_lead.lead.UpdateSignAttachmentAppearance;
import com.zhongbai233.super_lead.lead.UseConnectionAction;
import com.zhongbai233.super_lead.lead.ZiplineController;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-side rope interaction service.
 *
 * <p>
 * Owns crosshair picking, hovered-connection state, attachment preview state
 * and the small server-bound packets emitted by right-click/keybind actions.
 * The
 * render event subscriber still decides when to draw highlights; this helper
 * keeps
 * that subscriber from becoming the place where every interaction rule lives.
 */
public final class ClientRopeInteractions {
    private static final double ZIPLINE_PICK_RADIUS = 0.55D;
    /**
     * Looser radius for picking a rope to attach a decoration to. The aim usually
     * lands a bit off the rope (the strand is thin); a larger radius means the
     * player can put a lantern on the rope without having to laser-aim at it.
     */
    private static final double ATTACH_PICK_RADIUS = 0.50D;

    private static volatile UUID removalPreviewConnId;
    private static volatile UUID removalPreviewAttachId;
    private static volatile LeadConnection hoveredConnection;
    private static volatile Vec3 hoveredHitPoint;
    private static volatile double hoveredHitT = 0.5D;

    private ClientRopeInteractions() {
    }

    public static LeadConnection hoveredConnection() {
        return hoveredConnection;
    }

    public static Vec3 hoveredHitPoint() {
        return hoveredHitPoint;
    }

    public static double hoveredHitT() {
        return hoveredHitT;
    }

    static void clearHovered() {
        setHovered(null, null, 0.5D);
    }

    static void setHovered(LeadConnection connection, Vec3 hitPoint, double hitT) {
        hoveredConnection = connection;
        hoveredHitPoint = hitPoint;
        hoveredHitT = hitT;
    }

    static UUID removalPreviewConnectionId() {
        return removalPreviewConnId;
    }

    static UUID removalPreviewAttachmentId() {
        return removalPreviewAttachId;
    }

    static void clearRemovalPreview() {
        removalPreviewConnId = null;
        removalPreviewAttachId = null;
    }

    static void setRemovalPreview(UUID connectionId, UUID attachmentId) {
        removalPreviewConnId = connectionId;
        removalPreviewAttachId = attachmentId;
    }

    /**
     * Send a server-bound UseConnectionAction packet for the currently hovered
     * connection. Called on the client when the player right-clicks with an action
     * item and the client's ghost-preview pick produced a target the action can act
     * on.
     */
    static boolean trySendUseConnectionAction(net.minecraft.world.InteractionHand hand,
            LeadConnectionAction action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        LeadConnection hovered = hoveredConnection;
        Vec3 hitPoint = hoveredHitPoint;
        double hitT = hoveredHitT;
        if (hovered == null || !action.canTarget(hovered)) {
            return false;
        }
        if (hitPoint == null || !Double.isFinite(hitT)) {
            return false;
        }
        ClientPacketDistributor.sendToServer(
                new UseConnectionAction(
                        hovered.id(),
                        action.ordinal(),
                        hand == net.minecraft.world.InteractionHand.OFF_HAND,
                        hitPoint,
                        hitT));
        return true;
    }

    /**
     * Send a BoostRopePerch packet for the currently hovered connection.
     * Called when the player shift-right-clicks with seeds while aiming at a rope.
     */
    static boolean trySendBoostRopePerch() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        LeadConnection hovered = hoveredConnection;
        Vec3 hitPoint = hoveredHitPoint;
        double hitT = hoveredHitT;
        if (hovered == null || hitPoint == null || !Double.isFinite(hitT)) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new BoostRopePerch(hovered.id(), hitPoint, hitT));
        return true;
    }

    /** Try starting a zipline ride on the sync-zone rope under the crosshair. */
    static boolean trySendStartZipline(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!ZiplineController.isChain(mc.player.getItemInHand(hand))) {
            return false;
        }
        AttachPick pick = pickZiplinePoint(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        ClientPacketDistributor.sendToServer(
                new StartZipline(pick.connectionId(),
                        hand == net.minecraft.world.InteractionHand.OFF_HAND,
                        pick.point(),
                        pick.t()));
        return true;
    }

    /**
     * Try sending an AddRopeAttachment packet for the rope under the player's
     * crosshair. The held item must be a valid attachment item.
     */
    static boolean trySendAddRopeAttachment(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        if (!mc.player.getOffhandItem().is(net.minecraft.world.item.Items.STRING)) {
            return false;
        }
        ItemStack stack = mc.player.getItemInHand(hand);
        if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) {
            return false;
        }
        AttachPick pick = pickRopeAttachPoint(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        int frontSide = attachmentFrontSide(pick, mc.gameRenderer.getMainCamera().position());
        ClientPacketDistributor.sendToServer(
                new AddRopeAttachment(pick.connectionId, pick.t,
                        hand == net.minecraft.world.InteractionHand.OFF_HAND, frontSide));
        return true;
    }

    /** Try sending a RemoveRopeAttachment packet for the targeted attachment. */
    static boolean trySendRemoveRopeAttachment() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        AttachmentPick pick = pickAttachmentForRemoval(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new RemoveRopeAttachment(pick.connectionId, pick.attachmentId));
        return true;
    }

    /** Try toggling the block/item visual form of the targeted attachment. */
    static boolean trySendToggleRopeAttachmentForm() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) {
                continue;
            }
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                if (attachment.id().equals(pick.attachmentId)) {
                    if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(attachment.stack())) {
                        return false;
                    }
                    break;
                }
            }
        }
        ClientPacketDistributor.sendToServer(new ToggleRopeAttachmentForm(pick.connectionId, pick.attachmentId));
        return true;
    }

    /** Open the vanilla sign editor for a sign stored as a rope attachment. */
    static boolean tryOpenRopeAttachmentSignEditor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) {
                continue;
            }
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                if (!attachment.id().equals(pick.attachmentId)) {
                    continue;
                }
                ItemStack stack = attachment.stack();
                if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                        || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
                    return false;
                }
                return RopeAttachmentSignEditor.open(connection, attachment);
            }
        }
        return false;
    }

    /** Ask the server to open an AE2 ME Terminal mounted as a rope attachment. */
    static boolean tryOpenRopeAttachmentAeTerminal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) {
                continue;
            }
            if (connection.kind() != LeadKind.AE_NETWORK) {
                return false;
            }
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                if (!attachment.id().equals(pick.attachmentId)) {
                    continue;
                }
                if (!isAeTerminalAttachment(attachment.stack())) {
                    return false;
                }
                ClientPacketDistributor.sendToServer(new OpenRopeAeTerminal(pick.connectionId, pick.attachmentId));
                return true;
            }
        }
        return false;
    }

    static boolean trySendSignAttachmentDye(net.minecraft.world.item.DyeColor color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) {
                continue;
            }
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                if (!attachment.id().equals(pick.attachmentId)) {
                    continue;
                }
                if (!isRopeSignAttachment(attachment)) {
                    return false;
                }
                boolean frontText = RopeAttachmentSignEditor.isViewerOnFrontSideStatic(
                        connection, attachment, mc.player);
                ClientPacketDistributor.sendToServer(
                        UpdateSignAttachmentAppearance.dye(pick.connectionId, pick.attachmentId, color, frontText));
                return true;
            }
        }
        return false;
    }

    static boolean trySendSignAttachmentGlow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(mc.player)) {
            return false;
        }
        AttachmentPick pick = pickAttachment(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (pick == null) {
            return false;
        }
        for (LeadConnection connection : SuperLeadNetwork.connections(mc.level)) {
            if (!connection.id().equals(pick.connectionId)) {
                continue;
            }
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                if (!attachment.id().equals(pick.attachmentId)) {
                    continue;
                }
                if (!isRopeSignAttachment(attachment)) {
                    return false;
                }
                boolean frontText = RopeAttachmentSignEditor.isViewerOnFrontSideStatic(
                        connection, attachment, mc.player);
                ClientPacketDistributor.sendToServer(
                        UpdateSignAttachmentAppearance.glow(pick.connectionId, pick.attachmentId, frontText));
                return true;
            }
        }
        return false;
    }

    private static boolean isAeTerminalAttachment(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem());
        if (!id.getNamespace().equals("ae2")) {
            return false;
        }
        return switch (id.getPath()) {
            case "terminal", "crafting_terminal", "pattern_encoding_terminal", "pattern_access_terminal" -> true;
            default -> false;
        };
    }

    private static boolean isRopeSignAttachment(com.zhongbai233.super_lead.lead.RopeAttachment attachment) {
        ItemStack stack = attachment.stack();
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return false;
        }
        return blockItem.getBlock() instanceof net.minecraft.world.level.block.SignBlock;
    }

    /**
     * Picks the rope nearest to the player's crosshair and reports the arc-length
     * parameter at the hit.
     */
    static AttachPick pickRopeAttachPoint(Minecraft mc, float partialTick) {
        Player player = mc.player;
        if (player == null) {
            return null;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        var fwd = camera.forwardVector();
        double dirX = fwd.x(), dirY = fwd.y(), dirZ = fwd.z();
        double inv = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= inv;
        dirY *= inv;
        dirZ *= inv;
        double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
        net.minecraft.world.phys.HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            double hitDist = hitResult.getLocation().distanceTo(cameraPos);
            maxDistance = Math.min(maxDistance, hitDist + ATTACH_PICK_RADIUS);
        } else if (player != null) {
            maxDistance = Math.min(maxDistance, player.blockInteractionRange() + 2.0D);
        }
        double bestDistSqr = ATTACH_PICK_RADIUS * ATTACH_PICK_RADIUS;
        UUID bestId = null;
        double bestT = 0.5D;
        Vec3 bestPoint = Vec3.ZERO;
        Vec3 bestA = Vec3.ZERO;
        Vec3 bestB = Vec3.ZERO;

        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            RopeSimulation sim = SuperLeadClientEvents.simulation(connection.id());
            if (sim == null) {
                continue;
            }
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D) {
                continue;
            }
            int nodeCount = sim.nodeCount();
            for (int i = 0; i < nodeCount - 1; i++) {
                double ax = sim.renderX(i), ay = sim.renderY(i), az = sim.renderZ(i);
                double bx = sim.renderX(i + 1), by = sim.renderY(i + 1), bz = sim.renderZ(i + 1);
                for (int sample = 0; sample <= 4; sample++) {
                    double frac = sample / 4.0D;
                    double px = ax + (bx - ax) * frac;
                    double py = ay + (by - ay) * frac;
                    double pz = az + (bz - az) * frac;
                    net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(px, py - 0.35D, pz);
                    if (!level.getBlockState(below).isAir()) {
                        continue;
                    }
                    double d = RopePickMath.distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        bestId = connection.id();
                        double l0 = sim.renderLength(i);
                        double l1 = sim.renderLength(i + 1);
                        double arc = l0 + (l1 - l0) * frac;
                        bestT = Math.max(0.02D, Math.min(0.98D, arc / total));
                        bestPoint = new Vec3(px, py, pz);
                        bestA = new Vec3(ax, ay, az);
                        bestB = new Vec3(bx, by, bz);
                    }
                }
            }
        }
        return bestId == null ? null : new AttachPick(bestId, bestT, bestPoint, bestA, bestB);
    }

    static AttachPick pickZiplinePoint(Minecraft mc, float partialTick) {
        ZiplinePickContext context = ZiplinePickContext.from(mc);
        if (context == null)
            return null;

        List<LeadConnection> connections = SuperLeadNetwork.connections(context.level);
        ZiplinePickSearch search = new ZiplinePickSearch();
        for (LeadConnection connection : connections) {
            if (!isZiplinePickable(connection))
                continue;
            RopeSimulation sim = SuperLeadClientEvents.simulation(connection.id());
            if (sim != null) {
                searchSimZipline(connection, sim, partialTick, context, search);
            } else {
                searchFallbackZipline(connection, connections, context, search);
            }
        }
        return search.result();
    }

    private static boolean isZiplinePickable(LeadConnection connection) {
        LeadKind kind = connection.kind();
        return (kind == LeadKind.NORMAL || kind == LeadKind.REDSTONE)
                && playerZiplineEnabled(connection);
    }

    private static boolean playerZiplineEnabled(LeadConnection connection) {
        if (connection.physicsPreset().isBlank() || !PhysicsZonesClient.hasPreset(connection.physicsPreset())) {
            return false;
        }
        return resolveBool(PhysicsZonesClient.overridesForPreset(connection.physicsPreset()),
                ClientTuning.CONTACT_PLAYER_ZIPLINE);
    }

    private static boolean resolveBool(Map<String, String> overrides,
            com.zhongbai233.super_lead.tuning.TuningKey<Boolean> key) {
        String raw = overrides.get(key.id);
        if (raw != null) {
            try {
                Boolean parsed = key.type.parse(raw);
                if (key.type.validate(parsed)) {
                    return parsed;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return key.defaultValue;
    }

    private static void searchSimZipline(
            LeadConnection connection,
            RopeSimulation sim,
            float partialTick,
            ZiplinePickContext context,
            ZiplinePickSearch search) {
        double total = sim.prepareRender(partialTick);
        if (total <= 0.0D)
            return;
        int nodeCount = sim.nodeCount();
        for (int i = 0; i < nodeCount - 1; i++) {
            sampleSimZiplineSegment(connection, sim, i, total, context, search);
        }
    }

    private static void sampleSimZiplineSegment(
            LeadConnection connection,
            RopeSimulation sim,
            int segment,
            double total,
            ZiplinePickContext context,
            ZiplinePickSearch search) {
        double ax = sim.renderX(segment), ay = sim.renderY(segment), az = sim.renderZ(segment);
        double bx = sim.renderX(segment + 1), by = sim.renderY(segment + 1), bz = sim.renderZ(segment + 1);
        double l0 = sim.renderLength(segment);
        double l1 = sim.renderLength(segment + 1);
        for (int sample = 0; sample <= 4; sample++) {
            double frac = sample / 4.0D;
            double px = ax + (bx - ax) * frac;
            double py = ay + (by - ay) * frac;
            double pz = az + (bz - az) * frac;
            double arc = l0 + (l1 - l0) * frac;
            search.considerSegment(connection.id(), context, px, py, pz,
                    Math.max(0.02D, Math.min(0.98D, arc / total)),
                    ax, ay, az, bx, by, bz);
        }
    }

    private static void searchFallbackZipline(
            LeadConnection connection,
            List<LeadConnection> connections,
            ZiplinePickContext context,
            ZiplinePickSearch search) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(context.level, connection, connections);
        Vec3 endpointA = endpoints.from();
        Vec3 endpointB = endpoints.to();
        if (endpointA.distanceTo(endpointB) < 1.0e-6D)
            return;

        RopeTuning tuning = RopeTuning.forConnection(connection);
        Vec3 fallback = RopeSagModel.stableUnitVector(connection.id().getLeastSignificantBits());
        final int segments = 16;
        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            Vec3 segA = simpleSagPoint(endpointA, endpointB, t0, tuning, fallback);
            Vec3 segB = simpleSagPoint(endpointA, endpointB, t1, tuning, fallback);
            for (int sample = 0; sample <= 4; sample++) {
                double frac = sample / 4.0D;
                search.consider(connection.id(), context,
                        segA.x + (segB.x - segA.x) * frac,
                        segA.y + (segB.y - segA.y) * frac,
                        segA.z + (segB.z - segA.z) * frac,
                        Math.max(0.02D, Math.min(0.98D, t0 + (t1 - t0) * frac)),
                        segA, segB);
            }
        }
    }

    private static Vec3 simpleSagPoint(Vec3 a, Vec3 b, double t, RopeTuning tuning, Vec3 fallback) {
        return RopeSagModel.point(a, b, t, tuning.slack(), tuning.gravity(), fallback);
    }

    private static final class ZiplinePickContext {
        final ClientLevel level;
        final Player player;
        final Vec3 cameraPos;
        final double dirX;
        final double dirY;
        final double dirZ;
        final double maxDistance;

        private ZiplinePickContext(ClientLevel level, Player player, Vec3 cameraPos,
                double dirX, double dirY, double dirZ, double maxDistance) {
            this.level = level;
            this.player = player;
            this.cameraPos = cameraPos;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.maxDistance = maxDistance;
        }

        static ZiplinePickContext from(Minecraft mc) {
            Player player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null)
                return null;
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.position();
            var fwd = camera.forwardVector();
            double dirX = fwd.x(), dirY = fwd.y(), dirZ = fwd.z();
            double inv = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            double maxDistance = ziplineMaxDistance(mc, cameraPos);
            return new ZiplinePickContext(level, player, cameraPos, dirX * inv, dirY * inv, dirZ * inv, maxDistance);
        }

        private static double ziplineMaxDistance(Minecraft mc, Vec3 cameraPos) {
            double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
            net.minecraft.world.phys.HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                return Math.min(maxDistance, hit.getLocation().distanceTo(cameraPos) + ZIPLINE_PICK_RADIUS);
            }
            if (mc.player != null) {
                return Math.min(maxDistance, mc.player.blockInteractionRange() + 2.0D);
            }
            return maxDistance;
        }
    }

    private static final class ZiplinePickSearch {
        private double bestDistSqr = ZIPLINE_PICK_RADIUS * ZIPLINE_PICK_RADIUS;
        private UUID bestId;
        private double bestT = 0.5D;
        private Vec3 bestPoint = Vec3.ZERO;
        private Vec3 bestA = Vec3.ZERO;
        private Vec3 bestB = Vec3.ZERO;

        void consider(UUID connectionId, ZiplinePickContext context,
                double px, double py, double pz, double t, Vec3 a, Vec3 b) {
            if (!isBetter(context, px, py, pz))
                return;
            bestId = connectionId;
            bestT = t;
            bestPoint = new Vec3(px, py, pz);
            bestA = a;
            bestB = b;
        }

        void considerSegment(UUID connectionId, ZiplinePickContext context,
                double px, double py, double pz, double t,
                double ax, double ay, double az, double bx, double by, double bz) {
            if (!isBetter(context, px, py, pz))
                return;
            bestId = connectionId;
            bestT = t;
            bestPoint = new Vec3(px, py, pz);
            bestA = new Vec3(ax, ay, az);
            bestB = new Vec3(bx, by, bz);
        }

        private boolean isBetter(ZiplinePickContext context, double px, double py, double pz) {
            if (!ZiplineController.canReachStartPoint(context.player, px, py, pz)) {
                return false;
            }
            double distanceSqr = RopePickMath.distancePointToRaySqr(px, py, pz,
                    context.cameraPos.x, context.cameraPos.y, context.cameraPos.z,
                    context.dirX, context.dirY, context.dirZ, context.maxDistance);
            if (distanceSqr >= bestDistSqr)
                return false;
            bestDistSqr = distanceSqr;
            return true;
        }

        AttachPick result() {
            return bestId == null ? null : new AttachPick(bestId, bestT, bestPoint, bestA, bestB);
        }
    }

    /** Picks the existing attachment closest to the player's crosshair. */
    static AttachmentPick pickAttachment(Minecraft mc, float partialTick) {
        return pickAttachment0(mc, partialTick, false);
    }

    /** Wider, more forgiving pick for attachment removal and preview. */
    static AttachmentPick pickAttachmentForRemoval(Minecraft mc, float partialTick) {
        return pickAttachment0(mc, partialTick, true);
    }

    private static AttachmentPick pickAttachment0(Minecraft mc, float partialTick, boolean removalMode) {
        Player player = mc.player;
        if (player == null) {
            return null;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        var fwd = camera.forwardVector();
        double dirX = fwd.x(), dirY = fwd.y(), dirZ = fwd.z();
        double inv = 1.0D / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX *= inv;
        dirY *= inv;
        dirZ *= inv;
        double maxDistance = SuperLeadNetwork.maxExtendedLeashDistance();
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            maxDistance = Math.min(maxDistance, hit.getLocation().distanceTo(cameraPos) + (removalMode ? 2.0D : 0.6D));
        } else if (mc.player != null) {
            maxDistance = Math.min(maxDistance, mc.player.blockInteractionRange() + 2.0D);
        }
        double bestDistSqr = (removalMode ? 1.20D : 0.6D) * (removalMode ? 1.20D : 0.6D);
        UUID bestConnId = null;
        UUID bestAttachId = null;

        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            if (connection.attachments().isEmpty()) {
                continue;
            }
            RopeSimulation sim = SuperLeadClientEvents.simulation(connection.id());
            if (sim == null) {
                continue;
            }
            double total = sim.prepareRender(partialTick);
            if (total <= 0.0D) {
                continue;
            }
            int nodeCount = sim.nodeCount();
            for (com.zhongbai233.super_lead.lead.RopeAttachment attachment : connection.attachments()) {
                double target = attachment.t() * total;
                int seg = 0;
                for (int i = 0; i < nodeCount - 1; i++) {
                    if (target <= sim.renderLength(i + 1)) {
                        seg = i;
                        break;
                    }
                    seg = i;
                }
                double l0 = sim.renderLength(seg);
                double l1 = sim.renderLength(seg + 1);
                double span = l1 - l0;
                double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
                double px = sim.renderX(seg) + (sim.renderX(seg + 1) - sim.renderX(seg)) * frac;
                double py = sim.renderY(seg) + (sim.renderY(seg + 1) - sim.renderY(seg)) * frac;
                double pz = sim.renderZ(seg) + (sim.renderZ(seg + 1) - sim.renderZ(seg)) * frac;
                double d1 = RopePickMath.distancePointToRaySqr(px, py, pz,
                        cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                Vec3 bodyCenter = RopeAttachmentRenderer.attachmentBodyCenter(level,
                        attachment.stack(), attachment.displayAsBlock(), attachment.frontSide(),
                        px, py, pz,
                        sim.renderX(seg), sim.renderY(seg), sim.renderZ(seg),
                        sim.renderX(seg + 1), sim.renderY(seg + 1), sim.renderZ(seg + 1));
                double d2 = RopePickMath.distancePointToRaySqr(bodyCenter.x, bodyCenter.y, bodyCenter.z,
                        cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                double d = Math.min(d1, d2);
                if (removalMode) {
                    double margin = 0.35D;
                    double d3p = RopePickMath.distancePointToRaySqr(bodyCenter.x, bodyCenter.y - margin, bodyCenter.z,
                            cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                    double d4p = RopePickMath.distancePointToRaySqr(bodyCenter.x, bodyCenter.y + margin, bodyCenter.z,
                            cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                    double extra = Math.min(d3p, d4p);
                    double sx = sim.renderX(seg + 1) - sim.renderX(seg);
                    double sz = sim.renderZ(seg + 1) - sim.renderZ(seg);
                    double hLen = Math.sqrt(sx * sx + sz * sz);
                    if (hLen > 1.0e-6D) {
                        double nx = -sz / hLen;
                        double nz = sx / hLen;
                        double d5 = RopePickMath.distancePointToRaySqr(px + nx * margin, py, pz + nz * margin,
                                cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                        double d6 = RopePickMath.distancePointToRaySqr(px - nx * margin, py, pz - nz * margin,
                                cameraPos.x, cameraPos.y, cameraPos.z, dirX, dirY, dirZ, maxDistance);
                        extra = Math.min(extra, Math.min(d5, d6));
                    }
                    d = Math.min(d, extra);
                }
                if (d < bestDistSqr) {
                    bestDistSqr = d;
                    bestConnId = connection.id();
                    bestAttachId = attachment.id();
                }
            }
        }
        return bestConnId == null ? null : new AttachmentPick(bestConnId, bestAttachId);
    }

    static int attachmentFrontSide(AttachPick pick, Vec3 viewer) {
        return RopeAttachmentRenderer.frontSideForViewer(
                pick.a().x, pick.a().y, pick.a().z,
                pick.b().x, pick.b().y, pick.b().z,
                pick.point().x, pick.point().y, pick.point().z,
                viewer);
    }

    static ItemStack attachmentStackForPreview(Player player) {
        if (player == null || !SuperLeadNetwork.canModifyRopes(player) || player.isShiftKeyDown()) {
            return ItemStack.EMPTY;
        }
        if (!player.getOffhandItem().is(net.minecraft.world.item.Items.STRING)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = player.getMainHandItem();
        return com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)
                ? stack
                : ItemStack.EMPTY;
    }

    record AttachPick(UUID connectionId, double t, Vec3 point, Vec3 a, Vec3 b) {
    }

    record AttachmentPick(UUID connectionId, UUID attachmentId) {
    }
}
