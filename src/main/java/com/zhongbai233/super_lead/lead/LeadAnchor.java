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

public record LeadAnchor(BlockPos pos, Direction face) {
    public static final Codec<LeadAnchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(LeadAnchor::pos),
            Direction.CODEC.fieldOf("face").forGetter(LeadAnchor::face))
            .apply(instance, LeadAnchor::new));

    private static final double EXTRUDE = 0.06D;
    private static final double FENCE_KNOT_Y = 0.75D;
    private static final double IRON_BARS_KNOT_Y = 0.08D;

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
