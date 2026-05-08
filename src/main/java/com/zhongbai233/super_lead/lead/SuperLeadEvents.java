package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadEvents {
    private static final int LOGIN_SYNC_DELAY_TICKS = 10;
    private static final double CONNECTION_USE_RADIUS = 0.75D;
    private static final Map<UUID, Integer> DELAYED_SYNCS = new HashMap<>();

    private SuperLeadEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();

        if (tryUseConnectionAction(event)) {
            return;
        }

        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!stack.is(Items.LEAD) || event.getLevel().getBlockState(event.getPos()).isAir()) {
            return;
        }

        LeadAnchor anchor = createAnchor(event);
        InteractionResult result = handleLeadUse(event.getLevel(), event.getEntity(), stack, anchor);

        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    private static boolean tryUseConnectionAction(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action == null) {
            return false;
        }

        var point = event.getHitVec().getLocation();
        boolean handled = event.getLevel().isClientSide()
                ? action.hasTargetNear(event.getLevel(), point, CONNECTION_USE_RADIUS)
                : action.apply(event.getLevel(), point, event.getEntity());
        if (!handled) {
            return false;
        }

        if (!event.getLevel().isClientSide()) {
            action.consumeSuccessfulUse(stack, event.getEntity(), event.getHand());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof LeashFenceKnotEntity knot)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (stack.is(Items.LEAD)) {
            InteractionResult result = handleLeadUse(event.getLevel(), event.getEntity(), stack, new LeadAnchor(knot.getPos(), Direction.UP));
            event.setCanceled(true);
            event.setCancellationResult(result);
            return;
        }

        if (!stack.is(Items.SHEARS)) {
            return;
        }

        LeadAnchor anchor = new LeadAnchor(knot.getPos(), Direction.UP);
        boolean handled = event.getLevel().isClientSide()
                ? SuperLeadNetwork.hasConnectionAttachedTo(event.getLevel(), anchor)
                : SuperLeadNetwork.cutAttachedTo(event.getLevel(), anchor, event.getEntity()) > 0;
        if (handled) {
            if (!event.getLevel().isClientSide() && !event.getEntity().isCreative()) {
                stack.hurtAndBreak(1, event.getEntity(), event.getHand());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static InteractionResult handleLeadUse(Level level, Player player, ItemStack stack, LeadAnchor anchor) {
        InteractionResult result = InteractionResult.SUCCESS;
        LeadKind stackKind = SuperLeadItemData.kind(stack);
        var pending = SuperLeadNetwork.pendingAnchor(player);
        if (pending.isPresent()) {
            LeadAnchor first = pending.get();
            LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(stackKind);
            if (first.equals(anchor)) {
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
            } else if (first.attachmentPoint(level).distanceTo(anchor.attachmentPoint(level)) > SuperLeadNetwork.MAX_LEASH_DISTANCE) {
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                result = InteractionResult.FAIL;
            } else {
                SuperLeadNetwork.connect(level, first, anchor, kind);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                if (!level.isClientSide() && !player.isCreative()) {
                    stack.shrink(1);
                }
            }
        } else {
            SuperLeadNetwork.setPendingAnchor(player, anchor, stackKind);
            markLeashingState(stack);
        }
        return result;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        for (Player player : event.getLevel().players()) {
            SuperLeadNetwork.pendingAnchor(player).ifPresent(anchor -> {
                if (anchor.attachmentPoint(event.getLevel()).distanceTo(player.getRopeHoldPosition(1.0F)) > SuperLeadNetwork.MAX_LEASH_DISTANCE) {
                    SuperLeadNetwork.clearPendingAnchor(player);
                    clearLeashingState(player.getMainHandItem());
                    clearLeashingState(player.getOffhandItem());
                }
            });
        }
        if (!event.getLevel().isClientSide() && event.getLevel().getGameTime() % 20L == 0L) {
            SuperLeadNetwork.pruneInvalid(event.getLevel());
        }
        if (!event.getLevel().isClientSide()) {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                SuperLeadNetwork.tickRedstone(serverLevel);
            }
            flushDelayedSyncs();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            queueDelayedSync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            queueDelayedSync(player);
        }
    }

    private static void queueDelayedSync(ServerPlayer player) {
        DELAYED_SYNCS.put(player.getUUID(), LOGIN_SYNC_DELAY_TICKS);
    }

    private static void flushDelayedSyncs() {
        Iterator<Map.Entry<UUID, Integer>> iterator = DELAYED_SYNCS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticks = entry.getValue() - 1;
            if (ticks > 0) {
                entry.setValue(ticks);
                continue;
            }

            ServerPlayer player = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
                    .getPlayerList()
                    .getPlayer(entry.getKey());
            if (player != null) {
                SuperLeadPayloads.sendToPlayer(player);
            }
            iterator.remove();
        }
    }

    private static void markLeashingState(ItemStack stack) {
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    private static void clearLeashingState(ItemStack stack) {
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    private static boolean tryUpgradeHeldLead(Player player, InteractionHand usedHand) {
        ItemStack used = player.getItemInHand(usedHand);
        InteractionHand otherHand = usedHand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack other = player.getItemInHand(otherHand);

        if (used.is(Items.REDSTONE_BLOCK) && other.is(Items.LEAD) && !SuperLeadItemData.isRedstoneLead(other)) {
            upgradeOneLead(player, otherHand, other, used);
            return true;
        }
        if (used.is(Items.LEAD) && !SuperLeadItemData.isRedstoneLead(used) && other.is(Items.REDSTONE_BLOCK)) {
            upgradeOneLead(player, usedHand, used, other);
            return true;
        }
        return false;
    }

    private static void upgradeOneLead(Player player, InteractionHand leadHand, ItemStack leadStack, ItemStack redstoneBlockStack) {
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack upgraded = new ItemStack(Items.LEAD);
        SuperLeadItemData.setKind(upgraded, LeadKind.REDSTONE);
        if (!player.isCreative()) {
            leadStack.shrink(1);
            redstoneBlockStack.shrink(1);
        }

        if (leadStack.isEmpty()) {
            player.setItemInHand(leadHand, upgraded);
        } else if (!player.addItem(upgraded)) {
            player.drop(upgraded, false);
        }
    }

    private static LeadAnchor createAnchor(PlayerInteractEvent.RightClickBlock event) {
        Direction face = event.getLevel().getBlockState(event.getPos()).getBlock() instanceof FenceBlock
                ? Direction.UP
                : event.getHitVec().getDirection();
        return new LeadAnchor(event.getPos().immutable(), face);
    }
}
