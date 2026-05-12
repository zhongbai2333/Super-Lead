package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Config;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public enum LeadConnectionAction {
    CUT(0x66FFEE84) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.SHEARS);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.SHEARS;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            return true;
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.cutNearestInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            return SuperLeadNetwork.cutConnection(level, player, connection);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.hurtAndBreak(1, player, hand);
            }
        }
    },
    REDSTONE_UPGRADE(0x88FF2020) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.REDSTONE_BLOCK);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.REDSTONE_BLOCK;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            if (connection.kind() == LeadKind.NORMAL) {
                return true;
            }
            // Energy lead: tier upgrade allowed up to config cap.
            if (connection.kind() == LeadKind.ENERGY) {
                return connection.tier() < Config.energyTierMaxLevel();
            }
            return false;
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            // Try an energy tier upgrade first; otherwise upgrade normal lead to redstone.
            if (SuperLeadNetwork.canUpgradeNearestEnergyTierInView(level, player, radius)) {
                return SuperLeadNetwork.upgradeNearestEnergyTierInView(level, player, radius);
            }
            return SuperLeadNetwork.upgradeNearestToRedstoneInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            if (connection.kind() == LeadKind.ENERGY) {
                return SuperLeadNetwork.upgradeConnectionTier(level, player, connection,
                        Config.energyTierMaxLevel(), Items.REDSTONE_BLOCK);
            }
            return SuperLeadNetwork.upgradeConnectionKind(level, connection, LeadKind.REDSTONE);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    },
    ENERGY_UPGRADE(0x88FFAA33) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.IRON_BLOCK);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.IRON_BLOCK;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            return connection.kind() != LeadKind.ENERGY;
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.upgradeNearestToEnergyInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            return SuperLeadNetwork.upgradeConnectionKind(level, connection, LeadKind.ENERGY);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    },
    ITEM_UPGRADE(0x8888CCFF) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.HOPPER);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.HOPPER;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            // Highlight any rope so the player gets visual feedback when aiming with a
            // hopper.
            // Apply only actually upgrades non-ITEM ropes; for ITEM ropes the player
            // toggles
            // extract by clicking the anchor block, handled separately in SuperLeadEvents.
            return true;
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.upgradeNearestToItemInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            // Hopper does nothing on already-ITEM ropes; toggle is handled via right-click
            // on anchor.
            if (connection.kind() == LeadKind.ITEM)
                return false;
            return SuperLeadNetwork.upgradeConnectionKind(level, connection, LeadKind.ITEM);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    },
    ITEM_TIER_UPGRADE(0x88FFCC33) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.CHEST);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.CHEST;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            return connection.kind() == LeadKind.ITEM && connection.tier() < Config.itemTierMax();
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.upgradeNearestItemTierInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            if (connection.kind() != LeadKind.ITEM)
                return false;
            return SuperLeadNetwork.upgradeConnectionTier(level, player, connection,
                    Config.itemTierMax(), Items.CHEST);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    },
    CAULDRON_UPGRADE(0x8866FFEE) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.CAULDRON);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.CAULDRON;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            // Highlight any rope (mirrors ITEM_UPGRADE). Toggle-on-anchor is handled
            // separately.
            return true;
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.upgradeNearestToFluidInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            // Cauldron does nothing on already-FLUID ropes; toggle is handled via
            // right-click on anchor.
            if (connection.kind() == LeadKind.FLUID)
                return false;
            return SuperLeadNetwork.upgradeConnectionKind(level, connection, LeadKind.FLUID);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    },
    FLUID_TIER_UPGRADE(0x8833DDFF) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.BUCKET);
        }

        @Override
        public net.minecraft.world.item.Item iconItem() {
            return Items.BUCKET;
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            return connection.kind() == LeadKind.FLUID && connection.tier() < Config.fluidTierMax();
        }

        @Override
        public boolean apply(Level level, Player player, double radius) {
            return SuperLeadNetwork.upgradeNearestFluidTierInView(level, player, radius);
        }

        @Override
        public boolean applyTo(ServerLevel level, Player player, LeadConnection connection) {
            if (connection.kind() != LeadKind.FLUID)
                return false;
            return SuperLeadNetwork.upgradeConnectionTier(level, player, connection,
                    Config.fluidTierMax(), Items.BUCKET);
        }

        @Override
        public void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
    };

    private final int previewColor;

    LeadConnectionAction(int previewColor) {
        this.previewColor = previewColor;
    }

    public int previewColor() {
        return previewColor;
    }

    public abstract boolean matches(ItemStack stack);

    /**
     * Item used to perform this action; rendered as an icon in the rope tooltip.
     */
    public abstract net.minecraft.world.item.Item iconItem();

    public abstract boolean canTarget(LeadConnection connection);

    public abstract boolean apply(Level level, Player player, double radius);

    /**
     * Apply this action to one specific connection (server side). Returns true if
     * successful.
     */
    public abstract boolean applyTo(ServerLevel level, Player player, LeadConnection connection);

    public abstract void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand);

    public boolean hasTargetInView(Level level, Player player, double radius) {
        return SuperLeadNetwork.hasConnectionInView(level, player, radius, this::canTarget);
    }

    public static Optional<LeadConnectionAction> fromStack(ItemStack stack) {
        for (LeadConnectionAction action : values()) {
            if (action.matches(stack)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    public static Optional<LeadConnectionAction> fromHeldItems(Player player) {
        return fromStack(player.getMainHandItem()).or(() -> fromStack(player.getOffhandItem()));
    }
}
