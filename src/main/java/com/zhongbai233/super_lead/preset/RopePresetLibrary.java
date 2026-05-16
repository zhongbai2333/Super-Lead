package com.zhongbai233.super_lead.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * JSON-file backed library of named rope presets for the currently loaded save.
 *
 * <p>
 * Active presets live under the world directory so preset binders and zone
 * rules
 * cannot accidentally bleed into another save. The old global config path is
 * still used as an explicit import/export exchange folder.
 */
public final class RopePresetLibrary {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");

    private final Path dir;

    private RopePresetLibrary(Path dir) {
        this.dir = dir;
    }

    public static RopePresetLibrary forServer(MinecraftServer server) {
        Path dir = worldDirectory(server);
        ensureDirectory(dir, "world preset");
        return new RopePresetLibrary(dir);
    }

    public static Path worldDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig")
                .resolve("super_lead")
                .resolve("presets");
    }

    public static Path exchangeDirectory(MinecraftServer server) {
        return server.getServerDirectory()
                .resolve("config")
                .resolve("super_lead")
                .resolve("presets");
    }

    public Path directory() {
        return dir;
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return out;
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
        if (!isValidName(name))
            return Optional.empty();
        Path p = dir.resolve(name + ".json");
        if (!Files.isRegularFile(p))
            return Optional.empty();
        return loadFromPath(name, p);
    }

    private Optional<RopePreset> loadFromPath(String name, Path p) {
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(s, JsonObject.class);
            if (root == null)
                return Optional.empty();
            JsonObject ov = root.has("overrides") && root.get("overrides").isJsonObject()
                    ? root.getAsJsonObject("overrides")
                    : new JsonObject();
            UUID owner = null;
            if (root.has("owner") && root.get("owner").isJsonPrimitive()) {
                try {
                    String rawOwner = root.get("owner").getAsString();
                    if (rawOwner != null && !rawOwner.isBlank()) {
                        owner = UUID.fromString(rawOwner);
                    }
                } catch (IllegalArgumentException ignored) {
                    owner = null;
                }
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (var e : ov.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    m.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return Optional.of(new RopePreset(name, m, owner));
        } catch (IOException | RuntimeException e) {
            LOG.warn("[super_lead] cannot read preset {} from {}: {}", name, p, e.toString());
            return Optional.empty();
        }
    }

    public boolean save(RopePreset preset) {
        if (!isValidName(preset.name()))
            return false;
        Path p = dir.resolve(preset.name() + ".json");
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            root.addProperty("name", preset.name());
            if (preset.owner() != null) {
                root.addProperty("owner", preset.owner().toString());
            }
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
        if (!isValidName(name))
            return false;
        Path p = dir.resolve(name + ".json");
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot delete preset {}: {}", name, e.toString());
            return false;
        }
    }

    public int exportAllTo(Path targetDir) {
        if (!ensureDirectory(targetDir, "preset exchange"))
            return -1;
        int copied = 0;
        for (String name : list()) {
            Path source = dir.resolve(name + ".json");
            if (!Files.isRegularFile(source))
                continue;
            try {
                Files.copy(source, targetDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                LOG.warn("[super_lead] cannot export preset {} to {}: {}", name, targetDir, e.toString());
            }
        }
        return copied;
    }

    public int importAllFrom(Path sourceDir) {
        if (!Files.isDirectory(sourceDir))
            return 0;
        int imported = 0;
        try (Stream<Path> stream = Files.list(sourceDir)) {
            for (Path p : stream.filter(RopePresetLibrary::isPresetJsonFile).toList()) {
                String name = presetNameFromFile(p);
                if (!isValidName(name)) {
                    LOG.warn("[super_lead] skip invalid preset filename during import: {}", p.getFileName());
                    continue;
                }
                Optional<RopePreset> preset = loadFromPath(name, p);
                if (preset.isPresent() && save(preset.get())) {
                    imported++;
                }
            }
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot import presets from {}: {}", sourceDir, e.toString());
            return -1;
        }
        return imported;
    }

    private static boolean isPresetJsonFile(Path path) {
        return path != null
                && Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static String presetNameFromFile(Path path) {
        String fn = path.getFileName().toString();
        return fn.substring(0, fn.length() - 5);
    }

    private static boolean ensureDirectory(Path dir, String role) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            LOG.warn("[super_lead] cannot create {} dir {}: {}", role, dir, e.toString());
            return false;
        }
    }

}
