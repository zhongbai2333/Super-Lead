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

    /**
     * Non-block items that should be displayed as a flat panel rather than as a
     * tiny dropped item. AE2 terminal parts are items, not blocks, but visually
     * they behave like cable-mounted panels.
     */
    public static boolean isPanelLikeItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem());
        if (!id.getNamespace().equals("ae2")) {
            return false;
        }
        return switch (id.getPath()) {
            case "terminal", "crafting_terminal", "pattern_encoding_terminal", "pattern_access_terminal",
                    "storage_monitor", "conversion_monitor" -> true;
            default -> false;
        };
    }
}
