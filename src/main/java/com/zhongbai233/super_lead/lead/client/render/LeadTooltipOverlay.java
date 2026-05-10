package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.Config;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class LeadTooltipOverlay {
    private static final int PADDING = 6;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 4;
    private static final int ROW_GAP = 2;
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 4;
    private static final int BG_COLOR = 0xE0202830;
    private static final int BORDER_TOP = 0xFFFFC857;
    private static final int BORDER_BOTTOM = 0xFF8A6A20;
    private static final int TEXT_PRIMARY = 0xFFFFE9B0;
    private static final int TEXT_SECONDARY = 0xFFB0C4D0;
    private static final Component MAX_LABEL = Component.translatable("tooltip.super_lead.upgrade.max");

    private LeadTooltipOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (!main.is(Items.SHEARS) && !off.is(Items.SHEARS)) {
            return;
        }

        LeadConnection hovered = SuperLeadClientEvents.hoveredConnection();
        if (hovered == null) {
            return;
        }

        GuiGraphicsExtractor gfx = event.getGuiGraphics();
        Font font = mc.font;
        Component title = Component.translatable("tooltip.super_lead.kind." + hovered.kind().serializedName());
        Component subtitle;
        if (hovered.kind() == LeadKind.ENERGY || hovered.kind() == LeadKind.ITEM || hovered.kind() == LeadKind.FLUID) {
            int mult = hovered.speedMultiplier();
            subtitle = Component.translatable("tooltip.super_lead.tier",
                    hovered.tier(),
                    mult == Integer.MAX_VALUE ? "MAX" : ("\u00D7" + mult));
        } else {
            subtitle = Component.translatable("tooltip.super_lead.no_tier");
        }

        int titleW = font.width(title);
        int subW = font.width(subtitle);

        List<UpgradeRow> upgrades = collectUpgrades(hovered);
        int rowsH = 0;
        int rowsW = 0;
        if (!upgrades.isEmpty()) {
            for (UpgradeRow r : upgrades) {
                rowsW = Math.max(rowsW, font.width(r.label) + ICON_SIZE + ICON_TEXT_GAP);
            }
            rowsH = SECTION_GAP + (upgrades.size() * ICON_SIZE)
                    + ((upgrades.size() - 1) * ROW_GAP);
        } else if (hovered.kind() == LeadKind.REDSTONE) {
            rowsW = font.width(MAX_LABEL);
            rowsH = SECTION_GAP + font.lineHeight;
        }

        int contentW = Math.max(Math.max(titleW, subW), rowsW);
        int width = contentW + PADDING * 2;
        int height = font.lineHeight + LINE_GAP + font.lineHeight + rowsH + PADDING * 2;

        int cx = gfx.guiWidth() / 2;
        int cy = gfx.guiHeight() / 2;
        int x = cx + 14;
        int y = cy - height / 2;

        gfx.fill(x, y, x + width, y + 1, BORDER_TOP);
        gfx.fill(x, y + height - 1, x + width, y + height, BORDER_BOTTOM);
        gfx.fill(x, y, x + 1, y + height, BORDER_TOP);
        gfx.fill(x + width - 1, y, x + width, y + height, BORDER_BOTTOM);
        gfx.fill(x + 1, y + 1, x + width - 1, y + height - 1, BG_COLOR);

        int textX = x + PADDING;
        int textY = y + PADDING;
        gfx.text(font, title, textX, textY, TEXT_PRIMARY, true);
        gfx.text(font, subtitle, textX, textY + font.lineHeight + LINE_GAP, TEXT_SECONDARY, true);

        int rowY = textY + font.lineHeight + LINE_GAP + font.lineHeight + SECTION_GAP;
        if (!upgrades.isEmpty()) {
            for (UpgradeRow r : upgrades) {
                gfx.fakeItem(r.icon, textX, rowY);
                int labelY = rowY + (ICON_SIZE - font.lineHeight) / 2 + 1;
                gfx.text(font, r.label, textX + ICON_SIZE + ICON_TEXT_GAP, labelY, TEXT_SECONDARY, true);
                rowY += ICON_SIZE + ROW_GAP;
            }
        } else if (hovered.kind() == LeadKind.REDSTONE) {
            gfx.text(font, MAX_LABEL, textX, rowY, TEXT_SECONDARY, true);
        }
    }

    private static List<UpgradeRow> collectUpgrades(LeadConnection hovered) {
        List<UpgradeRow> rows = new ArrayList<>(4);
        switch (hovered.kind()) {
            case NORMAL -> {
                rows.add(new UpgradeRow(new ItemStack(Items.REDSTONE_BLOCK),
                        Component.translatable("tooltip.super_lead.upgrade.redstone")));
                rows.add(new UpgradeRow(new ItemStack(Items.IRON_BLOCK),
                        Component.translatable("tooltip.super_lead.upgrade.energy")));
                rows.add(new UpgradeRow(new ItemStack(Items.HOPPER),
                        Component.translatable("tooltip.super_lead.upgrade.item")));
                rows.add(new UpgradeRow(new ItemStack(Items.CAULDRON),
                        Component.translatable("tooltip.super_lead.upgrade.fluid")));
            }
            case ENERGY -> {
                if (hovered.tier() < Config.energyTierMaxLevel()) {
                    rows.add(new UpgradeRow(new ItemStack(Items.REDSTONE_BLOCK),
                            Component.translatable("tooltip.super_lead.upgrade.tier_up",
                                    hovered.tier() + 1)));
                }
            }
            case ITEM -> {
                if (hovered.tier() < SuperLeadNetwork.itemTierMax()) {
                    rows.add(new UpgradeRow(new ItemStack(Items.CHEST),
                            Component.translatable("tooltip.super_lead.upgrade.tier_up",
                                    hovered.tier() + 1)));
                }
            }
            case FLUID -> {
                if (hovered.tier() < SuperLeadNetwork.fluidTierMax()) {
                    rows.add(new UpgradeRow(new ItemStack(Items.BUCKET),
                            Component.translatable("tooltip.super_lead.upgrade.tier_up",
                                    hovered.tier() + 1)));
                }
            }
            default -> { }
        }
        return rows;
    }
}
