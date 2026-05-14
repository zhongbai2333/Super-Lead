package com.zhongbai233.super_lead.lead.client.cargo;

import com.zhongbai233.super_lead.lead.cargo.CargoManifestData;
import com.zhongbai233.super_lead.lead.cargo.CargoManifestMenu;
import com.zhongbai233.super_lead.lead.cargo.SetCargoManifestGhostSlot;
import com.zhongbai233.super_lead.lead.cargo.UpdateCargoManifestOptions;
import com.zhongbai233.super_lead.lead.cargo.UpdateCargoManifestTag;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class CargoManifestScreen extends AbstractContainerScreen<CargoManifestMenu> {
    private static final int WIDTH = 270;
    private static final int HEIGHT = 196;
    private static final int PANEL_X = 184;
    private static final int PANEL_W = 76;
    private static final int TITLE_Y = 8;
    private static final int SAMPLES_LABEL_Y = 22;
    private static final int OPTIONS_LABEL_Y = 22;
    private static final int BUTTON_W = 72;
    private static final int INVENTORY_LABEL_Y = 94;
    private static final int TAG_LABEL_Y = 86;
    private static final int TAG_INPUT_Y = 102;
    private static final int TAG_LIST_Y = 124;
    private static final int BASIC_HINT_Y = 90;
    private static final int BORDER = 0xAAFFEE84;
    private static final int BORDER_SHADOW = 0xD0101418;

    private EditBox tagBox;

    public CargoManifestScreen(CargoManifestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = TITLE_Y;
        this.inventoryLabelX = CargoManifestMenu.PLAYER_INV_X;
        this.inventoryLabelY = INVENTORY_LABEL_Y;
    }

    public List<JeiDropTarget> jeiDropTargets(ItemStack ingredient) {
        if (menu.sanitizeGhostSample(ingredient).isEmpty()) {
            return List.of();
        }
        List<JeiDropTarget> targets = new ArrayList<>(CargoManifestMenu.FILTER_SLOT_COUNT);
        for (int i = 0; i < CargoManifestMenu.FILTER_SLOT_COUNT; i++) {
            Slot slot = menu.slots.get(i);
            targets.add(new JeiDropTarget(i, new Rect2i(leftPos + slot.x, topPos + slot.y, 16, 16)));
        }
        return targets;
    }

    public boolean acceptJeiIngredient(int slotId, ItemStack ingredient) {
        ItemStack sample = menu.sanitizeGhostSample(ingredient);
        if (sample.isEmpty()) {
            return false;
        }
        menu.setGhostSlotFromExternal(slotId, sample);
        ClientPacketDistributor.sendToServer(new SetCargoManifestGhostSlot(menu.containerId, slotId, sample));
        return true;
    }

    public boolean quickMoveJeiIngredient(ItemStack ingredient) {
        int slotId = menu.firstEmptyFilterSlot();
        return slotId >= 0 && acceptJeiIngredient(slotId, ingredient);
    }

    public record JeiDropTarget(int slotId, Rect2i area) {
    }

    @Override
    protected void init() {
        super.init();
        tagBox = null;
        addRenderableWidget(Button.builder(whitelistLabel(), button -> toggleWhitelist())
            .bounds(leftPos + PANEL_X, topPos + 36, BUTTON_W, 18).build());
        addRenderableWidget(Button.builder(nbtLabel(), button -> toggleNbt())
            .bounds(leftPos + PANEL_X, topPos + 60, BUTTON_W, 18).build());

        if (menu.advanced()) {
            tagBox = new EditBox(this.font, leftPos + PANEL_X, topPos + TAG_INPUT_Y, 52, 16,
                    Component.translatable("container.super_lead.cargo_manifest.tag_hint"));
            tagBox.setMaxLength(CargoManifestData.TAG_MAX_LENGTH);
            tagBox.setHint(Component.literal("#tag"));
            addRenderableWidget(tagBox);
            addRenderableWidget(Button.builder(Component.literal("+"), button -> addTag())
                .bounds(leftPos + PANEL_X + 54, topPos + TAG_INPUT_Y, 18, 16).build());
            addTagRemoveButtons();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x80000000);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF02B3038);
        drawBorder(graphics, leftPos - 1, topPos - 1, imageWidth + 2, imageHeight + 2, BORDER_SHADOW);
        drawBorder(graphics, leftPos, topPos, imageWidth, imageHeight, BORDER);

        drawPanel(graphics, 8, 30, 170, 48);
        drawPanel(graphics, 8, 100, 170, 84);
        drawPanel(graphics, PANEL_X - 4, 30, PANEL_W + 8, imageHeight - 40);
        drawSlotBackdrops(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (menu.advanced()) {
            drawTags(graphics);
        } else {
            graphics.textWithWordWrap(this.font,
                Component.translatable("container.super_lead.cargo_manifest.basic_hint")
                    .withStyle(ChatFormatting.DARK_GRAY),
                leftPos + PANEL_X, topPos + BASIC_HINT_Y, PANEL_W - 4, 0xFF9AA0A8);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFFFD24F);
        graphics.text(this.font, Component.translatable("container.super_lead.cargo_manifest.samples"), 8,
            SAMPLES_LABEL_Y, 0xFFE8E8E8);
        graphics.text(this.font, Component.translatable("container.super_lead.cargo_manifest.options"), PANEL_X,
            OPTIONS_LABEL_Y,
                0xFFE8E8E8);
        graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFFE8E8E8);
        if (menu.advanced()) {
            graphics.text(this.font, Component.translatable("container.super_lead.cargo_manifest.tags"), PANEL_X,
                TAG_LABEL_Y, 0xFFE8E8E8);
        }
    }

        private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        int ax = leftPos + x;
        int ay = topPos + y;
        graphics.fill(ax, ay, ax + w, ay + h, 0x803A414A);
        drawBorder(graphics, ax, ay, w, h, 0x70101418);
        drawBorder(graphics, ax + 1, ay + 1, w - 2, h - 2, 0x40FFEE84);
        }

        private static void drawBorder(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
        }

    private void drawSlotBackdrops(GuiGraphicsExtractor graphics) {
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF15181D);
            graphics.fill(x, y, x + 16, y + 16, 0xFF3E4650);
        }
    }

    private void drawTags(GuiGraphicsExtractor graphics) {
        List<String> tags = menu.tags();
        if (tags.isEmpty()) {
            graphics.text(this.font,
                    Component.translatable("container.super_lead.cargo_manifest.no_tags")
                            .withStyle(ChatFormatting.DARK_GRAY),
                leftPos + PANEL_X, topPos + TAG_LIST_Y, 0xFF9AA0A8);
            return;
        }
        int max = Math.min(3, tags.size());
        for (int i = 0; i < max; i++) {
            String tag = tags.get(i);
            String text = tag.length() > 13 ? tag.substring(0, 12) + "…" : tag;
            graphics.text(this.font, Component.literal("#" + text), leftPos + PANEL_X, topPos + TAG_LIST_Y + i * 18,
                    0xFFB9D7FF);
        }
        if (tags.size() > max) {
            graphics.text(this.font, Component.literal("+" + (tags.size() - max)).withStyle(ChatFormatting.GRAY),
                leftPos + PANEL_X, topPos + TAG_LIST_Y + max * 18, 0xFF9AA0A8);
        }
    }

    private void addTagRemoveButtons() {
        List<String> tags = menu.tags();
        int max = Math.min(3, tags.size());
        for (int i = 0; i < max; i++) {
            String tag = tags.get(i);
            addRenderableWidget(Button.builder(Component.literal("×"), button -> removeTag(tag))
                    .bounds(leftPos + PANEL_X + 54, topPos + TAG_LIST_Y - 4 + i * 18, 18, 16).build());
        }
    }

    private Component whitelistLabel() {
        return Component.translatable(menu.whitelist()
                ? "container.super_lead.cargo_manifest.whitelist"
                : "container.super_lead.cargo_manifest.blacklist");
    }

    private Component nbtLabel() {
        return Component.translatable(menu.matchNbt()
                ? "container.super_lead.cargo_manifest.nbt_on"
                : "container.super_lead.cargo_manifest.nbt_off");
    }

    private void toggleWhitelist() {
        menu.setOptions(!menu.whitelist(), menu.matchNbt());
        ClientPacketDistributor.sendToServer(new UpdateCargoManifestOptions(menu.containerId, menu.whitelist(),
                menu.matchNbt()));
        rebuildWidgets();
    }

    private void toggleNbt() {
        menu.setOptions(menu.whitelist(), !menu.matchNbt());
        ClientPacketDistributor.sendToServer(new UpdateCargoManifestOptions(menu.containerId, menu.whitelist(),
                menu.matchNbt()));
        rebuildWidgets();
    }

    private void addTag() {
        if (tagBox == null) {
            return;
        }
        String tag = CargoManifestData.normalizeTagInput(tagBox.getValue());
        if (tag.isEmpty()) {
            return;
        }
        menu.addTag(tag);
        ClientPacketDistributor.sendToServer(new UpdateCargoManifestTag(menu.containerId, true, tag));
        rebuildWidgets();
    }

    private void removeTag(String tag) {
        menu.removeTag(tag);
        ClientPacketDistributor.sendToServer(new UpdateCargoManifestTag(menu.containerId, false, tag));
        rebuildWidgets();
    }
}