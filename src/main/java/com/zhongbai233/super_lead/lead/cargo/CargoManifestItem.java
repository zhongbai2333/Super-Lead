package com.zhongbai233.super_lead.lead.cargo;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;

public class CargoManifestItem extends Item {
    private final boolean advanced;

    public CargoManifestItem(Properties properties, boolean advanced) {
        super(properties);
        this.advanced = advanced;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            Component title = Component.translatable(advanced
                    ? "container.super_lead.advanced_cargo_manifest"
                    : "container.super_lead.basic_cargo_manifest");
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (id, inventory, owner) -> new CargoManifestMenu(id, inventory, hand, advanced), title);
            ((IPlayerExtension) serverPlayer).openMenu(provider,
                    buffer -> CargoManifestMenu.writeOpeningData(buffer, hand, advanced, stack));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.super_lead.cargo_manifest.mode",
                Component.translatable(CargoManifestData.whitelist(stack)
                        ? "tooltip.super_lead.cargo_manifest.whitelist"
                        : "tooltip.super_lead.cargo_manifest.blacklist"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.super_lead.cargo_manifest.match_nbt",
                Component.translatable(CargoManifestData.matchNbt(stack)
                        ? "super_lead.config.bool.on"
                        : "super_lead.config.bool.off"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.super_lead.cargo_manifest.entries",
                CargoManifestData.nonEmptyItemCount(stack), CargoManifestData.tags(stack).size())
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}