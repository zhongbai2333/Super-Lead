package com.zhongbai233.super_lead.tuning;

import com.zhongbai233.super_lead.Super_lead;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class ClientTuning {
    private static final Map<String, TuningKey<?>> KEYS = new LinkedHashMap<>();
    private static final List<BiConsumer<TuningKey<?>, Object>> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean loaded;
    private static volatile long renderEpoch;
    private static volatile long physicsEpoch;

    public static final TuningKey<Double> SLACK_LOOSE = registerD(
            "slack.loose", "physics.shape", 1.010D, 1.000D, 1.05D,
            "Slack multiplier for normal ropes. Higher values sag more.");
    public static final TuningKey<Double> SLACK_TIGHT = registerD(
            "slack.tight", "physics.shape", 1.005D, 1.000D, 1.03D,
            "Slack multiplier for loaded or tight ropes.");
    public static final TuningKey<Double> SEGMENT_LENGTH = registerD(
            "segment.length", "physics.shape", 0.30D, 0.15D, 0.60D,
            "Target segment length for newly created rope simulations.");
    public static final TuningKey<Integer> SEGMENT_MAX = registerI(
            "segment.max", "physics.shape", 32, 8, 64,
            "Maximum segment count for one rope.");

    public static final TuningKey<Double> GRAVITY = registerD(
            "gravity", "physics.solver", -0.065D, -0.20D, 0.0D,
            "Per-tick gravity acceleration in blocks per tick squared.");
    public static final TuningKey<Double> DAMPING = registerD(
            "damping", "physics.solver", 0.80D, 0.50D, 0.99D,
            "Velocity damping. 1.0 means no damping.");
    public static final TuningKey<Integer> ITER_AIR = registerI(
            "iterations.air", "physics.solver", 3, 1, 16,
            "Constraint iterations for rope segments in open air.");
    public static final TuningKey<Integer> ITER_CONTACT = registerI(
            "iterations.contact", "physics.solver", 8, 1, 32,
            "Constraint iterations when terrain, entities, or fields are nearby.");
    public static final TuningKey<Integer> ITER_ROPE = registerI(
            "iterations.rope", "physics.solver", 4, 1, 16,
            "Constraint iterations for rope-to-rope contacts.");
    public static final TuningKey<Double> COMPLIANCE = registerD(
            "compliance", "physics.solver", 1.0e-7D, 1.0e-9D, 1.0e-4D,
            "XPBD distance-constraint compliance. Lower values are stiffer.");

    public static final TuningKey<Boolean> CONTACT_PUSHBACK = registerB(
            "contact.pushback", "physics.contact", Boolean.TRUE,
            "Enable server-side player push-back when walking into synced physics-zone ropes.");
    public static final TuningKey<Double> CONTACT_RADIUS = registerD(
            "contact.radius", "physics.contact", 0.18D, 0.02D, 0.60D,
            "Soft gameplay radius around the rope used for player push-back contacts.");
    public static final TuningKey<Double> CONTACT_SPRING = registerD(
            "contact.spring", "physics.contact", 0.30D, 0.0D, 1.0D,
            "Depth-based push-back strength. Higher values resist overlap more strongly.");
    public static final TuningKey<Double> CONTACT_VELOCITY_DAMPING = registerD(
            "contact.velocityDamping", "physics.contact", 0.55D, 0.0D, 2.0D,
            "Approach-speed damping when the player moves into the rope.");
    public static final TuningKey<Double> CONTACT_MAX_RECOIL_PER_TICK = registerD(
            "contact.maxRecoilPerTick", "physics.contact", 0.20D, 0.0D, 0.50D,
            "Maximum 3D contact velocity added to the player per server tick before support scaling.");

    public static final TuningKey<Boolean> MODE_PHYSICS = registerB(
            "mode.physics", "render.mode", Boolean.TRUE,
            "Enable rope physics. Disable to keep ropes in static sag curves.");
    public static final TuningKey<Boolean> MODE_RENDER3D = registerB(
            "mode.render3d", "render.mode", Boolean.TRUE,
            "Render ropes as 3D prisms. Disable for camera-facing ribbons.");
    public static final TuningKey<Boolean> MODE_CHUNK_MESH_STATIC_ROPES = registerB(
            "mode.chunkMeshStaticRopes", "render.mode", Boolean.TRUE,
            "Bake static ropes into chunk meshes when physics is disabled.");

    public static final TuningKey<Double> THICKNESS_HALF = registerD(
            "thickness.half", "render.geom", 1.0D / 32.0D, 0.005D, 0.10D,
            "Half thickness of the rendered rope in blocks.");
    public static final TuningKey<Double> RIBBON_WIDTH_FACTOR = registerD(
            "ribbon.widthFactor", "render.geom", 1.45D, 0.5D, 3.0D,
            "Ribbon width multiplier relative to rope half thickness.");

    public static final TuningKey<Integer> COLOR_NORMAL_BASE = registerC(
            "color.normal.base", "render.color", 0x563B22,
            "Normal rope base stripe color. Accepts #RRGGBB, 0xRRGGBB, or decimal RGB.");
    public static final TuningKey<Integer> COLOR_NORMAL_ACCENT = registerC(
            "color.normal.accent", "render.color", 0x8E6539,
            "Normal rope accent stripe color. Accepts #RRGGBB, 0xRRGGBB, or decimal RGB.");
    public static final TuningKey<Integer> COLOR_REDSTONE_BASE = registerC(
            "color.redstone.base", "render.color", 0x540000,
            "Redstone rope base stripe color. Powered ropes brighten this palette.");
    public static final TuningKey<Integer> COLOR_REDSTONE_ACCENT = registerC(
            "color.redstone.accent", "render.color", 0xA81614,
            "Redstone rope accent stripe color. Powered ropes brighten this palette.");
    public static final TuningKey<Integer> COLOR_ENERGY_BASE = registerC(
            "color.energy.base", "render.color", 0x805416,
            "Energy rope base stripe color. Tier bands use the accent color.");
    public static final TuningKey<Integer> COLOR_ENERGY_ACCENT = registerC(
            "color.energy.accent", "render.color", 0xE49E36,
            "Energy rope accent stripe and tier-band color.");
    public static final TuningKey<Integer> COLOR_ITEM_BASE = registerC(
            "color.item.base", "render.color", 0x6E82A5,
            "Item rope base stripe color.");
    public static final TuningKey<Integer> COLOR_ITEM_ACCENT = registerC(
            "color.item.accent", "render.color", 0xAFC8E6,
            "Item rope accent stripe color.");
    public static final TuningKey<Integer> COLOR_FLUID_BASE = registerC(
            "color.fluid.base", "render.color", 0x329196,
            "Fluid rope base stripe color.");
    public static final TuningKey<Integer> COLOR_FLUID_ACCENT = registerC(
            "color.fluid.accent", "render.color", 0x5ADCDC,
            "Fluid rope accent stripe color.");

    public static final TuningKey<Double> LOD_RIBBON_DISTANCE = registerD(
            "lod.ribbonDistance", "render.lod", 48.0D, 8.0D, 256.0D,
            "Distance where ropes switch from prism geometry to ribbons.");
    public static final TuningKey<Double> LOD_STRIDE2_DISTANCE = registerD(
            "lod.stride2Distance", "render.lod", 8.0D, 4.0D, 64.0D,
            "Distance where every two physics segments merge for rendering.");
    public static final TuningKey<Double> LOD_STRIDE4_DISTANCE = registerD(
            "lod.stride4Distance", "render.lod", 20.0D, 4.0D, 96.0D,
            "Distance where every four physics segments merge for rendering.");

    public static final TuningKey<Double> ATTACH_ITEM_SCALE = registerD(
            "attach.itemScale", "render.attach", 0.85D, 0.30D, 2.0D,
            "Render scale for item-style rope attachments.");
    public static final TuningKey<Double> ATTACH_BLOCK_SCALE = registerD(
            "attach.blockScale", "render.attach", 0.95D, 0.30D, 2.0D,
            "Render scale for block-style rope attachments.");
    public static final TuningKey<Double> ATTACH_ITEM_DROP = registerD(
            "attach.itemDrop", "render.attach", 0.42D, 0.0D, 1.0D,
            "Vertical drop from the rope for item-style attachments.");
    public static final TuningKey<Double> ATTACH_BLOCK_DROP = registerD(
            "attach.blockDrop", "render.attach", 0.50D, 0.0D, 1.0D,
            "Vertical drop from the rope for block-style attachments.");

    public static final TuningKey<Double> PICK_ATTACH_RADIUS = registerD(
            "pick.attachRadius", "misc", 0.50D, 0.10D, 1.50D,
            "Client pick radius for placing attachments on a rope.");
    public static final TuningKey<Double> RENDER_MAX_DISTANCE = registerD(
            "render.maxDistance", "misc", 96.0D, 16.0D, 256.0D,
            "Distance beyond which ropes do not render or simulate.");

    private ClientTuning() {
    }

    private static TuningKey<Double> registerD(String id, String group,
            double defaultValue, double min, double max, String description) {
        TuningKey<Double> key = new TuningKey<>(
                id, group, TuningType.doubleRange(min, max), defaultValue, description);
        KEYS.put(id, key);
        return key;
    }

    private static TuningKey<Integer> registerI(String id, String group,
            int defaultValue, int min, int max, String description) {
        TuningKey<Integer> key = new TuningKey<>(
                id, group, TuningType.intRange(min, max), defaultValue, description);
        KEYS.put(id, key);
        return key;
    }

    private static TuningKey<Integer> registerC(String id, String group,
            int defaultValue, String description) {
        TuningKey<Integer> key = new TuningKey<>(
                id, group, TuningType.colorRgb(), defaultValue, description);
        KEYS.put(id, key);
        return key;
    }

    private static TuningKey<Boolean> registerB(String id, String group,
            boolean defaultValue, String description) {
        TuningKey<Boolean> key = new TuningKey<>(
                id, group, TuningType.bool(), defaultValue, description);
        KEYS.put(id, key);
        return key;
    }

    public static TuningKey<?> byId(String id) {
        return KEYS.get(id);
    }

    public static Collection<TuningKey<?>> allKeys() {
        return KEYS.values();
    }

    public static List<String> groups() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (TuningKey<?> key : KEYS.values()) {
            out.add(key.group);
        }
        return List.copyOf(out);
    }

    public static long renderEpoch() {
        return renderEpoch;
    }

    public static long physicsEpoch() {
        return physicsEpoch;
    }

    static void fire(TuningKey<?> key, Object oldValue, Object newValue) {
        if (key.group.startsWith("physics")) {
            physicsEpoch++;
        }
        if (key.group.startsWith("render") || key.id.startsWith("attach.") || key.group.equals("misc")) {
            renderEpoch++;
        }
        for (BiConsumer<TuningKey<?>, Object> listener : LISTENERS) {
            try {
                listener.accept(key, newValue);
            } catch (RuntimeException ignored) {
            }
        }
        if (loaded) {
            TuningStore.save();
        }
    }

    public static void addListener(BiConsumer<TuningKey<?>, Object> listener) {
        LISTENERS.add(listener);
    }

    public static synchronized void loadOnce() {
        if (loaded) {
            return;
        }
        TuningStore.load();
        loaded = true;
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        loadOnce();
    }
}
