package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public record LeadAnchor(BlockPos pos, Direction face) {
    public static final Codec<LeadAnchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(LeadAnchor::pos),
                    Direction.CODEC.fieldOf("face").forGetter(LeadAnchor::face))
            .apply(instance, LeadAnchor::new));

    /** 沿 face 法线把锚点向外推一点，避免端点落在方块内部导致光照变黑。 */
    private static final double EXTRUDE = 0.06D;

    public Vec3 attachmentPoint(Level level) {
        if (level.getBlockState(pos).getBlock() instanceof FenceBlock) {
            return Vec3.atLowerCornerOf(pos).add(0.5D, 0.75D, 0.5D);
        }

        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
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
