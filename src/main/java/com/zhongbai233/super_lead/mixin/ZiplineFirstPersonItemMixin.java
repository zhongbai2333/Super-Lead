package com.zhongbai233.super_lead.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.super_lead.lead.client.render.ZiplineClientState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ZiplineFirstPersonItemMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void superLead$hideMainHandChainWhileZiplining(AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equippedProgress,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int combinedLight,
            CallbackInfo ci) {
        if (hand == InteractionHand.MAIN_HAND
                && ZiplineClientState.shouldHideMainHandChain(player.getId(), stack)) {
            ci.cancel();
        }
    }
}
