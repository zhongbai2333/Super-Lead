package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SuperLeadItems {
    private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.withDefaultNamespace("tools_and_utilities"));

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Super_lead.MODID);
    public static final DeferredItem<Item> SUPER_LEAD = ITEMS.registerSimpleItem("super_lead");

    private SuperLeadItems() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
        eventBus.addListener(SuperLeadItems::addCreativeTabContents);
    }

    public static boolean isSuperLead(ItemStack stack) {
        return stack.is(SUPER_LEAD.asItem());
    }

    public static ItemStack stack(LeadKind kind) {
        ItemStack stack = new ItemStack(SUPER_LEAD.asItem());
        SuperLeadItemData.setKind(stack, kind);
        return stack;
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(TOOLS_AND_UTILITIES)) {
            event.accept(new ItemStack(SUPER_LEAD.asItem()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
