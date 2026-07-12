package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Creative tab containing Super Lead content and the vanilla items used with it. */
public final class SuperLeadCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, Super_lead.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SUPER_LEAD = CREATIVE_MODE_TABS.register(
            "super_lead",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.super_lead"))
                    .icon(() -> SuperLeadItems.stack(LeadKind.NORMAL))
                    .displayItems((parameters, output) -> {
                        for (LeadKind kind : LeadKind.values()) {
                            output.accept(SuperLeadItems.stack(kind));
                        }

                        output.accept(new ItemStack(SuperLeadItems.BASIC_CARGO_MANIFEST.asItem()));
                        output.accept(new ItemStack(SuperLeadItems.ADVANCED_CARGO_MANIFEST.asItem()));
                        output.accept(new ItemStack(SuperLeadItems.PRESET_BINDER.asItem()));
                        output.accept(new ItemStack(SuperLeadItems.ATTACHMENT_TUNER.asItem()));

                        output.accept(Items.SHEARS);
                        output.accept(Items.STRING);
                        output.accept(Items.IRON_CHAIN);
                        output.accept(Items.REDSTONE_BLOCK);
                        output.accept(Items.IRON_BLOCK);
                        output.accept(Items.HOPPER);
                        output.accept(Items.CAULDRON);
                        output.accept(Items.COPPER_BLOCK);
                    })
                    .build());

    private SuperLeadCreativeTabs() {
    }

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}