package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.tuning.ClientTuning;
import com.zhongbai233.super_lead.tuning.DoubleTuningType;
import com.zhongbai233.super_lead.tuning.IntTuningType;
import com.zhongbai233.super_lead.tuning.TuningKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class SuperLeadConfigScreen extends Screen {
    static final int WIDGET_H = 16;

    private static final int PADDING = 8;
    private static final int TAB_HEIGHT = 18;
    private static final int DESC_GAP = 2;
    private static final int DESC_H = 9;
    private static final int ROW_HEIGHT = WIDGET_H + DESC_GAP + DESC_H;
    private static final int ROW_GAP = 4;
    private static final int RESET_BTN_W = 14;
    private static final int VALUE_TEXT_W = 76;
    private static final int INPUT_W = 56;
    private static final int SCROLLBAR_W = 4;

    private final PreviewRope preview = new PreviewRope();
    private final List<ConfigRow> rows = new ArrayList<>();

    private List<String> groups;
    private int activeTab;
    private int bodyTop;
    private int bodyBottom;
    private int contentHeight;
    private int scrollOffset;

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
        }
        if (activeTab >= groups.size()) {
            activeTab = 0;
        }

        preview.setViewport(0, 0, this.width, this.height);

        int tabsY = PADDING + 12;
        int tabX = PADDING;
        for (int i = 0; i < groups.size(); i++) {
            int index = i;
            net.minecraft.network.chat.Component label = groupLabel(groups.get(i));
            int tabWidth = Math.max(60, this.font.width(label) + 12);
            Button tab = Button.builder(label, button -> {
                this.activeTab = index;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabX, tabsY, tabWidth, TAB_HEIGHT).build();
            tab.active = i != activeTab;
            addRenderableWidget(tab);
            tabX += tabWidth + 4;
        }

        rebuildBody(tabsY + TAB_HEIGHT + 6);

        Button close = Button.builder(Component.translatable("super_lead.config.close"), button -> onClose())
                .bounds(this.width - PADDING - 60, PADDING, 60, 14)
                .build();
        addRenderableWidget(close);

        Button opPresets = Button.builder(
                Component.translatable("super_lead.server_config.button"),
                button -> com.zhongbai233.super_lead.preset.client.ServerConfigScreen.open(this))
                .bounds(this.width - PADDING - 60 - 86, PADDING, 84, 14)
                .build();
        addRenderableWidget(opPresets);
    }

    private void rebuildBody(int startY) {
        rows.clear();
        if (groups == null || groups.isEmpty()) {
            return;
        }

        String group = groups.get(activeTab);
        bodyTop = startY;
        bodyBottom = this.height - PADDING;

        int rowW = this.width - PADDING * 2 - SCROLLBAR_W - 2;
        int sliderW = Math.max(100, rowW * 4 / 12);
        int labelW = rowW - sliderW - INPUT_W - VALUE_TEXT_W - RESET_BTN_W - 12;
        if (labelW < 60) {
            labelW = 60;
            sliderW = rowW - labelW - INPUT_W - VALUE_TEXT_W - RESET_BTN_W - 12;
        }
        int sliderX = PADDING + labelW + 4;
        int inputX = sliderX + sliderW + 4;
        int resetX = PADDING + rowW - RESET_BTN_W;

        int baseY = startY;
        for (TuningKey<?> key : ClientTuning.allKeys()) {
            if (!key.group.equals(group)) {
                continue;
            }
            int y = baseY - scrollOffset;
            AbstractWidget widget = buildWidget(key, sliderX, y, sliderW);
            AbstractWidget input = buildInput(key, inputX, y, INPUT_W);
            AbstractWidget reset = Button.builder(Component.translatable("super_lead.config.reset"), button -> {
                key.clearLocal();
                this.rebuildWidgets();
            }).bounds(resetX, y, RESET_BTN_W, WIDGET_H).build();
            boolean visible = y >= bodyTop && y + ROW_HEIGHT <= bodyBottom;
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
            rows.add(new ConfigRow(widget, input, reset, key, baseY));
            baseY += ROW_HEIGHT + ROW_GAP;
        }
        contentHeight = Math.max(0, baseY - startY);
        clampScroll();
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

        TuningKey<Boolean> boolKey = (TuningKey<Boolean>) key;
        return Button.builder(boolLabel(boolKey.get()), button -> {
            boolean next = !boolKey.get();
            boolKey.setLocalFromString(Boolean.toString(next));
            button.setMessage(boolLabel(next));
        }).bounds(x, y, width, WIDGET_H).build();
    }

    @SuppressWarnings("unchecked")
    private AbstractWidget buildInput(TuningKey<?> key, int x, int y, int width) {
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
        return key == ClientTuning.SLACK_LOOSE || key == ClientTuning.SLACK_TIGHT;
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

    @Override
    public void tick() {
        super.tick();
        preview.tick();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        preview.render(graphics);

        graphics.fill(0, 0, this.width, bodyTop, 0x80000000);
        graphics.fill(0, bodyTop, this.width, this.height, 0xA0000000);
        graphics.text(this.font, this.title, PADDING, PADDING, 0xFFFFFFAA);

        int sepY = bodyTop - 2;
        graphics.fill(PADDING, sepY, this.width - PADDING, sepY + 1, 0xFF40484F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (groups != null && !groups.isEmpty()) {
            renderRows(graphics);
        }
        renderScrollbar(graphics);
    }

    private void renderRows(GuiGraphicsExtractor graphics) {
        for (ConfigRow row : rows) {
            AbstractWidget widget = row.widget();
            if (!widget.visible) {
                continue;
            }

            TuningKey<?> key = row.key();
            int rowY = widget.getY() + (widget.getHeight() - this.font.lineHeight) / 2;
            Component label = Component.translatableWithFallback(
                    "super_lead.tuning." + key.id + ".label", key.id);
            graphics.text(this.font, label, PADDING, rowY, 0xFFE8E8E8);

            String value = key.formatEffective();
            int color = key.isPresetActive() ? 0xFFFFAAFF
                    : key.isLocalOverridden() ? 0xFFFFD24F : 0xFFAAAAAA;
            int valueX = row.input() != null
                    ? row.input().getX() - 4 - this.font.width(value)
                    : row.reset().getX() - 4 - this.font.width(value);
            graphics.text(this.font, value, valueX, rowY, color);

            if (key.description != null && !key.description.isEmpty()) {
                int descY = widget.getY() + WIDGET_H + DESC_GAP;
                int maxWidth = this.width - PADDING * 2 - SCROLLBAR_W - 2;
                Component desc = Component.translatableWithFallback(
                        "super_lead.tuning." + key.id + ".desc", key.description);
                String descStr = truncateToWidth(desc.getString(), maxWidth);
                graphics.text(this.font, Component.literal(descStr), PADDING, descY, 0xFF8090A0);
            }
        }
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
        int thumbY = bodyTop + scrollOffset * (viewport - thumbH) / maxScroll;
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF8090A0);
    }

    private static Component groupLabel(String group) {
        String fallback = switch (group) {
            case "physics.shape" -> "Shape";
            case "physics.solver" -> "Solver";
            case "render.mode" -> "Mode";
            case "render.geom" -> "Geometry";
            case "render.lod" -> "LOD";
            case "render.attach" -> "Attach";
            case "misc" -> "Misc";
            default -> group;
        };
        return Component.translatableWithFallback("super_lead.config.group." + group, fallback);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new SuperLeadConfigScreen());
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
            AbstractWidget reset, TuningKey<?> key, int baseY) {
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
}
