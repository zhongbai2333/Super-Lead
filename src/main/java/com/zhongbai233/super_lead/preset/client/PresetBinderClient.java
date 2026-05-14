package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.lead.cargo.SuperLeadDataComponents;
import com.zhongbai233.super_lead.preset.PresetBinderData;
import com.zhongbai233.super_lead.preset.PresetBinderToggleRope;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class PresetBinderClient {
    private PresetBinderClient() {
    }

    public static void openOrEdit(ItemStack stack, InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        PresetBinderData data = stack.get(SuperLeadDataComponents.PRESET_BINDER.get());
        if (data == null || !data.isBound()) {
            mc.setScreen(new PresetBinderNameScreen(mc.screen, hand));
            return;
        }
        mc.setScreen(new PresetEditScreen(mc.screen, data.presetName()));
    }

    public static void sendToggleRope(InteractionHand hand) {
        ClientPacketDistributor.sendToServer(new PresetBinderToggleRope(hand == InteractionHand.OFF_HAND));
    }
}