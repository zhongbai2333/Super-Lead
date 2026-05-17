package com.zhongbai233.super_lead.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlBuffer")
public interface GlBufferAccessor {
    @Accessor("handle")
    int superLead$getHandle();
}
