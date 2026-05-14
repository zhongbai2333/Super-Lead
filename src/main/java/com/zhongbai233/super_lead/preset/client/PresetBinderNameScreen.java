package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.PresetBinderCreate;
import com.zhongbai233.super_lead.preset.RopePresetLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class PresetBinderNameScreen extends Screen {
    private static final int W = 260;
    private static final int PADDING = 8;
    private static final int DIALOG_H = 118;
    private static final int MAX_BASE_LENGTH = 23;

    private final Screen parent;
    private final InteractionHand hand;
    private EditBox nameBox;
    private String draft = "";
    private String error = "";

    public PresetBinderNameScreen(Screen parent, InteractionHand hand) {
        super(Component.translatable("super_lead.preset_binder.create.title"));
        this.parent = parent;
        this.hand = hand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int x = (this.width - W) / 2;
        int y = this.height / 2 - DIALOG_H / 2;

        nameBox = new EditBox(this.font, x + PADDING, y + 44, W - PADDING * 2, 18,
                Component.translatable("super_lead.preset_binder.create.name"));
        nameBox.setMaxLength(MAX_BASE_LENGTH);
        nameBox.setHint(Component.translatable("super_lead.preset_binder.create.name_hint"));
        nameBox.setValue(draft);
        nameBox.setResponder(s -> {
            draft = s;
            error = "";
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int btnY = y + DIALOG_H - 32;
        addRenderableWidget(Button.builder(Component.translatable("super_lead.preset_binder.create.submit"), b -> submit())
                .bounds(x + PADDING, btnY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("super_lead.config.close"), b -> onClose())
                .bounds(x + W - PADDING - 70, btnY, 70, 20).build());
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        if (!RopePresetLibrary.isValidName(name) || name.length() > MAX_BASE_LENGTH) {
            error = Component.translatable("super_lead.preset_binder.create.invalid_name").getString();
            return;
        }
        ClientPacketDistributor.sendToServer(new PresetBinderCreate(hand == InteractionHand.OFF_HAND, name));
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        int x = (this.width - W) / 2;
        int y = this.height / 2 - DIALOG_H / 2;
        graphics.fill(x, y, x + W, y + DIALOG_H, 0xFF303840);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(this.font, this.title, x + PADDING, y + 8, 0xFFFFD24F);
        graphics.text(this.font, Component.translatable("super_lead.preset_binder.create.hint"), x + PADDING, y + 24,
                0xFFE8E8E8);
        graphics.text(this.font, Component.translatable("super_lead.preset_binder.create.name"), x + PADDING, y + 34,
                0xFFE8E8E8);
        if (!error.isEmpty()) {
            graphics.text(this.font, Component.literal(error), x + PADDING, y + DIALOG_H - 12, 0xFFFF6060);
        }
    }

    public static void open(Screen parent, InteractionHand hand) {
        Minecraft.getInstance().setScreen(new PresetBinderNameScreen(parent, hand));
    }
}