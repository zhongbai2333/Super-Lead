package com.zhongbai233.super_lead.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.super_lead.lead.client.VanillaLeashHook;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitLeash(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/state/EntityRenderState$LeashState;)V"))
    private void superLead$rerouteLeash(SubmitNodeCollector collector, PoseStack poseStack, EntityRenderState.LeashState leashState) {
        VanillaLeashHook.submit(collector, poseStack, leashState);
    }
}
