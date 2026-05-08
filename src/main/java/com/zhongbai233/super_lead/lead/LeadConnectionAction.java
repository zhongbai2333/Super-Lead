package com.zhongbai233.super_lead.lead;

import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public enum LeadConnectionAction {
    CUT(0x66FFEE84) {
        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(Items.SHEARS);
        }

        @Override
        public boolean canTarget(LeadConnection connection) {
            return true;
        }

        @Override
        public boolean apply(Level level, Vec3 point, Player player) {
            return SuperLeadNetwork.cutNearest(level, point, player);
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
        public boolean canTarget(LeadConnection connection) {
            return connection.kind() != LeadKind.REDSTONE;
        }

        @Override
        public boolean apply(Level level, Vec3 point, Player player) {
            return SuperLeadNetwork.upgradeNearestToRedstone(level, point, player);
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

    public abstract boolean canTarget(LeadConnection connection);

    public abstract boolean apply(Level level, Vec3 point, Player player);

    public abstract void consumeSuccessfulUse(ItemStack stack, Player player, InteractionHand hand);

    public boolean hasTargetNear(Level level, Vec3 point, double radius) {
        return SuperLeadNetwork.hasConnectionNear(level, point, radius, this::canTarget);
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