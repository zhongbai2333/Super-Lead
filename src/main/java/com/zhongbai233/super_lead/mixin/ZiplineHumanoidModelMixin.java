package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.RopeTripClientState;
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
    @Shadow
    public ModelPart head;
    @Shadow
    public ModelPart hat;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void superLead$ziplinePose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        if (RopeTripClientState.isTripping(avatar.id)) {
            superLead$tripPose(state, avatar);
            return;
        }
        if (!ZiplineClientState.isZiplining(avatar.id)) {
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

    private void superLead$tripPose(HumanoidRenderState state, AvatarRenderState avatar) {
        float fall = RopeTripClientState.renderFallAmount(state.partialTick);
        float slap = Mth.sin(fall * (float) Math.PI) * 0.08F;

        this.body.xRot = Mth.lerp(fall, 0.0F, -0.08F) - slap * 0.2F;
        this.body.yRot = 0.0F;
        this.body.zRot = 0.0F;

        this.head.xRot = Mth.lerp(fall, 0.0F, -0.22F) - slap;
        this.head.yRot *= 0.15F;
        this.head.zRot = 0.0F;
        this.hat.loadPose(this.head.storePose());

        this.rightArm.xRot = Mth.lerp(fall, this.rightArm.xRot, -0.10F) - slap;
        this.leftArm.xRot = Mth.lerp(fall, this.leftArm.xRot, -0.10F) - slap;
        this.rightArm.yRot = Mth.lerp(fall, this.rightArm.yRot, -0.12F);
        this.leftArm.yRot = Mth.lerp(fall, this.leftArm.yRot, 0.12F);
        this.rightArm.zRot = Mth.lerp(fall, this.rightArm.zRot, 1.56F);
        this.leftArm.zRot = Mth.lerp(fall, this.leftArm.zRot, -1.56F);

        this.rightLeg.xRot = Mth.lerp(fall, this.rightLeg.xRot, 0.08F) + slap * 0.3F;
        this.leftLeg.xRot = Mth.lerp(fall, this.leftLeg.xRot, 0.08F) - slap * 0.3F;
        this.rightLeg.yRot = Mth.lerp(fall, this.rightLeg.yRot, 0.22F);
        this.leftLeg.yRot = Mth.lerp(fall, this.leftLeg.yRot, -0.22F);
        this.rightLeg.zRot = Mth.lerp(fall, this.rightLeg.zRot, 0.40F);
        this.leftLeg.zRot = Mth.lerp(fall, this.leftLeg.zRot, -0.40F);
    }
}
