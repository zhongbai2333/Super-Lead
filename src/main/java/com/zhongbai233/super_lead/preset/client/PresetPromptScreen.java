package com.zhongbai233.super_lead.preset.client;

import com.zhongbai233.super_lead.preset.PresetPromptResponse;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class PresetPromptScreen extends Screen {
    private final Screen parent;
    private final String presetName;
    private final Map<String, String> overrides;
    private boolean replied;

    public PresetPromptScreen(Screen parent, String presetName, Map<String, String> overrides) {
        super(Component.translatable("super_lead.preset.prompt.title"));
        this.parent = parent;
        this.presetName = presetName;
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 24;
        Button accept = Button.builder(Component.translatable("super_lead.preset.prompt.accept"), b -> reply(true))
                .bounds(cx - 110, by, 100, 20).build();
        Button reject = Button.builder(Component.translatable("super_lead.preset.prompt.reject"), b -> reply(false))
                .bounds(cx + 10, by, 100, 20).build();
        addRenderableWidget(accept);
        addRenderableWidget(reject);
    }

    private void reply(boolean accepted) {
        if (replied)
            return;
        replied = true;
        if (accepted) {
            PresetClientHandler.applyOverrides(overrides);
        }
        ClientPacketDistributor.sendToServer(new PresetPromptResponse(presetName, accepted));
        if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        if (!replied)
            reply(false);
        else if (this.minecraft != null)
            this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int top = this.height / 2 - 50;
        graphics.centeredText(this.font, this.title, cx, top, 0xFFFFD24F);
        graphics.centeredText(this.font,
                Component.translatable("super_lead.preset.prompt.description", presetName, overrides.size()),
                cx, top + 16, 0xFFCCCCCC);
        graphics.centeredText(this.font,
                Component.translatable("super_lead.preset.prompt.hint"),
                cx, top + 30, 0xFF999999);
    }
}
