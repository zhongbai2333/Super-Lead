package com.zhongbai233.super_lead.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererBlockLightMixin {
    @ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"))
    private int superLead$applyRopeDynamicLight(int original, Entity entity, BlockPos blockPos) {
        return RopeDynamicLights.boostBlockLight(blockPos, original);
    }
}
