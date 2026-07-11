package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.ConfigureRopeAttachmentDisplay;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-side editor for one rope attachment's server-synchronized display overrides. */
public final class RopeAttachmentDisplayConfigScreen extends Screen {
    private static final int W = 310;
    private static final int H = 266;
    private static final int PADDING = 8;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 3;
    private static final int MODEL_STATE_PAGE_SIZE = 6;
    private static final int TAB_H = 18;
    private static final int TAB_GAP = 4;

    private final Screen parent;
    private final UUID connectionId;
    private final UUID attachmentId;
    private final ItemStack stack;
    private final Map<String, String> draftModelStateOverride = new HashMap<>();
    private final List<Label> labels = new ArrayList<>();

    private boolean advancedTab;
    private int modelStatePage;
    private int draftMountOverride;
    private int draftDisplayModeOverride;
    private int draftHangerOverride;
    private int draftPiercedOverride;
    private int draftFrontSide;
    private double draftHangOffsetOverride;
    private double draftMountOffsetOverride;
    private double draftHangerLengthOverride;
    private double draftHangerSpacingOverride;
    private double draftScaleOverride;
    private String status = "";
    private int statusColor = 0xFFB8C7D0;
    private int tabX;
    private int tabY;
    private int tabW;

    public RopeAttachmentDisplayConfigScreen(Screen parent, UUID connectionId, UUID attachmentId, ItemStack stack,
            int mountOverride, int displayModeOverride, int hangerOverride,
            int piercedOverride, double hangOffsetOverride, double mountOffsetOverride, double hangerLengthOverride,
            double hangerSpacingOverride, double scaleOverride, int frontSide, Map<String, String> modelStateOverride) {
        super(Component.translatable("super_lead.attachment_config.title"));
        this.parent = parent;
        this.connectionId = connectionId;
        this.attachmentId = attachmentId;
        this.stack = stack.copyWithCount(1);
        this.draftMountOverride = RopeAttachment.normalizeBooleanOverride(mountOverride);
        this.draftDisplayModeOverride = RopeAttachment.normalizeDisplayModeOverride(displayModeOverride);
        this.draftHangerOverride = RopeAttachment.normalizeBooleanOverride(hangerOverride);
        this.draftPiercedOverride = RopeAttachment.normalizeBooleanOverride(piercedOverride);
        this.draftHangOffsetOverride = RopeAttachment.normalizeOptionalNonNegative(hangOffsetOverride);
        this.draftMountOffsetOverride = RopeAttachment.normalizeOptionalNonNegative(mountOffsetOverride);
        this.draftHangerLengthOverride = RopeAttachment.normalizeOptionalNonNegative(hangerLengthOverride);
        this.draftHangerSpacingOverride = RopeAttachment.normalizeOptionalNonNegative(hangerSpacingOverride);
        this.draftScaleOverride = RopeAttachment.normalizeOptionalScale(scaleOverride);
        this.draftFrontSide = RopeAttachment.normalizeFrontSide(frontSide);
        this.draftModelStateOverride.putAll(RopeAttachment.normalizeModelStateOverride(modelStateOverride));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        labels.clear();
        int x = (this.width - W) / 2;
        int y = this.height / 2 - H / 2;
        int rowY = y + 26;
        tabX = x + PADDING;
        tabY = rowY;
        tabW = (W - PADDING * 2 - TAB_GAP) / 2;
        rowY += TAB_H + 6;

        if (advancedTab) {
            addAdvancedTab(x, rowY);
        } else {
            addBasicTab(x, rowY);
        }

        int btnY = y + H - 22;
        addRenderableWidget(Button.builder(Component.translatable("super_lead.attachment_config.reset_all"), b -> resetAll())
                .bounds(x + PADDING, btnY, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.attachment_config.apply"), b -> apply())
                .bounds(x + W - PADDING - 142, btnY, 66, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.config.close"), b -> onClose())
                .bounds(x + W - PADDING - 68, btnY, 68, 18).build());
    }

    private void addBasicTab(int x, int y) {
        int rowY = y;
        int colW = (W - PADDING * 2 - 4) / 2;
        int col2X = x + PADDING + colW + 4;
        labels.add(new Label(Component.translatable("super_lead.attachment_config.basic_help"), x + PADDING, rowY,
            0xFF9FB2C0));
        rowY += 14;
        addToggleButton(x + PADDING, rowY, colW, displayModeLabel(), b -> {
            draftDisplayModeOverride = RopeAttachment.cycleDisplayModeOverride(draftDisplayModeOverride);
            b.setMessage(displayModeLabel());
        });
        addToggleButton(col2X, rowY, colW, mountLabel(), b -> {
            draftMountOverride = RopeAttachment.cycleBooleanOverride(draftMountOverride);
            b.setMessage(mountLabel());
        });
        rowY += ROW_H + ROW_GAP;
        addToggleButton(x + PADDING, rowY, W - PADDING * 2, frontSideLabel(), b -> {
            draftFrontSide = nextFrontSide(draftFrontSide);
            b.setMessage(frontSideLabel());
        });
        rowY += ROW_H + ROW_GAP + 5;
        rowY = addNumberRow(rowY, "super_lead.attachment_config.scale", draftScaleOverride, RopeAttachment.MIN_SCALE,
                RopeAttachment.MAX_SCALE, v -> draftScaleOverride = v);
        labels.add(new Label(Component.translatable("super_lead.attachment_config.basic_tip"), x + PADDING, rowY + 7,
            0xFF9FB2C0));
    }

    private void addAdvancedTab(int x, int y) {
        int rowY = y;
        int colW = (W - PADDING * 2 - 4) / 2;
        int col2X = x + PADDING + colW + 4;
        addToggleButton(x + PADDING, rowY, colW, piercedLabel(), b -> {
            draftPiercedOverride = RopeAttachment.cycleBooleanOverride(draftPiercedOverride);
            b.setMessage(piercedLabel());
        });
        addToggleButton(col2X, rowY, colW, hangerLabel(), b -> {
            draftHangerOverride = RopeAttachment.cycleBooleanOverride(draftHangerOverride);
            b.setMessage(hangerLabel());
        });
        rowY += ROW_H + ROW_GAP + 2;
        rowY = addNumberRow(rowY, "super_lead.attachment_config.hang_offset", draftHangOffsetOverride, 0.0D, 1.5D,
                v -> draftHangOffsetOverride = v);
        rowY = addNumberRow(rowY, "super_lead.attachment_config.mount_offset", draftMountOffsetOverride, 0.0D, 1.5D,
                v -> draftMountOffsetOverride = v);
        rowY = addNumberRow(rowY, "super_lead.attachment_config.hanger_length", draftHangerLengthOverride, 0.0D, 1.5D,
                v -> draftHangerLengthOverride = v);
        rowY = addNumberRow(rowY, "super_lead.attachment_config.hanger_spacing", draftHangerSpacingOverride, 0.0D, 1.0D,
                v -> draftHangerSpacingOverride = v);
        addModelStateSection(x + PADDING, rowY + 5, W - PADDING * 2);
    }

    private void switchTab(boolean advanced) {
        advancedTab = advanced;
        status = "";
        rebuildConfigWidgets();
    }

    private Component tabLabel(boolean advanced) {
        String key = advanced ? "super_lead.attachment_config.tab.advanced" : "super_lead.attachment_config.tab.basic";
        return Component.translatable(key);
    }

    private void rebuildConfigWidgets() {
        clearWidgets();
        init();
    }

    private void addToggleButton(int bx, int by, int bw, Component message, java.util.function.Consumer<Button> action) {
        addRenderableWidget(Button.builder(message, b -> {
            action.accept(b);
            status = "";
        }).bounds(bx, by, bw, ROW_H).build());
    }

    private int addNumberRow(int y, String labelKey, double initial, double min, double max,
            java.util.function.DoubleConsumer setter) {
        int x = (this.width - W) / 2;
        int labelW = 58;
        int sliderW = 134;
        int inputW = 42;
        int resetW = 18;
        int gap = 6;
        int labelX = x + PADDING;
        int sliderX = labelX + labelW + gap;
        int inputX = sliderX + sliderW + gap;
        int resetX = inputX + inputW + gap;
        labels.add(new Label(Component.translatable(labelKey), labelX, y + 3));
        EditBox input = new EditBox(this.font, inputX, y, inputW, ROW_H, Component.translatable(labelKey));
        ValueSlider slider = new ValueSlider(sliderX, y, sliderW, ROW_H, initial, min, max, setter, input);
        input.setMaxLength(6);
        input.setValue(RopeAttachment.hasDoubleOverride(initial) ? format(initial) : "");
        input.setResponder(raw -> parseOptionalDouble(raw, input, slider, setter, min, max));
        addRenderableWidget(slider);
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
            setter.accept(RopeAttachment.DOUBLE_DEFAULT);
            input.setValue("");
            slider.setOptionalValue(RopeAttachment.DOUBLE_DEFAULT);
            status = "";
        }).bounds(resetX, y, resetW, ROW_H).build());
        return y + ROW_H + ROW_GAP;
    }

    private void parseOptionalDouble(String raw, EditBox box, ValueSlider slider, java.util.function.DoubleConsumer setter,
            double min, double max) {
        String s = raw.trim();
        if (s.isEmpty()) {
            setter.accept(RopeAttachment.DOUBLE_DEFAULT);
            slider.setOptionalValue(RopeAttachment.DOUBLE_DEFAULT);
            box.setTextColor(0xFFE8E8E8);
            status = "";
            return;
        }
        try {
            double value = Mth.clamp(Double.parseDouble(s), min, max);
            setter.accept(value);
            slider.setOptionalValue(value);
            box.setTextColor(0xFFE8E8E8);
            status = "";
        } catch (NumberFormatException ex) {
            box.setTextColor(0xFFFF6666);
            status = Component.translatable("super_lead.attachment_config.invalid_number").getString();
            statusColor = 0xFFFF6666;
        }
    }

    private void addModelStateSection(int x, int y, int w) {
        labels.add(new Label(modelStateTitle(), x, y, 0xFFFFD24F));
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            labels.add(new Label(Component.translatable("super_lead.attachment_config.model_state.none"), x, y + 15));
            return;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        List<Property<?>> properties = modelProperties(state);
        if (properties.isEmpty()) {
            labels.add(new Label(Component.translatable("super_lead.attachment_config.model_state.empty"), x, y + 15));
            return;
        }
        int maxPage = maxModelStatePage(properties.size());
        modelStatePage = Mth.clamp(modelStatePage, 0, maxPage);
        int start = modelStatePage * MODEL_STATE_PAGE_SIZE;
        int end = Math.min(properties.size(), start + MODEL_STATE_PAGE_SIZE);
        int colW = (w - 4) / 2;
        int col2X = x + colW + 4;
        for (int i = start; i < end; i++) {
            Property<?> property = properties.get(i);
            int local = i - start;
            int bx = (local % 2 == 0) ? x : col2X;
            int by = y + 15 + (local / 2) * (ROW_H + 3);
            addRenderableWidget(Button.builder(modelStateLabel(state, property, colW - 6), b -> {
                cycleModelState(property);
                b.setMessage(modelStateLabel(state, property, colW - 6));
                status = "";
            }).bounds(bx, by, colW, ROW_H).build());
        }
        if (maxPage > 0) {
            int navY = y + 15 + 3 * (ROW_H + 3);
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                modelStatePage = Math.max(0, modelStatePage - 1);
                rebuildConfigWidgets();
            }).bounds(x, navY, 24, ROW_H).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                modelStatePage = Math.min(maxPage, modelStatePage + 1);
                rebuildConfigWidgets();
            }).bounds(x + w - 24, navY, 24, ROW_H).build());
            labels.add(new Label(Component.translatable("super_lead.attachment_config.model_state.page",
                    modelStatePage + 1, maxPage + 1), x + 32, navY + 3, 0xFF9FB2C0));
        }
    }

    private Component modelStateTitle() {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return Component.translatable("super_lead.attachment_config.model_state");
        }
        int count = modelProperties(blockItem.getBlock().defaultBlockState()).size();
        return Component.translatable("super_lead.attachment_config.model_state.count", count);
    }

    private static List<Property<?>> modelProperties(BlockState state) {
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort((a, b) -> a.getName().compareTo(b.getName()));
        return properties;
    }

    private static int maxModelStatePage(int propertyCount) {
        return Math.max(0, (propertyCount - 1) / MODEL_STATE_PAGE_SIZE);
    }

    private Component modelStateLabel(BlockState state, Property<?> property, int maxWidth) {
        String prop = property.getName();
        String raw = draftModelStateOverride.get(prop);
        String value = raw == null ? Component.translatable("super_lead.attachment_config.default_short",
                defaultStateValue(state, property)).getString() : raw;
        return Component.literal(shorten(prop + ": " + value, maxWidth));
    }

    private void cycleModelState(Property<?> property) {
        String prop = property.getName();
        List<String> values = propertyValues(property);
        String raw = draftModelStateOverride.get(prop);
        if (values.isEmpty()) {
            draftModelStateOverride.remove(prop);
        } else if (raw == null) {
            draftModelStateOverride.put(prop, values.get(0));
        } else {
            int index = values.indexOf(raw.toLowerCase(Locale.ROOT));
            if (index < 0 || index + 1 >= values.size()) {
                draftModelStateOverride.remove(prop);
            } else {
                draftModelStateOverride.put(prop, values.get(index + 1));
            }
        }
    }

    private void resetAll() {
        draftMountOverride = RopeAttachment.OVERRIDE_DEFAULT;
        draftDisplayModeOverride = RopeAttachment.DISPLAY_DEFAULT;
        draftHangerOverride = RopeAttachment.OVERRIDE_DEFAULT;
        draftPiercedOverride = RopeAttachment.OVERRIDE_DEFAULT;
        draftHangOffsetOverride = RopeAttachment.DOUBLE_DEFAULT;
        draftMountOffsetOverride = RopeAttachment.DOUBLE_DEFAULT;
        draftHangerLengthOverride = RopeAttachment.DOUBLE_DEFAULT;
        draftHangerSpacingOverride = RopeAttachment.DOUBLE_DEFAULT;
        draftScaleOverride = RopeAttachment.DOUBLE_DEFAULT;
        draftFrontSide = 1;
        modelStatePage = 0;
        draftModelStateOverride.clear();
        status = Component.translatable("super_lead.attachment_config.reset_all.done").getString();
        statusColor = 0xFFFFD24F;
        rebuildConfigWidgets();
    }

    private void apply() {
        ClientPacketDistributor.sendToServer(new ConfigureRopeAttachmentDisplay(connectionId, attachmentId,
                draftMountOverride,
                draftDisplayModeOverride, draftHangerOverride, draftPiercedOverride, draftHangOffsetOverride,
                draftMountOffsetOverride, draftHangerLengthOverride, draftHangerSpacingOverride, draftScaleOverride,
                draftFrontSide, Map.copyOf(draftModelStateOverride)));
        status = Component.translatable("super_lead.attachment_config.applied").getString();
        statusColor = 0xFF90EE90;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        int x = (this.width - W) / 2;
        int y = this.height / 2 - H / 2;
        g.fill(0, 0, this.width, this.height, 0x90000000);
        g.fill(x, y, x + W, y + H, 0xE0202630);
        g.fill(x, y, x + W, y + 22, 0xFF2D3542);
        g.text(this.font, this.title, x + PADDING, y + 8, 0xFFFFFFAA);
        renderTabs(g, mx, my);
        g.text(this.font, stack.getHoverName(), x + PADDING, y + H - 40, 0xFF9FB2C0);
        for (Label label : labels) {
            g.text(this.font, label.text(), label.x(), label.y(), label.color());
        }
        if (!status.isEmpty()) {
            g.text(this.font, Component.literal(shorten(status, 160)), x + PADDING + 76, y + H - 17, statusColor);
        }
        super.extractRenderState(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (inTab(mouseX, mouseY, false)) {
            switchTab(false);
            return true;
        }
        if (inTab(mouseX, mouseY, true)) {
            switchTab(true);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void renderTabs(GuiGraphicsExtractor g, int mx, int my) {
        int stripTop = tabY + TAB_H - 1;
        g.fill(tabX, stripTop, tabX + tabW * 2 + TAB_GAP, stripTop + 1, 0xFF3A4657);
        renderTab(g, mx, my, false);
        renderTab(g, mx, my, true);
    }

    private void renderTab(GuiGraphicsExtractor g, int mx, int my, boolean advanced) {
        int left = advanced ? tabX + tabW + TAB_GAP : tabX;
        int right = left + tabW;
        boolean selected = advancedTab == advanced;
        boolean hovered = mx >= left && mx < right && my >= tabY && my < tabY + TAB_H;
        int bg = selected ? 0xFF2A3341 : hovered ? 0xFF3B4758 : 0xFF323D4D;
        int border = selected ? 0xFF6B86A7 : 0xFF46576E;
        int text = selected ? 0xFFF2F7FF : 0xFFBFCBDA;
        g.fill(left, tabY, right, tabY + TAB_H, bg);
        g.fill(left, tabY, right, tabY + 1, border);
        g.fill(left, tabY, left + 1, tabY + TAB_H, border);
        g.fill(right - 1, tabY, right, tabY + TAB_H, border);
        if (!selected) {
            g.fill(left, tabY + TAB_H - 1, right, tabY + TAB_H, 0xFF3A4657);
        }
        Component label = tabLabel(advanced);
        int tx = left + (tabW - this.font.width(label)) / 2;
        int ty = tabY + (TAB_H - this.font.lineHeight) / 2;
        g.text(this.font, label, tx, ty, text);
    }

    private boolean inTab(double mouseX, double mouseY, boolean advanced) {
        int left = advanced ? tabX + tabW + TAB_GAP : tabX;
        return mouseX >= left && mouseX < left + tabW
                && mouseY >= tabY && mouseY < tabY + TAB_H;
    }

    private Component piercedLabel() {
        return Component.translatable("super_lead.attachment_config.pierced.value", booleanOverrideText(draftPiercedOverride,
                "super_lead.bool.on", "super_lead.bool.off"));
    }

    private Component mountLabel() {
        return Component.translatable("super_lead.attachment_config.mount.value", booleanOverrideText(draftMountOverride,
                "super_lead.attachment_config.mount.above", "super_lead.attachment_config.mount.below"));
    }

    private Component displayModeLabel() {
        Component value = switch (RopeAttachment.normalizeDisplayModeOverride(draftDisplayModeOverride)) {
            case RopeAttachment.DISPLAY_BLOCK -> Component.translatable("super_lead.attachment_config.display.block");
            case RopeAttachment.DISPLAY_ITEM -> Component.translatable("super_lead.attachment_config.display.item");
            default -> Component.translatable("super_lead.attachment_config.default");
        };
        return Component.translatable("super_lead.attachment_config.display_mode.value", value);
    }

    private Component hangerLabel() {
        return Component.translatable("super_lead.attachment_config.hanger.value", booleanOverrideText(draftHangerOverride,
                "super_lead.bool.on", "super_lead.bool.off"));
    }

    private Component frontSideLabel() {
        return Component.translatable("super_lead.attachment_config.front_side.value",
                Component.translatable("super_lead.attachment_config.front_side." + frontSideName(draftFrontSide)));
    }

    private static Component booleanOverrideText(int value, String trueKey, String falseKey) {
        return switch (RopeAttachment.normalizeBooleanOverride(value)) {
            case RopeAttachment.OVERRIDE_TRUE -> Component.translatable(trueKey);
            case RopeAttachment.OVERRIDE_FALSE -> Component.translatable(falseKey);
            default -> Component.translatable("super_lead.attachment_config.default");
        };
    }

    private static int nextFrontSide(int side) {
        return switch (RopeAttachment.normalizeFrontSide(side)) {
            case 1 -> -1;
            case -1 -> 2;
            case 2 -> -2;
            case -2 -> 3;
            case 3 -> -3;
            default -> 1;
        };
    }

    private static String frontSideName(int side) {
        return switch (RopeAttachment.normalizeFrontSide(side)) {
            case 1 -> "front";
            case -1 -> "back";
            case 2 -> "right";
            case -2 -> "left";
            case 3 -> "up";
            case -3 -> "down";
            default -> "front";
        };
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<String> propertyValues(Property<?> property) {
        Property rawProperty = (Property) property;
        ArrayList<String> values = new ArrayList<>();
        for (Object value : rawProperty.getPossibleValues()) {
            values.add(rawProperty.getName((Comparable) value));
        }
        return values;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static String defaultStateValue(BlockState state, Property<?> property) {
        Property rawProperty = (Property) property;
        Comparable value = state.getValue(rawProperty);
        return rawProperty.getName(value);
    }

    private String shorten(String value, int maxWidth) {
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

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.round(value * 100.0D) / 100.0D);
    }

    private record Label(Component text, int x, int y, int color) {
        Label(Component text, int x, int y) {
            this(text, x, y, 0xFFE8E8E8);
        }
    }

    private static final class ValueSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final java.util.function.DoubleConsumer setter;
        private final EditBox input;
        private boolean hasOverride;

        ValueSlider(int x, int y, int width, int height, double initial, double min, double max,
                java.util.function.DoubleConsumer setter, EditBox input) {
            super(x, y, width, height, Component.empty(), toSlider(initial, min, max));
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.input = input;
            this.hasOverride = RopeAttachment.hasDoubleOverride(initial);
            updateMessage();
        }

        void setOptionalValue(double value) {
            hasOverride = RopeAttachment.hasDoubleOverride(value);
            if (hasOverride) {
                this.value = toSlider(value, min, max);
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(hasOverride ? Component.literal(format(toValue(this.value, min, max)))
                    : Component.translatable("super_lead.attachment_config.default"));
        }

        @Override
        protected void applyValue() {
            hasOverride = true;
            double next = Math.round(toValue(this.value, min, max) * 100.0D) / 100.0D;
            setter.accept(next);
            input.setValue(format(next));
            input.setTextColor(0xFFE8E8E8);
        }

        private static double toSlider(double value, double min, double max) {
            if (!RopeAttachment.hasDoubleOverride(value)) {
                return 0.0D;
            }
            return Mth.clamp((value - min) / Math.max(1.0e-6D, max - min), 0.0D, 1.0D);
        }

        private static double toValue(double slider, double min, double max) {
            return min + (max - min) * Mth.clamp(slider, 0.0D, 1.0D);
        }
    }
}
