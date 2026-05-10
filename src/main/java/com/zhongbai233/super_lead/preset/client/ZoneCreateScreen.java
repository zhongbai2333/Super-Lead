package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.PresetListRequest;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.preset.ZoneCreateRequest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class ZoneCreateScreen extends Screen {
    private static final int W = 260;
    private static final int PADDING = 8;

    private final Screen parent;
    private final BlockPos from;
    private final BlockPos to;
    private List<String> presets = new ArrayList<>();
    private EditBox nameBox;
    private EditBox presetBox;
    private String error = "";
    private String nameDraft = "";
    private String presetDraft = "";
    private long lastPresetRequestMs;

    public ZoneCreateScreen(Screen parent, BlockPos from, BlockPos to) {
        super(Component.translatable("super_lead.zone.create.title"));
        this.parent = parent;
        this.from = from;
        this.to = to;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        int x = (this.width - W) / 2;
        int y = this.height / 2 - 86;

        PresetClientHandler.setListListener(list -> {
            List<String> next = new ArrayList<>(list);
            boolean changed = !next.equals(this.presets);
            this.presets = next;
            if (presetBox != null && presetBox.getValue().isBlank() && !presets.isEmpty()) {
                presetBox.setValue(presets.get(0));
            }
            if (changed) this.rebuildWidgets();
        });
        if (PresetClientHandler.lastPresetList() != null) {
            presets = new ArrayList<>(PresetClientHandler.lastPresetList());
        }
        requestPresetList();

        nameBox = new EditBox(this.font, x + PADDING, y + 48, W - PADDING * 2, 18,
                Component.translatable("super_lead.zone.create.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("super_lead.zone.create.name"));
        nameBox.setValue(nameDraft);
        nameBox.setResponder(s -> { nameDraft = s; error = ""; });
        addRenderableWidget(nameBox);

        presetBox = new EditBox(this.font, x + PADDING, y + 88, W - PADDING * 2, 18,
                Component.translatable("super_lead.zone.create.preset"));
        presetBox.setMaxLength(32);
        presetBox.setHint(Component.translatable("super_lead.zone.create.preset"));
        presetBox.setValue(!presetDraft.isBlank() ? presetDraft : (!presets.isEmpty() ? presets.get(0) : ""));
        presetBox.setResponder(s -> { presetDraft = s; error = ""; });
        addRenderableWidget(presetBox);

        int btnY = y + 142;
        addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.create.create"), b -> submit())
                .bounds(x + PADDING, btnY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.config.close"), b -> onClose())
                .bounds(x + W - PADDING - 70, btnY, 70, 20).build());

        int px = x + PADDING;
        int py = y + 112;
        int maxButtons = Math.min(4, presets.size());
        for (int i = 0; i < maxButtons; i++) {
            String preset = presets.get(i);
            addRenderableWidget(Button.builder(Component.literal(preset), b -> presetBox.setValue(preset))
                    .bounds(px, py, Math.min(70, this.font.width(preset) + 12), 16).build());
            px += 74;
        }
    }

    private void requestPresetList() {
        long now = System.currentTimeMillis();
        if (now - lastPresetRequestMs < 500L) return;
        lastPresetRequestMs = now;
        ClientPacketDistributor.sendToServer(PresetListRequest.INSTANCE);
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        String preset = presetBox.getValue().trim();
        if (!RopePresetLibrary.isValidName(name)) {
            error = Component.translatable("super_lead.zone.create.invalid_name").getString();
            return;
        }
        if (!RopePresetLibrary.isValidName(preset)) {
            error = Component.translatable("super_lead.zone.create.invalid_preset").getString();
            return;
        }
        ClientPacketDistributor.sendToServer(new ZoneCreateRequest(name, preset, from, to));
        ZoneSelectionClient.clearCreatePreview();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        PresetClientHandler.setListListener(null);
        ZoneSelectionClient.clearCreatePreview();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int x = (this.width - W) / 2;
        int y = this.height / 2 - 86;
        graphics.fill(x, y, x + W, y + 176, 0xE0202428);
        graphics.text(this.font, this.title, x + PADDING, y + 8, 0xFFFFD24F);
        graphics.text(this.font, Component.literal(areaText()).withStyle(ChatFormatting.GRAY), x + PADDING, y + 22, 0xFFAAAAAA);
        graphics.text(this.font, Component.translatable("super_lead.zone.create.name"), x + PADDING, y + 38, 0xFFE8E8E8);
        graphics.text(this.font, Component.translatable("super_lead.zone.create.preset"), x + PADDING, y + 78, 0xFFE8E8E8);
        if (!error.isEmpty()) graphics.text(this.font, Component.literal(error), x + PADDING, y + 166, 0xFFFF6060);
    }

    private String areaText() {
        int x0 = Math.min(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ());
        int x1 = Math.max(from.getX(), to.getX());
        int y1 = Math.max(from.getY(), to.getY());
        int z1 = Math.max(from.getZ(), to.getZ());
        return String.format("[%d,%d,%d] .. [%d,%d,%d]", x0, y0, z0, x1, y1, z1);
    }

    public static void open(Screen parent, BlockPos from, BlockPos to) {
        Minecraft.getInstance().setScreen(new ZoneCreateScreen(parent, from, to));
    }
}