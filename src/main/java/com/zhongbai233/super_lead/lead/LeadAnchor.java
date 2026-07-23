package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public record LeadAnchor(BlockPos pos, Direction face, Vec3 hitPoint) {
    public static final Codec<LeadAnchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(anchor -> anchor.pos()),
            Direction.CODEC.fieldOf("face").forGetter(anchor -> anchor.face()),
            Vec3.CODEC.optionalFieldOf("hitPoint").forGetter(anchor -> java.util.Optional.ofNullable(anchor.hitPoint())))
            .apply(instance, (pos, face, hitPoint) -> new LeadAnchor(pos, face, hitPoint.orElse(null))));

    private static final double EXTRUDE = 0.06D;
    private static final double FENCE_KNOT_Y = 0.75D;
    private static final double IRON_BARS_KNOT_Y = 0.08D;

    public LeadAnchor(BlockPos pos, Direction face) {
        this(pos, face, null);
    }

    public LeadAnchor {
        pos = pos.immutable();
        if (hitPoint != null && (!Double.isFinite(hitPoint.x)
                || !Double.isFinite(hitPoint.y) || !Double.isFinite(hitPoint.z))) {
            hitPoint = null;
        }
    }

    /** Logical capability identity; the precise hit point is visual geometry only. */
    public boolean samePort(LeadAnchor other) {
        return other != null && pos.equals(other.pos) && face == other.face;
    }

    public LeadAnchor logicalPort() {
        return hitPoint == null ? this : new LeadAnchor(pos, face);
    }

    static boolean shouldPreserveHitPoint(VoxelShape shape) {
        return shape != null && !shape.isEmpty()
                && (shape.min(Direction.Axis.X) < 0.0D
                        || shape.min(Direction.Axis.Y) < 0.0D
                        || shape.min(Direction.Axis.Z) < 0.0D
                        || shape.max(Direction.Axis.X) > 1.0D
                        || shape.max(Direction.Axis.Y) > 1.0D
                        || shape.max(Direction.Axis.Z) > 1.0D);
    }

    public static boolean isKnotBlock(BlockState state) {
        return state != null && (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof IronBarsBlock);
    }

    public static boolean isIronBarsKnotBlock(BlockState state) {
        return state != null && state.getBlock() instanceof IronBarsBlock;
    }

    public static Direction knotFace(BlockState state, Direction fallback) {
        return isKnotBlock(state) ? Direction.UP : fallback;
    }

    public Vec3 attachmentPoint(Level level) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FenceBlock) {
            return Vec3.atLowerCornerOf(pos).add(0.5D, FENCE_KNOT_Y, 0.5D);
        }
        if (state.getBlock() instanceof IronBarsBlock) {
            return Vec3.atLowerCornerOf(pos).add(0.5D, IRON_BARS_KNOT_Y, 0.5D);
        }

        if (hitPoint != null) {
            return hitPoint.add(
                    face.getStepX() * EXTRUDE,
                    face.getStepY() * EXTRUDE,
                    face.getStepZ() * EXTRUDE);
        }

        VoxelShape shape = state.getShape(level, pos);
        AABB bounds = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();

        double x = (bounds.minX + bounds.maxX) * 0.5D;
        double y = (bounds.minY + bounds.maxY) * 0.5D;
        double z = (bounds.minZ + bounds.maxZ) * 0.5D;

        switch (face) {
            case DOWN -> y = bounds.minY;
            case UP -> y = bounds.maxY;
            case NORTH -> z = bounds.minZ;
            case SOUTH -> z = bounds.maxZ;
            case WEST -> x = bounds.minX;
            case EAST -> x = bounds.maxX;
        }

        return Vec3.atLowerCornerOf(pos).add(
                x + face.getStepX() * EXTRUDE,
                y + face.getStepY() * EXTRUDE,
                z + face.getStepZ() * EXTRUDE);
    }
}
