package com.zhongbai233.super_lead.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.super_lead.lead.client.render.ZiplineClientState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
public abstract class ZiplineHeldItemLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void superLead$hideMainHandChainWhileZiplining(ArmedEntityRenderState state,
            ItemStackRenderState itemState,
            ItemStack stack,
            HumanoidArm arm,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int combinedLight,
            CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar
                && arm == state.mainArm
                && ZiplineClientState.shouldHideMainHandChain(avatar.id, stack)) {
            ci.cancel();
        }
    }
}
