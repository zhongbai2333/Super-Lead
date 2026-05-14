package com.zhongbai233.super_lead.lead.integration.ae2;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * AE2 material matching without linking against AE2 item classes.
 */
public final class AE2LeadMaterials {
    private static final Identifier FLUIX_BLOCK_ID = Identifier.fromNamespaceAndPath("ae2", "fluix_block");
    private static final Identifier SPATIAL_CELL_COMPONENT_16_ID = Identifier.fromNamespaceAndPath("ae2",
            "spatial_cell_component_16");

    private AE2LeadMaterials() {
    }

    public static boolean isAe2Loaded() {
        return net.neoforged.fml.ModList.get().isLoaded("ae2");
    }

    public static boolean isFluixBlock(ItemStack stack) {
        return isAe2Loaded() && stack != null && !stack.isEmpty() && hasId(stack, FLUIX_BLOCK_ID);
    }

    public static boolean isSpatialCellComponent16(ItemStack stack) {
        return isAe2Loaded() && stack != null && !stack.isEmpty()
                && hasId(stack, SPATIAL_CELL_COMPONENT_16_ID);
    }

    public static Item fluixBlockIcon() {
        return itemOrFallback(FLUIX_BLOCK_ID, Items.AMETHYST_BLOCK);
    }

    public static Item spatialCellComponent16Icon() {
        return itemOrFallback(SPATIAL_CELL_COMPONENT_16_ID, Items.ENDER_PEARL);
    }

    private static boolean hasId(ItemStack stack, Identifier id) {
        return id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static Item itemOrFallback(Identifier id, Item fallback) {
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return id.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : fallback;
    }
}