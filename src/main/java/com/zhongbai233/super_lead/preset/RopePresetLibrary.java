package com.zhongbai233.super_lead.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** JSON file backed library of named rope presets, located under <serverConfig>/super_lead/presets/. */
public final class RopePresetLibrary {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");

    private final Path dir;

    private RopePresetLibrary(Path dir) {
        this.dir = dir;
    }

    public static RopePresetLibrary forServer(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve("config").resolve("super_lead").resolve("presets");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot create preset dir {}: {}", dir, e.toString());
        }
        return new RopePresetLibrary(dir);
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        out.add(fn.substring(0, fn.length() - 5));
                    });
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot list presets: {}", e.toString());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public Optional<RopePreset> load(String name) {
        if (!isValidName(name)) return Optional.empty();
        Path p = dir.resolve(name + ".json");
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(s, JsonObject.class);
            if (root == null) return Optional.empty();
            JsonObject ov = root.has("overrides") && root.get("overrides").isJsonObject()
                    ? root.getAsJsonObject("overrides") : new JsonObject();
            Map<String, String> m = new LinkedHashMap<>();
            for (var e : ov.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    m.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return Optional.of(new RopePreset(name, m));
        } catch (IOException | RuntimeException e) {
            LOG.warn("[super_lead] cannot read preset {}: {}", name, e.toString());
            return Optional.empty();
        }
    }

    public boolean save(RopePreset preset) {
        if (!isValidName(preset.name())) return false;
        Path p = dir.resolve(preset.name() + ".json");
        try {
            JsonObject root = new JsonObject();
            root.addProperty("name", preset.name());
            JsonObject ov = new JsonObject();
            for (Map.Entry<String, String> e : preset.overrides().entrySet()) {
                ov.addProperty(e.getKey(), e.getValue());
            }
            root.add("overrides", ov);
            Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[super_lead] cannot save preset {}: {}", preset.name(), e.toString());
            return false;
        }
    }

    public boolean delete(String name) {
        if (!isValidName(name)) return false;
        Path p = dir.resolve(name + ".json");
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot delete preset {}: {}", name, e.toString());
            return false;
        }
    }

}
