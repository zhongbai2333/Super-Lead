package com.zhongbai233.super_lead.lead.client.render;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Render-layer choices for rope-shaped custom geometry.
 *
 * <p>
 * These streams are emitted as explicit quads, so the selected layer must keep
 * quad-compatible ordering. Keep the render-state choice centralized and out of
 * the geometry builders.
 */
public final class RopeRenderTypes {
    private RopeRenderTypes() {
    }

    public static RenderType dynamicRope() {
        return quadGeometry();
    }

    public static RenderType attachmentLine() {
        return quadGeometry();
    }

    public static RenderType ziplineChain() {
        return quadGeometry();
    }

    private static RenderType quadGeometry() {
        return RenderTypes.textBackground();
    }
}
