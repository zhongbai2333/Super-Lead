package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.PresetEditKey;
import com.zhongbai233.super_lead.preset.PresetDetailsRequest;
import com.zhongbai233.super_lead.preset.PresetDetailsResponse;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.ColorTuningType;
import com.zhongbai233.super_lead.tuning.DoubleTuningType;
import com.zhongbai233.super_lead.tuning.IntTuningType;
import com.zhongbai233.super_lead.tuning.TuningKey;
import com.zhongbai233.super_lead.tuning.gui.PreviewRope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client editor for dimension presets and physics zones.
 *
 * <p>
 * The screen mirrors server-provided preset details, validates text fields
 * locally, then emits compact packet requests for create/update/delete actions.
 * Rendering helpers should remain UI-only; server authority lives in the preset
 * server manager.
 */
public final class PresetEditScreen extends Screen {
    private static final int WIDGET_H = 16;
    private static final int COLOR_WIDGET_H = 34;
    private static final int PADDING = 8;
    private static final int SIDEBAR_W = 100;
    private static final int TAB_BTN_H = 20;
    private static final int TAB_GAP = 2;
    private static final int DESC_GAP = 2;
    private static final int DESC_H = 9;
    private static final int ROW_HEIGHT = WIDGET_H + DESC_GAP + DESC_H;
    private static final int COLOR_ROW_HEIGHT = COLOR_WIDGET_H + DESC_GAP + DESC_H;
    private static final int ROW_GAP = 4;
    private static final int RESET_BTN_W = 14;
    private static final int VALUE_TEXT_W = 76;
    private static final int INPUT_W = 56;
    private static final int SCROLLBAR_W = 4;

    private final Screen parent;
    private final String presetName;
    private final Map<String, String> overrides = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final PreviewRope preview = new PreviewRope();

    private List<String> groups;
    private int activeTab;
    private int bodyTop;
    private int bodyBottom;
    private int contentHeight;
    private int scrollOffset;
    private int sidebarScrollOffset;
    private int sidebarContentHeight;
    private boolean loaded;
    private boolean detailsRequested;
    private boolean pointerDownInBody;
    private boolean initDone;
    private PresetDetailsResponse pendingDetails;

    public PresetEditScreen(Screen parent, String presetName) {
        super(Component.translatable("super_lead.preset.edit.title", presetName));
        this.parent = parent;
        this.presetName = presetName;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        ClientTuning.loadOnce();
        if (groups == null) {
            groups = new ArrayList<>(ClientTuning.groups());
        }
        if (activeTab >= groups.size())
            activeTab = 0;
        preview.setViewport(0, 0, this.width, this.height);
        preview.setOverrides(overrides, true);

        // One-time network setup — must not re-run on tab switches because
        // Screen.rebuildWidgets() → init() would overwrite local edits with
        // stale cached server data.
        if (!initDone) {
            initDone = true;
            PresetClientHandler.setDetailsListener(resp -> {
                if (!resp.name().equals(presetName))
                    return;
                applyDetails(resp, true);
            });
            PresetDetailsResponse cached = PresetClientHandler.lastDetails();
            if (cached != null && cached.name().equals(presetName) && cached.exists()) {
                applyDetails(cached, false);
            }
        }
        if (!detailsRequested) {
            detailsRequested = true;
            ClientPacketDistributor.sendToServer(new PresetDetailsRequest(presetName));
        }

        // --- Top bar: back button ---
        Button back = Button.builder(Component.translatable("super_lead.preset.edit.back"), b -> onClose())
                .bounds(this.width - PADDING - 60, PADDING, 60, 14).build();
        addRenderableWidget(back);

        // --- Left sidebar: vertical tab buttons with scroll ---
        int sidebarTop = PADDING + 20;
        int sidebarBottom = this.height - PADDING;
        int sidebarAreaH = sidebarBottom - sidebarTop;
        sidebarContentHeight = groups.size() * (TAB_BTN_H + TAB_GAP) - TAB_GAP;
        clampSidebarScroll(sidebarAreaH);

        int btnW = SIDEBAR_W - 4;
        for (int i = 0; i < groups.size(); i++) {
            int index = i;
            int y = sidebarTop + i * (TAB_BTN_H + TAB_GAP) - sidebarScrollOffset;
            // Only create buttons visible within the sidebar area
            if (y + TAB_BTN_H < sidebarTop || y > sidebarBottom)
                continue;
            Component label = groupLabel(groups.get(i));
            Button tab = Button.builder(label, b -> {
                this.activeTab = index;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(PADDING, y, btnW, TAB_BTN_H).build();
            tab.active = i != activeTab;
            addRenderableWidget(tab);
        }

        // --- Right body: config rows for active tab ---
        rebuildBody(sidebarTop);
    }

    private void rebuildBody(int startY) {
        rows.clear();
        if (groups == null || groups.isEmpty())
            return;
        String group = groups.get(activeTab);
        bodyTop = startY;
        bodyBottom = this.height - PADDING;
        int contentX = PADDING + SIDEBAR_W;

        int contentW = this.width - contentX - PADDING - SCROLLBAR_W - 2;
        int sliderW = Math.max(100, contentW * 4 / 12);
        int labelW = contentW - sliderW - INPUT_W - VALUE_TEXT_W - RESET_BTN_W - 12;
        if (labelW < 60) {
            labelW = 60;
            sliderW = contentW - labelW - INPUT_W - VALUE_TEXT_W - RESET_BTN_W - 12;
        }
        int sliderX = contentX + labelW + 4;
        int inputX = sliderX + sliderW + 4;
        int resetX = contentX + contentW - RESET_BTN_W;

        int baseY = startY;
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            if (!key.group.equals(group))
                continue;
            int rowH = rowHeightFor(key);
            int y = baseY - scrollOffset;
            AbstractWidget widget = buildWidget(key, sliderX, y, sliderW);
            AbstractWidget input = buildInput(key, inputX, y, INPUT_W);
            AbstractWidget reset = Button.builder(Component.translatable("super_lead.config.reset"), b -> {
                clearOverride(key);
                this.rebuildWidgets();
            }).bounds(resetX, y, RESET_BTN_W, WIDGET_H).build();
            boolean visible = y >= bodyTop && y + rowH <= bodyBottom;
            boolean interactive = loaded && visible;
            widget.visible = visible;
            widget.active = interactive && widget.active;
            if (input != null) {
                input.visible = visible;
                input.active = interactive && input.active;
            }
            reset.visible = visible;
            reset.active = interactive && overrides.containsKey(key.id);
            addRenderableWidget(widget);
            if (input != null) {
                addRenderableWidget(input);
            }
            addRenderableWidget(reset);
            rows.add(new Row(widget, input, reset, key, baseY));
            baseY += rowH + ROW_GAP;
        }
        contentHeight = Math.max(0, baseY - startY);
        clampScroll();
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }

    private void clampSidebarScroll(int visibleH) {
        int maxScroll = Math.max(0, sidebarContentHeight - visibleH);
        sidebarScrollOffset = Mth.clamp(sidebarScrollOffset, 0, maxScroll);
    }

    private static int rowHeightFor(TuningKey<?> key) {
        return key.type instanceof ColorTuningType ? COLOR_ROW_HEIGHT : ROW_HEIGHT;
    }

    private void applyDetails(PresetDetailsResponse response, boolean rebuild) {
        if (rebuild && isInteractingWithBodyControls()) {
            pendingDetails = response;
            return;
        }
        if (!response.exists()) {
            overrides.clear();
            loaded = true;
            preview.setOverrides(overrides, true);
            if (rebuild)
                this.rebuildWidgets();
            return;
        }
        if (loaded && overrides.equals(response.overrides())) {
            return;
        }
        Map<String, String> normalized = ClientTuning.normalizeOverrides(response.overrides());
        if (loaded && overrides.equals(normalized)) {
            return;
        }
        overrides.clear();
        overrides.putAll(normalized);
        loaded = true;
        preview.setOverrides(overrides, true);
        if (rebuild)
            this.rebuildWidgets();
    }

    private boolean isInteractingWithBodyControls() {
        if (pointerDownInBody)
            return true;
        for (Row row : rows) {
            if (row.input instanceof EditBox box && box.isFocused()) {
                return true;
            }
        }
        return false;
    }

    private void applyPendingDetailsIfIdle() {
        if (pendingDetails == null || isInteractingWithBodyControls()) {
            return;
        }
        PresetDetailsResponse response = pendingDetails;
        pendingDetails = null;
        applyDetails(response, true);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int contentX = PADDING + SIDEBAR_W;
        if (mx < contentX && my >= PADDING + 20 && my <= this.height - PADDING) {
            int visibleH = (this.height - PADDING) - (PADDING + 20);
            sidebarScrollOffset -= (int) Math.round(dy * (TAB_BTN_H + TAB_GAP));
            clampSidebarScroll(visibleH);
            this.rebuildWidgets();
            return true;
        }
        if (my >= bodyTop && my <= bodyBottom) {
            scrollOffset -= (int) Math.round(dy * (ROW_HEIGHT + ROW_GAP));
            clampScroll();
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        pointerDownInBody = handled && event.y() >= bodyTop && event.y() <= bodyBottom;
        return handled;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        pointerDownInBody = false;
        boolean handled = super.mouseReleased(event);
        applyPendingDetailsIfIdle();
        return handled;
    }

    @SuppressWarnings("unchecked")
    private AbstractWidget buildWidget(TuningKey<?> key, int x, int y, int width) {
        if (key.type instanceof DoubleTuningType doubleType) {
            TuningKey<Double> dKey = (TuningKey<Double>) key;
            boolean log = doubleType.min() > 0.0D && doubleType.max() / doubleType.min() >= 1000.0D;
            double current = parseDouble(overrides.get(key.id), dKey.getDefault());
            double initial = mapValueToSlider(current, doubleType.min(), doubleType.max(), log);
            return new PresetDoubleSlider(x, y, width, WIDGET_H, doubleType, log, initial, value -> {
                setOverride(key, value);
            });
        }
        if (key.type instanceof IntTuningType intType) {
            TuningKey<Integer> iKey = (TuningKey<Integer>) key;
            int current = parseInt(overrides.get(key.id), iKey.getDefault());
            double initial = (double) (current - intType.min()) / Math.max(1, intType.max() - intType.min());
            return new PresetIntSlider(x, y, width, WIDGET_H, intType, initial, value -> {
                setOverride(key, value);
            });
        }
        if (key.type instanceof ColorTuningType) {
            TuningKey<Integer> colorKey = (TuningKey<Integer>) key;
            int current = parseColor(overrides.get(key.id), colorKey.getDefault(), colorKey);
            return new PresetColorRgbControl(x, y, width, COLOR_WIDGET_H, current, value -> {
                setOverride(key, value);
            });
        }

        TuningKey<Boolean> bKey = (TuningKey<Boolean>) key;
        boolean current = parseBool(overrides.get(key.id), bKey.getDefault());
        final boolean[] state = { current };
        return Button.builder(boolLabel(state[0]), button -> {
            state[0] = !state[0];
            String value = Boolean.toString(state[0]);
            setOverride(key, value);
            button.setMessage(boolLabel(state[0]));
        }).bounds(x, y, width, WIDGET_H).build();
    }

    @SuppressWarnings("unchecked")
    private AbstractWidget buildInput(TuningKey<?> key, int x, int y, int width) {
        if (key.type instanceof ColorTuningType) {
            TuningKey<Integer> colorKey = (TuningKey<Integer>) key;
            EditBox box = new EditBox(this.font, x, y, width, WIDGET_H,
                    Component.translatableWithFallback("super_lead.config.value", "Value"));
            box.setMaxLength(10);
            box.setValue(formatColorInputValue(colorKey));
            box.setResponder(raw -> {
                boolean ok = isValidColorInput(colorKey, raw);
                box.setTextColor(ok ? 0xFFE8E8E8 : 0xFFFF6666);
                if (ok) {
                    setOverride(colorKey, raw);
                }
            });
            return box;
        }
        if (!(key.type instanceof DoubleTuningType)) {
            return null;
        }
        TuningKey<Double> doubleKey = (TuningKey<Double>) key;
        EditBox box = new EditBox(this.font, x, y, width, WIDGET_H,
                Component.translatableWithFallback("super_lead.config.value", "Value"));
        box.setMaxLength(16);
        box.setValue(formatInputValue(doubleKey));
        box.setResponder(raw -> {
            boolean ok = isValidDoubleInput(doubleKey, raw);
            box.setTextColor(ok ? 0xFFE8E8E8 : 0xFFFF6666);
            if (ok) {
                setOverride(doubleKey, raw);
            }
        });
        return box;
    }

    private String formatColorInputValue(TuningKey<Integer> key) {
        String raw = overrides.get(key.id);
        if (raw == null) {
            return key.type.format(key.getDefault());
        }
        try {
            return key.type.format(key.type.parse(raw));
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    private String formatInputValue(TuningKey<Double> key) {
        String raw = overrides.get(key.id);
        if (raw == null) {
            return key.type.format(key.getDefault());
        }
        try {
            return key.type.format(key.type.parse(raw));
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    private static boolean isValidDoubleInput(TuningKey<Double> key, String raw) {
        try {
            Double parsed = key.type.parse(raw);
            return key.type.validate(parsed)
                    || (isUnboundedInputKey(key) && Double.isFinite(parsed));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isValidColorInput(TuningKey<Integer> key, String raw) {
        try {
            Integer parsed = key.type.parse(raw);
            return key.type.validate(parsed);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isUnboundedInputKey(TuningKey<Double> key) {
        return ClientTuning.isUncheckedFiniteDoubleKey(key);
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null)
            return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null)
            return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int parseColor(String raw, int fallback, TuningKey<Integer> key) {
        if (raw == null)
            return fallback;
        try {
            int parsed = key.type.parse(raw.trim());
            return key.type.validate(parsed) ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null)
            return fallback;
        return Boolean.parseBoolean(raw.trim());
    }

    private static Component boolLabel(boolean value) {
        return Component.translatable(value ? "super_lead.config.bool.on" : "super_lead.config.bool.off");
    }

    private static double mapValueToSlider(double value, double min, double max, boolean log) {
        if (log) {
            double lo = Math.log(min);
            double hi = Math.log(max);
            return Mth.clamp((Math.log(Math.max(min, value)) - lo) / (hi - lo), 0.0D, 1.0D);
        }
        return Mth.clamp((value - min) / (max - min), 0.0D, 1.0D);
    }

    private void setOverride(TuningKey<?> key, String value) {
        if (value.equals(overrides.get(key.id))) {
            return;
        }
        overrides.put(key.id, value);
        preview.setOverrides(overrides, true);
        for (Row row : rows) {
            if (row.key == key) {
                row.reset.active = row.reset.visible;
            }
        }
        send(new PresetEditKey(presetName, key.id, value, false));
    }

    private void clearOverride(TuningKey<?> key) {
        if (overrides.remove(key.id) == null) {
            return;
        }
        preview.setOverrides(overrides, true);
        send(new PresetEditKey(presetName, key.id, "", true));
    }

    private void send(PresetEditKey edit) {
        ClientPacketDistributor.sendToServer(edit);
    }

    @Override
    public void onClose() {
        PresetClientHandler.setDetailsListener(null);
        if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        preview.tick();
        applyPendingDetailsIfIdle();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        preview.render(graphics);
        int contentX = PADDING + SIDEBAR_W;

        // Top bar background
        graphics.fill(0, 0, this.width, bodyTop, 0x80000000);
        // Sidebar background
        graphics.fill(0, bodyTop, contentX, this.height, 0x90000000);
        // Content area background
        graphics.fill(contentX, bodyTop, this.width, this.height, 0xA0000000);

        graphics.text(this.font, this.title, PADDING, PADDING, 0xFFFFD24F);

        int sepY = bodyTop - 2;
        graphics.fill(contentX, sepY, this.width - PADDING, sepY + 1, 0xFF40484F);
        // Sidebar separator
        graphics.fill(contentX - 1, bodyTop, contentX, this.height, 0xFF40484F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (!loaded) {
            graphics.centeredText(this.font,
                    Component.translatable("super_lead.preset.edit.loading"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            return;
        }
        if (groups != null && !groups.isEmpty())
            renderRows(graphics);
        renderScrollbar(graphics);
        renderSidebarScrollbar(graphics);
    }

    private void renderRows(GuiGraphicsExtractor graphics) {
        int contentX = PADDING + SIDEBAR_W;
        for (Row row : rows) {
            AbstractWidget w = row.widget;
            if (!w.visible)
                continue;
            TuningKey<?> key = row.key;
            syncColorInput(row, key);
            int rowY = w.getY() + (w.getHeight() - this.font.lineHeight) / 2;
            Component label = Component.translatableWithFallback(
                    "super_lead.tuning." + key.id + ".label", key.id);
            graphics.text(this.font, label, contentX, rowY, 0xFFE8E8E8);

            String displayed = formatRowValue(key);
            boolean overridden = overrides.containsKey(key.id);
            int color = overridden ? 0xFFFFAAFF : 0xFFAAAAAA;
            int valueX = row.input != null
                    ? row.input.getX() - 4 - this.font.width(displayed)
                    : row.reset.getX() - 4 - this.font.width(displayed);
            graphics.text(this.font, Component.literal(displayed), valueX, rowY, color);

            if (key.description != null && !key.description.isEmpty()) {
                int descY = w.getY() + w.getHeight() + DESC_GAP;
                int maxWidth = this.width - contentX - PADDING - SCROLLBAR_W - 2;
                Component desc = Component.translatableWithFallback(
                        "super_lead.tuning." + key.id + ".desc", key.description);
                graphics.text(this.font, Component.literal(truncate(desc.getString(), maxWidth)),
                        contentX, descY, 0xFF8090A0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String formatRowValue(TuningKey<?> key) {
        String raw = overrides.get(key.id);
        if (raw == null) {
            return ((TuningKey<Object>) key).type.format(((TuningKey<Object>) key).getDefault())
                    + " (default)";
        }
        try {
            Object parsed = ((TuningKey<Object>) key).type.parse(raw);
            return ((TuningKey<Object>) key).type.format(parsed);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
        int viewport = bodyBottom - bodyTop;
        if (contentHeight <= viewport || viewport <= 0)
            return;
        int trackX = this.width - PADDING - SCROLLBAR_W;
        graphics.fill(trackX, bodyTop, trackX + SCROLLBAR_W, bodyBottom, 0x60000000);
        int thumbH = Math.max(16, viewport * viewport / contentHeight);
        int maxScroll = contentHeight - viewport;
        if (maxScroll > 0) {
            int thumbY = bodyTop + scrollOffset * (viewport - thumbH) / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF8090A0);
        }
    }

    private void renderSidebarScrollbar(GuiGraphicsExtractor graphics) {
        int sidebarTop = PADDING + 20;
        int sidebarBottom = this.height - PADDING;
        int viewport = sidebarBottom - sidebarTop;
        if (sidebarContentHeight <= viewport || viewport <= 0)
            return;
        int trackX = PADDING + SIDEBAR_W - SCROLLBAR_W;
        graphics.fill(trackX, sidebarTop, trackX + SCROLLBAR_W, sidebarBottom, 0x60000000);
        int thumbH = Math.max(12, viewport * viewport / sidebarContentHeight);
        int maxScroll = sidebarContentHeight - viewport;
        if (maxScroll > 0) {
            int thumbY = sidebarTop + sidebarScrollOffset * (viewport - thumbH) / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF8090A0);
        }
    }

    private String truncate(String value, int maxWidth) {
        if (this.font.width(value) <= maxWidth)
            return value;
        String e = "...";
        int eW = this.font.width(e);
        StringBuilder out = new StringBuilder();
        int w = 0;
        for (int i = 0; i < value.length(); i++) {
            String ch = String.valueOf(value.charAt(i));
            int cw = this.font.width(ch);
            if (w + cw + eW > maxWidth)
                break;
            out.append(ch);
            w += cw;
        }
        return out.append(e).toString();
    }

    private static Component groupLabel(String group) {
        String fallback = switch (group) {
            case "physics.shape" -> "Shape";
            case "physics.solver" -> "Solver";
            case "physics.solverExt" -> "Solver Ext";
            case "physics.geom" -> "Geometry";
            case "physics.settle" -> "Settle";
            case "physics.sag" -> "Sag Model";
            case "physics.step" -> "Step Control";
            case "physics.contact" -> "Contact";
            case "physics.wind" -> "Wind";
            case "physics.zipline" -> "Zipline";
            case "render.mode" -> "Mode";
            case "render.geom" -> "Geometry";
            case "render.color" -> "Color";
            case "render.lod" -> "LOD";
            case "render.attach" -> "Attach";
            case "misc" -> "Misc";
            default -> group;
        };
        return Component.translatableWithFallback("super_lead.config.group." + group, fallback);
    }

    public static void open(Screen parent, String name) {
        Minecraft.getInstance().setScreen(new PresetEditScreen(parent, name));
    }

    private record Row(AbstractWidget widget, AbstractWidget input, AbstractWidget reset, TuningKey<?> key, int baseY) {
    }

    private static final class PresetDoubleSlider extends AbstractSliderButton {
        private final DoubleTuningType type;
        private final boolean log;
        private final java.util.function.Consumer<String> sink;

        PresetDoubleSlider(int x, int y, int w, int h, DoubleTuningType type, boolean log, double initial,
                java.util.function.Consumer<String> sink) {
            super(x, y, w, h, Component.empty(), initial);
            this.type = type;
            this.log = log;
            this.sink = sink;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            double v;
            if (log) {
                double lo = Math.log(type.min());
                double hi = Math.log(type.max());
                v = Math.exp(lo + (hi - lo) * this.value);
            } else {
                v = type.min() + (type.max() - type.min()) * this.value;
            }
            v = Math.round(v * 1.0e6D) / 1.0e6D;
            sink.accept(Double.toString(v));
        }
    }

    private static final class PresetIntSlider extends AbstractSliderButton {
        private final IntTuningType type;
        private final java.util.function.Consumer<String> sink;

        PresetIntSlider(int x, int y, int w, int h, IntTuningType type, double initial,
                java.util.function.Consumer<String> sink) {
            super(x, y, w, h, Component.empty(), initial);
            this.type = type;
            this.sink = sink;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            int span = type.max() - type.min();
            int next = type.min() + (int) Math.round(this.value * span);
            sink.accept(Integer.toString(next));
        }
    }

    private void syncColorInput(Row row, TuningKey<?> key) {
        if (!(key.type instanceof ColorTuningType) || !(row.input instanceof EditBox box) || box.isFocused()) {
            return;
        }
        String value = formatColorDisplay(key);
        if (!value.equals(box.getValue())) {
            box.setValue(value);
            box.setTextColor(0xFFE8E8E8);
        }
    }

    private String formatColorDisplay(TuningKey<?> key) {
        @SuppressWarnings("unchecked")
        TuningKey<Integer> colorKey = (TuningKey<Integer>) key;
        String raw = overrides.get(colorKey.id);
        if (raw == null) {
            return colorKey.type.format(colorKey.getDefault());
        }
        try {
            return colorKey.type.format(colorKey.type.parse(raw));
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    private static final class PresetColorRgbControl extends AbstractWidget {
        private static final int SWATCH_W = 18;
        private static final int BAR_H = 8;
        private static final int BAR_GAP = 3;
        private static final int BAR_PAD_X = 6;
        private static final int HANDLE_W = 3;

        private final java.util.function.Consumer<String> sink;
        private int rgb;
        private int activeChannel = -1;

        PresetColorRgbControl(int x, int y, int width, int height, int initialRgb,
                java.util.function.Consumer<String> sink) {
            super(x, y, width, height, Component.empty());
            this.rgb = initialRgb & 0xFFFFFF;
            this.sink = sink;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                float partialTick) {
            int x = getX();
            int y = getY();
            graphics.fill(x, y, x + SWATCH_W, y + getHeight(), 0xFF101010);
            graphics.fill(x + 1, y + 1, x + SWATCH_W - 1, y + getHeight() - 1, 0xFF000000 | rgb);

            int[] values = { (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF };
            int[] colors = { 0xFFFF4040, 0xFF40FF60, 0xFF508CFF };
            for (int channel = 0; channel < 3; channel++) {
                drawChannel(graphics, channel, values[channel], colors[channel]);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
        }

        private void drawChannel(GuiGraphicsExtractor graphics, int channel, int value, int color) {
            int barX0 = barX0();
            int barX1 = barX1();
            int barY0 = channelY(channel);
            int barY1 = barY0 + BAR_H;
            graphics.fill(barX0, barY0, barX1, barY1, 0xFF202020);
            graphics.fill(barX0 + 1, barY0 + 1, barX1 - 1, barY1 - 1, 0xFF333333);
            int fillX = barX0 + Math.round((barX1 - barX0 - 1) * (value / 255.0F));
            graphics.fill(barX0 + 1, barY0 + 1, fillX, barY1 - 1, color);
            int handleX = Mth.clamp(fillX - HANDLE_W / 2, barX0, barX1 - HANDLE_W);
            graphics.fill(handleX, barY0, handleX + HANDLE_W, barY1, 0xFFE8E8E8);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!this.active || !this.visible || !isMouseOver(event.x(), event.y())) {
                return false;
            }
            this.activeChannel = channelAt(event.y());
            if (this.activeChannel < 0) {
                return false;
            }
            setFocused(true);
            setChannelFromMouse(this.activeChannel, event.x());
            return true;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            if (this.activeChannel < 0 || !this.active || !this.visible) {
                return false;
            }
            setChannelFromMouse(this.activeChannel, event.x());
            return true;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (this.activeChannel < 0) {
                return false;
            }
            this.activeChannel = -1;
            return true;
        }

        private void setChannelFromMouse(int channel, double mouseX) {
            int barX0 = barX0();
            int barX1 = barX1();
            double slider = Mth.clamp((mouseX - barX0) / Math.max(1.0D, barX1 - barX0), 0.0D, 1.0D);
            int channelValue = (int) Math.round(slider * 255.0D);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            if (channel == 0) {
                r = channelValue;
            } else if (channel == 1) {
                g = channelValue;
            } else {
                b = channelValue;
            }
            rgb = (r << 16) | (g << 8) | b;
            sink.accept(String.format(java.util.Locale.ROOT, "#%06X", rgb));
        }

        private int channelAt(double mouseY) {
            for (int channel = 0; channel < 3; channel++) {
                int y0 = channelY(channel);
                if (mouseY >= y0 && mouseY < y0 + BAR_H) {
                    return channel;
                }
            }
            return -1;
        }

        private int barX0() {
            return getX() + SWATCH_W + BAR_PAD_X;
        }

        private int barX1() {
            return getX() + getWidth();
        }

        private int channelY(int channel) {
            return getY() + channel * (BAR_H + BAR_GAP);
        }
    }
}
