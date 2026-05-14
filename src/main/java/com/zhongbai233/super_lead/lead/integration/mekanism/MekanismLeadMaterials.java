package com.zhongbai233.super_lead.lead.integration.mekanism;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Mekanism material matching without a hard dependency on Mekanism item classes.
 */
public final class MekanismLeadMaterials {
    private static final Identifier STEEL_BLOCK_ID = Identifier.fromNamespaceAndPath("mekanism", "block_steel");
    private static final Identifier REINFORCED_ALLOY_ID = Identifier.fromNamespaceAndPath("mekanism",
            "alloy_reinforced");

    private static final TagKey<Item> C_STEEL_BLOCKS = TagKey.create(Registries.ITEM,
        Identifier.fromNamespaceAndPath("c", "storage_blocks/steel"));
    /** Mekanism 26.1 tags reinforced alloy as c:alloys/elite. */
    private static final TagKey<Item> C_REINFORCED_ALLOYS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "alloys/elite"));
    private static final TagKey<Item> MEK_REINFORCED_ALLOYS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("mekanism", "alloys/reinforced"));

    private MekanismLeadMaterials() {
    }

    public static boolean isMekanismLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded("mekanism");
    }

    public static boolean isSteelBlock(ItemStack stack) {
        return isMekanismLoaded() && stack != null && !stack.isEmpty()
                && (stack.is(C_STEEL_BLOCKS) || hasId(stack, STEEL_BLOCK_ID));
    }

    public static boolean isReinforcedAlloy(ItemStack stack) {
        return isMekanismLoaded() && stack != null && !stack.isEmpty()
                && (stack.is(C_REINFORCED_ALLOYS)
                        || stack.is(MEK_REINFORCED_ALLOYS)
                        || hasId(stack, REINFORCED_ALLOY_ID));
    }

    public static Item steelBlockIcon() {
        return itemOrFallback(STEEL_BLOCK_ID, Items.IRON_BLOCK);
    }

    public static Item reinforcedAlloyIcon() {
        return itemOrFallback(REINFORCED_ALLOY_ID, Items.REDSTONE);
    }

    private static boolean hasId(ItemStack stack, Identifier id) {
        return id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static Item itemOrFallback(Identifier id, Item fallback) {
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return id.equals(BuiltInRegistries.ITEM.getKey(item)) ? item : fallback;
    }
}