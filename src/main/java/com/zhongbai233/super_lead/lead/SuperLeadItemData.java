package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class SuperLeadItemData {
    private static final String KIND_KEY = Super_lead.MODID + ".kind";

    private SuperLeadItemData() {
    }

    public static LeadKind kind(ItemStack stack) {
        if (!SuperLeadItems.isSuperLead(stack)) {
            return LeadKind.NORMAL;
        }

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return LeadKind.NORMAL;
        }

        CompoundTag tag = data.copyTag();
        return LeadKind.byName(tag.getStringOr(KIND_KEY, LeadKind.NORMAL.serializedName()));
    }

    public static boolean isRedstoneLead(ItemStack stack) {
        return kind(stack) == LeadKind.REDSTONE;
    }

    public static void setKind(ItemStack stack, LeadKind kind) {
        if (!SuperLeadItems.isSuperLead(stack)) {
            return;
        }

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KIND_KEY, kind.serializedName()));
        if (kind == LeadKind.REDSTONE) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.redstone_lead"));
        } else if (kind == LeadKind.ENERGY) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.energy_lead"));
        } else if (kind == LeadKind.ITEM) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.item_lead"));
        } else if (kind == LeadKind.FLUID) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.fluid_lead"));
        } else if (kind == LeadKind.PRESSURIZED) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.pressurized_lead"));
        } else if (kind == LeadKind.THERMAL) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.thermal_lead"));
        } else if (kind == LeadKind.AE_NETWORK) {
            stack.set(DataComponents.ITEM_NAME, Component.translatable("item.super_lead.ae_network_lead"));
        }
    }
}
