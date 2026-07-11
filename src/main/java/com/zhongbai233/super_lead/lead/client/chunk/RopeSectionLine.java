package com.zhongbai233.super_lead.lead.client.chunk;

/** Static chunk-section line segment used for attachment hanger strings. */
public record RopeSectionLine(
        float ax, float ay, float az,
        float bx, float by, float bz,
        int color,
        int light) {
}