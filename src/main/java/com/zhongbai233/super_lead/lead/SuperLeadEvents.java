package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
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
    private static final Map<UUID, Integer> DELAYED_SYNCS = new HashMap<>();

    private SuperLeadEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();

        // Hopper on a block that already has an ITEM rope attached: toggle that anchor as extract source.
        if (stack.is(Items.HOPPER) && tryToggleItemExtract(event)) {
            return;
        }
        // Cauldron on a block that already has a FLUID rope attached: toggle that anchor as extract source.
        if (stack.is(Items.CAULDRON) && tryToggleFluidExtract(event)) {
            return;
        }

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

    private static boolean tryToggleItemExtract(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!SuperLeadNetwork.hasItemConnectionAt(level, event.getPos())) {
            return false;
        }
        if (!level.isClientSide()) {
            SuperLeadNetwork.toggleItemExtractAt(level, event.getPos());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private static boolean tryToggleFluidExtract(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!SuperLeadNetwork.hasFluidConnectionAt(level, event.getPos())) {
            return false;
        }
        if (!level.isClientSide()) {
            SuperLeadNetwork.toggleFluidExtractAt(level, event.getPos());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private static boolean tryUseConnectionAction(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action == null) {
            return false;
        }
        if (!event.getLevel().isClientSide()) {
            // Server-side handling is driven exclusively by the client-confirmed packet
            // (UseConnectionAction). Avoid running the server's own view-pick here so that
            // straight-line ray + larger radius cannot upgrade a rope the client never highlighted.
            return false;
        }
        if (!sendClientUseAction(event.getEntity(), event.getHand(), action)) {
            return false;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    /**
     * Client-side: if the player is currently hovering a connection that this action can target,
     * send the use packet to the server. Returns true when a packet was sent (caller should cancel
     * the interaction event so the vanilla use packet is suppressed).
     */
    private static boolean sendClientUseAction(Player player, InteractionHand hand, LeadConnectionAction action) {
        return com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents
                .trySendUseConnectionAction(hand, action);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action != null && event.getLevel().isClientSide()) {
            // Mirror tryUseConnectionAction: client-only confirmation, server runs via packet.
            if (sendClientUseAction(event.getEntity(), event.getHand(), action)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
        }

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
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                SuperLeadNetwork.tickStuckBreaks(serverLevel);
                SuperLeadNetwork.tickRedstone(serverLevel);
                SuperLeadNetwork.tickEnergy(serverLevel);
                SuperLeadNetwork.tickItem(serverLevel);
                SuperLeadNetwork.tickFluid(serverLevel);
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

        LeadKind usedMaterialKind = upgradeKindFromMaterial(used);
        if (usedMaterialKind != null && other.is(Items.LEAD) && SuperLeadItemData.kind(other) != usedMaterialKind) {
            upgradeOneLead(player, otherHand, other, used, usedMaterialKind);
            return true;
        }

        LeadKind otherMaterialKind = upgradeKindFromMaterial(other);
        if (used.is(Items.LEAD) && otherMaterialKind != null && SuperLeadItemData.kind(used) != otherMaterialKind) {
            upgradeOneLead(player, usedHand, used, other, otherMaterialKind);
            return true;
        }
        return false;
    }

    private static LeadKind upgradeKindFromMaterial(ItemStack stack) {
        if (stack.is(Items.REDSTONE_BLOCK)) {
            return LeadKind.REDSTONE;
        }
        if (stack.is(Items.IRON_BLOCK)) {
            return LeadKind.ENERGY;
        }
        if (stack.is(Items.HOPPER)) {
            return LeadKind.ITEM;
        }
        if (stack.is(Items.CAULDRON)) {
            return LeadKind.FLUID;
        }
        return null;
    }

    private static void upgradeOneLead(Player player, InteractionHand leadHand, ItemStack leadStack, ItemStack materialStack, LeadKind kind) {
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack upgraded = new ItemStack(Items.LEAD);
        SuperLeadItemData.setKind(upgraded, kind);
        if (!player.isCreative()) {
            leadStack.shrink(1);
            materialStack.shrink(1);
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
