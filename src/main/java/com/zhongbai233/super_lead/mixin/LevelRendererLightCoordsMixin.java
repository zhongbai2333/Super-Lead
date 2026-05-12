package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererLightCoordsMixin {
    @Inject(method = "getLightCoords(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private static void superLead$applyRopeDynamicLight(LevelRenderer.BrightnessGetter brightnessGetter,
            BlockAndLightGetter level, BlockState state, BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        int boosted = RopeDynamicLights.boostPackedLight(pos, cir.getReturnValueI());
        if (boosted != cir.getReturnValueI()) {
            cir.setReturnValue(boosted);
        }
    }
}
