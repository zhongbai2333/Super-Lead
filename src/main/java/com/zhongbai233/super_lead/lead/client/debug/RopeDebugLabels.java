package com.zhongbai233.super_lead.lead.client.debug;

import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class RopeDebugLabels {
    private static final int MAX_LABELS = 36;
    private static final int MAX_WIDTH = 180;
    private static final double MAX_LABEL_DISTANCE_SQR = 80.0D * 80.0D;
    private static final int BG = 0xB010141A;
    private static final int TEXT_DYNAMIC = 0xFFE8F7FF;
    private static final int TEXT_MESH = 0xFFC7E8B8;
    private static final Object LOCK = new Object();

    private static volatile boolean enabled;
    private static List<Sample> frame = List.of();
    private static final List<Sample> pending = new ArrayList<>(MAX_LABELS);

    private RopeDebugLabels() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            clear();
        }
    }

    public static boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    public static void beginFrame() {
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            pending.clear();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            pending.clear();
            frame = List.of();
        }
    }

    public static void publishFrame() {
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            pending.sort(Comparator.comparingDouble(Sample::distanceSqr));
            frame = List.copyOf(pending);
        }
    }

    public static void record(UUID id, Vec3 world, int nodes, int renderPressure,
            double physicsMs, String physicsState, boolean chunkMesh, double distanceSqr) {
        if (!enabled || world == null || distanceSqr > MAX_LABEL_DISTANCE_SQR) {
            return;
        }
        String shortId = id == null ? "prev" : id.toString().substring(0, 4);
        String label = String.format(Locale.ROOT, "%s L%d P%s N%d %s",
                shortId, renderPressure, formatPhysics(physicsMs, physicsState), nodes, chunkMesh ? "mesh" : "dyn");
        synchronized (LOCK) {
            if (pending.size() < MAX_LABELS) {
                pending.add(new Sample(world, label, chunkMesh, distanceSqr));
                return;
            }
            int farthest = -1;
            double farthestDist = distanceSqr;
            for (int i = 0; i < pending.size(); i++) {
                double d = pending.get(i).distanceSqr();
                if (d > farthestDist) {
                    farthestDist = d;
                    farthest = i;
                }
            }
            if (farthest >= 0) {
                pending.set(farthest, new Sample(world, label, chunkMesh, distanceSqr));
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        List<Sample> samples = frame;
        if (samples.isEmpty()) {
            return;
        }
        GuiGraphicsExtractor gfx = event.getGuiGraphics();
        Font font = mc.font;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.position();
        var forward = camera.forwardVector();
        double fx = forward.x();
        double fy = forward.y();
        double fz = forward.z();
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0e-5D) {
            return;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;

        double rx = -fz;
        double ry = 0.0D;
        double rz = fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1.0e-5D) {
            rx = 1.0D;
            rz = 0.0D;
            rl = 1.0D;
        }
        rx /= rl;
        rz /= rl;
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1.0e-5D) {
            uy = 1.0D;
            ul = 1.0D;
        }
        ux /= ul;
        uy /= ul;
        uz /= ul;

        int sw = gfx.guiWidth();
        int sh = gfx.guiHeight();
        double scale = (sh * 0.5D) / Math.tan(Math.toRadians(70.0D) * 0.5D);
        for (Sample sample : samples) {
            Vec3 rel = sample.world().subtract(cameraPos);
            double depth = rel.x * fx + rel.y * fy + rel.z * fz;
            if (depth <= 0.4D) {
                continue;
            }
            double side = rel.x * rx + rel.y * ry + rel.z * rz;
            double up = rel.x * ux + rel.y * uy + rel.z * uz;
            int x = (int) Math.round(sw * 0.5D + (side / depth) * scale);
            int y = (int) Math.round(sh * 0.5D - (up / depth) * scale);
            if (x < -MAX_WIDTH || x > sw + MAX_WIDTH || y < -20 || y > sh + 20) {
                continue;
            }
            drawLabel(gfx, font, sample.label(), x, y, sample.chunkMesh());
        }
    }

    private static void drawLabel(GuiGraphicsExtractor gfx, Font font, String text, int x, int y, boolean chunkMesh) {
        Component component = Component.literal(text);
        int width = Math.min(MAX_WIDTH, font.width(component));
        int height = font.lineHeight + 4;
        gfx.fill(x - 3, y - 2, x + width + 3, y + height - 2, BG);
        gfx.text(font, component, x, y, chunkMesh ? TEXT_MESH : TEXT_DYNAMIC, true);
    }

    private static String formatPhysics(double ms, String state) {
        String safeState = shortState(state);
        if (ms <= 0.0D) {
            return safeState;
        }
        return String.format(Locale.ROOT, "%.2fms/%s", ms, safeState);
    }

    private static String shortState(String state) {
        if (state == null || state.isBlank()) {
            return "cache";
        }
        return switch (state) {
            case "20tps" -> "20";
            case "cached" -> "cache";
            default -> state;
        };
    }

    private record Sample(Vec3 world, String label, boolean chunkMesh, double distanceSqr) {
    }
}
