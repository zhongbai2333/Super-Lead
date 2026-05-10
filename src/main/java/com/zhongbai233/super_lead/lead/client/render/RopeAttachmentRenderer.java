package com.zhongbai233.super_lead.lead.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class RopeAttachmentRenderer {
    // Length of the thin hanger "string" between the rope and the top of the attachment.
    private static final double HANGER_LENGTH = 0.20D;
    // Half-distance between the two suspension strings, measured ALONG the rope axis.
    // Two strings spaced this far apart give the item a visible support span; combined
    // with the rope-pitch tilt rotation the item appears physically suspended.
    private static final double HANGER_HALF_SPACING = 0.18D;
    // Half-block render scale so attachments look like a small placed block.
    private static final float BLOCK_RENDER_SCALE = 0.50F;
    // Visual centre offset below the rope point along the rope-tilted drop direction.
    private static final double BLOCK_HANG_OFFSET = HANGER_LENGTH + BLOCK_RENDER_SCALE * 0.5D;
    private static final double ITEM_HANG_OFFSET = HANGER_LENGTH + 0.20D;
    private static final float ITEM_SCALE = 0.85F;
    // Half-thickness of the hanger string and its dark color.
    private static final float HANGER_HALF_THICKNESS = 0.012F;
    private static final int HANGER_COLOR = 0xFF1F1A14;

    private RopeAttachmentRenderer() {}

    public static void submitAll(SubmitNodeCollector collector,
                          Vec3 cameraPos,
                          ClientLevel level,
                          RopeSimulation sim,
                          java.util.List<RopeAttachment> attachments,
                          boolean redstonePowered,
                          float partialTick) {
        if (attachments.isEmpty()) return;
        double total = sim.prepareRender(partialTick);
        if (total < 1.0e-6D) return;
        int nodeCount = sim.nodeCount();
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        for (RopeAttachment attachment : attachments) {
            double target = attachment.t() * total;
            int seg = locateSegment(sim, nodeCount, target);
            if (seg < 0) continue;
            double l0 = sim.renderLength(seg);
            double l1 = sim.renderLength(seg + 1);
            double span = l1 - l0;
            double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
            double ax = sim.renderX(seg), ay = sim.renderY(seg), az = sim.renderZ(seg);
            double bx = sim.renderX(seg + 1), by = sim.renderY(seg + 1), bz = sim.renderZ(seg + 1);
            double px = ax + (bx - ax) * frac;
            double py = ay + (by - ay) * frac;
            double pz = az + (bz - az) * frac;
            BlockPos lightPos = BlockPos.containing(px, py, pz);
            int blockLight = level.getBrightness(LightLayer.BLOCK, lightPos);
            int skyLight = level.getBrightness(LightLayer.SKY, lightPos);
            int packedLight = LightCoordsUtil.pack(blockLight, skyLight);
            HangFrame frame = computeFrame(ax, ay, az, bx, by, bz);

            renderOne(collector, cameraPos, level, mc, player, attachment.stack(),
                    attachment.displayAsBlock(), redstonePowered,
                    px, py, pz, frame, lightPos, packedLight, 0, 1.0F, false);
        }
    }

    /** Submit a translucent ghost of {@code stack} at the supplied arc-length on {@code sim}.
     *  Used by the highlight system to preview where a right-click would attach. */
    public static void submitGhost(SubmitNodeCollector collector,
                            Vec3 cameraPos,
                            ClientLevel level,
                            RopeSimulation sim,
                            net.minecraft.world.item.ItemStack stack,
                            double t,
                            float partialTick) {
        double total = sim.prepareRender(partialTick);
        if (total < 1.0e-6D || stack.isEmpty()) return;
        int nodeCount = sim.nodeCount();
        double target = t * total;
        int seg = locateSegment(sim, nodeCount, target);
        if (seg < 0) return;
        double l0 = sim.renderLength(seg);
        double l1 = sim.renderLength(seg + 1);
        double span = l1 - l0;
        double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
        double ax = sim.renderX(seg), ay = sim.renderY(seg), az = sim.renderZ(seg);
        double bx = sim.renderX(seg + 1), by = sim.renderY(seg + 1), bz = sim.renderZ(seg + 1);
        double px = ax + (bx - ax) * frac;
        double py = ay + (by - ay) * frac;
        double pz = az + (bz - az) * frac;
        BlockPos lightPos = BlockPos.containing(px, py, pz);
        int packedLight = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos),
                level.getBrightness(LightLayer.SKY, lightPos));
        HangFrame frame = computeFrame(ax, ay, az, bx, by, bz);
        Minecraft mc = Minecraft.getInstance();
        boolean asBlock = com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(stack);
        renderOne(collector, cameraPos, level, mc, mc.player, stack,
                asBlock, false, px, py, pz, frame, lightPos, packedLight,
                0xFFFFFFFF, 1.02F, true);
    }

    /** Common render path for both real and ghost attachments. Emits the two suspension
     *  strings and the body (block-form via submitMovingBlock when possible, otherwise
     *  item-model). */
    private static void renderOne(SubmitNodeCollector collector, Vec3 cameraPos,
                                  ClientLevel level, Minecraft mc, Player player,
                                  net.minecraft.world.item.ItemStack stack,
                                  boolean displayAsBlock, boolean redstonePowered,
                                  double px, double py, double pz, HangFrame frame,
                                  BlockPos lightPos, int packedLight,
                                  int tintColor, float scaleMul, boolean ghost) {
        boolean asBlockItem = displayAsBlock
                && com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(stack);
        // BlockEntity-rendered blocks (signs, shulker boxes, chests, banners, ...) only have
        // a partial static model; submitMovingBlock would render an empty post for signs,
        // etc. Detect them up front and route through the item-model FIXED path which uses
        // the full item display model.
        boolean useMovingBlock = asBlockItem && !needsItemFallback(stack);

        // Suspension strings: two parallel lines from offset rope points down along the
        // tilted drop direction.
        double offX = frame.rdx * HANGER_HALF_SPACING;
        double offY = frame.rdy * HANGER_HALF_SPACING;
        double offZ = frame.rdz * HANGER_HALF_SPACING;
        double dropX = frame.dropX * HANGER_LENGTH;
        double dropY = frame.dropY * HANGER_LENGTH;
        double dropZ = frame.dropZ * HANGER_LENGTH;
        double a1x = px - offX, a1y = py - offY, a1z = pz - offZ;
        double a2x = px + offX, a2y = py + offY, a2z = pz + offZ;
        submitTwoStrings(collector, cameraPos,
                a1x, a1y, a1z, a1x + dropX, a1y + dropY, a1z + dropZ,
                a2x, a2y, a2z, a2x + dropX, a2y + dropY, a2z + dropZ,
                packedLight);

        // Item/block centre is below the rope point along the tilted drop direction.
        double offset = useMovingBlock ? BLOCK_HANG_OFFSET : ITEM_HANG_OFFSET;
        double cx = px + frame.dropX * offset;
        double cy = py + frame.dropY * offset;
        double cz = pz + frame.dropZ * offset;

        if (useMovingBlock
                && submitBlockForm(collector, cameraPos, level, stack, redstonePowered,
                        frame.tilt, cx, cy, cz, lightPos)) {
            return;
        }

        // Use a fresh state per attachment: submit() may capture by reference for
        // deferred batching, so reusing one instance can cause every entry to render
        // with the last item's state.
        ItemStackRenderState renderState = new ItemStackRenderState();
        // For BlockItems we display as block-form but couldn't go through submitMovingBlock
        // (BlockEntity blocks like signs), use FIXED so the full item model is shown.
        ItemDisplayContext context = (asBlockItem && needsItemFallback(stack))
                ? ItemDisplayContext.FIXED : ItemDisplayContext.GROUND;
        mc.getItemModelResolver().updateForTopItem(renderState, stack, context, level, player, 0);
        if (renderState.isEmpty()) return;
        float scale = ITEM_SCALE * scaleMul;
        PoseStack pose = new PoseStack();
        pose.translate(cx - cameraPos.x, cy - cameraPos.y, cz - cameraPos.z);
        pose.mulPose(frame.tilt);
        pose.scale(scale, scale, scale);
        renderState.submit(pose, collector, packedLight,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, tintColor);
    }

    /** Render the block form of {@code stack} via the moving-block pipeline so we get
     *  a consistent half-block visual and can override LIT/POWERED for redstone-aware
     *  blocks. Returns false on failure so the caller can fall back to an item model. */
    private static boolean submitBlockForm(SubmitNodeCollector collector,
                                           Vec3 cameraPos,
                                           ClientLevel level,
                                           net.minecraft.world.item.ItemStack stack,
                                           boolean powered,
                                           Quaternionf tilt,
                                           double cx, double cy, double cz,
                                           BlockPos lightPos) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) return false;
        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        if (powered) {
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, Boolean.TRUE);
            }
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, Boolean.TRUE);
            }
        }
        try {
            net.minecraft.client.renderer.block.MovingBlockRenderState mb =
                    new net.minecraft.client.renderer.block.MovingBlockRenderState();
            mb.blockPos = lightPos;
            mb.randomSeedPos = lightPos;
            mb.blockState = state;
            mb.biome = level.getBiome(lightPos);
            mb.cardinalLighting = level.cardinalLighting();
            mb.lightEngine = level.getLightEngine();
            // submitMovingBlock draws a unit cube in pose-local space with its minimum
            // corner at the origin. Centre it on the hang point, apply rope tilt, scale.
            PoseStack pose = new PoseStack();
            pose.translate(cx - cameraPos.x, cy - cameraPos.y, cz - cameraPos.z);
            pose.mulPose(tilt);
            pose.scale(BLOCK_RENDER_SCALE, BLOCK_RENDER_SCALE, BLOCK_RENDER_SCALE);
            pose.translate(-0.5D, -0.5D, -0.5D);
            collector.submitMovingBlock(pose, mb);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when this BlockItem has visible content rendered by a BlockEntityRenderer
     *  (signs, banners, shulker boxes, chests, beds, ...). The static block model alone
     *  would be empty or a stub, so we use the full item model instead. */
    private static boolean needsItemFallback(net.minecraft.world.item.ItemStack stack) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) return false;
        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        // hasBlockEntity() catches signs, banners, chests, shulker boxes, beds, etc.
        // Render shape INVISIBLE / ENTITYBLOCK_ANIMATED also need the item model.
        if (state.hasBlockEntity()) return true;
        net.minecraft.world.level.block.RenderShape shape = state.getRenderShape();
        return shape != net.minecraft.world.level.block.RenderShape.MODEL;
    }

    /** Geometry used to tilt the attachment with rope slope. */
    private record HangFrame(double rdx, double rdy, double rdz,
                             double dropX, double dropY, double dropZ,
                             Quaternionf tilt) {}

    private static HangFrame computeFrame(double ax, double ay, double az,
                                          double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6D) {
            return new HangFrame(1, 0, 0, 0, -1, 0, new Quaternionf());
        }
        double rdx = dx / len, rdy = dy / len, rdz = dz / len;
        // Build a right-handed orthonormal basis aligned with the rope:
        //   local +X (tangent) = rope direction; local +Y = world-up projected perpendicular
        //   to tangent (so the item's "up" stays as upright as possible); local +Z = X cross Y.
        // The resulting quaternion combines yaw (around world Y, so the item faces along the
        // rope's horizontal heading) and pitch (around the in-plane axis, so the item tilts
        // with the rope's slope -- top edge perpendicular to tangent in the vertical plane).
        Vector3f tangent = new Vector3f((float) rdx, (float) rdy, (float) rdz);
        Vector3f up = new Vector3f(0f, 1f, 0f);
        // up' = worldUp - tangent * (tangent . worldUp) = worldUp - tangent * tangent.y
        up.fma(-tangent.y, tangent);
        if (up.lengthSquared() < 1.0e-6f) {
            // Rope is essentially vertical: drop straight down without any rotation.
            return new HangFrame(rdx, rdy, rdz, 0, -1, 0, new Quaternionf());
        }
        up.normalize();
        Vector3f side = new Vector3f(tangent).cross(up);
        Matrix3f basis = new Matrix3f()
                .setColumn(0, tangent)
                .setColumn(1, up)
                .setColumn(2, side);
        Quaternionf q = new Quaternionf().setFromUnnormalized(basis);
        // Drop direction = -localY (item centre hangs perpendicular below the rope tangent).
        return new HangFrame(rdx, rdy, rdz, -up.x, -up.y, -up.z, q);
    }

    /** Emit the two suspension strings as a single batched custom-geometry submission.
     *  Each string is rendered as two perpendicular thin quads (double-sided) so it's
     *  visible from any angle. */
    private static void submitTwoStrings(SubmitNodeCollector collector, Vec3 cameraPos,
                                         double a1x, double a1y, double a1z,
                                         double b1x, double b1y, double b1z,
                                         double a2x, double a2y, double a2z,
                                         double b2x, double b2y, double b2z,
                                         int packedLight) {
        final float A1x = (float) (a1x - cameraPos.x), A1y = (float) (a1y - cameraPos.y), A1z = (float) (a1z - cameraPos.z);
        final float B1x = (float) (b1x - cameraPos.x), B1y = (float) (b1y - cameraPos.y), B1z = (float) (b1z - cameraPos.z);
        final float A2x = (float) (a2x - cameraPos.x), A2y = (float) (a2y - cameraPos.y), A2z = (float) (a2z - cameraPos.z);
        final float B2x = (float) (b2x - cameraPos.x), B2y = (float) (b2y - cameraPos.y), B2z = (float) (b2z - cameraPos.z);
        final int color = HANGER_COLOR;
        final int light = packedLight;
        collector.submitCustomGeometry(IDENTITY_POSE, RenderTypes.textBackground(), (state, buffer) -> {
            emitLineQuads(buffer, A1x, A1y, A1z, B1x, B1y, B1z, color, light);
            emitLineQuads(buffer, A2x, A2y, A2z, B2x, B2y, B2z, color, light);
        });
    }

    /** Emit the four quads (two perpendicular planes, both faces) that make up one
     *  thin double-sided line between (ax,ay,az) and (bx,by,bz) in camera-relative space. */
    private static void emitLineQuads(com.mojang.blaze3d.vertex.VertexConsumer buffer,
                                      float ax, float ay, float az,
                                      float bx, float by, float bz,
                                      int color, int light) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5F) return;
        float udx = dx / len, udy = dy / len, udz = dz / len;
        // n1 perpendicular to direction, in horizontal plane if possible.
        float n1x, n1y, n1z;
        if (Math.abs(udy) > 0.99F) {
            n1x = 1; n1y = 0; n1z = 0;
        } else {
            n1x = udz; n1y = 0; n1z = -udx;
            float l1 = (float) Math.sqrt(n1x * n1x + n1z * n1z);
            n1x /= l1; n1z /= l1;
        }
        // n2 = direction × n1.
        float n2x = udy * n1z - udz * n1y;
        float n2y = udz * n1x - udx * n1z;
        float n2z = udx * n1y - udy * n1x;
        final float w = HANGER_HALF_THICKNESS;
        n1x *= w; n1y *= w; n1z *= w;
        n2x *= w; n2y *= w; n2z *= w;
        // Plane 1, front + back.
        buffer.addVertex(ax - n1x, ay - n1y, az - n1z).setColor(color).setLight(light);
        buffer.addVertex(bx - n1x, by - n1y, bz - n1z).setColor(color).setLight(light);
        buffer.addVertex(bx + n1x, by + n1y, bz + n1z).setColor(color).setLight(light);
        buffer.addVertex(ax + n1x, ay + n1y, az + n1z).setColor(color).setLight(light);
        buffer.addVertex(ax + n1x, ay + n1y, az + n1z).setColor(color).setLight(light);
        buffer.addVertex(bx + n1x, by + n1y, bz + n1z).setColor(color).setLight(light);
        buffer.addVertex(bx - n1x, by - n1y, bz - n1z).setColor(color).setLight(light);
        buffer.addVertex(ax - n1x, ay - n1y, az - n1z).setColor(color).setLight(light);
        // Plane 2, front + back.
        buffer.addVertex(ax - n2x, ay - n2y, az - n2z).setColor(color).setLight(light);
        buffer.addVertex(bx - n2x, by - n2y, bz - n2z).setColor(color).setLight(light);
        buffer.addVertex(bx + n2x, by + n2y, bz + n2z).setColor(color).setLight(light);
        buffer.addVertex(ax + n2x, ay + n2y, az + n2z).setColor(color).setLight(light);
        buffer.addVertex(ax + n2x, ay + n2y, az + n2z).setColor(color).setLight(light);
        buffer.addVertex(bx + n2x, by + n2y, bz + n2z).setColor(color).setLight(light);
        buffer.addVertex(bx - n2x, by - n2y, bz - n2z).setColor(color).setLight(light);
        buffer.addVertex(ax - n2x, ay - n2y, az - n2z).setColor(color).setLight(light);
    }

    private static final PoseStack IDENTITY_POSE = new PoseStack();

    /** Find the segment index {@code i} such that {@code renderLength(i) <= target <= renderLength(i+1)}. */
    private static int locateSegment(RopeSimulation sim, int nodeCount, double target) {
        if (target <= 0.0D) return 0;
        for (int i = 0; i < nodeCount - 1; i++) {
            if (target <= sim.renderLength(i + 1)) return i;
        }
        return nodeCount - 2;
    }

    /** Best-fit segment + fraction for a 3D world point along the rope's polyline.
     *  Returns the cumulative arc length at the projected point, or -1 if {@code sim} is empty. */
    static double projectArcLength(RopeSimulation sim, double targetX, double targetY, double targetZ, float partialTick) {
        double total = sim.prepareRender(partialTick);
        if (total <= 0.0D) return -1.0D;
        int nodeCount = sim.nodeCount();
        double bestLen = 0.0D;
        double bestDistSqr = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nodeCount - 1; i++) {
            double ax = sim.renderX(i), ay = sim.renderY(i), az = sim.renderZ(i);
            double bx = sim.renderX(i + 1), by = sim.renderY(i + 1), bz = sim.renderZ(i + 1);
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double lenSqr = dx * dx + dy * dy + dz * dz;
            if (lenSqr < 1.0e-10D) continue;
            double frac = ((targetX - ax) * dx + (targetY - ay) * dy + (targetZ - az) * dz) / lenSqr;
            if (frac < 0.0D) frac = 0.0D;
            else if (frac > 1.0D) frac = 1.0D;
            double cx = ax + dx * frac, cy = ay + dy * frac, cz = az + dz * frac;
            double rx = targetX - cx, ry = targetY - cy, rz = targetZ - cz;
            double distSqr = rx * rx + ry * ry + rz * rz;
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestLen = sim.renderLength(i) + (sim.renderLength(i + 1) - sim.renderLength(i)) * frac;
            }
        }
        if (bestDistSqr == Double.POSITIVE_INFINITY) return -1.0D;
        return bestLen;
    }
}
