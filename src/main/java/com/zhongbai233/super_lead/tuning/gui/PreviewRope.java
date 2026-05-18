package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Lightweight rope preview model used by tuning screens.
 *
 * <p>
 * It renders a deterministic sample rope independent of world state, making
 * radius, sag and color changes immediately visible without spawning entities
 * or
 * touching the server simulation.
 */
public final class PreviewRope {
    private static final int SIM_TICK_INTERVAL = 3;
    private static final int LINE_SAMPLE_STRIDE_PX = 4;

    private double spanMeters = 5.0D;

    private double[] x;
    private double[] y;
    private double[] px;
    private double[] py;
    private int[] screenX;
    private int[] screenY;
    private boolean[] pinned;
    private int n;
    private double restLen;
    private long lastPhysEpoch = -1L;
    private long lastRenderEpoch = -1L;
    private int frame;

    private int viewX, viewY, viewW, viewH;
    private double pxPerMeter;
    private boolean renderDirty = true;

    private boolean colliderActive = true;
    private boolean animatedPreview = true;
    private double colliderX = 2.5D, colliderY = 1.4D;
    private final double colliderR = 0.45D;
    private Map<String, String> overrides = Map.of();
    private boolean defaultMissingOverrides;
    private long overridesEpoch;
    private long lastOverridesEpoch = -1L;

    public PreviewRope() {
        rebuildFromTuning(true);
    }

    public void setViewport(int x, int y, int w, int h) {
        boolean changed = this.viewX != x || this.viewY != y || this.viewW != w || this.viewH != h;
        this.viewX = x;
        this.viewY = y;
        this.viewW = w;
        this.viewH = h;
        double margin = 0.10D;
        this.pxPerMeter = (w * (1.0D - 2.0D * margin)) / spanMeters;
        if (changed) {
            renderDirty = true;
        }
    }

    public void setColliderActive(boolean active) {
        this.colliderActive = active;
    }

    public void setAnimatedPreview(boolean animated) {
        if (animatedPreview == animated) {
            return;
        }
        animatedPreview = animated;
        colliderActive = animated;
        rebuildFromTuning(true);
    }

    public boolean animatedPreview() {
        return animatedPreview;
    }

    public void setOverrides(Map<String, String> overrides, boolean defaultMissingOverrides) {
        Map<String, String> next = ClientTuning.normalizeOverrides(overrides);
        if (this.overrides.equals(next) && this.defaultMissingOverrides == defaultMissingOverrides) {
            return;
        }
        this.overrides = next;
        this.defaultMissingOverrides = defaultMissingOverrides;
        overridesEpoch++;
        rebuildFromTuning(true);
    }

    public void disturb() {
        if (n < 3)
            return;
        for (int i = 1; i < n - 1; i++) {
            double t = (double) i / (n - 1);
            double w = 4.0D * t * (1.0D - t);
            x[i] += 0.35D * w;
            y[i] -= 0.20D * w;
        }
        renderDirty = true;
    }

    public void reset() {
        rebuildFromTuning(true);
    }

    private boolean rebuildFromTuning(boolean force) {
        long pe = ClientTuning.physicsEpoch();
        if (!force && pe == lastPhysEpoch && overridesEpoch == lastOverridesEpoch)
            return false;
        lastPhysEpoch = pe;
        lastOverridesEpoch = overridesEpoch;

        double segLen = value(ClientTuning.SEGMENT_LENGTH);
        int segMax = value(ClientTuning.SEGMENT_MAX);
        int segments = Math.max(2, Math.min(segMax, (int) Math.round(spanMeters / Math.max(0.05D, segLen))));
        int newN = segments + 1;
        if (newN != n || x == null) {
            n = newN;
            x = new double[n];
            y = new double[n];
            px = new double[n];
            py = new double[n];
            screenX = new int[n];
            screenY = new int[n];
            pinned = new boolean[n];
            pinned[0] = true;
            pinned[n - 1] = true;
        }
        restLen = spanMeters / segments;
        resetStaticShape();
        renderDirty = true;
        return true;
    }

    private void resetStaticShape() {
        double slack = value(ClientTuning.SLACK);
        double gravity = value(ClientTuning.GRAVITY);
        double sag = Math.abs(RopeSagModel.midspanSag(spanMeters, slack, gravity));
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            x[i] = t * spanMeters;
            y[i] = Math.sin(Math.PI * t) * sag;
            px[i] = x[i];
            py[i] = y[i];
        }
    }

    public void tick() {
        boolean rebuilt = rebuildFromTuning(false);
        frame++;
        if (!animatedPreview) {
            if (rebuilt) {
                renderDirty = true;
            }
            return;
        }
        if (frame % SIM_TICK_INTERVAL != 0) {
            return;
        }
        if (colliderActive) {
            double t = frame / 60.0D;
            colliderX = spanMeters * (0.5D + 0.30D * Math.sin(t));
            colliderY = 1.30D + 0.15D * Math.sin(t * 0.7D);
        }

        double slack = value(ClientTuning.SLACK);
        double gravity = -value(ClientTuning.GRAVITY);
        double damping = value(ClientTuning.DAMPING);
        int iterAir = value(ClientTuning.ITER_AIR);
        int iterContact = value(ClientTuning.ITER_CONTACT);
        int iters = colliderActive ? Math.max(iterAir, iterContact) : iterAir;
        int segments = n - 1;
        double tautWeight = RopeSagModel.tautProjectionWeight(slack);
        int minPasses = Math.max((segments + 1) / 2,
                (int) Math.ceil(segments * (1.0D + tautWeight * 3.0D)));
        if (iters < minPasses) {
            iters = minPasses;
        }

        double target = restLen * RopeSagModel.lengthFactor(slack);
        double gravityScale = 1.0D - tautWeight;

        integrateNodes(damping, gravity, gravityScale);
        solveConstraints(iters, target);
        applyTautProjection(tautWeight);
        renderDirty = true;
    }

    private void integrateNodes(double damping, double gravity, double gravityScale) {
        for (int i = 0; i < n; i++) {
            if (pinned[i]) {
                px[i] = x[i];
                py[i] = y[i];
                continue;
            }
            double vx = (x[i] - px[i]) * damping;
            double vy = (y[i] - py[i]) * damping;
            px[i] = x[i];
            py[i] = y[i];
            x[i] += vx;
            y[i] += vy + gravity * gravityScale;
        }
    }

    private void solveConstraints(int iters, double target) {
        for (int it = 0; it < iters; it++) {
            solveDistanceConstraints(target);
            if (colliderActive) {
                solveColliderConstraints();
            }
        }
    }

    private void solveDistanceConstraints(double target) {
        for (int i = 0; i < n - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < 1.0e-9D)
                continue;
            double diff = (d - target) / d;
            double wA = pinned[i] ? 0.0D : 1.0D;
            double wB = pinned[i + 1] ? 0.0D : 1.0D;
            double wSum = wA + wB;
            if (wSum <= 0.0D)
                continue;
            double sA = wA / wSum;
            double sB = wB / wSum;
            x[i] += dx * diff * sA;
            y[i] += dy * diff * sA;
            x[i + 1] -= dx * diff * sB;
            y[i + 1] -= dy * diff * sB;
        }
    }

    private void solveColliderConstraints() {
        for (int i = 1; i < n - 1; i++) {
            double dx = x[i] - colliderX;
            double dy = y[i] - colliderY;
            double d2 = dx * dx + dy * dy;
            double r = colliderR;
            if (d2 >= r * r) {
                continue;
            }
            double d = Math.sqrt(d2);
            if (d < 1.0e-6D) {
                x[i] += 0.0D;
                y[i] -= r;
            } else {
                double push = (r - d) / d;
                x[i] += dx * push;
                y[i] += dy * push;
            }
        }
    }

    private void applyTautProjection(double weight) {
        if (weight <= 0.0D || n < 3) {
            return;
        }
        double clamped = Math.min(1.0D, weight);
        double keepVelocity = 1.0D - clamped;
        int last = n - 1;
        for (int i = 1; i < last; i++) {
            double vx = x[i] - px[i];
            double vy = y[i] - py[i];
            double t = i / (double) last;
            double tx = t * spanMeters;
            double ty = 0.0D;
            x[i] += (tx - x[i]) * clamped;
            y[i] += (ty - y[i]) * clamped;
            px[i] = x[i] - vx * keepVelocity;
            py[i] = y[i] - vy * keepVelocity;
        }
    }

    public void render(GuiGraphicsExtractor gg) {
        if (n < 2)
            return;
        double margin = 0.10D;
        int left = (int) (viewX + viewW * margin);
        int top = (int) (viewY + viewH * 0.30D);

        double halfMeters = value(ClientTuning.THICKNESS_HALF);
        boolean render3d = value(ClientTuning.MODE_RENDER3D);
        double widthFactor = value(ClientTuning.RIBBON_WIDTH_FACTOR);
        double halfPx = halfMeters * pxPerMeter * (render3d ? 1.0D : widthFactor);
        int thick = Math.max(1, (int) Math.round(halfPx * 2.0D));

        int color = 0xFF000000 | (value(ClientTuning.COLOR_NORMAL_ACCENT) & 0xFFFFFF);
        int outlineColor = 0xFF000000 | (value(ClientTuning.COLOR_NORMAL_BASE) & 0xFFFFFF);
        rebuildScreenCache(left, top);

        for (int i = 0; i < n - 1; i++) {
            drawThickLine(gg, screenX[i], screenY[i], screenX[i + 1], screenY[i + 1], thick + 1, outlineColor);
            drawThickLine(gg, screenX[i], screenY[i], screenX[i + 1], screenY[i + 1], thick, color);
        }

        for (int i = 0; i < n; i++) {
            int nx = screenX[i];
            int ny = screenY[i];
            int dot = pinned[i] ? 3 : 1;
            int dotColor = pinned[i] ? 0xFFFFCC44 : 0xFF3322FF;
            gg.fill(nx - dot, ny - dot, nx + dot + 1, ny + dot + 1, dotColor);
        }

        if (animatedPreview && colliderActive) {
            int cx = (int) Math.round(left + colliderX * pxPerMeter);
            int cy = (int) Math.round(top + colliderY * pxPerMeter);
            int rad = (int) Math.round(colliderR * pxPerMeter);
            gg.fill(cx - rad, cy - rad, cx + rad, cy + rad, 0x40FF4444);
            gg.fill(cx - rad, cy - rad, cx + rad, cy - rad + 1, 0xFFAA2222);
            gg.fill(cx - rad, cy + rad - 1, cx + rad, cy + rad, 0xFFAA2222);
            gg.fill(cx - rad, cy - rad, cx - rad + 1, cy + rad, 0xFFAA2222);
            gg.fill(cx + rad - 1, cy - rad, cx + rad, cy + rad, 0xFFAA2222);
        }
    }

    private void rebuildScreenCache(int left, int top) {
        long renderEpoch = ClientTuning.renderEpoch();
        if (!renderDirty && renderEpoch == lastRenderEpoch) {
            return;
        }
        lastRenderEpoch = renderEpoch;
        for (int i = 0; i < n; i++) {
            screenX[i] = (int) Math.round(left + x[i] * pxPerMeter);
            screenY[i] = (int) Math.round(top + y[i] * pxPerMeter);
        }
        renderDirty = false;
    }

    private static void drawThickLine(GuiGraphicsExtractor gg, int x1, int y1, int x2, int y2, int thickness,
            int argb) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            int h = thickness / 2;
            gg.fill(x1 - h, y1 - h, x1 - h + thickness, y1 - h + thickness, argb);
            return;
        }
        int h = thickness / 2;
        if (Math.abs(dy) <= Math.max(1, thickness / 2)) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int y = (y1 + y2) / 2;
            gg.fill(minX, y - h, maxX + 1, y - h + thickness, argb);
            return;
        }
        if (Math.abs(dx) <= Math.max(1, thickness / 2)) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int x = (x1 + x2) / 2;
            gg.fill(x - h, minY, x - h + thickness, maxY + 1, argb);
            return;
        }
        int stride = Math.max(LINE_SAMPLE_STRIDE_PX, thickness);
        int samples = Math.max(1, (steps + stride - 1) / stride);
        double sx = dx / (double) samples;
        double sy = dy / (double) samples;
        double fx = x1, fy = y1;
        for (int i = 0; i <= samples; i++) {
            int ix = (int) Math.round(fx);
            int iy = (int) Math.round(fy);
            gg.fill(ix - h, iy - h, ix - h + thickness, iy - h + thickness, argb);
            fx += sx;
            fy += sy;
        }
    }

    private <T> T value(TuningKey<T> key) {
        String raw = ClientTuning.overrideValue(overrides, key);
        if (raw != null) {
            try {
                T parsed = key.type.parse(raw);
                if (key.type.validate(parsed) || isUncheckedFiniteDouble(key, parsed)) {
                    return parsed;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return defaultMissingOverrides ? key.getDefault() : key.get();
    }

    private static <T> boolean isUncheckedFiniteDouble(TuningKey<T> key, T parsed) {
        if (!ClientTuning.isUncheckedFiniteDoubleKey(key)) {
            return false;
        }
        return parsed instanceof Double d && Double.isFinite(d);
    }
}
