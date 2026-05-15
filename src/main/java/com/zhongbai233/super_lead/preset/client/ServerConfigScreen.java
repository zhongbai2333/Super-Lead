package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.preset.ServerQuery;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSet;
import com.zhongbai233.super_lead.serverconfig.client.ServerConfigClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class ServerConfigScreen extends Screen {
    private static final int PADDING = 8;
    private static final int TAB_HEIGHT = 18;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 2;

    private final Screen parent;
    private int activeTab; // 0 = server, 1 = presets, 2 = zones
    private List<String> presets = new ArrayList<>();
    private int selectedPreset = -1;
    private int selectedZone = -1;
    private long lastListMs;
    private long lastZoneListMs;
    private long lastZoneEpoch;
    private String newNameDraft = "";
    private String newNameError = "";

    private int bodyTop;
    private int bodyBottom;
    private int contentHeight;
    private int scrollOffset;

    public ServerConfigScreen(Screen parent) {
        super(Component.translatable("super_lead.server_config.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        // tabs
        int tabsY = PADDING + 12;
        int tabX = PADDING;
        Component[] labels = {
                Component.translatable("super_lead.server_config.tab.server"),
                Component.translatable("super_lead.server_config.tab.presets"),
                Component.translatable("super_lead.server_config.tab.zones"),
        };
        for (int i = 0; i < labels.length; i++) {
            int idx = i;
            int w = Math.max(80, this.font.width(labels[i]) + 16);
            Button tab = Button.builder(labels[i], b -> {
                this.activeTab = idx;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabX, tabsY, w, TAB_HEIGHT).build();
            tab.active = i != activeTab;
            addRenderableWidget(tab);
            tabX += w + 4;
        }

        // header back/close
        Button close = Button.builder(Component.translatable("super_lead.config.close"), b -> onClose())
                .bounds(this.width - PADDING - 60, PADDING, 60, 14).build();
        addRenderableWidget(close);

        bodyTop = tabsY + TAB_HEIGHT + 6;
        bodyBottom = this.height - PADDING;

        // listener for preset list
        PresetClientHandler.setListListener(list -> {
            this.presets = new ArrayList<>(list);
            if (selectedPreset >= presets.size())
                selectedPreset = -1;
            this.rebuildWidgets();
        });
        if (PresetClientHandler.lastPresetList() != null) {
            this.presets = new ArrayList<>(PresetClientHandler.lastPresetList());
        }

        if (activeTab == 0) {
            buildServerTab();
        } else if (activeTab == 1) {
            requestList();
            buildPresetsTab();
        } else {
            requestZones();
            buildZonesTab();
        }
    }

    private void requestList() {
        long now = System.currentTimeMillis();
        if (now - lastListMs < 200)
            return;
        lastListMs = now;
        ClientPacketDistributor.sendToServer(ServerQuery.presetList());
    }

    private void requestZones() {
        long now = System.currentTimeMillis();
        if (now - lastZoneListMs < 200)
            return;
        lastZoneListMs = now;
        ClientPacketDistributor.sendToServer(ServerQuery.zoneList());
    }

    private enum FieldKind {
        INT, DOUBLE, BOOL
    }

    private record FieldDef(String id, String label, FieldKind kind,
            double min, double max, String defaultVal) {
    }

    private static final FieldDef[] SERVER_FIELDS = {
            new FieldDef("energy.tier_max_level", "super_lead.server_config.field.energy.tier_max_level", FieldKind.INT,
                    0, 30, "30"),
            new FieldDef("energy.base_transfer_per_tick", "super_lead.server_config.field.energy.base_transfer",
                    FieldKind.INT, 1, 16384, "256"),
            new FieldDef("network.max_leash_distance", "super_lead.server_config.field.network.max_leash_distance",
                    FieldKind.DOUBLE, 4.0, 32.0, "12.0"),
            new FieldDef("network.item_tier_max", "super_lead.server_config.field.network.item_tier_max", FieldKind.INT,
                    1, 12, "6"),
            new FieldDef("network.fluid_tier_max", "super_lead.server_config.field.network.fluid_tier_max",
                    FieldKind.INT, 1, 12, "4"),
            new FieldDef("network.pressurized_tier_max",
                    "super_lead.server_config.field.network.pressurized_tier_max", FieldKind.INT, 1, 12, "4"),
            new FieldDef("network.pressurized_batch_amount",
                    "super_lead.server_config.field.network.pressurized_batch_amount", FieldKind.INT, 1, 100000,
                    "1000"),
            new FieldDef("network.thermal_tier_max", "super_lead.server_config.field.network.thermal_tier_max",
                    FieldKind.INT, 1, 12, "4"),
            new FieldDef("network.thermal_transfer_per_tick",
                    "super_lead.server_config.field.network.thermal_transfer", FieldKind.DOUBLE, 1.0, 1000000.0,
                    "1000.0"),
            new FieldDef("network.item_transfer_interval_ticks",
                    "super_lead.server_config.field.network.item_transfer_interval", FieldKind.INT, 1, 40, "4"),
            new FieldDef("network.fluid_bucket_amount", "super_lead.server_config.field.network.fluid_bucket_amount",
                    FieldKind.INT, 100, 10000, "1000"),
            new FieldDef("network.stuck_break_ticks", "super_lead.server_config.field.network.stuck_break_ticks",
                    FieldKind.INT, 20, 1200, "100"),
            new FieldDef("network.max_ropes_per_block_face",
                    "super_lead.server_config.field.network.max_ropes_per_block_face",
                    FieldKind.INT, 1, 64, "8"),
            new FieldDef("presets.allow_op_visual_presets",
                    "super_lead.server_config.field.presets.allow_op_visual_presets", FieldKind.BOOL, 0, 1, "true"),
    };

    /**
     * Optimistic local overrides applied immediately after the user edits a
     * slider/toggle,
     * before the server's snapshot echo arrives.
     */
    private final Map<String, String> overrides = new HashMap<>();

    private void buildServerTab() {
        // ensure we have a snapshot listener and request fresh data
        ServerConfigClient.setListener(map -> {
            // server-confirmed snapshot — drop optimistic overrides for keys it covers
            for (String k : map.keySet())
                overrides.remove(k);
            this.rebuildWidgets();
        });
        ClientPacketDistributor.sendToServer(ServerQuery.serverConfig());

        int rowW = this.width - PADDING * 2;
        int labelW = 200;
        int idW = 180;
        int resetW = 40;
        int sliderW = Math.max(80, rowW - labelW - idW - resetW - 12);
        int sliderX = PADDING + labelW + idW;
        int resetX = sliderX + sliderW + 6;

        int y = bodyTop;
        for (FieldDef def : SERVER_FIELDS) {
            int yReal = y - scrollOffset;
            boolean visible = yReal >= bodyTop - ROW_H && yReal + ROW_H <= bodyBottom + ROW_H;
            AbstractWidget editor = buildEditor(def, sliderX, yReal + 1, sliderW, ROW_H - 4);
            editor.visible = visible;
            editor.active = visible;
            addRenderableWidget(editor);

            Button reset = Button.builder(Component.translatable("super_lead.tuning.reset"),
                    b -> sendValue(def.id(), def.defaultVal()))
                    .bounds(resetX, yReal + 1, resetW, ROW_H - 4).build();
            reset.visible = visible;
            reset.active = visible;
            addRenderableWidget(reset);

            y += ROW_H + ROW_GAP;
        }
        contentHeight = SERVER_FIELDS.length * (ROW_H + ROW_GAP);
        clampScroll();
    }

    private AbstractWidget buildEditor(FieldDef def, int x, int y, int w, int h) {
        String current = currentValue(def.id());
        switch (def.kind()) {
            case BOOL -> {
                boolean on = Boolean.parseBoolean(current);
                Component msg = Component.literal(on ? "ON" : "OFF");
                return Button.builder(msg, b -> sendValue(def.id(), Boolean.toString(!on)))
                        .bounds(x, y, w, h).build();
            }
            case INT -> {
                int min = (int) def.min();
                int max = (int) def.max();
                int cur;
                try {
                    cur = Integer.parseInt(current);
                } catch (RuntimeException e) {
                    cur = min;
                }
                cur = Mth.clamp(cur, min, max);
                double t = max == min ? 0.0 : (double) (cur - min) / (double) (max - min);
                final FieldDef capture = def;
                return new ServerIntSlider(x, y, w, h, min, max, t, v -> sendValue(capture.id(), Integer.toString(v)));
            }
            case DOUBLE -> {
                double min = def.min();
                double max = def.max();
                double cur;
                try {
                    cur = Double.parseDouble(current);
                } catch (RuntimeException e) {
                    cur = min;
                }
                cur = Mth.clamp(cur, min, max);
                double t = max == min ? 0.0 : (cur - min) / (max - min);
                final FieldDef capture = def;
                return new ServerDoubleSlider(x, y, w, h, min, max, t,
                        v -> sendValue(capture.id(), Double.toString(v)));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void sendValue(String key, String value) {
        overrides.put(key, value);
        ClientPacketDistributor.sendToServer(new ServerConfigSet(key, value));
        this.rebuildWidgets();
    }

    private String currentValue(String key) {
        String v = overrides.get(key);
        if (v != null)
            return v;
        Map<String, String> last = ServerConfigClient.last();
        if (last != null && last.containsKey(key))
            return last.get(key);
        return serverFieldValue(key);
    }

    private void buildPresetsTab() {
        int rowW = this.width - PADDING * 2;
        int listW = Math.min(220, rowW * 4 / 10);
        int detailX = PADDING + listW + 12;

        // top row: refresh + new-name input + create
        int btnY = bodyTop;
        int refreshW = Math.min(80, listW / 2 - 2);
        Button refresh = Button.builder(Component.translatable("super_lead.preset.op_screen.refresh"),
                b -> {
                    lastListMs = 0;
                    requestList();
                })
                .bounds(PADDING, btnY, refreshW, 14).build();
        addRenderableWidget(refresh);

        int createW = 50;
        int boxX = PADDING + refreshW + 4;
        int boxW = Math.max(60, listW - refreshW - createW - 8);
        EditBox box = new EditBox(this.font, boxX, btnY, boxW, 14,
                Component.translatable("super_lead.preset.op_screen.new_name_hint"));
        box.setMaxLength(32);
        box.setHint(Component.translatable("super_lead.preset.op_screen.new_name_hint"));
        box.setValue(newNameDraft);
        box.setResponder(s -> {
            newNameDraft = s;
            newNameError = "";
        });
        addRenderableWidget(box);

        Button create = Button.builder(Component.translatable("super_lead.preset.op_screen.create"),
                b -> tryCreatePreset(box.getValue()))
                .bounds(boxX + boxW + 4, btnY, createW, 14).build();
        addRenderableWidget(create);
        btnY += 18;

        // preset rows
        int y = btnY;
        for (int i = 0; i < presets.size(); i++) {
            int idx = i;
            int yReal = y - scrollOffset;
            String name = presets.get(i);
            boolean sel = i == selectedPreset;
            Button row = Button.builder(
                    Component.literal((sel ? "▶ " : "  ") + name),
                    b -> {
                        selectedPreset = idx;
                        rebuildWidgets();
                    })
                    .bounds(PADDING, yReal, listW, ROW_H - 2).build();
            row.visible = yReal >= btnY && yReal + ROW_H <= bodyBottom;
            row.active = row.visible;
            addRenderableWidget(row);
            y += ROW_H;
        }
        contentHeight = Math.max(0, y - btnY);

        // detail panel (only when selected)
        if (selectedPreset >= 0 && selectedPreset < presets.size()) {
            String name = presets.get(selectedPreset);
            int dy = btnY;
            addRenderableWidget(Button.builder(
                    Component.translatable("super_lead.preset.op_screen.edit"),
                    b -> PresetEditScreen.open(this, name))
                    .bounds(detailX, dy, 160, 18).build());
            dy += 22;
            addRenderableWidget(Button.builder(
                    Component.translatable("super_lead.preset.op_screen.delete"),
                    b -> {
                        // Send delete via command rather than a new payload to keep scope tight.
                        if (this.minecraft != null && this.minecraft.player != null
                                && this.minecraft.player.connection != null) {
                            this.minecraft.player.connection.sendCommand("superlead preset delete " + name);
                        }
                    })
                    .bounds(detailX, dy, 160, 18).build());
        }
        clampScroll();
    }

    private void buildZonesTab() {
        List<SyncPhysicsZones.Entry> zones = PhysicsZonesClient.zones();
        int rowW = this.width - PADDING * 2;
        int listW = Math.min(260, rowW * 5 / 10);
        int detailX = PADDING + listW + 12;

        int btnY = bodyTop;
        addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.manage.refresh"),
                b -> {
                    lastZoneListMs = 0;
                    requestZones();
                })
                .bounds(PADDING, btnY, 80, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.manage.select_tool"),
                b -> {
                    if (this.minecraft != null && this.minecraft.player != null
                            && this.minecraft.player.connection != null) {
                        ZoneSelectionClient.clearManagedPreview();
                        this.minecraft.player.connection.sendCommand("superlead zone select");
                        closeToGame();
                    }
                }).bounds(PADDING + 84, btnY, 120, 14).build());
        btnY += 18;

        int y = btnY;
        for (int i = 0; i < zones.size(); i++) {
            int idx = i;
            SyncPhysicsZones.Entry zone = zones.get(i);
            int yReal = y - scrollOffset;
            boolean sel = i == selectedZone;
            Button row = Button
                    .builder(Component.literal((sel ? "▶ " : "  ") + zone.name() + " -> " + zone.presetName()), b -> {
                        selectedZone = idx;
                        ZoneSelectionClient.previewZone(zone);
                        rebuildWidgets();
                    })
                    .bounds(PADDING, yReal, listW, ROW_H - 2).build();
            row.visible = yReal >= btnY && yReal + ROW_H <= bodyBottom;
            row.active = row.visible;
            addRenderableWidget(row);
            y += ROW_H;
        }
        contentHeight = Math.max(0, y - btnY);

        if (selectedZone >= 0 && selectedZone < zones.size()) {
            SyncPhysicsZones.Entry zone = zones.get(selectedZone);
            int dy = btnY;
            addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.manage.preview"),
                    b -> ZoneSelectionClient.previewZone(zone))
                    .bounds(detailX, dy, 160, 18).build());
            dy += 22;
            addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.manage.preview_clear"),
                    b -> ZoneSelectionClient.clearManagedPreview())
                    .bounds(detailX, dy, 160, 18).build());
            dy += 22;
            addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.manage.delete"),
                    b -> {
                        if (this.minecraft != null && this.minecraft.player != null
                                && this.minecraft.player.connection != null) {
                            this.minecraft.player.connection.sendCommand("superlead zone remove " + zone.name());
                            selectedZone = -1;
                            ZoneSelectionClient.clearManagedPreview();
                            lastZoneListMs = 0;
                            requestZones();
                        }
                    })
                    .bounds(detailX, dy, 160, 18).build());
        }
        clampScroll();
    }

    private void tryCreatePreset(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!RopePresetLibrary.isValidName(name)) {
            newNameError = Component.translatable("super_lead.preset.op_screen.invalid_name").getString();
            this.rebuildWidgets();
            return;
        }
        newNameError = "";
        newNameDraft = "";
        PresetEditScreen.open(this, name);
    }

    @Override
    public void tick() {
        super.tick();
        if (activeTab == 2 && lastZoneEpoch != PhysicsZonesClient.epoch()) {
            lastZoneEpoch = PhysicsZonesClient.epoch();
            if (selectedZone >= PhysicsZonesClient.zones().size())
                selectedZone = -1;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (my >= bodyTop && my <= bodyBottom && contentHeight > bodyBottom - bodyTop) {
            scrollOffset -= (int) Math.round(dy * (ROW_H + ROW_GAP));
            clampScroll();
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }

    @Override
    public void onClose() {
        detachListeners();
        if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    private void closeToGame() {
        detachListeners();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    private void detachListeners() {
        PresetClientHandler.setListListener(null);
        ServerConfigClient.setListener(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, bodyTop, 0x80000000);
        graphics.fill(0, bodyTop, this.width, this.height, 0xA0000000);
        graphics.text(this.font, this.title, PADDING, PADDING, 0xFFFFD24F);

        int sepY = bodyTop - 2;
        graphics.fill(PADDING, sepY, this.width - PADDING, sepY + 1, 0xFF40484F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (activeTab == 0) {
            renderServerTab(graphics);
        } else if (activeTab == 1) {
            renderPresetsTab(graphics);
        } else {
            renderZonesTab(graphics);
        }
    }

    private void renderServerTab(GuiGraphicsExtractor graphics) {
        int y = bodyTop - scrollOffset;
        int valueX = this.width - PADDING - 100;
        for (FieldDef field : SERVER_FIELDS) {
            if (y >= bodyTop - ROW_H && y <= bodyBottom) {
                graphics.text(this.font, Component.translatable(field.label()), PADDING, y + 4, 0xFFE8E8E8);
                graphics.text(this.font, Component.literal(field.id()).copy()
                        .withStyle(net.minecraft.ChatFormatting.GRAY),
                        PADDING + 200, y + 4, 0xFF888888);
                String value = currentValue(field.id());
                graphics.text(this.font, Component.literal(value), valueX, y + 4, 0xFFCCCCCC);
            }
            y += ROW_H + ROW_GAP;
        }
        graphics.text(this.font,
                Component.translatable("super_lead.server_config.hint"),
                PADDING, this.height - PADDING - 28, 0xFF8090A0);
    }

    private static String serverFieldValue(String key) {
        return switch (key) {
            case "energy.tier_max_level" -> Integer.toString(Config.energyTierMaxLevel());
            case "energy.base_transfer_per_tick" -> Integer.toString(Config.energyBaseTransfer());
            case "network.max_leash_distance" -> Double.toString(Config.maxLeashDistance());
            case "network.item_tier_max" -> Integer.toString(Config.itemTierMax());
            case "network.fluid_tier_max" -> Integer.toString(Config.fluidTierMax());
            case "network.pressurized_tier_max" -> Integer.toString(Config.pressurizedTierMax());
            case "network.pressurized_batch_amount" -> Integer.toString(Config.pressurizedBatchAmount());
            case "network.thermal_tier_max" -> Integer.toString(Config.thermalTierMax());
            case "network.thermal_transfer_per_tick" -> Double.toString(Config.thermalBaseTransfer());
            case "network.item_transfer_interval_ticks" -> Integer.toString(Config.itemTransferIntervalTicks());
            case "network.fluid_bucket_amount" -> Integer.toString(Config.fluidBucketAmount());
            case "network.stuck_break_ticks" -> Integer.toString(Config.stuckBreakTicks());
            case "network.max_ropes_per_block_face" -> Integer.toString(Config.maxRopesPerBlockFace());
            case "presets.allow_op_visual_presets" -> Boolean.toString(Config.allowOpVisualPresets());
            default -> "?";
        };
    }

    private void renderPresetsTab(GuiGraphicsExtractor graphics) {
        if (presets.isEmpty()) {
            graphics.text(this.font,
                    Component.translatable("super_lead.preset.op_screen.empty"),
                    PADDING, bodyTop + 22, 0xFFAAAAAA);
        }
        if (newNameError != null && !newNameError.isEmpty()) {
            graphics.text(this.font, Component.literal(newNameError),
                    PADDING, bodyTop + 36, 0xFFFF6060);
        }
        if (selectedPreset >= 0 && selectedPreset < presets.size()) {
            int rowW = this.width - PADDING * 2;
            int listW = Math.min(220, rowW * 4 / 10);
            int detailX = PADDING + listW + 12;
            int dy = bodyTop + 22 * 4 + 8;
            graphics.text(this.font,
                    Component.translatable("super_lead.preset.op_screen.cmd_hint"),
                    detailX, dy, 0xFF8090A0);
        }
    }

    private void renderZonesTab(GuiGraphicsExtractor graphics) {
        List<SyncPhysicsZones.Entry> zones = PhysicsZonesClient.zones();
        if (zones.isEmpty()) {
            graphics.text(this.font, Component.translatable("super_lead.zone.manage.empty"),
                    PADDING, bodyTop + 24, 0xFFAAAAAA);
        }
        if (selectedZone >= 0 && selectedZone < zones.size()) {
            SyncPhysicsZones.Entry z = zones.get(selectedZone);
            int rowW = this.width - PADDING * 2;
            int listW = Math.min(260, rowW * 5 / 10);
            int detailX = PADDING + listW + 12;
            int y = bodyTop + 86;
            graphics.text(this.font, Component.literal(formatZoneArea(z)), detailX, y, 0xFFAAAAAA);
            graphics.text(this.font, Component.translatable("super_lead.zone.manage.hint"), detailX, y + 14,
                    0xFF8090A0);
        }
    }

    private static String formatZoneArea(SyncPhysicsZones.Entry z) {
        return String.format("[%.0f,%.0f,%.0f] .. [%.0f,%.0f,%.0f]",
                z.minX(), z.minY(), z.minZ(), z.maxX() - 1.0D, z.maxY() - 1.0D, z.maxZ() - 1.0D);
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ServerConfigScreen(parent));
    }

    private static final class ServerIntSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final Consumer<Integer> sink;

        ServerIntSlider(int x, int y, int w, int h, int min, int max, double initial,
                Consumer<Integer> sink) {
            super(x, y, w, h, Component.empty(), initial);
            this.min = min;
            this.max = max;
            this.sink = sink;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            int next = min + (int) Math.round(this.value * (max - min));
            sink.accept(Mth.clamp(next, min, max));
        }
    }

    private static final class ServerDoubleSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final Consumer<Double> sink;

        ServerDoubleSlider(int x, int y, int w, int h, double min, double max, double initial,
                Consumer<Double> sink) {
            super(x, y, w, h, Component.empty(), initial);
            this.min = min;
            this.max = max;
            this.sink = sink;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            double next = min + (max - min) * this.value;
            next = Math.round(next * 1000.0D) / 1000.0D;
            sink.accept(Mth.clamp(next, min, max));
        }
    }
}
