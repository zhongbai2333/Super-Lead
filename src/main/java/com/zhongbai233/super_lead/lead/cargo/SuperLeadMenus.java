package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SuperLeadMenus {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,
            Super_lead.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CargoManifestMenu>> CARGO_MANIFEST = MENUS
            .register("cargo_manifest", () -> IMenuTypeExtension.create(CargoManifestMenu::fromNetwork));

    private SuperLeadMenus() {
    }

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}