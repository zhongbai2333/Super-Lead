package com.zhongbai233.super_lead.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * GUI for editing per-block rope attachment and signal properties.
 * <p>
 * Save writes to {@code config/super_lead_block_properties.json}.
 */
public final class BlockPropertyEditScreen extends Screen {
    private static final int PADDING = 10;
    private static final int HEADER_H = 34;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 3;
    private static final int SCROLLBAR_W = 4;

    private final Screen parent;
    private final Map<String, BlockProperty> entries;
    private final List<Label> labels = new ArrayList<>();
    private final List<ListRow> listRows = new ArrayList<>();

    private int listX;
    private int listY;
    private int listW;
    private int listBottom;
    private int detailX;
    private int detailY;
    private int detailW;
    private int listContentHeight;
    private int listScroll;

    private String addBlockId = "";
    private String selectedId;
    private String status = "";
    private int statusColor = 0xFFB8C7D0;

    public BlockPropertyEditScreen(Screen parent) {
        super(Component.literal("方块属性"));
        this.parent = parent;
        BlockPropertyRegistry.ensureLoaded();
        this.entries = new TreeMap<>(BlockPropertyRegistry.snapshot());
        ensureSelectedValid();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        labels.clear();
        listRows.clear();
        ensureSelectedValid();

        int leftW = Math.min(320, Math.max(220, this.width / 3));
        listX = PADDING;
        listY = HEADER_H + 10;
        listW = leftW;
        listBottom = this.height - PADDING - 18;
        detailX = listX + listW + 14;
        detailY = listY;
        detailW = Math.max(140, this.width - detailX - PADDING);

        int addW = Math.max(80, listW - 58);
        EditBox addBox = new EditBox(this.font, listX, PADDING, addW, 16,
                Component.literal("minecraft:stone"));
        addBox.setMaxLength(96);
        addBox.setValue(addBlockId);
        addBox.setResponder(value -> addBlockId = value);
        addRenderableWidget(addBox);

        addRenderableWidget(Button.builder(Component.literal("+ 添加"), button -> addEntry())
                .bounds(listX + addW + 4, PADDING, 54, 16)
                .build());

        addRenderableWidget(Button.builder(Component.literal("保存"), button -> saveFile())
                .bounds(this.width - PADDING - 152, PADDING, 76, 16)
                .build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(this.width - PADDING - 72, PADDING, 72, 16)
                .build());

        rebuildListWidgets();
        rebuildDetailWidgets();
    }

    private void rebuildListWidgets() {
        listRows.clear();
        int baseY = listY;
        for (String id : entries.keySet()) {
            int y = baseY - listScroll;
            boolean visible = y + ROW_H >= listY && y <= listBottom;
            if (visible) {
                boolean selected = id.equals(selectedId);
                Button row = Button.builder(Component.literal(shorten(id, listW - 10)), button -> {
                    selectedId = id;
                    status = "";
                    this.rebuildWidgets();
                }).bounds(listX, y, listW, ROW_H).build();
                row.active = !selected;
                addRenderableWidget(row);
                listRows.add(new ListRow(y, selected));
            }
            baseY += ROW_H + ROW_GAP;
        }
        listContentHeight = Math.max(0, baseY - listY);
        clampListScroll();
    }

    private void rebuildDetailWidgets() {
        labels.add(new Label(Component.literal("方块列表"), listX, listY - 13, 0xFFFFD24F));
        labels.add(new Label(Component.literal("挂件 / 信号规则"), detailX, detailY - 13, 0xFFFFD24F));

        if (selectedId == null) {
            labels.add(new Label(Component.literal("在左上角输入方块 ID 后添加。"), detailX, detailY + 8,
                    0xFFE8E8E8));
            return;
        }

        BlockProperty bp = currentRaw();
        int y = detailY + 4;
        labels.add(new Label(Component.literal(selectedId), detailX, y, 0xFFFFFFFF));
        y += 20;

        int colW = Math.max(86, (detailW - 6) / 2);
        addTriButton(detailX, y, colW, "穿绳", piercedText(bp.attachPierced()),
                () -> setPierced(cycle(bp.attachPierced())));
        addTriButton(detailX + colW + 6, y, colW, "位置", mountText(bp.attachMountAbove()),
                () -> setMountAbove(cycle(bp.attachMountAbove())));
        y += ROW_H + 6;

        addTriButton(detailX, y, colW, "细线", hangerText(bp.attachHangerEnabled()),
                () -> setHangerEnabled(cycle(bp.attachHangerEnabled())));
        addTriButton(detailX + colW + 6, y, colW, "跨面连接", bridgeText(bp.signalBridgeEnabled()),
                () -> setSignalBridge(cycle(bp.signalBridgeEnabled())));
        y += ROW_H + 12;

        y = addModelControls(bp, y, colW);
        y += 8;

        labels.add(new Label(Component.literal("数字留空 = 继承默认值。"), detailX, y, 0xFF9FB2C0));
        y += 14;

        int labelW = Math.min(126, Math.max(90, detailW / 3));
        int boxX = detailX + labelW + 8;
        int boxW = Math.max(66, Math.min(120, detailX + detailW - boxX));
        addDoubleBox("下挂偏移", y, boxX, boxW, bp.attachHangOffset(), this::setHangOffset);
        y += ROW_H + 5;
        addDoubleBox("上放偏移", y, boxX, boxW, bp.attachMountOffset(), this::setMountOffset);
        y += ROW_H + 5;
        addDoubleBox("细线长度", y, boxX, boxW, bp.attachHangerLength(), this::setHangerLength);
        y += ROW_H + 5;
        addDoubleBox("细线间距", y, boxX, boxW, bp.attachHangerSpacing(), this::setHangerSpacing);
        y += ROW_H + 12;

        addRenderableWidget(Button.builder(Component.literal("删除条目"), button -> deleteSelected())
                .bounds(detailX, y, 96, 18)
                .build());
        addRenderableWidget(Button.builder(Component.literal("清空字段"), button -> clearSelected())
                .bounds(detailX + 104, y, 96, 18)
                .build());

        labels.add(new Label(Component.literal("穿绳=自动 时使用模型形状启发式判断。"), detailX, y + 28,
                0xFF9FB2C0));
        labels.add(new Label(Component.literal("模型状态会直接覆盖方块展示用 BlockState。"), detailX,
                y + 40, 0xFF9FB2C0));
    }

    private int addModelControls(BlockProperty bp, int y, int colW) {
        addModeButton(detailX, y, Math.min(detailW, colW * 2 + 6), bp.attachModelMode());
        y += ROW_H + 8;

        labels.add(new Label(Component.literal("模型状态：自动读取此方块可用样式"), detailX, y, 0xFFFFD24F));
        y += 13;

        BlockState state = selectedBlockState();
        if (state == null) {
            labels.add(new Label(Component.literal("当前 ID 未注册为方块，无法读取模型状态。"), detailX, y,
                    0xFFFF8888));
            return y + 15;
        }

        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort((a, b) -> a.getName().compareTo(b.getName()));
        if (properties.isEmpty()) {
            labels.add(new Label(Component.literal("此方块没有可切换的 BlockState 样式。"), detailX, y,
                    0xFF9FB2C0));
            return y + 15;
        }

        int propW = Math.max(86, (detailW - 6) / 2);
        for (int i = 0; i < properties.size(); i++) {
            Property<?> property = properties.get(i);
            int x = detailX + (i % 2) * (propW + 6);
            if (i > 0 && i % 2 == 0) {
                y += ROW_H + 4;
            }
            addStateButton(x, y, propW, state, property, bp);
        }
        return y + ROW_H + 4;
    }

    private void addModeButton(int x, int y, int w, String rawMode) {
        Button button = Button.builder(Component.literal("显示模型: " + modelModeText(rawMode)), ignored -> {
            setModelMode(nextModelMode(rawMode));
            this.rebuildWidgets();
        }).bounds(x, y, w, ROW_H).build();
        addRenderableWidget(button);
    }

    private void addStateButton(int x, int y, int w, BlockState state, Property<?> property, BlockProperty bp) {
        String prop = property.getName();
        String raw = bp.attachModelState() == null ? null : bp.attachModelState().get(prop);
        Button button = Button
                .builder(Component.literal(shorten(prop + ": " + stateValueText(state, property, raw), w - 8)),
                        ignored -> {
                            setModelStateValue(prop, nextStateValue(property, raw));
                            this.rebuildWidgets();
                        })
                .bounds(x, y, w, ROW_H).build();
        addRenderableWidget(button);
    }

    private void addTriButton(int x, int y, int w, String label, String value, Runnable action) {
        Button button = Button.builder(Component.literal(label + ": " + value), ignored -> {
            action.run();
            this.rebuildWidgets();
        }).bounds(x, y, w, ROW_H).build();
        addRenderableWidget(button);
    }

    private void addDoubleBox(String label, int y, int boxX, int boxW, Double value, NullableDoubleSetter setter) {
        labels.add(new Label(Component.literal(label), detailX, y + 5, 0xFFE8E8E8));
        EditBox box = new EditBox(this.font, boxX, y, boxW, ROW_H, Component.literal(label));
        box.setMaxLength(18);
        box.setValue(value == null ? "" : Double.toString(value));
        box.setResponder(raw -> parseDoubleField(raw, box, setter));
        addRenderableWidget(box);
    }

    private void parseDoubleField(String raw, EditBox box, NullableDoubleSetter setter) {
        String s = raw.trim();
        if (s.isEmpty()) {
            setter.set(null);
            box.setTextColor(0xFFE8E8E8);
            status = "";
            return;
        }
        try {
            double value = Double.parseDouble(s);
            if (!Double.isFinite(value)) {
                throw new NumberFormatException(s);
            }
            setter.set(value);
            box.setTextColor(0xFFE8E8E8);
            status = "";
        } catch (NumberFormatException ex) {
            box.setTextColor(0xFFFF6666);
            status = "Invalid number: " + s;
            statusColor = 0xFFFF6666;
        }
    }

    private void addEntry() {
        String id = normalizeId(addBlockId);
        if (id.isEmpty()) {
            status = "请先输入方块 ID。";
            statusColor = 0xFFFF6666;
            return;
        }
        entries.putIfAbsent(id, BlockProperty.EMPTY);
        selectedId = id;
        addBlockId = "";
        listScroll = 0;
        status = "已选中 " + id;
        statusColor = 0xFF90EE90;
        this.rebuildWidgets();
    }

    private void saveFile() {
        BlockPropertyRegistry.save(entries);
        status = "已保存到 config/super_lead_block_properties.json";
        statusColor = 0xFF90EE90;
    }

    private void deleteSelected() {
        if (selectedId != null) {
            entries.remove(selectedId);
            status = "已删除 " + selectedId;
            statusColor = 0xFFFFD24F;
            selectedId = null;
            ensureSelectedValid();
            this.rebuildWidgets();
        }
    }

    private void clearSelected() {
        if (selectedId != null) {
            entries.put(selectedId, BlockProperty.EMPTY);
            status = "已清空 " + selectedId + " 的字段";
            statusColor = 0xFFFFD24F;
            this.rebuildWidgets();
        }
    }

    private void ensureSelectedValid() {
        if (selectedId == null || !entries.containsKey(selectedId)) {
            selectedId = entries.isEmpty() ? null : entries.keySet().iterator().next();
        }
    }

    private BlockProperty currentRaw() {
        return selectedId == null ? BlockProperty.EMPTY : entries.getOrDefault(selectedId, BlockProperty.EMPTY);
    }

    private void putCurrent(BlockProperty bp) {
        if (selectedId != null) {
            entries.put(selectedId, bp);
        }
    }

    private void setPierced(Boolean value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(value, bp.attachMountAbove(), bp.attachHangOffset(), bp.attachMountOffset(),
                bp.attachHangerLength(), bp.attachHangerSpacing(), bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setMountAbove(Boolean value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), value, bp.attachHangOffset(), bp.attachMountOffset(),
                bp.attachHangerLength(), bp.attachHangerSpacing(), bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setHangOffset(Double value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), value, bp.attachMountOffset(),
                bp.attachHangerLength(), bp.attachHangerSpacing(), bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setMountOffset(Double value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(), value,
                bp.attachHangerLength(), bp.attachHangerSpacing(), bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setHangerLength(Double value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), value, bp.attachHangerSpacing(), bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setHangerSpacing(Double value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), bp.attachHangerLength(), value, bp.attachHangerEnabled(),
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setHangerEnabled(Boolean value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), bp.attachHangerLength(), bp.attachHangerSpacing(), value,
                bp.signalBridgeEnabled(), bp.attachModelMode(), bp.attachModelState()));
    }

    private void setSignalBridge(Boolean value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), bp.attachHangerLength(), bp.attachHangerSpacing(),
                bp.attachHangerEnabled(), value, bp.attachModelMode(), bp.attachModelState()));
    }

    private void setModelMode(String value) {
        BlockProperty bp = currentRaw();
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), bp.attachHangerLength(), bp.attachHangerSpacing(),
                bp.attachHangerEnabled(), bp.signalBridgeEnabled(), value, bp.attachModelState()));
    }

    private void setModelStateValue(String key, String value) {
        BlockProperty bp = currentRaw();
        Map<String, String> next = new HashMap<>();
        if (bp.attachModelState() != null) {
            next.putAll(bp.attachModelState());
        }
        if (value == null || value.isBlank()) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        putCurrent(new BlockProperty(bp.attachPierced(), bp.attachMountAbove(), bp.attachHangOffset(),
                bp.attachMountOffset(), bp.attachHangerLength(), bp.attachHangerSpacing(),
                bp.attachHangerEnabled(), bp.signalBridgeEnabled(), bp.attachModelMode(),
                next.isEmpty() ? null : next));
    }

    private static Boolean cycle(Boolean value) {
        return value == null ? Boolean.TRUE : (value ? Boolean.FALSE : null);
    }

    private static String piercedText(Boolean value) {
        return value == null ? "自动" : (value ? "开" : "关");
    }

    private static String mountText(Boolean value) {
        return value == null ? "默认" : (value ? "上方" : "下方");
    }

    private static String hangerText(Boolean value) {
        return value == null ? "默认" : (value ? "开" : "关");
    }

    private static String bridgeText(Boolean value) {
        return value == null ? "默认" : (value ? "开" : "关");
    }

    private static String modelModeText(String raw) {
        String mode = BlockProperty.normalizeModelMode(raw);
        if (mode == null)
            return "默认";
        return switch (mode) {
            case BlockProperty.MODEL_MODE_BLOCK -> "强制方块";
            case BlockProperty.MODEL_MODE_ITEM -> "强制物品";
            default -> "自动";
        };
    }

    private static String nextModelMode(String raw) {
        String mode = BlockProperty.normalizeModelMode(raw);
        if (mode == null)
            return BlockProperty.MODEL_MODE_AUTO;
        if (mode.equals(BlockProperty.MODEL_MODE_AUTO))
            return BlockProperty.MODEL_MODE_BLOCK;
        if (mode.equals(BlockProperty.MODEL_MODE_BLOCK))
            return BlockProperty.MODEL_MODE_ITEM;
        return null;
    }

    private static String stateValueText(BlockState state, Property<?> property, String raw) {
        if (raw == null || raw.isBlank()) {
            return "默认(" + defaultStateValue(state, property) + ")";
        }
        return raw;
    }

    private static String nextStateValue(Property<?> property, String raw) {
        List<String> values = propertyValues(property);
        if (values.isEmpty())
            return null;
        if (raw == null || raw.isBlank())
            return values.get(0);
        int index = values.indexOf(raw.toLowerCase(Locale.ROOT));
        if (index < 0)
            return values.get(0);
        return index + 1 >= values.size() ? null : values.get(index + 1);
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

    private BlockState selectedBlockState() {
        Block block = selectedBlock();
        return block == null ? null : block.defaultBlockState();
    }

    private Block selectedBlock() {
        if (selectedId == null)
            return null;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).toString().equals(selectedId)) {
                return block;
            }
        }
        return null;
    }

    private static String normalizeId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return "";
        }
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
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

    private void clampListScroll() {
        int viewport = listBottom - listY;
        int max = Math.max(0, listContentHeight - viewport);
        listScroll = Mth.clamp(listScroll, 0, max);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listBottom) {
            listScroll -= (int) Math.round(dy * (ROW_H + ROW_GAP) * 2.0D);
            clampListScroll();
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        g.fill(0, 0, this.width, HEADER_H, 0xA0202630);
        g.text(this.font, this.title, PADDING, HEADER_H - 12, 0xFFFFFFAA);

        g.fill(listX - 4, listY - 18, listX + listW + 4, listBottom + 4, 0x80202630);
        g.fill(detailX - 4, detailY - 18, detailX + detailW + 4, listBottom + 4, 0x80202630);

        for (ListRow row : listRows) {
            if (row.selected()) {
                g.fill(listX - 2, row.y() - 1, listX + listW + 2, row.y() + ROW_H + 1, 0x70FFD24F);
            }
        }

        for (Label label : labels) {
            g.text(this.font, label.text(), label.x(), label.y(), label.color());
        }

        renderScrollbar(g);
        super.extractRenderState(g, mx, my, partialTick);

        if (!status.isEmpty()) {
            g.text(this.font, Component.literal(status), PADDING, this.height - PADDING - 10, statusColor);
        }
    }

    private void renderScrollbar(GuiGraphicsExtractor g) {
        int viewport = listBottom - listY;
        if (listContentHeight <= viewport || viewport <= 0) {
            return;
        }
        int trackX = listX + listW + 2;
        g.fill(trackX, listY, trackX + SCROLLBAR_W, listBottom, 0x60000000);
        int thumbH = Math.max(16, viewport * viewport / listContentHeight);
        int maxScroll = Math.max(1, listContentHeight - viewport);
        int thumbY = listY + listScroll * (viewport - thumbH) / maxScroll;
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF8090A0);
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new BlockPropertyEditScreen(parent));
    }

    @FunctionalInterface
    private interface NullableDoubleSetter {
        void set(Double value);
    }

    private record Label(Component text, int x, int y, int color) {
    }

    private record ListRow(int y, boolean selected) {
    }
}