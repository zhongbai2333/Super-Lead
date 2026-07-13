package com.zhongbai233.super_lead.lead.client.chunk;

/** Static chunk-section line segment used for attachment hanger strings. */
public record RopeSectionLine(
        double ax, double ay, double az,
        double bx, double by, double bz,
        int color,
        int light) {
}