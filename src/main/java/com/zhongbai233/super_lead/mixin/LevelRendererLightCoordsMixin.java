package com.zhongbai233.super_lead.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererLightCoordsMixin {
    @ModifyReturnValue(method = "getLightCoords(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"))
    private static int superLead$applyRopeDynamicLight(int original,
            LevelRenderer.BrightnessGetter brightnessGetter,
            BlockAndLightGetter level, BlockState state, BlockPos pos) {
        return RopeDynamicLights.boostPackedLight(pos, original);
    }
}
