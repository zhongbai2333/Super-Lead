package com.zhongbai233.super_lead.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * Loads block properties from:
 * <ol>
 * <li>built-in resource: {@code data/super_lead/block_properties.json}</li>
 * <li>user overrides: {@code config/super_lead_block_properties.json}</li>
 * </ol>
 * User overrides take precedence.
 */
public final class BlockPropertyRegistry {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String BUILTIN_PATH = "/data/super_lead/block_properties.json";
    private static final String USER_FILE = "super_lead_block_properties.json";

    private static volatile Map<String, BlockProperty> properties = Map.of();
    private static volatile boolean loaded;

    private BlockPropertyRegistry() {
    }

    public static void ensureLoaded() {
        if (loaded)
            return;
        loaded = true;
        Map<String, BlockProperty> map = new HashMap<>();
        loadBuiltin(map);
        loadUser(map);
        properties = Map.copyOf(map);
        LOG.info("[super_lead] Block property registry ready ({} entries).", map.size());
    }

    private static void loadBuiltin(Map<String, BlockProperty> target) {
        try (InputStream in = BlockPropertyRegistry.class.getResourceAsStream(BUILTIN_PATH)) {
            if (in == null)
                return;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                parseEntries(r, target);
            }
        } catch (Exception e) {
            LOG.warn("[super_lead] Built-in block properties: {}", e.toString());
        }
    }

    private static void loadUser(Map<String, BlockProperty> target) {
        Path path = userPath();
        if (path == null || !Files.exists(path))
            return;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parseEntries(r, target);
        } catch (Exception e) {
            LOG.warn("[super_lead] User block properties: {}", e.toString());
        }
    }

    private static void parseEntries(Reader reader, Map<String, BlockProperty> target) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String id = e.getKey();
            if (id == null || id.isEmpty())
                continue;
            if (!(e.getValue() instanceof JsonObject obj))
                continue;
            target.put(id, parseEntry(obj));
        }
    }

    static BlockProperty parseEntry(JsonObject obj) {
        BlockProperty.Builder b = BlockProperty.builder();
        if (obj.has("pierced"))
            b.pierced(obj.get("pierced").getAsBoolean());
        if (obj.has("mount_above"))
            b.mountAbove(obj.get("mount_above").getAsBoolean());
        if (obj.has("hang_offset"))
            b.hangOffset(obj.get("hang_offset").getAsDouble());
        if (obj.has("mount_offset"))
            b.mountOffset(obj.get("mount_offset").getAsDouble());
        if (obj.has("hanger_length"))
            b.hangerLength(obj.get("hanger_length").getAsDouble());
        if (obj.has("hanger_spacing"))
            b.hangerSpacing(obj.get("hanger_spacing").getAsDouble());
        if (obj.has("hanger_enabled"))
            b.hangerEnabled(obj.get("hanger_enabled").getAsBoolean());
        if (obj.has("signal_bridge"))
            b.signalBridge(obj.get("signal_bridge").getAsBoolean());
        if (obj.has("model_mode"))
            b.modelMode(obj.get("model_mode").getAsString());
        if (obj.has("model_state") && obj.get("model_state").isJsonObject()) {
            TreeMap<String, String> state = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("model_state").entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                    state.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            b.modelState(state);
        }
        return b.build();
    }

    public static BlockProperty get(Block block) {
        ensureLoaded();
        String key = BuiltInRegistries.BLOCK.getKey(block).toString();
        BlockProperty entry = properties.getOrDefault(key, BlockProperty.EMPTY);
        return entry.merge(BlockProperty.DEFAULTS);
    }

    public static boolean signalBridgeEnabled(Block block) {
        return get(block).bridgeOn();
    }

    /** Returns a mutable snapshot of all entries for editing. */
    public static Map<String, BlockProperty> snapshot() {
        ensureLoaded();
        return new TreeMap<>(properties);
    }

    /** Save entries to the user config file. */
    public static void save(Map<String, BlockProperty> entries) {
        Path path = userPath();
        if (path == null)
            return;
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, BlockProperty> e : new TreeMap<>(entries).entrySet()) {
                BlockProperty bp = e.getValue();
                if (bp == null || bp == BlockProperty.EMPTY)
                    continue;
                JsonObject obj = new JsonObject();
                if (bp.attachPierced() != null)
                    obj.addProperty("pierced", bp.attachPierced());
                if (bp.attachMountAbove() != null)
                    obj.addProperty("mount_above", bp.attachMountAbove());
                if (bp.attachHangOffset() != null)
                    obj.addProperty("hang_offset", bp.attachHangOffset());
                if (bp.attachMountOffset() != null)
                    obj.addProperty("mount_offset", bp.attachMountOffset());
                if (bp.attachHangerLength() != null)
                    obj.addProperty("hanger_length", bp.attachHangerLength());
                if (bp.attachHangerSpacing() != null)
                    obj.addProperty("hanger_spacing", bp.attachHangerSpacing());
                if (bp.attachHangerEnabled() != null)
                    obj.addProperty("hanger_enabled", bp.attachHangerEnabled());
                if (bp.signalBridgeEnabled() != null)
                    obj.addProperty("signal_bridge", bp.signalBridgeEnabled());
                if (bp.attachModelMode() != null)
                    obj.addProperty("model_mode", bp.attachModelMode());
                if (bp.attachModelState() != null && !bp.attachModelState().isEmpty()) {
                    JsonObject state = new JsonObject();
                    for (Map.Entry<String, String> stateEntry : new TreeMap<>(bp.attachModelState()).entrySet()) {
                        state.addProperty(stateEntry.getKey(), stateEntry.getValue());
                    }
                    obj.add("model_state", state);
                }
                root.add(e.getKey(), obj);
            }
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
            properties = Map.copyOf(entries);
            LOG.info("[super_lead] Saved {} block property entries.", entries.size());
        } catch (Exception e) {
            LOG.warn("[super_lead] Failed to save block properties: {}", e.toString());
        }
    }

    private static Path userPath() {
        return Path.of("config").resolve(USER_FILE);
    }
}
