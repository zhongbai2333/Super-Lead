package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.render.SuperLeadGlCommandEncoderBridge;
import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin implements SuperLeadGlCommandEncoderBridge {
    @Override
    public void superLead$invalidateCachedState() {
        clearField("lastPipeline");
        clearField("lastProgram");
    }

    private void clearField(String name) {
        try {
            Field field = this.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, null);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
