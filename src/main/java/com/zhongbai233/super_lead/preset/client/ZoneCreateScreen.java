package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.ServerQuery;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import com.zhongbai233.super_lead.preset.ZoneCreateRequest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class ZoneCreateScreen extends Screen {
    private static final int W = 260;
    private static final int PADDING = 8;
    private static final int DIALOG_H = 152;

    private final Screen parent;
    private final BlockPos from;
    private final BlockPos to;
    private List<String> presets = new ArrayList<>();
    private EditBox nameBox;
    private String error = "";
    private String nameDraft = "";
    private String presetDraft = "";
    private long lastPresetRequestMs;

    private Button presetDropdownBtn;
    private boolean dropdownOpen;
    private List<Button> dropdownOptions = new ArrayList<>();
    private int dropdownScroll;

    public ZoneCreateScreen(Screen parent, BlockPos from, BlockPos to) {
        super(Component.translatable("super_lead.zone.create.title"));
        this.parent = parent;
        this.from = from;
        this.to = to;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int x = (this.width - W) / 2;
        int y = this.height / 2 - DIALOG_H / 2;

        PresetClientHandler.setListListener(list -> {
            List<String> next = new ArrayList<>(list);
            boolean changed = !next.equals(this.presets);
            this.presets = next;
            if (changed)
                this.rebuildWidgets();
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
        nameBox.setResponder(s -> {
            nameDraft = s;
            error = "";
        });
        addRenderableWidget(nameBox);

        // Preset dropdown
        List<String> options = presets.isEmpty() ? List.of("") : new ArrayList<>(presets);
        if (presetDraft.isBlank() || !options.contains(presetDraft))
            presetDraft = options.get(0);

        int dropdownX = x + PADDING;
        int dropdownY = y + 88;
        int dropdownW = W - PADDING * 2;
        int dropdownH = 20;
        presetDropdownBtn = Button.builder(
                Component.literal(presetDraft.isEmpty() ? "—" : presetDraft),
                b -> {
                    dropdownOpen = !dropdownOpen;
                    dropdownScroll = 0;
                    rebuildDropdownOptions(dropdownX, dropdownY + dropdownH, dropdownW);
                })
                .bounds(dropdownX, dropdownY, dropdownW, dropdownH)
                .build();
        addRenderableWidget(presetDropdownBtn);

        int btnY = y + DIALOG_H - 40;
        addRenderableWidget(Button.builder(Component.translatable("super_lead.zone.create.create"), b -> submit())
                .bounds(x + PADDING, btnY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.config.close"), b -> onClose())
                .bounds(x + W - PADDING - 70, btnY, 70, 20).build());

        if (dropdownOpen) {
            rebuildDropdownOptions(dropdownX, dropdownY + dropdownH, dropdownW);
        }
    }

    private void requestPresetList() {
        long now = System.currentTimeMillis();
        if (now - lastPresetRequestMs < 500L)
            return;
        lastPresetRequestMs = now;
        ClientPacketDistributor.sendToServer(ServerQuery.presetList());
    }

    private void rebuildDropdownOptions(int x, int y, int w) {
        for (Button btn : dropdownOptions) {
            removeWidget(btn);
        }
        dropdownOptions.clear();
        if (!dropdownOpen)
            return;

        List<String> options = presets.isEmpty() ? List.of("") : new ArrayList<>(presets);
        int optionH = 16;
        int maxVisible = 6;
        int listH = Math.min(options.size(), maxVisible) * optionH;
        int maxScroll = Math.max(0, options.size() * optionH - listH);
        dropdownScroll = Mth.clamp(dropdownScroll, 0, maxScroll);

        for (int i = 0; i < options.size(); i++) {
            String opt = options.get(i);
            int oy = y + i * optionH - dropdownScroll;
            if (oy < y || oy + optionH > y + listH)
                continue;
            Button optBtn = Button.builder(
                    Component.literal(opt.isEmpty() ? "—" : opt),
                    b -> {
                        presetDraft = opt;
                        presetDropdownBtn.setMessage(Component.literal(opt.isEmpty() ? "—" : opt));
                        dropdownOpen = false;
                        for (Button db : dropdownOptions)
                            removeWidget(db);
                        dropdownOptions.clear();
                        error = "";
                    })
                    .bounds(x, oy, w, optionH)
                    .build();
            addRenderableWidget(optBtn);
            dropdownOptions.add(optBtn);
        }
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        String preset = presetDraft;
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
        if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (dropdownOpen) {
            dropdownScroll -= (int) Math.round(dy * 16);
            int x = (this.width - W) / 2;
            int y = this.height / 2 - DIALOG_H / 2;
            int dropdownX = x + PADDING;
            int dropdownY = y + 88 + 20;
            int dropdownW = W - PADDING * 2;
            rebuildDropdownOptions(dropdownX, dropdownY, dropdownW);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (dropdownOpen && !isClickOnDropdown(event.x(), event.y())) {
            dropdownOpen = false;
            for (Button btn : dropdownOptions)
                removeWidget(btn);
            dropdownOptions.clear();
            this.rebuildWidgets();
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean isClickOnDropdown(double mx, double my) {
        if (presetDropdownBtn != null && presetDropdownBtn.isMouseOver(mx, my))
            return true;
        for (Button btn : dropdownOptions) {
            if (btn.isMouseOver(mx, my))
                return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        PresetClientHandler.setListListener(null);
        ZoneSelectionClient.clearCreatePreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        int x = (this.width - W) / 2;
        int y = this.height / 2 - DIALOG_H / 2;
        graphics.fill(x, y, x + W, y + DIALOG_H, 0xFF303840);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(this.font, this.title, x + PADDING, y + 8, 0xFFFFD24F);
        graphics.text(this.font, Component.literal(areaText()), x + PADDING, y + 22,
                0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("super_lead.zone.create.name"), x + PADDING, y + 38,
                0xFFE8E8E8);
        graphics.text(this.font, Component.translatable("super_lead.zone.create.preset"), x + PADDING, y + 78,
                0xFFE8E8E8);
        if (!error.isEmpty())
            graphics.text(this.font, Component.literal(error), x + PADDING, y + DIALOG_H - 12, 0xFFFF6060);
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