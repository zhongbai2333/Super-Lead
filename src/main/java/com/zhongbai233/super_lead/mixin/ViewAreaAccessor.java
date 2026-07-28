package com.zhongbai233.super_lead.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the render section selected by the vanilla circular view grid. */
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
    @Invoker("getRenderSection")
    SectionRenderDispatcher.RenderSection superLead$getRenderSection(long sectionPos);
}