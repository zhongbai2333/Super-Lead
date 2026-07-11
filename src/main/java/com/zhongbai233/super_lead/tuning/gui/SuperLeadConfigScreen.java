package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.ColorTuningType;
import com.zhongbai233.super_lead.tuning.DoubleTuningType;
import com.zhongbai233.super_lead.tuning.IntTuningType;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/**
 * Client-side tuning screen for local visual/simulation preferences.
 *
 * <p>
 * This is separate from the OP-only server config editor: changes here are
 * written through {@link com.zhongbai233.super_lead.tuning.ClientTuning} and
 * are
 * meant for previewing and adjusting client presentation only.
 */
public final class SuperLeadConfigScreen extends Screen {
    static final int WIDGET_H = 16;

    private static final int PADDING = 8;
    private static final int SIDEBAR_W = 100;
    private static final int TAB_BTN_H = 20;
    private static final int TAB_GAP = 2;
    private static final int DESC_GAP = 2;
    private static final int DESC_H = 9;
    private static final int COLOR_WIDGET_H = 34;
    private static final int ROW_HEIGHT = WIDGET_H + DESC_GAP + DESC_H;
    private static final int COLOR_ROW_HEIGHT = COLOR_WIDGET_H + DESC_GAP + DESC_H;
    private static final int ROW_GAP = 4;
    private static final int RESET_BTN_W = 14;
    private static final int VALUE_TEXT_W = 76;
    private static final int INPUT_W = 56;
    private static final int SCROLLBAR_W = 4;
    private static final int SEARCH_W = 150;
    private static boolean lowPowerPreview;

    private final PreviewRope preview = new PreviewRope();
    private final List<ConfigRow> rows = new ArrayList<>();

    private List<String> groups;
    private int activeTab;
    private int bodyTop;
    private int bodyBottom;
    private int contentHeight;
    private int scrollOffset;
    private String searchText = "";
    private EditBox searchBox;
    private boolean restoreSearchFocus;

    public SuperLeadConfigScreen() {
        super(Component.translatable("super_lead.config.title"));
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
            groups.remove("physics.contact");
            groups.remove("physics.wind");
        }
        if (activeTab >= groups.size()) {
            activeTab = 0;
        }

        preview.setViewport(0, 0, this.width, this.height);
        preview.setAnimatedPreview(!lowPowerPreview);

        // --- Top bar: title + action buttons ---
        int topBarY = PADDING;

        Button close = Button.builder(Component.translatable("super_lead.config.close"), button -> onClose())
                .bounds(this.width - PADDING - 60, topBarY, 60, 14)
                .build();
        addRenderableWidget(close);

        Button opPresets = Button.builder(
                Component.translatable("super_lead.server_config.button"),
                button -> com.zhongbai233.super_lead.preset.client.ServerConfigScreen.open(this))
                .bounds(this.width - PADDING - 60 - 86, topBarY, 84, 14)
                .build();
        addRenderableWidget(opPresets);

        Button blockProps = Button.builder(
                Component.literal("Block Props"),
                button -> com.zhongbai233.super_lead.data.BlockPropertyEditScreen.open(this))
                .bounds(this.width - PADDING - 60 - 86 - 90, topBarY, 86, 14)
                .build();
        addRenderableWidget(blockProps);

        Button previewMode = Button.builder(previewModeLabel(), button -> {
            lowPowerPreview = !lowPowerPreview;
            preview.setAnimatedPreview(!lowPowerPreview);
            button.setMessage(previewModeLabel());
        }).bounds(this.width - PADDING - 60 - 86 - 90 - 92, topBarY, 88, 14).build();
        addRenderableWidget(previewMode);

        searchBox = new EditBox(this.font, PADDING + SIDEBAR_W, topBarY,
                Math.min(SEARCH_W, Math.max(80, this.width / 4)), 14,
                Component.translatable("super_lead.config.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("super_lead.config.search"));
        searchBox.setValue(searchText);
        searchBox.setResponder(value -> {
            String next = value == null ? "" : value;
            if (next.equals(searchText)) {
                return;
            }
            searchText = next;
            scrollOffset = 0;
            restoreSearchFocus = true;
            rebuildWidgets();
        });
        addRenderableWidget(searchBox);
        if (restoreSearchFocus) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            restoreSearchFocus = false;
        }

        // --- Left sidebar: vertical tab buttons ---
        int sidebarTop = topBarY + 20;
        int tabY = sidebarTop;
        for (int i = 0; i < groups.size(); i++) {
            int index = i;
            Component label = groupLabel(groups.get(i));
            Button tab = Button.builder(label, button -> {
                this.activeTab = index;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(PADDING, tabY, SIDEBAR_W - 4, TAB_BTN_H).build();
            tab.active = i != activeTab;
            addRenderableWidget(tab);
            tabY += TAB_BTN_H + TAB_GAP;
        }

        // --- Right body: config rows for active tab ---
        rebuildBody(sidebarTop);
    }

    private void rebuildBody(int startY) {
        rows.clear();
        if (groups == null || groups.isEmpty()) {
            return;
        }

        String group = groups.get(activeTab);
        String filter = normalizedSearchText();
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
            if (filter.isEmpty() ? !key.group.equals(group) : !matchesSearch(key, filter)) {
                continue;
            }
            int rowHeight = rowHeightFor(key);
            int y = baseY - scrollOffset;
            AbstractWidget widget = buildWidget(key, sliderX, y, sliderW);
            AbstractWidget input = buildInput(key, inputX, y, INPUT_W);
            AbstractWidget reset = Button.builder(Component.translatable("super_lead.config.reset"), button -> {
                key.clearLocal();
                this.rebuildWidgets();
            }).bounds(resetX, y, RESET_BTN_W, WIDGET_H).build();
            boolean visible = y >= bodyTop && y + rowHeight <= bodyBottom;
            widget.visible = visible;
            widget.active = visible && widget.active;
            if (input != null) {
                input.visible = visible;
                input.active = visible && input.active;
            }
            reset.visible = visible;
            reset.active = visible;
            addRenderableWidget(widget);
            if (input != null) {
                addRenderableWidget(input);
            }
            addRenderableWidget(reset);
            rows.add(new ConfigRow(widget, input, reset, key, baseY, rowHeight));
            baseY += rowHeight + ROW_GAP;
        }
        contentHeight = Math.max(0, baseY - startY);
        clampScroll();
    }

    private String normalizedSearchText() {
        return searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesSearch(TuningKey<?> key, String filter) {
        return key.id.toLowerCase(Locale.ROOT).contains(filter)
                || key.group.toLowerCase(Locale.ROOT).contains(filter)
                || localizedKeyLabel(key).getString().toLowerCase(Locale.ROOT).contains(filter)
                || localizedKeyDescription(key).getString().toLowerCase(Locale.ROOT).contains(filter)
                || groupLabel(key.group).getString().toLowerCase(Locale.ROOT).contains(filter);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (my >= bodyTop && my <= bodyBottom) {
            scrollOffset -= (int) Math.round(dy * (ROW_HEIGHT + ROW_GAP));
            clampScroll();
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @SuppressWarnings("unchecked")
    private AbstractWidget buildWidget(TuningKey<?> key, int x, int y, int width) {
        if (key.type instanceof DoubleTuningType doubleType) {
            TuningKey<Double> doubleKey = (TuningKey<Double>) key;
            boolean log = doubleType.min() > 0.0D
                    && doubleType.max() / doubleType.min() >= 1000.0D;
            double initial = mapValueToSlider(doubleKey.get(), doubleType.min(), doubleType.max(), log);
            return new DoubleTuningSlider(x, y, width, WIDGET_H, doubleKey, doubleType, log, initial);
        }
        if (key.type instanceof IntTuningType intType) {
            TuningKey<Integer> intKey = (TuningKey<Integer>) key;
            double initial = (double) (intKey.get() - intType.min())
                    / Math.max(1, intType.max() - intType.min());
            return new IntTuningSlider(x, y, width, WIDGET_H, intKey, intType, initial);
        }
        if (key.type instanceof ColorTuningType) {
            TuningKey<Integer> colorKey = (TuningKey<Integer>) key;
            return new ColorRgbControl(x, y, width, COLOR_WIDGET_H, colorKey);
        }

        TuningKey<Boolean> boolKey = (TuningKey<Boolean>) key;
        return Button.builder(boolLabel(boolKey.get()), button -> {
            boolean next = !boolKey.get();
            boolKey.setLocalFromString(Boolean.toString(next));
            button.setMessage(boolLabel(next));
        }).bounds(x, y, width, WIDGET_H).build();
    }

    @SuppressWarnings("unchecked")
    private AbstractWidget buildInput(TuningKey<?> key, int x, int y, int width) {
        if (key.type instanceof ColorTuningType) {
            TuningKey<Integer> colorKey = (TuningKey<Integer>) key;
            EditBox box = new EditBox(this.font, x, y, width, WIDGET_H,
                    Component.translatableWithFallback("super_lead.config.value", "Value"));
            box.setMaxLength(10);
            box.setValue(colorKey.formatEffective());
            box.setResponder(raw -> {
                boolean ok = colorKey.setLocalFromString(raw);
                box.setTextColor(ok ? 0xFFE8E8E8 : 0xFFFF6666);
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
        box.setValue(doubleKey.formatEffective());
        box.setResponder(raw -> {
            boolean ok = isUnboundedInputKey(doubleKey)
                    ? doubleKey.setLocalUncheckedFromString(raw)
                    : doubleKey.setLocalFromString(raw);
            box.setTextColor(ok ? 0xFFE8E8E8 : 0xFFFF6666);
        });
        return box;
    }

    private static boolean isUnboundedInputKey(TuningKey<Double> key) {
        return ClientTuning.isUncheckedFiniteDoubleKey(key);
    }

    private static Component boolLabel(boolean value) {
        return Component.translatable(value ? "super_lead.config.bool.on" : "super_lead.config.bool.off");
    }

    private static Component previewModeLabel() {
        return Component.translatable(lowPowerPreview
                ? "super_lead.config.preview.static"
                : "super_lead.config.preview.animated");
    }

    private static double mapValueToSlider(double value, double min, double max, boolean log) {
        if (log) {
            double lo = Math.log(min);
            double hi = Math.log(max);
            return Mth.clamp((Math.log(Math.max(min, value)) - lo) / (hi - lo), 0.0D, 1.0D);
        }
        return Mth.clamp((value - min) / (max - min), 0.0D, 1.0D);
    }

    @Override
    public void tick() {
        super.tick();
        preview.tick();
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

        graphics.text(this.font, this.title, PADDING, PADDING, 0xFFFFFFAA);

        int sepY = bodyTop - 2;
        graphics.fill(contentX, sepY, this.width - PADDING, sepY + 1, 0xFF40484F);
        // Sidebar separator
        graphics.fill(contentX - 1, bodyTop, contentX, this.height, 0xFF40484F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (groups != null && !groups.isEmpty()) {
            renderRows(graphics);
        }
        renderScrollbar(graphics);
    }

    private void renderRows(GuiGraphicsExtractor graphics) {
        int contentX = PADDING + SIDEBAR_W;
        for (ConfigRow row : rows) {
            AbstractWidget widget = row.widget();
            if (!widget.visible) {
                continue;
            }

            TuningKey<?> key = row.key();
            syncColorInput(row, key);
            int rowY = widget.getY() + (widget.getHeight() - this.font.lineHeight) / 2;
            Component label = localizedKeyLabel(key);
            graphics.text(this.font, label, contentX, rowY, 0xFFE8E8E8);

            if (!normalizedSearchText().isEmpty()) {
                Component group = groupLabel(key.group);
                int groupX = contentX + Math.min(this.font.width(label) + 8, 120);
                graphics.text(this.font, Component.literal("[" + group.getString() + "]"), groupX, rowY, 0xFF6FA8DC);
            }

            String value = key.formatEffective();
            int color = key.isPresetActive() ? 0xFFFFAAFF
                    : key.isLocalOverridden() ? 0xFFFFD24F : 0xFFAAAAAA;
            int valueX = row.input() != null
                    ? row.input().getX() - 4 - this.font.width(value)
                    : row.reset().getX() - 4 - this.font.width(value);
            graphics.text(this.font, value, valueX, rowY, color);

            if (key.description != null && !key.description.isEmpty()) {
                int descY = widget.getY() + widget.getHeight() + DESC_GAP;
                int maxWidth = this.width - contentX - PADDING - SCROLLBAR_W - 2;
                Component desc = localizedKeyDescription(key);
                String descStr = truncateToWidth(desc.getString(), maxWidth);
                graphics.text(this.font, Component.literal(descStr), contentX, descY, 0xFF8090A0);
            }
        }
    }

    private static Component localizedKeyLabel(TuningKey<?> key) {
        return Component.translatableWithFallback("super_lead.tuning." + key.id + ".label", key.id);
    }

    private static Component localizedKeyDescription(TuningKey<?> key) {
        return Component.translatableWithFallback("super_lead.tuning." + key.id + ".desc", key.description);
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
        int viewport = bodyBottom - bodyTop;
        if (contentHeight <= viewport || viewport <= 0) {
            return;
        }

        int trackX = this.width - PADDING - SCROLLBAR_W;
        graphics.fill(trackX, bodyTop, trackX + SCROLLBAR_W, bodyBottom, 0x60000000);
        int thumbH = Math.max(16, viewport * viewport / contentHeight);
        int maxScroll = contentHeight - viewport;
        if (maxScroll > 0) {
            int thumbY = bodyTop + scrollOffset * (viewport - thumbH) / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF8090A0);
        }
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
            case "physics.zipline" -> "Zipline";
            case "render.mode" -> "Mode";
            case "render.geom" -> "Geometry";
            case "render.color" -> "Color";
            case "render.lod" -> "LOD";
            case "render.performance" -> "Performance";
            case "render.attach" -> "Attach";
            case "misc" -> "Misc";
            default -> group;
        };
        return Component.translatableWithFallback("super_lead.config.group." + group, fallback);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new SuperLeadConfigScreen());
    }

    private static int rowHeightFor(TuningKey<?> key) {
        return key.type instanceof ColorTuningType ? COLOR_ROW_HEIGHT : ROW_HEIGHT;
    }

    private String truncateToWidth(String value, int maxWidth) {
        if (this.font.width(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            String next = String.valueOf(value.charAt(i));
            int nextWidth = this.font.width(next);
            if (width + nextWidth + ellipsisWidth > maxWidth) {
                break;
            }
            out.append(next);
            width += nextWidth;
        }
        return out.append(ellipsis).toString();
    }

    private record ConfigRow(AbstractWidget widget, AbstractWidget input,
            AbstractWidget reset, TuningKey<?> key, int baseY, int rowHeight) {
    }

    private static final class DoubleTuningSlider extends AbstractSliderButton {
        private final TuningKey<Double> key;
        private final DoubleTuningType type;
        private final boolean log;

        private DoubleTuningSlider(int x, int y, int width, int height,
                TuningKey<Double> key, DoubleTuningType type, boolean log, double initial) {
            super(x, y, width, height, Component.empty(), initial);
            this.key = key;
            this.type = type;
            this.log = log;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            double next = sliderToValue(this.value, type.min(), type.max(), log);
            next = Math.round(next * 1.0e6D) / 1.0e6D;
            key.setLocalFromString(Double.toString(next));
        }

        private static double sliderToValue(double slider, double min, double max, boolean log) {
            if (log) {
                double lo = Math.log(min);
                double hi = Math.log(max);
                return Math.exp(lo + (hi - lo) * slider);
            }
            return min + (max - min) * slider;
        }
    }

    private static final class IntTuningSlider extends AbstractSliderButton {
        private final TuningKey<Integer> key;
        private final IntTuningType type;

        private IntTuningSlider(int x, int y, int width, int height,
                TuningKey<Integer> key, IntTuningType type, double initial) {
            super(x, y, width, height, Component.empty(), initial);
            this.key = key;
            this.type = type;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            int span = type.max() - type.min();
            int next = type.min() + (int) Math.round(this.value * span);
            key.setLocalFromString(Integer.toString(next));
        }
    }

    private static final class ColorRgbControl extends AbstractWidget {
        private static final int SWATCH_W = 18;
        private static final int BAR_H = 8;
        private static final int BAR_GAP = 3;
        private static final int BAR_PAD_X = 6;
        private static final int HANDLE_W = 3;

        private final TuningKey<Integer> key;
        private int activeChannel = -1;

        private ColorRgbControl(int x, int y, int width, int height, TuningKey<Integer> key) {
            super(x, y, width, height, Component.empty());
            this.key = key;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                float partialTick) {
            int rgb = key.get() & 0xFFFFFF;
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
            int rgb = key.get() & 0xFFFFFF;
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
            key.setLocalFromString(key.type.format((r << 16) | (g << 8) | b));
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

    private static void syncColorInput(ConfigRow row, TuningKey<?> key) {
        if (!(key.type instanceof ColorTuningType) || !(row.input() instanceof EditBox box) || box.isFocused()) {
            return;
        }
        String value = key.formatEffective();
        if (!value.equals(box.getValue())) {
            box.setValue(value);
            box.setTextColor(0xFFE8E8E8);
        }
    }
}
