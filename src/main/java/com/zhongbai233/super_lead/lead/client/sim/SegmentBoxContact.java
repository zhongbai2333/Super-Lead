package com.zhongbai233.super_lead.lead.client.sim;

import net.minecraft.world.phys.AABB;

final class SegmentBoxContact {
    double ux;
    double uy;
    double uz;
    double segLenSqr;
    double s;
    double spx;
    double spy;
    double spz;
    double cpx;
    double cpy;
    double cpz;
    double dx;
    double dy;
    double dz;
    double distSqr;

    SegmentBoxContact() {
    }

    SegmentBoxContact compute(double ax, double ay, double az,
            double bx, double by, double bz, AABB box) {
        return compute(ax, ay, az, bx, by, bz, box, 1.0e-12D);
    }

    SegmentBoxContact compute(double ax, double ay, double az,
            double bx, double by, double bz, AABB box, double degenerateThreshold) {
        ux = bx - ax;
        uy = by - ay;
        uz = bz - az;
        segLenSqr = ux * ux + uy * uy + uz * uz;

        if (segLenSqr < degenerateThreshold) {
            s = 0.0D;
            spx = ax;
            spy = ay;
            spz = az;
        } else {
            double mx = (box.minX + box.maxX) * 0.5D - ax;
            double my = (box.minY + box.maxY) * 0.5D - ay;
            double mz = (box.minZ + box.maxZ) * 0.5D - az;
            s = clamp01((mx * ux + my * uy + mz * uz) / segLenSqr);
            setSegmentPoint(ax, ay, az);
        }

        for (int it = 0; it < 4; it++) {
            setBoxPoint(box);
            if (segLenSqr < degenerateThreshold) {
                spx = ax;
                spy = ay;
                spz = az;
                break;
            }

            double tx = cpx - ax;
            double ty = cpy - ay;
            double tz = cpz - az;
            double ns = clamp01((tx * ux + ty * uy + tz * uz) / segLenSqr);
            if (Math.abs(ns - s) < 1.0e-6D) {
                s = ns;
                setSegmentPoint(ax, ay, az);
                break;
            }

            s = ns;
            setSegmentPoint(ax, ay, az);
        }

        setBoxPoint(box);
        dx = spx - cpx;
        dy = spy - cpy;
        dz = spz - cpz;
        distSqr = dx * dx + dy * dy + dz * dz;
        return this;
    }

    private void setSegmentPoint(double ax, double ay, double az) {
        spx = ax + ux * s;
        spy = ay + uy * s;
        spz = az + uz * s;
    }

    private void setBoxPoint(AABB box) {
        cpx = clamp(spx, box.minX, box.maxX);
        cpy = clamp(spy, box.minY, box.maxY);
        cpz = clamp(spz, box.minZ, box.maxZ);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }
}
