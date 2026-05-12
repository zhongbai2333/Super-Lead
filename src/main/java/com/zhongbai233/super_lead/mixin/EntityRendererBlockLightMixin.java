package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererBlockLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void superLead$applyRopeDynamicLight(Entity entity, BlockPos blockPos,
            CallbackInfoReturnable<Integer> cir) {
        int boosted = RopeDynamicLights.boostBlockLight(blockPos, cir.getReturnValueI());
        if (boosted != cir.getReturnValueI()) {
            cir.setReturnValue(boosted);
        }
    }
}
