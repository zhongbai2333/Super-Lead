package com.zhongbai233.super_lead.lead;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class RopeAttachmentItems {
    private RopeAttachmentItems() {
    }

    /**
     * Whether {@code stack} can be attached to a rope. STRING is excluded because
     * it
     * is the bind material (must be in the opposite hand to perform the attach).
     */
    public static boolean isAttachable(ItemStack stack) {
        return !stack.isEmpty() && !stack.is(Items.STRING);
    }

    public static boolean isBlockItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }
}
