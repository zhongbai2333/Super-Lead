package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.preset.PresetBinderData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Data component type registry for cargo manifests and preset binders. */
public final class SuperLeadDataComponents {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister
            .createDataComponents(Registries.DATA_COMPONENT_TYPE, Super_lead.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> CARGO_MANIFEST_ITEMS = COMPONENTS
            .registerComponentType("cargo_manifest_items", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC)
                    .cacheEncoding());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PresetBinderData>> PRESET_BINDER = COMPONENTS
            .registerComponentType("preset_binder", builder -> builder
                    .persistent(PresetBinderData.CODEC)
                    .networkSynchronized(PresetBinderData.STREAM_CODEC)
                    .cacheEncoding());

    private SuperLeadDataComponents() {
    }

    public static void register(IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}