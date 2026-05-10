package com.zhongbai233.super_lead.lead.client.render;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class UpgradeRow {
    final ItemStack icon;
    final Component label;

    UpgradeRow(ItemStack icon, Component label) {
        this.icon = icon;
        this.label = label;
    }
}
