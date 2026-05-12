package com.zhongbai233.super_lead.lead.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class RopeAttachmentRenderer {
    // Length of the thin hanger "string" between the rope and the top of the
    // attachment.
    private static final double HANGER_LENGTH = 0.20D;
    // Half-distance between the two suspension strings, measured ALONG the rope
    // axis.
    // Two strings spaced this far apart give the item a visible support span;
    // combined
    // with the rope-pitch tilt rotation the item appears physically suspended.
    private static final double HANGER_HALF_SPACING = 0.18D;
    // Half-block render scale so attachments look like a small placed block.
    private static final float BLOCK_RENDER_SCALE = 0.50F;
    private static final float SIGN_RENDER_SCALE = 1.0F;
    private static final double ITEM_HANG_OFFSET = HANGER_LENGTH + 0.20D;
    private static final float ITEM_SCALE = 0.85F;
    // Half-thickness of the hanger string and its dark color.
    private static final float HANGER_HALF_THICKNESS = 0.012F;
    private static final int HANGER_COLOR = 0xFF1F1A14;
    private static final double INSERT_BOTTOM_AREA_MAX = 0.18D;
    private static final double INSERT_MIN_HEIGHT = 0.35D;
    private static final double PIERCED_LIFT_OFFSET = 0.5D / 16.0D;
    private static final double MIN_HANGER_HALF_SPACING = 0.035D;

    private RopeAttachmentRenderer() {
    }

    public record BakedAttachment(UUID connectionId,
            net.minecraft.world.item.ItemStack stack,
            boolean displayAsBlock,
            boolean redstonePowered,
            int frontSide,
            double px, double py, double pz,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double lightX, double lightY, double lightZ) {
        public BakedAttachment {
            stack = stack.copyWithCount(1);
            frontSide = RopeAttachment.normalizeFrontSide(frontSide);
        }
    }

    public static void submitAll(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            RopeSimulation sim,
            java.util.List<RopeAttachment> attachments,
            boolean redstonePowered,
            float partialTick) {
        if (attachments.isEmpty())
            return;
        double total = sim.prepareRender(partialTick);
        if (total < 1.0e-6D)
            return;
        int nodeCount = sim.nodeCount();
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        for (RopeAttachment attachment : attachments) {
            double target = attachment.t() * total;
            int seg = locateSegment(sim, nodeCount, target);
            if (seg < 0)
                continue;
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
            int packedLight = packedLight(level, lightPos);
            HangFrame frame = computeFrame(ax, ay, az, bx, by, bz);

            renderOne(collector, cameraPos, level, mc, player, attachment.stack(),
                    attachment.displayAsBlock(), redstonePowered,
                    attachment.frontSide(), px, py, pz, frame, lightPos, packedLight, 0, 1.0F, false);
        }
    }

    public static void submitBakedAll(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            List<BakedAttachment> attachments) {
        if (attachments == null || attachments.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        for (BakedAttachment attachment : attachments) {
            BlockPos lightPos = BlockPos.containing(attachment.lightX(), attachment.lightY(), attachment.lightZ());
            int packedLight = packedLight(level, lightPos);
            HangFrame frame = computeFrame(attachment.ax(), attachment.ay(), attachment.az(),
                    attachment.bx(), attachment.by(), attachment.bz());
            renderOne(collector, cameraPos, level, mc, player, attachment.stack(),
                    attachment.displayAsBlock(), attachment.redstonePowered(),
                    attachment.frontSide(), attachment.px(), attachment.py(), attachment.pz(), frame, lightPos,
                    packedLight, 0, 1.0F, false);
        }
    }

    public static List<BakedAttachment> bakeStatic(BlockGetter level, LeadConnection connection, float[] x, float[] y,
            float[] z) {
        if (connection.attachments().isEmpty() || x.length < 2 || x.length != y.length || x.length != z.length) {
            return List.of();
        }
        double[] lengths = new double[x.length];
        double total = 0.0D;
        for (int i = 1; i < x.length; i++) {
            double dx = x[i] - x[i - 1];
            double dy = y[i] - y[i - 1];
            double dz = z[i] - z[i - 1];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            lengths[i] = total;
        }
        if (total <= 1.0e-6D) {
            return List.of();
        }

        boolean redstonePowered = connection.kind() == com.zhongbai233.super_lead.lead.LeadKind.REDSTONE
                && connection.powered();
        List<BakedAttachment> out = new ArrayList<>(connection.attachments().size());
        for (RopeAttachment attachment : connection.attachments()) {
            double target = attachment.t() * total;
            int seg = locateSegment(lengths, target);
            double l0 = lengths[seg];
            double l1 = lengths[seg + 1];
            double span = l1 - l0;
            double frac = span > 1.0e-6D ? (target - l0) / span : 0.0D;
            double ax = x[seg], ay = y[seg], az = z[seg];
            double bx = x[seg + 1], by = y[seg + 1], bz = z[seg + 1];
            double px = ax + (bx - ax) * frac;
            double py = ay + (by - ay) * frac;
            double pz = az + (bz - az) * frac;
            Vec3 light = attachmentBodyCenter(level, attachment.stack(), attachment.displayAsBlock(),
                    attachment.frontSide(), px, py, pz, ax, ay, az, bx, by, bz);
            out.add(new BakedAttachment(connection.id(), attachment.stack(), attachment.displayAsBlock(),
                    redstonePowered, attachment.frontSide(), px, py, pz, ax, ay, az, bx, by, bz,
                    light.x, light.y, light.z));
        }
        return List.copyOf(out);
    }

    /**
     * Submit a translucent ghost of {@code stack} at the supplied arc-length on
     * {@code sim}.
     * Used by the highlight system to preview where a right-click would attach.
     */
    public static void submitGhost(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            RopeSimulation sim,
            net.minecraft.world.item.ItemStack stack,
            double t,
            float partialTick) {
        double total = sim.prepareRender(partialTick);
        if (total < 1.0e-6D || stack.isEmpty())
            return;
        int nodeCount = sim.nodeCount();
        double target = t * total;
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
        BlockPos lightPos = BlockPos.containing(px, py, pz);
        int packedLight = packedLight(level, lightPos);
        HangFrame frame = computeFrame(ax, ay, az, bx, by, bz);
        Minecraft mc = Minecraft.getInstance();
        boolean asBlock = com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(stack);
        renderOne(collector, cameraPos, level, mc, mc.player, stack,
                asBlock, false, viewerFrontSide(frame, px, py, pz, cameraPos),
                px, py, pz, frame, lightPos, packedLight,
                0xFFFFFFFF, 1.02F, true);
    }

    private static int packedLight(ClientLevel level, BlockPos lightPos) {
        int packed = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos),
                level.getBrightness(LightLayer.SKY, lightPos));
        return RopeDynamicLights.boostPackedLight(lightPos, packed);
    }

    /**
     * Common render path for both real and ghost attachments. Emits the two
     * suspension
     * strings and the body (block-form via submitMovingBlock when possible,
     * otherwise
     * item-model).
     */
    private static void renderOne(SubmitNodeCollector collector, Vec3 cameraPos,
            ClientLevel level, Minecraft mc, Player player,
            net.minecraft.world.item.ItemStack stack,
            boolean displayAsBlock, boolean redstonePowered,
            int frontSide,
            double px, double py, double pz, HangFrame frame,
            BlockPos lightPos, int packedLight,
            int tintColor, float scaleMul, boolean ghost) {
        boolean asBlockItem = displayAsBlock
                && com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(stack);
        // BlockEntity-rendered blocks (signs, shulker boxes, chests, banners, ...) only
        // have
        // a partial static model; submitMovingBlock would render an empty post for
        // signs,
        // etc. Detect them up front and route through the item-model FIXED path which
        // uses
        // the full item display model.
        boolean useMovingBlock = asBlockItem && !needsItemFallback(stack);

        AttachmentLayout layout = attachmentLayout(level, lightPos, stack, displayAsBlock, frontSide);

        // Suspension strings: two parallel lines from offset rope points to the
        // computed
        // top contact on the model. Thin-stem models (torch/sign post) are pierced by
        // the
        // rope instead, so they do not get dangling strings.
        if (!layout.pierced() && layout.hangerLength() > 1.0e-6D) {
            double offX = frame.rdx * layout.hangerHalfSpacing();
            double offY = frame.rdy * layout.hangerHalfSpacing();
            double offZ = frame.rdz * layout.hangerHalfSpacing();
            double dropX = frame.dropX * layout.hangerLength();
            double dropY = frame.dropY * layout.hangerLength();
            double dropZ = frame.dropZ * layout.hangerLength();
            double a1x = px - offX, a1y = py - offY, a1z = pz - offZ;
            double a2x = px + offX, a2y = py + offY, a2z = pz + offZ;
            submitTwoStrings(collector, cameraPos,
                    a1x, a1y, a1z, a1x + dropX, a1y + dropY, a1z + dropZ,
                    a2x, a2y, a2z, a2x + dropX, a2y + dropY, a2z + dropZ,
                    packedLight);
        }

        double cx = px + frame.dropX * layout.centerDropOffset();
        double cy = py + frame.dropY * layout.centerDropOffset();
        double cz = pz + frame.dropZ * layout.centerDropOffset();
        float bodyScale = layout.bodyScale() * scaleMul;

        if (asBlockItem && isSignBlock(stack)
                && submitSignBlockEntity(collector, cameraPos, level, mc, stack, frontSide,
                        frame.tilt, cx, cy, cz, bodyScale, lightPos, packedLight)) {
            return;
        }

        if (useMovingBlock
                && submitBlockForm(collector, cameraPos, level, stack, redstonePowered,
                        frontSide, frame.tilt, cx, cy, cz, bodyScale, lightPos)) {
            return;
        }

        // Use a fresh state per attachment: submit() may capture by reference for
        // deferred batching, so reusing one instance can cause every entry to render
        // with the last item's state.
        ItemStackRenderState renderState = new ItemStackRenderState();
        // For BlockItems we display as block-form but couldn't go through
        // submitMovingBlock
        // (BlockEntity blocks like signs), use FIXED so the full item model is shown.
        ItemDisplayContext context = (asBlockItem && needsItemFallback(stack))
                ? ItemDisplayContext.FIXED
                : ItemDisplayContext.GROUND;
        mc.getItemModelResolver().updateForTopItem(renderState, stack, context, level, player, 0);
        if (renderState.isEmpty())
            return;
        float scale = ITEM_SCALE * scaleMul;
        PoseStack pose = new PoseStack();
        pose.translate(cx - cameraPos.x, cy - cameraPos.y, cz - cameraPos.z);
        pose.mulPose(frame.tilt);
        pose.scale(scale, scale, scale);
        // BlockEntity blocks (furnace, dispenser, mod generators, ...) render via the
        // item
        // model in FIXED context. That path uses the block's DEFAULT state
        // (FACING=NORTH),
        // so orientBlockState's FACING is ignored. Manually rotate around the model's
        // local
        // Y axis to bring the default visual front (model -Z) onto the player-selected
        // side.
        if (context == ItemDisplayContext.FIXED) {
            float yawDeg = itemModelYaw(frontSide);
            if (yawDeg != 0.0F) {
                pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(yawDeg)));
            }
        }
        renderState.submit(pose, collector, packedLight,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, tintColor);
    }

    /**
     * Yaw applied to BE-block item models so the default front face (model -Z) ends
     * up
     * on the side of the block facing the viewer after the tilt basis rotates model
     * +Z
     * to the world side direction.
     */
    private static float itemModelYaw(int frontSide) {
        return switch (RopeAttachment.normalizeFrontSide(frontSide)) {
            case 1 -> 180.0F; // viewer on +side -> rotate -Z to +Z
            case -2 -> 90.0F; // viewer on -tangent -> rotate -Z to -X
            case 2 -> -90.0F; // viewer on +tangent -> rotate -Z to +X
            default -> 0.0F; // frontSide == -1: default front already on -Z
        };
    }

    /**
     * Render the block form of {@code stack} via the moving-block pipeline so we
     * get
     * a consistent half-block visual and can override LIT/POWERED for
     * redstone-aware
     * blocks. Returns false on failure so the caller can fall back to an item
     * model.
     */
    private static boolean submitBlockForm(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            net.minecraft.world.item.ItemStack stack,
            boolean powered,
            int frontSide,
            Quaternionf tilt,
            double cx, double cy, double cz,
            float blockScale,
            BlockPos lightPos) {
        if (!(stack.getItem() instanceof BlockItem blockItem))
            return false;
        BlockState state = blockItem.getBlock().defaultBlockState();
        state = orientBlockState(state, frontSide);
        // Lanterns on ropes should render with the hanging chain model (top loop +
        // chain).
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HANGING)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HANGING,
                    Boolean.TRUE);
        }
        if (powered) {
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
                // Redstone torches default to LIT=true → powered means extinguish (LIT=false).
                // Redstone lamps default to LIT=false → powered means light up (LIT=true).
                // Invert the default value so both categories render correctly.
                boolean defaultLit = state
                        .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT,
                        !defaultLit);
            }
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED,
                        Boolean.TRUE);
            }
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED)) {
                state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED,
                        Boolean.TRUE);
            }
        }
        try {
            net.minecraft.client.renderer.block.MovingBlockRenderState mb = new net.minecraft.client.renderer.block.MovingBlockRenderState();
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
            pose.scale(blockScale, blockScale, blockScale);
            pose.translate(-0.5D, -0.5D, -0.5D);
            collector.submitMovingBlock(pose, mb);

            if (powered && state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
                submitPistonHead(collector, cameraPos, level, state.getBlock(), frontSide, tilt,
                        cx, cy, cz, blockScale, lightPos);
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void submitPistonHead(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            net.minecraft.world.level.block.Block pistonBlock,
            int frontSide,
            Quaternionf tilt,
            double cx, double cy, double cz,
            float blockScale,
            BlockPos lightPos) {
        net.minecraft.core.Direction facing = localFaceDirection(frontSide);
        BlockState headState = net.minecraft.world.level.block.Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, facing)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE,
                        pistonBlock == net.minecraft.world.level.block.Blocks.STICKY_PISTON
                                ? net.minecraft.world.level.block.state.properties.PistonType.STICKY
                                : net.minecraft.world.level.block.state.properties.PistonType.DEFAULT)
                .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.SHORT, Boolean.FALSE);

        net.minecraft.client.renderer.block.MovingBlockRenderState head = new net.minecraft.client.renderer.block.MovingBlockRenderState();
        head.blockPos = lightPos.relative(facing);
        head.randomSeedPos = head.blockPos;
        head.blockState = headState;
        head.biome = level.getBiome(lightPos);
        head.cardinalLighting = level.cardinalLighting();
        head.lightEngine = level.getLightEngine();

        PoseStack headPose = new PoseStack();
        headPose.translate(cx - cameraPos.x, cy - cameraPos.y, cz - cameraPos.z);
        headPose.mulPose(tilt);
        headPose.scale(blockScale, blockScale, blockScale);
        headPose.translate(
                facing.getStepX() - 0.5D,
                facing.getStepY() - 0.5D,
                facing.getStepZ() - 0.5D);
        collector.submitMovingBlock(headPose, head);
    }

    private static boolean submitSignBlockEntity(SubmitNodeCollector collector,
            Vec3 cameraPos,
            ClientLevel level,
            Minecraft mc,
            net.minecraft.world.item.ItemStack stack,
            int frontSide,
            Quaternionf tilt,
            double cx, double cy, double cz,
            float blockScale,
            BlockPos lightPos,
            int packedLight) {
        if (!(stack.getItem() instanceof BlockItem blockItem))
            return false;
        BlockState state = signRenderBlockState(blockItem);
        if (state == null)
            return false;
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.SignBlock))
            return false;
        state = orientBlockState(state, frontSide);

        net.minecraft.world.level.block.entity.SignBlockEntity sign = state
                .getBlock() instanceof net.minecraft.world.level.block.HangingSignBlock
                        ? new net.minecraft.world.level.block.entity.HangingSignBlockEntity(lightPos, state)
                        : new net.minecraft.world.level.block.entity.SignBlockEntity(lightPos, state);
        sign.setLevel(level);
        applyBlockEntityData(stack, sign, level);

        try {
            net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher = mc
                    .getBlockEntityRenderDispatcher();
            dispatcher.prepare(cameraPos);
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState = dispatcher
                    .tryExtractRenderState(sign, 0.0F, null, null);
            if (renderState == null)
                return false;
            renderState.lightCoords = packedLight;

            PoseStack pose = new PoseStack();
            pose.translate(cx - cameraPos.x, cy - cameraPos.y, cz - cameraPos.z);
            pose.mulPose(tilt);
            pose.scale(blockScale, blockScale, blockScale);
            pose.translate(-0.5D, -0.5D, -0.5D);
            dispatcher.submit(renderState, pose, collector,
                    mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void applyBlockEntityData(net.minecraft.world.item.ItemStack stack,
            net.minecraft.world.level.block.entity.SignBlockEntity sign,
            ClientLevel level) {
        net.minecraft.world.item.component.TypedEntityData<net.minecraft.world.level.block.entity.BlockEntityType<?>> data = stack
                .get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        if (data != null && data.type() == sign.getType()) {
            data.loadInto(sign, level.registryAccess());
        }
        sign.applyComponentsFromItemStack(stack);
    }

    private static boolean isSignBlock(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof net.minecraft.world.level.block.SignBlock;
    }

    private static boolean isHangingSignBlock(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.HangingSignItem
                || (stack.getItem() instanceof BlockItem blockItem
                        && blockItem.getBlock() instanceof net.minecraft.world.level.block.HangingSignBlock);
    }

    private static BlockState signRenderBlockState(BlockItem blockItem) {
        net.minecraft.world.level.block.Block block = blockItem.getBlock();
        if (!(block instanceof net.minecraft.world.level.block.SignBlock)) {
            return null;
        }
        return block.defaultBlockState();
    }

    private static BlockState orientBlockState(BlockState state, int frontSide) {
        net.minecraft.core.Direction front = localFaceDirection(frontSide);
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, front);
        } else if (state
                .hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                    front.getAxis().isHorizontal() ? front : sideFaceDirection(frontSide));
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16,
                    rotation16ForLocalFace(frontSide));
        }
        return state;
    }

    private static net.minecraft.core.Direction localFaceDirection(int frontSide) {
        return switch (RopeAttachment.normalizeFrontSide(frontSide)) {
            case -3 -> net.minecraft.core.Direction.DOWN;
            case 3 -> net.minecraft.core.Direction.UP;
            case -2 -> net.minecraft.core.Direction.WEST;
            case 2 -> net.minecraft.core.Direction.EAST;
            case -1 -> net.minecraft.core.Direction.NORTH;
            default -> net.minecraft.core.Direction.SOUTH;
        };
    }

    private static net.minecraft.core.Direction sideFaceDirection(int frontSide) {
        return RopeAttachment.normalizeFrontSide(frontSide) < 0
                ? net.minecraft.core.Direction.NORTH
                : net.minecraft.core.Direction.SOUTH;
    }

    private static int rotation16ForLocalFace(int frontSide) {
        return switch (RopeAttachment.normalizeFrontSide(frontSide)) {
            case -2 -> 4;
            case 2 -> 12;
            case -1 -> 0;
            default -> 8;
        };
    }

    private static int viewerLocalFace(HangFrame frame, double px, double py, double pz, Vec3 viewer) {
        double sideX = frame.rdz * frame.dropY - frame.rdy * frame.dropZ;
        double sideY = frame.rdx * frame.dropZ - frame.rdz * frame.dropX;
        double sideZ = frame.rdy * frame.dropX - frame.rdx * frame.dropY;
        double vx = viewer.x - px;
        double vy = viewer.y - py;
        double vz = viewer.z - pz;
        double tangentDot = vx * frame.rdx + vy * frame.rdy + vz * frame.rdz;
        double sideDot = vx * sideX + vy * sideY + vz * sideZ;
        double tangentAbs = Math.abs(tangentDot);
        double sideAbs = Math.abs(sideDot);
        if (tangentAbs > sideAbs) {
            return tangentDot >= 0.0D ? 2 : -2;
        }
        return sideDot >= 0.0D ? 1 : -1;
    }

    private static int viewerFrontSide(HangFrame frame, double px, double py, double pz, Vec3 viewer) {
        return viewerLocalFace(frame, px, py, pz, viewer);
    }

    /**
     * Determine which side of the rope the attachment front should face. This
     * intentionally
     * lives next to the render-frame math so placement packets and ghost/real
     * rendering don't
     * drift into subtly different coordinate systems.
     */
    public static int frontSideForViewer(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double px, double py, double pz,
            Vec3 viewer) {
        if (viewer == null) {
            return 1;
        }
        return viewerFrontSide(computeFrame(ax, ay, az, bx, by, bz), px, py, pz, viewer);
    }

    public static Vec3 attachmentBodyCenter(BlockGetter level,
            net.minecraft.world.item.ItemStack stack,
            boolean displayAsBlock,
            int frontSide,
            double px, double py, double pz,
            double ax, double ay, double az,
            double bx, double by, double bz) {
        HangFrame frame = computeFrame(ax, ay, az, bx, by, bz);
        AttachmentLayout layout = attachmentLayout(level, BlockPos.containing(px, py, pz), stack, displayAsBlock,
                frontSide);
        return new Vec3(
                px + frame.dropX * layout.centerDropOffset(),
                py + frame.dropY * layout.centerDropOffset(),
                pz + frame.dropZ * layout.centerDropOffset());
    }

    private static AttachmentLayout attachmentLayout(BlockGetter level, BlockPos pos,
            net.minecraft.world.item.ItemStack stack, boolean displayAsBlock, int frontSide) {
        boolean asBlockItem = displayAsBlock
                && com.zhongbai233.super_lead.lead.RopeAttachmentItems.isBlockItem(stack);
        if (!asBlockItem) {
            return AttachmentLayout.hanging(ITEM_HANG_OFFSET, HANGER_LENGTH, HANGER_HALF_SPACING, BLOCK_RENDER_SCALE);
        }

        float bodyScale = isSignBlock(stack) ? SIGN_RENDER_SCALE : BLOCK_RENDER_SCALE;
        ShapeProfile shape = shapeProfile(level, pos, stack, frontSide);

        // Plain signs have a thin post and need readable text, so keep them full-size
        // and
        // pierced by the rope. Hanging signs should stay below the rope.
        if (isSignBlock(stack) && isHangingSignBlock(stack)) {
            return hangingSignLayout(shape, bodyScale);
        }
        if (isSignBlock(stack)) {
            double bottomY = shape.valid() ? shape.minY() : 0.0D;
            return AttachmentLayout.pierced(bodyScale * (bottomY - 0.5D) - PIERCED_LIFT_OFFSET, bodyScale);
        }

        // Lanterns (and soul lanterns) have a narrow top loop designed for hanging;
        // they should always dangle below the rope with the HANGING chain model.
        if (stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof net.minecraft.world.level.block.LanternBlock) {
            return hangingSignLayout(shape, bodyScale);
        }

        if (shape.valid() && shouldPierce(shape)) {
            return AttachmentLayout.pierced(bodyScale * (shape.minY() - 0.5D) - PIERCED_LIFT_OFFSET, bodyScale);
        }

        return hangingBlockLayout(shape, bodyScale);
    }

    private static AttachmentLayout hangingSignLayout(ShapeProfile shape, float bodyScale) {
        // Vanilla hanging signs have the chain attachment plate near the top of the
        // rope on the plate above the chains; aim at the chain top instead so the
        // chains
        // visually grip the rope rather than the model's top edge sitting on it.
        double centerDropOffset = bodyScale * 0.5D;
        return AttachmentLayout.hanging(centerDropOffset, 0.0D, 0.0D, bodyScale);
    }

    private static AttachmentLayout hangingBlockLayout(ShapeProfile shape, float bodyScale) {
        double topY = shape.valid() ? shape.maxY() : 1.0D;
        // Keep the body at the stable hanging position; adjust the two thin strings
        // instead
        // so short/flat models (comparator/repeater) are not pulled upward.
        double centerDropOffset = HANGER_LENGTH + bodyScale * 0.5D;
        double hangerLength = Math.max(0.02D, centerDropOffset - bodyScale * (topY - 0.5D));
        double halfSpacing = HANGER_HALF_SPACING;
        if (shape.valid()) {
            double horizontalWidth = Math.max(shape.maxX() - shape.minX(), shape.maxZ() - shape.minZ());
            halfSpacing = Math.min(halfSpacing,
                    Math.max(MIN_HANGER_HALF_SPACING, horizontalWidth * bodyScale * 0.42D));
        }
        return AttachmentLayout.hanging(centerDropOffset, hangerLength, halfSpacing, bodyScale);
    }

    private static boolean shouldPierce(ShapeProfile shape) {
        return shape.height() >= INSERT_MIN_HEIGHT
                && shape.bottomFootprintArea() <= INSERT_BOTTOM_AREA_MAX;
    }

    private static ShapeProfile shapeProfile(BlockGetter level, BlockPos pos,
            net.minecraft.world.item.ItemStack stack, int frontSide) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || level == null) {
            return ShapeProfile.INVALID;
        }
        BlockState state = orientBlockState(blockItem.getBlock().defaultBlockState(), frontSide);
        VoxelShape shape = state.getShape(level, pos == null ? BlockPos.ZERO : pos);
        if (shape.isEmpty()) {
            shape = state.getCollisionShape(level, pos == null ? BlockPos.ZERO : pos);
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return ShapeProfile.FULL_BLOCK;
        }

        double minX = 1.0D, minY = 1.0D, minZ = 1.0D;
        double maxX = 0.0D, maxY = 0.0D, maxZ = 0.0D;
        for (AABB box : boxes) {
            minX = Math.min(minX, clamp01(box.minX));
            minY = Math.min(minY, clamp01(box.minY));
            minZ = Math.min(minZ, clamp01(box.minZ));
            maxX = Math.max(maxX, clamp01(box.maxX));
            maxY = Math.max(maxY, clamp01(box.maxY));
            maxZ = Math.max(maxZ, clamp01(box.maxZ));
        }
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return ShapeProfile.FULL_BLOCK;
        }
        double height = maxY - minY;
        double slice = Math.min(0.18D, Math.max(0.055D, height * 0.18D));
        double bottomArea = horizontalUnionArea(boxes, minY, minY + slice);
        return new ShapeProfile(true, minX, minY, minZ, maxX, maxY, maxZ, bottomArea);
    }

    private static double horizontalUnionArea(List<AABB> boxes, double sliceMinY, double sliceMaxY) {
        ArrayList<Double> xs = new ArrayList<>();
        ArrayList<Double> zs = new ArrayList<>();
        ArrayList<AABB> active = new ArrayList<>();
        for (AABB box : boxes) {
            if (box.maxY <= sliceMinY || box.minY >= sliceMaxY) {
                continue;
            }
            double minX = clamp01(box.minX);
            double maxX = clamp01(box.maxX);
            double minZ = clamp01(box.minZ);
            double maxZ = clamp01(box.maxZ);
            if (maxX <= minX || maxZ <= minZ) {
                continue;
            }
            AABB clamped = new AABB(minX, 0.0D, minZ, maxX, 1.0D, maxZ);
            active.add(clamped);
            xs.add(minX);
            xs.add(maxX);
            zs.add(minZ);
            zs.add(maxZ);
        }
        if (active.isEmpty()) {
            return 0.0D;
        }
        xs.sort(Double::compareTo);
        zs.sort(Double::compareTo);
        double area = 0.0D;
        for (int xi = 0; xi < xs.size() - 1; xi++) {
            double x0 = xs.get(xi), x1 = xs.get(xi + 1);
            if (x1 <= x0)
                continue;
            for (int zi = 0; zi < zs.size() - 1; zi++) {
                double z0 = zs.get(zi), z1 = zs.get(zi + 1);
                if (z1 <= z0)
                    continue;
                double cx = (x0 + x1) * 0.5D;
                double cz = (z0 + z1) * 0.5D;
                for (AABB box : active) {
                    if (cx >= box.minX && cx <= box.maxX && cz >= box.minZ && cz <= box.maxZ) {
                        area += (x1 - x0) * (z1 - z0);
                        break;
                    }
                }
            }
        }
        return Math.min(1.0D, area);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    private record AttachmentLayout(boolean pierced, double centerDropOffset,
            double hangerLength, double hangerHalfSpacing,
            float bodyScale) {
        static AttachmentLayout hanging(double centerDropOffset, double hangerLength,
                double hangerHalfSpacing, float bodyScale) {
            return new AttachmentLayout(false, centerDropOffset, hangerLength, hangerHalfSpacing, bodyScale);
        }

        static AttachmentLayout pierced(double centerDropOffset, float bodyScale) {
            return new AttachmentLayout(true, centerDropOffset, 0.0D, 0.0D, bodyScale);
        }
    }

    private record ShapeProfile(boolean valid,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double bottomFootprintArea) {
        static final ShapeProfile INVALID = new ShapeProfile(false, 0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D, 1.0D);
        static final ShapeProfile FULL_BLOCK = new ShapeProfile(true, 0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D, 1.0D);

        double height() {
            return maxY - minY;
        }
    }

    /**
     * True when this BlockItem has visible content rendered by a
     * BlockEntityRenderer
     * (signs, banners, shulker boxes, chests, beds, ...). The static block model
     * alone
     * would be empty or a stub, so we use the full item model instead.
     */
    private static boolean needsItemFallback(net.minecraft.world.item.ItemStack stack) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem))
            return false;
        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();
        // hasBlockEntity() catches signs, banners, chests, shulker boxes, beds, etc.
        // Render shape INVISIBLE / ENTITYBLOCK_ANIMATED also need the item model.
        if (state.hasBlockEntity())
            return true;
        net.minecraft.world.level.block.RenderShape shape = state.getRenderShape();
        return shape != net.minecraft.world.level.block.RenderShape.MODEL;
    }

    /** Geometry used to tilt the attachment with rope slope. */
    private record HangFrame(double rdx, double rdy, double rdz,
            double dropX, double dropY, double dropZ,
            Quaternionf tilt) {
    }

    private static HangFrame computeFrame(double ax, double ay, double az,
            double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6D) {
            return new HangFrame(1, 0, 0, 0, -1, 0, new Quaternionf());
        }
        double rdx = dx / len, rdy = dy / len, rdz = dz / len;
        // Build a right-handed orthonormal basis aligned with the rope:
        // local +X (tangent) = rope direction; local +Y = world-up projected
        // perpendicular
        // to tangent (so the item's "up" stays as upright as possible); local +Z = X
        // cross Y.
        // The resulting quaternion combines yaw (around world Y, so the item faces
        // along the
        // rope's horizontal heading) and pitch (around the in-plane axis, so the item
        // tilts
        // with the rope's slope -- top edge perpendicular to tangent in the vertical
        // plane).
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
        // Drop direction = -localY (item centre hangs perpendicular below the rope
        // tangent).
        return new HangFrame(rdx, rdy, rdz, -up.x, -up.y, -up.z, q);
    }

    /**
     * Emit the two suspension strings as a single batched custom-geometry
     * submission.
     * Each string is rendered as two perpendicular thin quads (double-sided) so
     * it's
     * visible from any angle.
     */
    private static void submitTwoStrings(SubmitNodeCollector collector, Vec3 cameraPos,
            double a1x, double a1y, double a1z,
            double b1x, double b1y, double b1z,
            double a2x, double a2y, double a2z,
            double b2x, double b2y, double b2z,
            int packedLight) {
        final float A1x = (float) (a1x - cameraPos.x), A1y = (float) (a1y - cameraPos.y),
                A1z = (float) (a1z - cameraPos.z);
        final float B1x = (float) (b1x - cameraPos.x), B1y = (float) (b1y - cameraPos.y),
                B1z = (float) (b1z - cameraPos.z);
        final float A2x = (float) (a2x - cameraPos.x), A2y = (float) (a2y - cameraPos.y),
                A2z = (float) (a2z - cameraPos.z);
        final float B2x = (float) (b2x - cameraPos.x), B2y = (float) (b2y - cameraPos.y),
                B2z = (float) (b2z - cameraPos.z);
        final int color = HANGER_COLOR;
        final int light = packedLight;
        collector.submitCustomGeometry(IDENTITY_POSE, RenderTypes.textBackground(), (state, buffer) -> {
            emitLineQuads(buffer, A1x, A1y, A1z, B1x, B1y, B1z, color, light);
            emitLineQuads(buffer, A2x, A2y, A2z, B2x, B2y, B2z, color, light);
        });
    }

    /**
     * Emit the four quads (two perpendicular planes, both faces) that make up one
     * thin double-sided line between (ax,ay,az) and (bx,by,bz) in camera-relative
     * space.
     */
    private static void emitLineQuads(com.mojang.blaze3d.vertex.VertexConsumer buffer,
            float ax, float ay, float az,
            float bx, float by, float bz,
            int color, int light) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5F)
            return;
        float udx = dx / len, udy = dy / len, udz = dz / len;
        // n1 perpendicular to direction, in horizontal plane if possible.
        float n1x, n1y, n1z;
        if (Math.abs(udy) > 0.99F) {
            n1x = 1;
            n1y = 0;
            n1z = 0;
        } else {
            n1x = udz;
            n1y = 0;
            n1z = -udx;
            float l1 = (float) Math.sqrt(n1x * n1x + n1z * n1z);
            n1x /= l1;
            n1z /= l1;
        }
        // n2 = direction × n1.
        float n2x = udy * n1z - udz * n1y;
        float n2y = udz * n1x - udx * n1z;
        float n2z = udx * n1y - udy * n1x;
        final float w = HANGER_HALF_THICKNESS;
        n1x *= w;
        n1y *= w;
        n1z *= w;
        n2x *= w;
        n2y *= w;
        n2z *= w;
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

    /**
     * Find the segment index {@code i} such that
     * {@code renderLength(i) <= target <= renderLength(i+1)}.
     */
    private static int locateSegment(RopeSimulation sim, int nodeCount, double target) {
        if (target <= 0.0D)
            return 0;
        for (int i = 0; i < nodeCount - 1; i++) {
            if (target <= sim.renderLength(i + 1))
                return i;
        }
        return nodeCount - 2;
    }

    private static int locateSegment(double[] lengths, double target) {
        if (target <= 0.0D)
            return 0;
        for (int i = 0; i < lengths.length - 1; i++) {
            if (target <= lengths[i + 1])
                return i;
        }
        return lengths.length - 2;
    }

    /**
     * Best-fit segment + fraction for a 3D world point along the rope's polyline.
     * Returns the cumulative arc length at the projected point, or -1 if
     * {@code sim} is empty.
     */
    static double projectArcLength(RopeSimulation sim, double targetX, double targetY, double targetZ,
            float partialTick) {
        double total = sim.prepareRender(partialTick);
        if (total <= 0.0D)
            return -1.0D;
        int nodeCount = sim.nodeCount();
        double bestLen = 0.0D;
        double bestDistSqr = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nodeCount - 1; i++) {
            double ax = sim.renderX(i), ay = sim.renderY(i), az = sim.renderZ(i);
            double bx = sim.renderX(i + 1), by = sim.renderY(i + 1), bz = sim.renderZ(i + 1);
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double lenSqr = dx * dx + dy * dy + dz * dz;
            if (lenSqr < 1.0e-10D)
                continue;
            double frac = ((targetX - ax) * dx + (targetY - ay) * dy + (targetZ - az) * dz) / lenSqr;
            if (frac < 0.0D)
                frac = 0.0D;
            else if (frac > 1.0D)
                frac = 1.0D;
            double cx = ax + dx * frac, cy = ay + dy * frac, cz = az + dz * frac;
            double rx = targetX - cx, ry = targetY - cy, rz = targetZ - cz;
            double distSqr = rx * rx + ry * ry + rz * rz;
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestLen = sim.renderLength(i) + (sim.renderLength(i + 1) - sim.renderLength(i)) * frac;
            }
        }
        if (bestDistSqr == Double.POSITIVE_INFINITY)
            return -1.0D;
        return bestLen;
    }
}
