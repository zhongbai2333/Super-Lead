package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
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
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = Super_lead.MODID)
public final class SuperLeadEvents {
    private SuperLeadEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();

        // Sneak-gated rope-targeting actions: cut, upgrade, extract toggle, remove attachment.
        if (event.getEntity().isShiftKeyDown()) {
            if (stack.is(Items.SHEARS) && tryUseConnectionAction(event)) {
                return;
            }
            if (stack.is(Items.HOPPER) && tryToggleItemExtract(event)) {
                return;
            }
            if (stack.is(Items.CAULDRON) && tryToggleFluidExtract(event)) {
                return;
            }
            if (tryUseConnectionAction(event)) {
                return;
            }
            if (stack.isEmpty() && tryRemoveRopeAttachment(event.getLevel())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
        } else {
            // Non-sneak: attach decoration to upgraded ropes. Kept separate from the upgrade
            // path so a player carrying e.g. redstone dust can both upgrade (sneak) and decorate
            // (no sneak) without conflict.
            if (tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
        }

        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!SuperLeadItems.isSuperLead(stack) || event.getLevel().getBlockState(event.getPos()).isAir()) {
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
            // Still consume the vanilla block interaction when the server can confirm a compatible
            // rope in view; otherwise block items like chests can be placed after the custom
            // upgrade packet succeeds.
            if (event.getLevel() instanceof ServerLevel serverLevel
                    && SuperLeadNetwork.hasClientPickCompatibleConnectionInView(
                            serverLevel, event.getEntity(), action::canTarget)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return true;
            }
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
        boolean shift = event.getEntity().isShiftKeyDown();

        // Connection actions (shears, redstone, energy, item, fluid) all require sneak now so
        // they don't conflict with attachment placement.
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action != null && shift && event.getLevel().isClientSide()) {
            if (sendClientUseAction(event.getEntity(), event.getHand(), action)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
        }

        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!shift && tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (!event.getLevel().isClientSide()) return;
        ItemStack stack = event.getItemStack();
        boolean shift = event.getEntity().isShiftKeyDown();
        if (shift) {
            if (stack.isEmpty()) {
                tryRemoveRopeAttachment(event.getLevel());
            }
            return;
        }
        if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) {
            tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (!event.getLevel().isClientSide()) return;
        // Plain left-click on a rope attachment with both block- and item-display forms
        // toggles between the two. trySend… filters on BlockItem so harmless on misses.
        com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents.trySendToggleRopeAttachmentForm();
    }

    /** Attempt to add the held attachable item to a rope under the player's crosshair.
     *  Client-only entry point; server is driven by the resulting packet. */
    private static boolean tryAddRopeAttachment(Player player, InteractionHand hand, Level level) {
        if (!level.isClientSide()) return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) return false;
        return com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents
                .trySendAddRopeAttachment(hand);
    }

    /** Attempt to remove the attachment under the player's crosshair. Client-only entry point. */
    private static boolean tryRemoveRopeAttachment(Level level) {
        if (!level.isClientSide()) return false;
        return com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents
                .trySendRemoveRopeAttachment();
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof LeashFenceKnotEntity knot)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (SuperLeadItems.isSuperLead(stack)) {
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
                RopeContactTracker.tickRopeContacts(serverLevel);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SuperLeadPayloads.sendToPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SuperLeadPayloads.sendToPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        SuperLeadPayloads.sendChunkToPlayer(event.getLevel(), event.getPlayer(), event.getPos());
    }

    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        SuperLeadPayloads.unloadChunkForPlayer(event.getPlayer(), event.getPos());
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
        if (usedMaterialKind != null && SuperLeadItems.isSuperLead(other) && SuperLeadItemData.kind(other) != usedMaterialKind) {
            upgradeOneLead(player, otherHand, other, used, usedMaterialKind);
            return true;
        }

        LeadKind otherMaterialKind = upgradeKindFromMaterial(other);
        if (SuperLeadItems.isSuperLead(used) && otherMaterialKind != null && SuperLeadItemData.kind(used) != otherMaterialKind) {
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

        ItemStack upgraded = SuperLeadItems.stack(kind);
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
