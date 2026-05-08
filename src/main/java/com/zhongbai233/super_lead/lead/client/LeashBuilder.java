package com.zhongbai233.super_lead.lead.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.super_lead.lead.LeadKind;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * 自定义绳子几何：保留 1 模型像素见方的可拉伸方柱，不再复用原版扁平 leash 模型。
 *
 * Java 世界内的原版 leash 并没有独立贴图，原版视觉来自顶点色条纹。
 * 注意不能使用 RenderTypes.leash()：它的顶点模式是 TRIANGLE_STRIP，而这里提交的是 QUADS。
 * 因此这里使用无贴图且带 lightmap 的 QUADS 渲染层，再用程序棕色条纹模拟原版绳色。
 */
public final class LeashBuilder {
    public static final int NO_HIGHLIGHT = 0;
    public static final int DEFAULT_HIGHLIGHT = 0x66FFEE84;

    private static final double HALF_THICKNESS = 1.0D / 32.0D;
    private static final double HIGHLIGHT_HALF_THICKNESS = HALF_THICKNESS * 2.45D;
    private static final double STRIPE_LENGTH = 0.25D;
    private static final float[] EMPTY_PULSES = new float[0];
    private static final double PULSE_SIGMA = 0.07D;
    private static final double PULSE_AMPLITUDE = 1.6D;
    private static final double FIRST_SEGMENT_AMPLITUDE = 0.9D;
    private static final double FIRST_SEGMENT_LENGTH = 0.45D;

    private LeashBuilder() {}

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            boolean highlighted) {
        submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlighted ? DEFAULT_HIGHLIGHT : NO_HIGHLIGHT, LeadKind.NORMAL, false, 0);
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            boolean highlighted,
            LeadKind kind,
            boolean powered) {
            submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlighted ? DEFAULT_HIGHLIGHT : NO_HIGHLIGHT, kind, powered, 0);
            }

            public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier) {
        submit(collector, cameraPos, sim, partialTick, blockA, blockB, skyA, skyB,
                highlightColor, kind, powered, tier, EMPTY_PULSES, 0);
    }

    public static void submit(
            SubmitNodeCollector collector,
            Vec3 cameraPos,
            RopeSimulation sim,
            float partialTick,
            int blockA, int blockB,
            int skyA, int skyB,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        boolean glow = powered && (kind == LeadKind.REDSTONE || kind == LeadKind.ENERGY);
        final int effectiveBlockA = glow ? 15 : blockA;
        final int effectiveBlockB = glow ? 15 : blockB;

        int nodeCount = sim.nodeCount();
        Vec3[] nodes = new Vec3[nodeCount];
        double[] lengths = new double[nodeCount];
        nodes[0] = sim.nodeAt(0, partialTick);
        for (int i = 1; i < nodeCount; i++) {
            nodes[i] = sim.nodeAt(i, partialTick);
            lengths[i] = lengths[i - 1] + nodes[i].distanceTo(nodes[i - 1]);
        }
        final double totalLength = Math.max(1.0e-6D, lengths[nodeCount - 1]);
        PoseStack pose = new PoseStack();
        collector.submitCustomGeometry(pose, RenderTypes.textBackground(), (poseState, buffer) -> {
            for (int i = 0; i < nodeCount - 1; i++) {
                Vec3 start = nodes[i].subtract(cameraPos);
                Vec3 end = nodes[i + 1].subtract(cameraPos);
                float t0 = i / (float) (nodeCount - 1);
                float t1 = (i + 1) / (float) (nodeCount - 1);
                int light0 = LightCoordsUtil.pack((int) lerp(t0, effectiveBlockA, effectiveBlockB), (int) lerp(t0, skyA, skyB));
                int light1 = LightCoordsUtil.pack((int) lerp(t1, effectiveBlockA, effectiveBlockB), (int) lerp(t1, skyA, skyB));
                renderSegment(buffer, poseState, start, end, lengths[i], lengths[i + 1], totalLength, light0, light1, NO_HIGHLIGHT, kind, powered, tier, pulsePositions, extractEnd);
                if (highlightColor != NO_HIGHLIGHT) {
                    renderSegment(buffer, poseState, start, end, lengths[i], lengths[i + 1], totalLength, light0, light1, highlightColor, kind, powered, tier, pulsePositions, extractEnd);
                }
            }
        });
    }

    private static float lerp(float t, int a, int b) {
        return a + (b - a) * t;
    }

    private static void renderSegment(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 start,
            Vec3 end,
            double lengthStart,
            double lengthEnd,
            double totalLength,
            int light0,
            int light1,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier,
            float[] pulsePositions,
            int extractEnd) {
        Vec3 dir = end.subtract(start);
        double length = dir.length();
        if (length < 1.0e-6D) {
            return;
        }
        dir = dir.scale(1.0D / length);

        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0e-8D) {
            side = dir.cross(new Vec3(1, 0, 0));
        }
        double baseThickness = highlightColor != NO_HIGHLIGHT ? HIGHLIGHT_HALF_THICKNESS : HALF_THICKNESS;
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();

        double cursor = lengthStart;
        while (cursor < lengthEnd - 1.0e-6D) {
            double nextStripe = (Math.floor(cursor / STRIPE_LENGTH) + 1.0D) * STRIPE_LENGTH;
            double next = Math.min(lengthEnd, nextStripe);
            double localStart = (cursor - lengthStart) / (lengthEnd - lengthStart);
            double localEnd = (next - lengthStart) / (lengthEnd - lengthStart);
            Vec3 subStart = interpolate(start, end, localStart);
            Vec3 subEnd = interpolate(start, end, localEnd);
            int stripe = (int) Math.floor((cursor + next) * 0.5D / STRIPE_LENGTH);
            int subLight0 = interpolateLight(light0, light1, localStart);
            int subLight1 = interpolateLight(light0, light1, localEnd);
            double midNorm = ((cursor + next) * 0.5D) / totalLength;
            double mult = thicknessMultiplier(midNorm, pulsePositions, extractEnd);
            double t = baseThickness * mult;
            Vec3 sideScaled = side.scale(t);
            Vec3 upScaled = up.scale(t);
            renderSubSegment(buffer, pose, subStart, subEnd, sideScaled, upScaled, stripe, subLight0, subLight1, highlightColor, kind, powered, tier);
            cursor = next;
        }
    }

    private static double thicknessMultiplier(double normalizedPos, float[] pulsePositions, int extractEnd) {
        double bonus = 0.0D;
        if (pulsePositions != null) {
            for (float p : pulsePositions) {
                double d = (normalizedPos - p) / PULSE_SIGMA;
                bonus += PULSE_AMPLITUDE * Math.exp(-d * d);
            }
        }
        // extractEnd: 1 = `from` end (start of rope), 2 = `to` end (end of rope), 0 = none.
        if (extractEnd == 1 && normalizedPos < FIRST_SEGMENT_LENGTH) {
            double f = 1.0D - (normalizedPos / FIRST_SEGMENT_LENGTH);
            bonus += FIRST_SEGMENT_AMPLITUDE * f * f;
        } else if (extractEnd == 2 && normalizedPos > 1.0D - FIRST_SEGMENT_LENGTH) {
            double f = (normalizedPos - (1.0D - FIRST_SEGMENT_LENGTH)) / FIRST_SEGMENT_LENGTH;
            bonus += FIRST_SEGMENT_AMPLITUDE * f * f;
        }
        return 1.0D + bonus;
    }

    private static Vec3 interpolate(Vec3 a, Vec3 b, double t) {
        return a.add(b.subtract(a).scale(t));
    }

    private static int interpolateLight(int packedA, int packedB, double t) {
        int blockA = packedA & 65535;
        int skyA = packedA >> 16 & 65535;
        int blockB = packedB & 65535;
        int skyB = packedB >> 16 & 65535;
        return LightCoordsUtil.pack((int) (blockA + (blockB - blockA) * t), (int) (skyA + (skyB - skyA) * t));
    }

    private static void renderSubSegment(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 start,
            Vec3 end,
            Vec3 side,
            Vec3 up,
            int stripe,
            int light0,
            int light1,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier) {
        Vec3 a = start.add(side).add(up);
        Vec3 b = start.add(side).subtract(up);
        Vec3 c = start.subtract(side).subtract(up);
        Vec3 d = start.subtract(side).add(up);
        Vec3 e = end.add(side).add(up);
        Vec3 f = end.add(side).subtract(up);
        Vec3 g = end.subtract(side).subtract(up);
        Vec3 h = end.subtract(side).add(up);

        quad(buffer, pose, a, e, f, b, stripe, light0, light1, 0, highlightColor, kind, powered, tier);
        quad(buffer, pose, b, f, g, c, stripe, light0, light1, 1, highlightColor, kind, powered, tier);
        quad(buffer, pose, c, g, h, d, stripe, light0, light1, 2, highlightColor, kind, powered, tier);
        quad(buffer, pose, d, h, e, a, stripe, light0, light1, 3, highlightColor, kind, powered, tier);
    }

    private static void quad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Vec3 p0,
            Vec3 p1,
            Vec3 p2,
            Vec3 p3,
            int stripe,
            int light0,
            int light1,
            int face,
            int highlightColor,
            LeadKind kind,
            boolean powered,
            int tier) {
        int color = highlightColor != NO_HIGHLIGHT ? highlightOverlayColor(face, highlightColor) : ropeColor(stripe, face, kind, powered, tier);
        vertex(buffer, pose, p0, color, light0);
        vertex(buffer, pose, p1, color, light1);
        vertex(buffer, pose, p2, color, light1);
        vertex(buffer, pose, p3, color, light0);

        // textBackground 是会剔除背面的 QUADS 渲染层；方柱绳子很细，必须双面提交，
        // 否则面朝玩家的两面和背朝玩家的两面会随视角互相“消失”。
        vertex(buffer, pose, p3, color, light0);
        vertex(buffer, pose, p2, color, light1);
        vertex(buffer, pose, p1, color, light1);
        vertex(buffer, pose, p0, color, light0);
    }

    private static int ropeColor(int stripe, int face, LeadKind kind, boolean powered, int tier) {
        boolean bright = (stripe & 1) == 0;
        double shade = switch (face) {
            case 0 -> 1.0D;
            case 1 -> 0.82D;
            case 2 -> 0.68D;
            default -> 0.9D;
        };
        if (kind == LeadKind.REDSTONE) {
            int r = (int) ((bright ? (powered ? 255 : 168) : (powered ? 142 : 84)) * shade);
            int g = (int) ((bright ? (powered ? 64 : 22) : (powered ? 10 : 0)) * shade);
            int b = (int) ((bright ? (powered ? 58 : 20) : (powered ? 12 : 0)) * shade);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (kind == LeadKind.ENERGY) {
            // Tier accent: every (tier+1)-th stripe is golden — higher tier = denser gold bands.
            boolean goldBand = tier > 0 && (stripe % Math.max(2, 4 - Math.min(3, tier))) == 0 && bright;
            int rBase, gBase, bBase;
            if (powered) {
                rBase = bright ? 255 : 200;
                gBase = bright ? 220 : 150;
                bBase = bright ? 80 : 40;
            } else {
                rBase = bright ? 228 : 128;
                gBase = bright ? 158 : 84;
                bBase = bright ? 54 : 22;
            }
            if (goldBand) {
                rBase = 255;
                gBase = powered ? 240 : 210;
                bBase = powered ? 120 : 60;
            }
            int r = (int) (rBase * shade);
            int g = (int) (gBase * shade);
            int b = (int) (bBase * shade);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        int r = (int) ((bright ? 142 : 86) * shade);
        int g = (int) ((bright ? 101 : 59) * shade);
        int b = (int) ((bright ? 57 : 34) * shade);
        if (kind == LeadKind.ITEM) {
            // Cool steel-blue palette to read as "item conduit"; keep stripe contrast.
            r = (int) ((bright ? 175 : 110) * shade);
            g = (int) ((bright ? 200 : 130) * shade);
            b = (int) ((bright ? 230 : 165) * shade);
        } else if (kind == LeadKind.FLUID) {
            // Cyan/teal palette to read as "fluid conduit".
            r = (int) ((bright ? 90 : 50) * shade);
            g = (int) ((bright ? 220 : 145) * shade);
            b = (int) ((bright ? 220 : 150) * shade);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int highlightOverlayColor(int face, int baseColor) {
        double shade = switch (face) {
            case 0 -> 1.0D;
            case 1 -> 0.9D;
            case 2 -> 0.8D;
            default -> 0.95D;
        };
        int a = baseColor >>> 24;
        int r = (int) (((baseColor >> 16) & 255) * shade);
        int g = (int) (((baseColor >> 8) & 255) * shade);
        int b = (int) ((baseColor & 255) * shade);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 pos, int color, int light) {
        buffer.addVertex(pose, (float) pos.x, (float) pos.y, (float) pos.z)
                .setColor(color)
                .setLight(light);
    }
}
