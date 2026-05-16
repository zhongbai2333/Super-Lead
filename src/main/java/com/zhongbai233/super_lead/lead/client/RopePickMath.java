package com.zhongbai233.super_lead.lead.client;

/**
 * Shared ray/rope picking math for client interaction and highlight rendering.
 */
final class RopePickMath {
    private RopePickMath() {
    }

    static double distancePointToRaySqr(
            double px, double py, double pz,
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double maxDistance) {
        double offX = px - ox;
        double offY = py - oy;
        double offZ = pz - oz;
        double along = offX * dx + offY * dy + offZ * dz;
        if (along < 0.0D || along > maxDistance) {
            return Double.POSITIVE_INFINITY;
        }
        double cx = ox + dx * along;
        double cy = oy + dy * along;
        double cz = oz + dz * along;
        double rx = px - cx;
        double ry = py - cy;
        double rz = pz - cz;
        return rx * rx + ry * ry + rz * rz;
    }
}
