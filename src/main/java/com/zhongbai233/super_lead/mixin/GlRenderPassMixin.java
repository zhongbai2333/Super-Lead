package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.render.SuperLeadGlCommandEncoderBridge;
import com.zhongbai233.super_lead.lead.client.render.SuperLeadGlRenderPassBridge;
import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class GlRenderPassMixin implements SuperLeadGlRenderPassBridge {
    @Override
    public void superLead$invalidateEncoderState() {
        try {
            Field field = this.getClass().getDeclaredField("encoder");
            field.setAccessible(true);
            Object encoder = field.get(this);
            if (encoder instanceof SuperLeadGlCommandEncoderBridge bridge) {
                bridge.superLead$invalidateCachedState();
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
