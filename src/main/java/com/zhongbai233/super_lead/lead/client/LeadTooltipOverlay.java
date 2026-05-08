package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
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

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class LeadTooltipOverlay {
    private static final int PADDING = 6;
    private static final int LINE_GAP = 2;
    private static final int BG_COLOR = 0xE0202830;
    private static final int BORDER_TOP = 0xFFFFC857;
    private static final int BORDER_BOTTOM = 0xFF8A6A20;
    private static final int TEXT_PRIMARY = 0xFFFFE9B0;
    private static final int TEXT_SECONDARY = 0xFFB0C4D0;

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
        int contentW = Math.max(titleW, subW);
        int width = contentW + PADDING * 2;
        int height = font.lineHeight + LINE_GAP + font.lineHeight + PADDING * 2;

        int cx = gfx.guiWidth() / 2;
        int cy = gfx.guiHeight() / 2;
        // Anchored just below crosshair, centered.
        int x = cx - width / 2;
        int y = cy + 14;

        // Border (Create-OreUI feel: thin gold gradient frame + dark inner).
        gfx.fill(x, y, x + width, y + 1, BORDER_TOP);
        gfx.fill(x, y + height - 1, x + width, y + height, BORDER_BOTTOM);
        gfx.fill(x, y, x + 1, y + height, BORDER_TOP);
        gfx.fill(x + width - 1, y, x + width, y + height, BORDER_BOTTOM);
        gfx.fill(x + 1, y + 1, x + width - 1, y + height - 1, BG_COLOR);

        int textX = x + PADDING;
        int textY = y + PADDING;
        gfx.text(font, title, textX, textY, TEXT_PRIMARY, true);
        gfx.text(font, subtitle, textX, textY + font.lineHeight + LINE_GAP, TEXT_SECONDARY, true);
    }
}
