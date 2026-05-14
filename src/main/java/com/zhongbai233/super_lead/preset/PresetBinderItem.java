package com.zhongbai233.super_lead.preset;

import com.zhongbai233.super_lead.lead.cargo.SuperLeadDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** Survival-facing tool for creating and binding single-rope physics presets. */
public class PresetBinderItem extends Item {
    public PresetBinderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        PresetBinderData data = stack.get(SuperLeadDataComponents.PRESET_BINDER.get());
        if (data == null || !data.isBound()) {
            tooltip.accept(Component.translatable("tooltip.super_lead.preset_binder.unbound")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.translatable("tooltip.super_lead.preset_binder.create_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.accept(Component.translatable("tooltip.super_lead.preset_binder.bound", data.presetName())
                .withStyle(ChatFormatting.AQUA));
        tooltip.accept(Component.translatable("tooltip.super_lead.preset_binder.edit_hint")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.super_lead.preset_binder.bind_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}