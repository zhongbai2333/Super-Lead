package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

public record RopeAttachment(UUID id, double t, ItemStack stack, boolean displayAsBlock) {
    public static final Codec<RopeAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(RopeAttachment::id),
                    Codec.DOUBLE.fieldOf("t").forGetter(a -> Double.valueOf(a.t())),
                    ItemStack.CODEC.fieldOf("stack").forGetter(RopeAttachment::stack),
                    Codec.BOOL.optionalFieldOf("display_as_block", Boolean.TRUE).forGetter(a -> Boolean.valueOf(a.displayAsBlock())))
            .apply(instance, (id, t, stack, asBlock) -> new RopeAttachment(id, t.doubleValue(), stack, asBlock.booleanValue())));

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeAttachment> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RopeAttachment::id,
            ByteBufCodecs.DOUBLE, a -> Double.valueOf(a.t()),
            ItemStack.STREAM_CODEC, RopeAttachment::stack,
            ByteBufCodecs.BOOL, a -> Boolean.valueOf(a.displayAsBlock()),
            (id, t, stack, asBlock) -> new RopeAttachment(id, t.doubleValue(), stack, asBlock.booleanValue()));

    public RopeAttachment {
        if (Double.isNaN(t)) t = 0.5D;
        t = Math.max(0.02D, Math.min(0.98D, t));
        stack = stack.copyWithCount(1);
    }

    public static RopeAttachment create(double t, ItemStack stack) {
        boolean asBlock = RopeAttachmentItems.isBlockItem(stack);
        return new RopeAttachment(UUID.randomUUID(), t, stack, asBlock);
    }

    public RopeAttachment withDisplayAsBlock(boolean asBlock) {
        return new RopeAttachment(id, t, stack, asBlock);
    }
}
