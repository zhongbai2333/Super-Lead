package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * Item or block-like decoration attached to a rope at parameter {@code t}.
 *
 * <p>
 * {@code frontSide} stores the face shown to the viewer for directional
 * attachments such as signs. The stack is copied/normalized so render and
 * server
 * drop logic can treat attachments as immutable samples.
 */
public record RopeAttachment(UUID id, double t, ItemStack stack, boolean displayAsBlock, int frontSide) {
    public static final Codec<RopeAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(RopeAttachment::id),
            Codec.DOUBLE.fieldOf("t").forGetter(a -> Double.valueOf(a.t())),
            ItemStack.CODEC.fieldOf("stack").forGetter(RopeAttachment::stack),
            Codec.BOOL.optionalFieldOf("display_as_block", Boolean.TRUE)
                    .forGetter(a -> Boolean.valueOf(a.displayAsBlock())),
            Codec.INT.optionalFieldOf("front_side", 1).forGetter(a -> Integer.valueOf(a.frontSide())))
            .apply(instance, (id, t, stack, asBlock, frontSide) -> new RopeAttachment(id, t.doubleValue(), stack,
                    asBlock.booleanValue(), frontSide.intValue())));

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeAttachment> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RopeAttachment::id,
            ByteBufCodecs.DOUBLE, a -> Double.valueOf(a.t()),
            ItemStack.STREAM_CODEC, RopeAttachment::stack,
            ByteBufCodecs.BOOL, a -> Boolean.valueOf(a.displayAsBlock()),
            ByteBufCodecs.VAR_INT, a -> Integer.valueOf(a.frontSide()),
            (id, t, stack, asBlock, frontSide) -> new RopeAttachment(id, t.doubleValue(), stack,
                    asBlock.booleanValue(), frontSide.intValue()));

    public RopeAttachment {
        if (Double.isNaN(t))
            t = 0.5D;
        t = Math.max(0.02D, Math.min(0.98D, t));
        stack = stack.copyWithCount(1);
        frontSide = normalizeFrontSide(frontSide);
    }

    public static RopeAttachment create(double t, ItemStack stack) {
        return create(t, stack, 1);
    }

    public static RopeAttachment create(double t, ItemStack stack, int frontSide) {
        boolean asBlock = RopeAttachmentItems.isBlockItem(stack) || RopeAttachmentItems.isPanelLikeItem(stack);
        return new RopeAttachment(UUID.randomUUID(), t, stack, asBlock, frontSide);
    }

    public RopeAttachment withDisplayAsBlock(boolean asBlock) {
        return new RopeAttachment(id, t, stack, asBlock, frontSide);
    }

    public RopeAttachment withStack(ItemStack stack) {
        return new RopeAttachment(id, t, stack, displayAsBlock, frontSide);
    }

    public static int normalizeFrontSide(int frontSide) {
        if (frontSide == 0) {
            return 1;
        }
        if (frontSide > 3) {
            return 3;
        }
        if (frontSide < -3) {
            return -3;
        }
        return frontSide;
    }
}
