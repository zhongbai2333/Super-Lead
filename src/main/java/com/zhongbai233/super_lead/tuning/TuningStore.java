package com.zhongbai233.super_lead.tuning;

import com.zhongbai233.super_lead.Super_lead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TuningStore {
    private static final Logger LOG = LoggerFactory.getLogger("SuperLeadTuning");
    private static final String FILE_NAME = "super_lead-tuning.properties";

    private TuningStore() {
    }

    static void load() {
        Path file = path();
        if (file == null)
            return;
        if (!Files.exists(file))
            return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            LOG.warn("[{}] failed to read {}: {}", Super_lead.MODID, file, e.toString());
            return;
        }
        for (String key : p.stringPropertyNames()) {
            TuningKey<?> tk = ClientTuning.byId(key);
            if (tk == null)
                continue;
            if (!tk.setLocalFromString(p.getProperty(key))) {
                LOG.warn("[{}] discarded invalid value for {}: {}", Super_lead.MODID, key, p.getProperty(key));
            }
        }
    }

    static void save() {
        Path file = path();
        if (file == null)
            return;
        // Sort for stable diff. Only persist keys that diverge from default.
        TreeMap<String, String> sorted = new TreeMap<>();
        for (TuningKey<?> tk : ClientTuning.allKeys()) {
            if (tk.isLocalOverridden()) {
                sorted.put(tk.id, tk.type.format(castUnchecked(tk.getLocalOrDefault())));
            }
        }
        Properties p = new Properties();
        p.putAll(sorted);
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "Super Lead client tuning (only non-default values are saved)");
            }
        } catch (IOException e) {
            LOG.warn("[{}] failed to write {}: {}", Super_lead.MODID, file, e.toString());
        }
    }

    private static Path path() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
            return null;
        return mc.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    @SuppressWarnings("unchecked")
    private static <T> T castUnchecked(Object o) {
        return (T) o;
    }
}
