package com.zhongbai233.super_lead.preset.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.preset.PresetApplyOverrides;
import com.zhongbai233.super_lead.preset.PresetDetailsResponse;
import com.zhongbai233.super_lead.preset.PresetListResponse;
import com.zhongbai233.super_lead.preset.PresetPromptOpen;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class PresetClientHandler {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile List<String> lastPresetList = List.of();
    private static volatile java.util.function.Consumer<List<String>> listListener;
    private static volatile PresetDetailsResponse lastDetails;
    private static volatile java.util.function.Consumer<PresetDetailsResponse> detailsListener;

    private PresetClientHandler() {}

    public static void onPromptOpen(PresetPromptOpen payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new PresetPromptScreen(mc.screen, payload.presetName(), payload.overrides())));
    }

    public static void onApply(PresetApplyOverrides payload) {
        Minecraft.getInstance().execute(() -> applyOverrides(payload.overrides()));
    }

    public static void onClear() {
        Minecraft.getInstance().execute(PresetClientHandler::clearAllPresets);
    }

    public static void onListResponse(PresetListResponse payload) {
        lastPresetList = List.copyOf(payload.names());
        java.util.function.Consumer<List<String>> l = listListener;
        if (l != null) {
            Minecraft.getInstance().execute(() -> l.accept(lastPresetList));
        }
    }

    public static List<String> lastPresetList() {
        return lastPresetList;
    }

    public static void setListListener(java.util.function.Consumer<List<String>> listener) {
        listListener = listener;
    }

    public static void onDetailsResponse(PresetDetailsResponse payload) {
        lastDetails = payload;
        java.util.function.Consumer<PresetDetailsResponse> l = detailsListener;
        if (l != null) {
            Minecraft.getInstance().execute(() -> l.accept(payload));
        }
    }

    public static PresetDetailsResponse lastDetails() {
        return lastDetails;
    }

    public static void setDetailsListener(java.util.function.Consumer<PresetDetailsResponse> listener) {
        detailsListener = listener;
    }

    /** Apply a set of stringified key->value overrides as preset values, clearing any keys not present. */
    public static void applyOverrides(Map<String, String> overrides) {
        ClientTuning.loadOnce();
        Map<String, String> map = new HashMap<>(overrides);
        List<String> failures = new ArrayList<>();
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            String raw = map.get(key.id);
            if (raw == null) {
                key.clearPreset();
            } else if (!setPresetValue(key, raw)) {
                failures.add(key.id + "='" + raw + "'");
            }
        }
        if (!failures.isEmpty()) {
            LOG.warn("[super_lead] preset apply: {} keys failed to parse: {}", failures.size(), failures);
        }
    }

    private static boolean setPresetValue(TuningKey<?> key, String raw) {
        if (acceptsUncheckedFiniteDouble(key)) {
            return key.setPresetUncheckedFromString(raw);
        }
        return key.setPresetFromString(raw);
    }

    private static boolean acceptsUncheckedFiniteDouble(TuningKey<?> key) {
        return key.id.equals(ClientTuning.SLACK_LOOSE.id)
                || key.id.equals(ClientTuning.SLACK_TIGHT.id);
    }

    public static void clearAllPresets() {
        ClientTuning.loadOnce();
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            key.clearPreset();
        }
    }
}
