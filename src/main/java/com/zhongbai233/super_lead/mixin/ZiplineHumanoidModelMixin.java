package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.render.ZiplineClientState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class ZiplineHumanoidModelMixin {
    @Shadow
    public ModelPart rightArm;
    @Shadow
    public ModelPart leftArm;
    @Shadow
    public ModelPart rightLeg;
    @Shadow
    public ModelPart leftLeg;
    @Shadow
    public ModelPart body;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void superLead$ziplinePose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar) || !ZiplineClientState.isZiplining(avatar.id)) {
            return;
        }
        float sway = Mth.sin(state.ageInTicks * 0.28F) * 0.035F;
        this.body.xRot = 0.10F;
        this.rightArm.xRot = (float) Math.PI + 0.10F + sway;
        this.leftArm.xRot = (float) Math.PI + 0.10F - sway;
        this.rightArm.yRot = -0.18F;
        this.leftArm.yRot = 0.18F;
        this.rightArm.zRot = 0.32F;
        this.leftArm.zRot = -0.32F;
        this.rightLeg.xRot = -0.35F - sway * 0.5F;
        this.leftLeg.xRot = -0.18F + sway * 0.5F;
        this.rightLeg.zRot = 0.08F;
        this.leftLeg.zRot = -0.08F;
    }
}