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
/**
 * Client-only tuning values used by render, simulation and debug UI paths.
 *
 * <p>
 * These values are intentionally local and mutable so users can tune rope
 * visuals without touching server gameplay config. Accessors should stay cheap;
 * avoid doing file IO from render or physics hot paths.
 */
public final class ClientTuning {
    private static final Map<String, TuningKey<?>> KEYS = new LinkedHashMap<>();
    private static final List<BiConsumer<TuningKey<?>, Object>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final String LEGACY_SLACK_LOOSE_ID = "slack.loose";
    private static final String LEGACY_SLACK_TIGHT_ID = "slack.tight";

    private static volatile boolean loaded;
    private static volatile long renderEpoch;
    private static volatile long physicsEpoch;

    public static final TuningKey<Double> SLACK = registerD(
            "slack", "physics.shape", 0.30D, 0.0D, 1.20D,
            "Slack dial for ropes. 0.0 is taut, 0.1 is very tight, 0.3 is normal, and higher values sag more.");
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
            "damping", "physics.solver", 0.92D, 0.50D, 0.99D,
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
    public static final TuningKey<Double> CONTACT_TOP_PADDING = registerD(
            "contact.topPadding", "physics.contact", 0.20D, 0.0D, 0.50D,
            "Extra top-surface reach for player-vs-rope contact sampling, making the thin rope easier to stand on.");
    public static final TuningKey<Double> CONTACT_TOP_NORMAL_THRESHOLD = registerD(
            "contact.topNormalThreshold", "physics.contact", 0.55D, 0.45D, 0.95D,
            "Normal-Y threshold used to treat a rope contact as a top/support contact.");
    public static final TuningKey<Double> CONTACT_SIDE_ABSORB = registerD(
            "contact.sideAbsorb", "physics.contact", 1.0D, 0.0D, 1.0D,
            "Fraction of inward horizontal normal speed cancelled by side rope contacts; 1.0 fully cancels each tick so the player cannot creep through.");
    public static final TuningKey<Double> CONTACT_SIDE_INTENT_RELEASE = registerD(
            "contact.sideIntentRelease", "physics.contact", 1.0D, 0.0D, 1.0D,
            "How strongly outward movement input disables side absorption so players can leave the rope volume.");
    public static final TuningKey<Double> CONTACT_SIDE_DEADBAND_RATIO = registerD(
            "contact.sideDeadbandRatio", "physics.contact", 0.20D, 0.0D, 0.75D,
            "Fraction of contact radius ignored for side absorption to prevent edge-contact mud-trap damping.");
    public static final TuningKey<Double> CONTACT_PUSHBACK_ENABLE_DEPTH = registerD(
            "contact.pushbackEnableDepth", "physics.contact", 0.001D, 0.0D, 0.50D,
            "Minimum penetration depth in blocks before rope push-back activates. Set to 0 to always push back.");
    public static final TuningKey<Boolean> CONTACT_PARROT_PATHFINDING = registerB(
            "contact.parrotPathfinding", "physics.contact", Boolean.TRUE,
            "Whether parrots can pathfind to ropes in this zone.");
    public static final TuningKey<Boolean> CONTACT_PLAYER_ZIPLINE = registerB(
            "contact.playerZipline", "physics.contact", Boolean.TRUE,
            "Whether players can use ropes in this zone as ziplines.");

    public static final TuningKey<Double> ZIPLINE_SPEED_LIMIT = registerD(
            "zipline.speedLimit", "physics.zipline", 1.35D, -1.0D, 64.0D,
            "Maximum zipline speed in blocks per tick. Use -1 to disable the speed cap.");
    public static final TuningKey<Double> ZIPLINE_REDSTONE_ACCELERATION_MULTIPLIER = registerD(
            "zipline.redstoneAccelerationMultiplier", "physics.zipline", 1.0D, 0.0D, 32.0D,
            "Multiplier applied to powered redstone zipline acceleration.");

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
    public static final TuningKey<Integer> COLOR_PRESSURIZED_BASE = registerC(
            "color.pressurized.base", "render.color", 0x77828D,
            "Mekanism pressurized rope base stripe color.");
    public static final TuningKey<Integer> COLOR_PRESSURIZED_ACCENT = registerC(
            "color.pressurized.accent", "render.color", 0xC5D8E8,
            "Mekanism pressurized rope accent stripe color.");
    public static final TuningKey<Integer> COLOR_THERMAL_BASE = registerC(
            "color.thermal.base", "render.color", 0x9B4F24,
            "Mekanism thermal rope base stripe color.");
    public static final TuningKey<Integer> COLOR_THERMAL_ACCENT = registerC(
            "color.thermal.accent", "render.color", 0xF39A4A,
            "Mekanism thermal rope accent stripe color.");
    public static final TuningKey<Integer> COLOR_AE_NETWORK_BASE = registerC(
            "color.ae_network.base", "render.color", 0x5B4F9F,
            "AE2 ME cable rope base stripe color.");
    public static final TuningKey<Integer> COLOR_AE_NETWORK_ACCENT = registerC(
            "color.ae_network.accent", "render.color", 0xC8A8FF,
            "AE2 ME cable rope accent stripe and channel-band color.");

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

    // ====================================================================================
    // Physics geometry (physics.geom)
    // ====================================================================================
    public static final TuningKey<Double> ROPE_RADIUS_K = registerD(
            "ropeRadius", "physics.geom", 0.045D, 0.01D, 0.20D,
            "Rope collision radius in blocks.");
    public static final TuningKey<Double> TERRAIN_RADIUS_K = registerD(
            "terrainRadius", "physics.geom", 0.085D, 0.02D, 0.30D,
            "Rope-vs-terrain collision radius. Larger than ropeRadius so highlights don't clip.");
    public static final TuningKey<Double> ROPE_REPEL_DISTANCE = registerD(
            "ropeRepelDistance", "physics.geom", 0.06D, 0.01D, 0.30D,
            "Rope-to-rope repel distance.");
    public static final TuningKey<Double> COLLISION_EPS = registerD(
            "collisionEps", "physics.geom", 0.015D, 0.0D, 0.10D,
            "General collision epsilon / slop margin.");
    public static final TuningKey<Double> TERRAIN_PROXIMITY_MARGIN = registerD(
            "terrainProximityMargin", "physics.geom", 0.35D, 0.05D, 1.0D,
            "Extra margin when checking for nearby terrain.");
    public static final TuningKey<Double> SEGMENT_CORNER_PUSH_FACTOR = registerD(
            "segmentCornerPushFactor", "physics.geom", 0.65D, 0.10D, 1.50D,
            "Multiplier on ropeRadius for segment-corner push eps.");
    public static final TuningKey<Double> SEGMENT_TOP_SUPPORT_FACTOR = registerD(
            "segmentTopSupportFactor", "physics.geom", 1.80D, 0.50D, 4.0D,
            "Multiplier on ropeRadius for segment top-support eps.");

    // ====================================================================================
    // Physics solver extras (physics.solverExt)
    // ====================================================================================
    public static final TuningKey<Integer> MIN_SEGMENTS = registerI(
            "minSegments", "physics.solverExt", 4, 2, 16,
            "Minimum segment count for one rope.");
    public static final TuningKey<Integer> MAX_SUBSTEPS = registerI(
            "maxSubsteps", "physics.solverExt", 5, 1, 20,
            "Maximum substeps per game tick.");
    public static final TuningKey<Double> SUBSTEP_SPEED_TIER1 = registerD(
            "substepSpeedTier1", "physics.solverExt", 0.35D, 0.05D, 2.0D,
            "Speed threshold (blocks/tick) for 1 to 2 substeps.");
    public static final TuningKey<Double> SUBSTEP_SPEED_TIER2 = registerD(
            "substepSpeedTier2", "physics.solverExt", 0.75D, 0.10D, 3.0D,
            "Speed threshold (blocks/tick) for 2 to 3 substeps.");
    public static final TuningKey<Double> SUBSTEP_SPEED_TIER3 = registerD(
            "substepSpeedTier3", "physics.solverExt", 1.20D, 0.20D, 4.0D,
            "Speed threshold (blocks/tick) for 3 to max substeps.");
    public static final TuningKey<Double> SUPPORT_DOWN_INV_MASS = registerD(
            "supportDownInvMass", "physics.solverExt", 1.0D, 0.0D, 2.0D,
            "Inverse-mass multiplier for downward push on terrain-supported nodes. <1 = harder to push down.");
    public static final TuningKey<Double> CONTACT_PUSH_GAIN = registerD(
            "contactPushGain", "physics.solverExt", 0.45D, 0.0D, 2.0D,
            "Gain applied to external (server-broadcast) contact pushes.");
    public static final TuningKey<Double> ENTITY_PUSH_GAIN = registerD(
            "contact.entityPushGain", "physics.solverExt", 0.80D, 0.0D, 4.0D,
            "Extra horizontal rope displacement gained from entity approach speed, making high-impulse impacts bend the rope.");
    public static final TuningKey<Double> ROPE_ROPE_PARALLEL_RELAX = registerD(
            "ropeRopeParallelRelax", "physics.solverExt", 0.60D, 0.10D, 1.0D,
            "Under-relaxation for rope-rope corrections in parallel solve.");
    public static final TuningKey<Double> CONTACT_NODE_DAMPING = registerD(
            "contactNodeDamping", "physics.solverExt", 0.50D, 0.0D, 1.0D,
            "Velocity damping applied to nodes that had terrain/rope contact in the current substep.");
    public static final TuningKey<Double> INITIAL_VELOCITY_KICK = registerD(
            "initialVelocityKick", "physics.solverExt", 0.06D, 0.0D, 0.50D,
            "Initial lateral velocity kick to avoid perfectly straight ropes.");

    public static final TuningKey<Boolean> WIND_ENABLED = registerB(
            "wind.enabled", "physics.wind", Boolean.FALSE,
            "Enable rhythmic spatial gusts for ropes using this preset.");
    public static final TuningKey<Double> WIND_PHYSICS_DISTANCE = registerD(
            "wind.physicsDistance", "physics.wind", 32.0D, 0.0D, 96.0D,
            "Maximum nearest-rope distance where wind applies real rope physics. Beyond this, wind is skipped.");
    public static final TuningKey<Double> WIND_STRENGTH = registerD(
            "wind.strength", "physics.wind", 0.035D, 0.0D, 0.45D,
            "Horizontal wind acceleration in blocks per tick squared.");
    public static final TuningKey<Double> WIND_STRENGTH_JITTER = registerD(
            "wind.strengthJitter", "physics.wind", 0.65D, 0.0D, 1.0D,
            "Per-gust strength variation. 0 is steady, 1 allows very soft and strong gusts.");
    public static final TuningKey<Double> WIND_DIRECTION_DEG = registerD(
            "wind.directionDeg", "physics.wind", 35.0D, -360.0D, 360.0D,
            "Dominant wind direction in degrees on the X/Z plane.");
    public static final TuningKey<Double> WIND_DIRECTION_JITTER_DEG = registerD(
            "wind.directionJitterDeg", "physics.wind", 14.0D, 0.0D, 90.0D,
            "Maximum per-gust direction drift around wind.directionDeg.");
    public static final TuningKey<Double> WIND_WAVELENGTH = registerD(
            "wind.waveLength", "physics.wind", 34.0D, 4.0D, 256.0D,
            "World-space distance between gust fronts.");
    public static final TuningKey<Double> WIND_SPEED = registerD(
            "wind.speed", "physics.wind", 0.34D, 0.0D, 4.0D,
            "Gust-front travel speed in blocks per tick.");
    public static final TuningKey<Double> WIND_DUTY = registerD(
            "wind.duty", "physics.wind", 0.42D, 0.05D, 0.95D,
            "Fraction of each wind wave that is active; lower values create longer pauses.");
    public static final TuningKey<Double> WIND_DURATION_JITTER = registerD(
            "wind.durationJitter", "physics.wind", 0.35D, 0.0D, 1.0D,
            "Per-gust active-duration variation around wind.duty.");
    public static final TuningKey<Double> WIND_PAUSE_JITTER = registerD(
            "wind.pauseJitter", "physics.wind", 0.40D, 0.0D, 1.0D,
            "Per-gust pause-duration variation around wind.duty.");
    public static final TuningKey<Double> WIND_RAMP_BIAS = registerD(
            "wind.rampBias", "physics.wind", 0.35D, 0.0D, 1.0D,
            "How often gusts build from small wind to stronger wind before fading.");
    public static final TuningKey<Double> WIND_VERTICAL_LIFT = registerD(
            "wind.verticalLift", "physics.wind", 0.12D, -1.0D, 1.0D,
            "Vertical lift as a fraction of horizontal wind strength.");

    // ====================================================================================
    // Physics settle (physics.settle)
    // ====================================================================================
    public static final TuningKey<Integer> SETTLE_THRESHOLD_TICKS = registerI(
            "settleThresholdTicks", "physics.settle", 4, 1, 20,
            "Number of consecutive low-motion ticks before a rope is considered settled.");
    public static final TuningKey<Double> SETTLE_MOTION_SQR = registerD(
            "settleMotionSqr", "physics.settle", 1.0e-5D, 1.0e-10D, 1.0e-2D,
            "Squared motion threshold for settle detection.");
    public static final TuningKey<Double> ENDPOINT_WAKE_DISTANCE_SQR = registerD(
            "endpointWakeDistanceSqr", "physics.settle", 1.0e-5D, 1.0e-10D, 1.0e-2D,
            "Squared endpoint movement threshold for wake-up.");

    // ====================================================================================
    // Physics sag model (physics.sag)
    // ====================================================================================
    public static final TuningKey<Double> SAG_ARC_APPROX_FACTOR = registerD(
            "sagArcApproxFactor", "physics.sag", 0.375D, 0.10D, 1.0D,
            "Arc-length approximation factor for the parabolic sag model.");
    public static final TuningKey<Double> FULL_SLACK_HORIZONTAL_RATIO = registerD(
            "fullSlackHorizontalRatio", "physics.sag", 0.45D, 0.10D, 1.0D,
            "Horizontal ratio used in effective-slack calculation.");
    public static final TuningKey<Double> STEEP_ANGLE_DEG = registerD(
            "steepAngleDeg", "physics.sag", 80.0D, 60.0D, 89.0D,
            "Angle threshold (degrees from horizontal) above which a rope is considered steep.");

    // ====================================================================================
    // Physics step control (physics.step)
    // ====================================================================================
    public static final TuningKey<Integer> MAX_TICK_DELTA = registerI(
            "maxTickDelta", "physics.step", 2, 1, 10,
            "Maximum tick delta to catch up in one step.");
    public static final TuningKey<Double> TUNNEL_THRESHOLD_SQR = registerD(
            "tunnelThresholdSqr", "physics.step", 0.25D, 0.01D, 2.0D,
            "Squared movement threshold for sweep-and-prune tunnel detection.");

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
        if (LEGACY_SLACK_LOOSE_ID.equals(id) || LEGACY_SLACK_TIGHT_ID.equals(id)) {
            return SLACK;
        }
        return KEYS.get(id);
    }

    public static <T> String overrideValue(Map<String, String> overrides, TuningKey<T> key) {
        if (overrides == null || overrides.isEmpty()) {
            return null;
        }
        String raw = overrides.get(key.id);
        if (raw != null || key != SLACK) {
            return raw;
        }
        raw = overrides.get(LEGACY_SLACK_TIGHT_ID);
        return raw != null ? raw : overrides.get(LEGACY_SLACK_LOOSE_ID);
    }

    public static Map<String, String> normalizeOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>(overrides);
        String slack = overrideValue(normalized, SLACK);
        normalized.remove(LEGACY_SLACK_LOOSE_ID);
        normalized.remove(LEGACY_SLACK_TIGHT_ID);
        if (slack != null) {
            normalized.put(SLACK.id, slack);
        }
        return Map.copyOf(normalized);
    }

    public static boolean isUncheckedFiniteDoubleKey(TuningKey<?> key) {
        return key == SLACK;
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
