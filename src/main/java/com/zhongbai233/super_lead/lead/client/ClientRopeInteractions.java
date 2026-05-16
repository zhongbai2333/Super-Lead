package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.AddRopeAttachment;
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
final class ClientRopeInteractions {
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

    static LeadConnection hoveredConnection() {
        return hoveredConnection;
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
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
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

        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            maxDistance = Math.min(maxDistance, hit.getLocation().distanceTo(cameraPos) + ZIPLINE_PICK_RADIUS);
        }

        double bestDistSqr = ZIPLINE_PICK_RADIUS * ZIPLINE_PICK_RADIUS;
        UUID bestId = null;
        double bestT = 0.5D;
        Vec3 bestPoint = Vec3.ZERO;
        Vec3 bestA = Vec3.ZERO;
        Vec3 bestB = Vec3.ZERO;

        for (LeadConnection connection : SuperLeadNetwork.connections(level)) {
            LeadKind kind = connection.kind();
            if (kind != LeadKind.NORMAL && kind != LeadKind.REDSTONE) {
                continue;
            }
            if (connection.physicsPreset().isBlank()) {
                continue;
            }
            RopeSimulation sim = SuperLeadClientEvents.simulation(connection.id());
            if (sim != null) {
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
                        double d = RopePickMath.distancePointToRaySqr(px, py, pz,
                                cameraPos.x, cameraPos.y, cameraPos.z,
                                dirX, dirY, dirZ, maxDistance);
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
                continue;
            }

            LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                    SuperLeadNetwork.connections(level));
            Vec3 endpointA = endpoints.from();
            Vec3 endpointB = endpoints.to();
            double length = endpointA.distanceTo(endpointB);
            if (length < 1.0e-6D) {
                continue;
            }
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
                    double t = t0 + (t1 - t0) * frac;
                    double px = segA.x + (segB.x - segA.x) * frac;
                    double py = segA.y + (segB.y - segA.y) * frac;
                    double pz = segA.z + (segB.z - segA.z) * frac;
                    double d = RopePickMath.distancePointToRaySqr(px, py, pz,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            dirX, dirY, dirZ, maxDistance);
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        bestId = connection.id();
                        bestT = Math.max(0.02D, Math.min(0.98D, t));
                        bestPoint = new Vec3(px, py, pz);
                        bestA = segA;
                        bestB = segB;
                    }
                }
            }
        }
        return bestId == null ? null : new AttachPick(bestId, bestT, bestPoint, bestA, bestB);
    }

    private static Vec3 simpleSagPoint(Vec3 a, Vec3 b, double t, RopeTuning tuning, Vec3 fallback) {
        return RopeSagModel.point(a, b, t, tuning.slack(), tuning.gravity(), fallback);
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
        double maxDistance = SuperLeadNetwork.MAX_LEASH_DISTANCE;
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            maxDistance = Math.min(maxDistance, hit.getLocation().distanceTo(cameraPos) + (removalMode ? 2.0D : 0.6D));
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
